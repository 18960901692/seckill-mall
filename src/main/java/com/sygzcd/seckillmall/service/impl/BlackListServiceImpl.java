package com.sygzcd.seckillmall.service.impl;

import com.sygzcd.seckillmall.service.BlackListService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 黑名单服务实现
 * 使用 Redis Set 存储黑名单成员，配合计数器实现自动拉黑
 *
 * 使用 StringRedisTemplate 避免 Jackson 序列化导致 Set member 带双引号
 *
 * 设计：
 * - 黑名单 Set：blacklist:ip、blacklist:user（持久存储，需手动移除）
 * - 违规计数器：blacklist:count:{type}:{key}（TTL 10分钟，窗口内累计违规次数）
 * - 阈值：5 次违规自动拉黑
 */
@Slf4j
@Service
public class BlackListServiceImpl implements BlackListService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    /** 黑名单 Set Key 前缀 */
    private static final String BLACKLIST_KEY_PREFIX = "blacklist:";

    /** 违规计数器 Key 前缀 */
    private static final String COUNT_KEY_PREFIX = "blacklist:count:";

    /** 违规拉黑阈值 */
    private static final int VIOLATION_THRESHOLD = 5;

    /** 计数器窗口时间（秒） */
    private static final long COUNT_WINDOW_SECONDS = 600;

    @Override
    public boolean recordViolation(String type, String key) {
        String countKey = COUNT_KEY_PREFIX + type + ":" + key;
        Long count = stringRedisTemplate.opsForValue().increment(countKey);

        // 首次违规时设置过期时间
        if (count != null && count == 1) {
            stringRedisTemplate.expire(countKey, COUNT_WINDOW_SECONDS, TimeUnit.SECONDS);
        }

        // 达到阈值，自动拉黑
        if (count != null && count >= VIOLATION_THRESHOLD) {
            addToBlackList(type, key);
            stringRedisTemplate.delete(countKey);
            log.warn("自动拉黑：type={}, key={}, 违规次数={}", type, key, count);
            return true;
        }

        return false;
    }

    @Override
    public boolean isBlackListed(String type, String key) {
        String setKey = BLACKLIST_KEY_PREFIX + type;
        Boolean isMember = stringRedisTemplate.opsForSet().isMember(setKey, key);
        return Boolean.TRUE.equals(isMember);
    }

    @Override
    public void addToBlackList(String type, String key) {
        String setKey = BLACKLIST_KEY_PREFIX + type;
        stringRedisTemplate.opsForSet().add(setKey, key);
        log.info("加入黑名单：type={}, key={}", type, key);
    }

    @Override
    public void removeFromBlackList(String type, String key) {
        String setKey = BLACKLIST_KEY_PREFIX + type;
        stringRedisTemplate.opsForSet().remove(setKey, key);
        // 同时清除违规计数器
        String countKey = COUNT_KEY_PREFIX + type + ":" + key;
        stringRedisTemplate.delete(countKey);
        log.info("移出黑名单：type={}, key={}", type, key);
    }

    @Override
    public Set<String> getBlackList(String type) {
        String setKey = BLACKLIST_KEY_PREFIX + type;
        return stringRedisTemplate.opsForSet().members(setKey);
    }
}
