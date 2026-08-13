package com.sygzcd.seckillmall.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.sygzcd.seckillmall.common.UserDTO;
import com.sygzcd.seckillmall.config.CacheInvalidateConfig;
import com.sygzcd.seckillmall.entity.User;
import com.sygzcd.seckillmall.mapper.UserMapper;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * 用户缓存服务
 * 三级缓存：Caffeine → Redis → MySQL
 * 通过 Redis Pub/Sub 实现多实例缓存一致性
 */
@Slf4j
@Service
public class UserCacheService {

    private static final String USER_KEY = "user:";
    private static final String LOCK_KEY = "lock:user:";

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private Cache<String, UserDTO> userCaffeineCache;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private RedissonClient redissonClient;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 根据 ID 查询用户（带三级缓存）
     */
    public UserDTO getById(Long id) {
        String key = USER_KEY + id;

        // 第一层：Caffeine 本地缓存
        UserDTO user = userCaffeineCache.getIfPresent(key);
        if (user != null) {
            return user;
        }

        // 第二层：Redis 缓存
        user = getFromRedis(key);
        if (user != null) {
            userCaffeineCache.put(key, user);
            return user;
        }

        // 第三层：缓存未命中，使用互斥锁防止缓存击穿
        String lockKey = LOCK_KEY + id;
        RLock lock = redissonClient.getLock(lockKey);

        boolean locked;
        try {
            locked = lock.tryLock(50, 0, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return loadFromDb(id);
        }
        if (!locked) {
            user = getFromRedis(key);
            if (user != null) {
                userCaffeineCache.put(key, user);
                return user;
            }
            return loadFromDb(id);
        }

        try {
            // 双重检查
            user = getFromRedis(key);
            if (user != null) {
                userCaffeineCache.put(key, user);
                return user;
            }

            user = loadFromDb(id);
            if (user != null) {
                putToRedis(key, user);
                userCaffeineCache.put(key, user);
            }
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }

        return user;
    }

    /**
     * 将用户信息写入缓存（登录、注册时调用）
     */
    public void putUser(User user) {
        if (user == null) {
            return;
        }
        UserDTO dto = convertToDTO(user);
        String key = USER_KEY + user.getId();
        putToRedis(key, dto);
        userCaffeineCache.put(key, dto);
    }

    /**
     * 失效指定用户的缓存（修改、删除时调用）
     */
    public void invalidateById(Long id) {
        String key = USER_KEY + id;

        // 1. 清除 Redis 缓存
        stringRedisTemplate.delete(key);

        // 2. 失效本地 Caffeine 缓存
        userCaffeineCache.invalidate(key);

        // 3. 广播通知其他实例清除本地 Caffeine
        stringRedisTemplate.convertAndSend(
                CacheInvalidateConfig.USER_CACHE_INVALIDATE_CHANNEL, key);

        log.debug("用户缓存已失效，用户ID: {}", id);
    }

    /**
     * 从 Redis 读取用户缓存
     */
    private UserDTO getFromRedis(String key) {
        try {
            String json = stringRedisTemplate.opsForValue().get(key);
            if (json != null && !json.isEmpty()) {
                return objectMapper.readValue(json, UserDTO.class);
            }
        } catch (JsonProcessingException e) {
            log.warn("解析用户缓存 JSON 失败, key={}", key, e);
        }
        return null;
    }

    /**
     * 写入 Redis 缓存
     */
    private void putToRedis(String key, UserDTO user) {
        try {
            String json = objectMapper.writeValueAsString(user);
            stringRedisTemplate.opsForValue().set(key, json, 30, TimeUnit.MINUTES);
        } catch (JsonProcessingException e) {
            log.warn("序列化用户缓存失败, key={}", key, e);
        }
    }

    /**
     * 从数据库加载用户
     */
    private UserDTO loadFromDb(Long id) {
        User user = userMapper.selectById(id);
        return user != null ? convertToDTO(user) : null;
    }

    /**
     * User 实体转 UserDTO（脱敏，去除密码）
     */
    private UserDTO convertToDTO(User user) {
        return new UserDTO(user.getId(), user.getUsername(), user.getCreateTime());
    }
}