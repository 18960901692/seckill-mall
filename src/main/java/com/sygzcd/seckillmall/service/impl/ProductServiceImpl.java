package com.sygzcd.seckillmall.service.impl;

import com.github.benmanes.caffeine.cache.Cache;
import com.sygzcd.seckillmall.common.ProductDTO;
import com.sygzcd.seckillmall.config.CacheInvalidateConfig;
import com.sygzcd.seckillmall.entity.Product;
import com.sygzcd.seckillmall.mapper.ProductMapper;
import com.sygzcd.seckillmall.service.ProductService;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class ProductServiceImpl implements ProductService {

    @Autowired
    private ProductMapper productMapper;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private Cache<String, ProductDTO> caffeineCache;

    @Autowired
    private RedissonClient redissonClient;

    private static final String PRODUCT_KEY = "product:";
    private static final String STOCK_KEY = "seckill:stock:";
    private static final String LOCK_KEY = "lock:product:";

    @Override
    public ProductDTO getById(Long id) {
        String key = PRODUCT_KEY + id;

        // 第一层：Caffeine 本地缓存（存 ProductDTO，不含 stock/version）
        ProductDTO productDTO = caffeineCache.getIfPresent(key);
        if (productDTO != null) {
            return productDTO;
        }

        // 第二层：Redis 缓存（存完整 Product）
        Product product = (Product) redisTemplate.opsForValue().get(key);
        if (product != null) {
            productDTO = toDTO(product);
            caffeineCache.put(key, productDTO);
            return productDTO;
        }

        // 第三层：缓存未命中，使用互斥锁防止缓存击穿
        String lockKey = LOCK_KEY + id;
        RLock lock = redissonClient.getLock(lockKey);

        // 尝试获取锁，等待 50ms
        // leaseTime=0 表示由 Redisson 看门狗自动续期（默认30s，线程持有期间自动续期）
        // 避免设置固定过期时间导致锁提前释放、被其他线程误持
        boolean locked;
        try {
            locked = lock.tryLock(50, 0, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            // 线程被中断时直接查 DB 兜底
            return toDTO(productMapper.selectById(id));
        }
        if (!locked) {
            // 等待超时仍未获取锁，说明数据可能已被其他线程加载到 Redis
            product = (Product) redisTemplate.opsForValue().get(key);
            if (product != null) {
                productDTO = toDTO(product);
                caffeineCache.put(key, productDTO);
                return productDTO;
            }
            // Redis 也未命中（极端情况），直接查 DB
            return toDTO(productMapper.selectById(id));
        }

        try {
            // 再次检查缓存（双重检查）
            product = (Product) redisTemplate.opsForValue().get(key);
            if (product != null) {
                productDTO = toDTO(product);
                caffeineCache.put(key, productDTO);
                return productDTO;
            }

            // 从数据库加载
            product = productMapper.selectById(id);
            if (product != null) {
                // Redis 存完整 Product（包括 stock/version，兼容现有数据）
                // 热点商品永不过期，普通商品设置随机TTL防止缓存雪崩
                if (product.getHot() != null && product.getHot() == 1) {
                    redisTemplate.opsForValue().set(key, product);
                } else {
                    long baseTtl = 30 * 60;
                    long randomTtl = ThreadLocalRandom.current().nextLong(0, 5 * 60);
                    redisTemplate.opsForValue().set(key, product, baseTtl + randomTtl, TimeUnit.SECONDS);
                }
                // Caffeine 存 ProductDTO（不含 stock/version，避免脏数据）
                productDTO = toDTO(product);
                caffeineCache.put(key, productDTO);
            }
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }

        return productDTO;
    }

    @Override
    public Integer getStock(Long id) {
        String key = STOCK_KEY + id;

        // 第一层：Redis（实时库存，秒杀一致性保证）
        Integer stock = (Integer) redisTemplate.opsForValue().get(key);
        if (stock != null) {
            return stock;
        }

        // 第二层：MySQL（Redis 未初始化时回填，返回后后续请求走 Redis）
        Product product = productMapper.selectById(id);
        if (product != null) {
            stock = product.getStock();
            // 随机TTL防止缓存雪崩：基础30分钟 + 随机0-5分钟
            long baseTtl = 30 * 60;
            long randomTtl = ThreadLocalRandom.current().nextLong(0, 5 * 60);
            redisTemplate.opsForValue().set(key, stock, baseTtl + randomTtl, TimeUnit.SECONDS);
            return stock;
        }

        return 0;
    }

    @Override
    public void warmUpProduct(Long id) {
        Product product = productMapper.selectById(id);
        if (product != null) {
            String key = PRODUCT_KEY + id;
            String stockKey = STOCK_KEY + id;

            // 商品信息预热到 Redis（存完整 Product）
            redisTemplate.opsForValue().set(key, product);
            // 库存预热到 Redis（独立 key）
            redisTemplate.opsForValue().set(stockKey, product.getStock());

            // 仅商品信息预热到 Caffeine（存 ProductDTO，不含库存）
            caffeineCache.put(key, toDTO(product));
        }
    }

    /**
     * 失效商品缓存
     * 清除 Redis product:{id} + 失效 Caffeine 本地缓存 + 广播通知其他实例
     * 库存仅存在 Redis，失效时直接删除即可，不经过 Caffeine
     */
    @Override
    public void invalidateCache(Long id) {
        String key = PRODUCT_KEY + id;
        String stockKey = STOCK_KEY + id;

        // 1. 清除 Redis 缓存（商品信息 + 库存）
        //    使用 StringRedisTemplate 避免 Jackson 序列化给 key 加双引号
        stringRedisTemplate.delete(key);
        stringRedisTemplate.delete(stockKey);

        // 2. 失效本地 Caffeine 缓存（仅商品信息 ProductDTO）
        caffeineCache.invalidate(key);

        // 3. 广播通知其他实例清除本地 Caffeine
        //    统一使用 StringRedisTemplate，与监听端 new String(getBody()) 匹配
        stringRedisTemplate.convertAndSend(CacheInvalidateConfig.CACHE_INVALIDATE_CHANNEL, key);

        log.debug("商品缓存已失效，商品ID: {}", id);
    }

    /**
     * Product → ProductDTO 转换
     * 脱敏高频变更字段（stock、version），仅保留基本信息
     */
    private ProductDTO toDTO(Product product) {
        if (product == null) {
            return null;
        }
        ProductDTO dto = new ProductDTO();
        dto.setId(product.getId());
        dto.setName(product.getName());
        dto.setPrice(product.getPrice());
        dto.setHot(product.getHot());
        dto.setCreateTime(product.getCreateTime());
        return dto;
    }
}