package com.sygzcd.seckillmall.service.impl;

import com.github.benmanes.caffeine.cache.Cache;
import com.sygzcd.seckillmall.entity.Product;
import com.sygzcd.seckillmall.mapper.ProductMapper;
import com.sygzcd.seckillmall.service.ProductService;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

@Service
public class ProductServiceImpl implements ProductService {

    @Autowired
    private ProductMapper productMapper;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private Cache<String, Object> caffeineCache;

    @Autowired
    private RedissonClient redissonClient;

    private static final String PRODUCT_KEY = "product:";
    private static final String STOCK_KEY = "seckill:stock:";
    private static final String LOCK_KEY = "lock:product:";

    @Override
    public Product getById(Long id) {
        String key = PRODUCT_KEY + id;

        // 第一层：Caffeine 本地缓存
        Product product = (Product) caffeineCache.getIfPresent(key);
        if (product != null) {
            return product;
        }

        // 第二层：Redis 缓存
        product = (Product) redisTemplate.opsForValue().get(key);
        if (product != null) {
            caffeineCache.put(key, product);
            return product;
        }

        // 第三层：缓存未命中，使用互斥锁防止缓存击穿
        String lockKey = LOCK_KEY + id;
        RLock lock = redissonClient.getLock(lockKey);

        try {
            // 尝试获取锁，最多等待3秒，持有锁最多10秒
            boolean locked = lock.tryLock(3, 10, TimeUnit.SECONDS);
            if (!locked) {
                // 获取锁失败，短暂休眠后重试从缓存读取
                Thread.sleep(50);
                return getById(id);
            }

            // 获取锁成功，再次检查缓存（双重检查）
            product = (Product) redisTemplate.opsForValue().get(key);
            if (product != null) {
                caffeineCache.put(key, product);
                return product;
            }

            // 从数据库加载
            product = productMapper.selectById(id);
            if (product != null) {
                // 热点商品永不过期，普通商品设置随机TTL防止缓存雪崩
                if (product.getHot() != null && product.getHot() == 1) {
                    // 热点商品：永不过期
                    redisTemplate.opsForValue().set(key, product);
                } else {
                    // 普通商品：基础TTL 30分钟 + 随机抖动0-5分钟
                    long baseTtl = 30 * 60;
                    long randomTtl = ThreadLocalRandom.current().nextLong(0, 5 * 60);
                    redisTemplate.opsForValue().set(key, product, baseTtl + randomTtl, TimeUnit.SECONDS);
                }
                caffeineCache.put(key, product);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            // 释放锁
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }

        return product;
    }

    @Override
    public Integer getStock(Long id) {
        String key = STOCK_KEY + id;

        // 第一层：Caffeine
        Integer stock = (Integer) caffeineCache.getIfPresent(key);
        if (stock != null) {
            return stock;
        }

        // 第二层：Redis
        stock = (Integer) redisTemplate.opsForValue().get(key);
        if (stock != null) {
            caffeineCache.put(key, stock);
            return stock;
        }

        // 第三层：MySQL
        Product product = productMapper.selectById(id);
        if (product != null) {
            stock = product.getStock();
            // 随机TTL防止缓存雪崩：基础30分钟 + 随机0-5分钟
            long baseTtl = 30 * 60;
            long randomTtl = ThreadLocalRandom.current().nextLong(0, 5 * 60);
            redisTemplate.opsForValue().set(key, stock, baseTtl + randomTtl, TimeUnit.SECONDS);
            caffeineCache.put(key, stock);
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

            // 预热到Redis
            redisTemplate.opsForValue().set(key, product);
            redisTemplate.opsForValue().set(stockKey, product.getStock());

            // 预热到Caffeine
            caffeineCache.put(key, product);
            caffeineCache.put(stockKey, product.getStock());
        }
    }
}
