package com.sygzcd.seckillmall.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.sygzcd.seckillmall.entity.Product;
import com.sygzcd.seckillmall.mapper.ProductMapper;
import com.sygzcd.seckillmall.service.ProductService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
public class ProductServiceImpl implements ProductService {

    @Autowired
    private ProductMapper productMapper;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private Cache<String, Object> caffeineCache;

    private static final String PRODUCT_KEY = "product:";
    private static final String STOCK_KEY = "product:stock:";

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

        // 第三层：MySQL 数据库
        product = productMapper.selectById(id);
        if (product != null) {
            // 热点商品永不过期，普通商品 30 分钟过期
            long ttl = product.getHot() != null && product.getHot() == 1 ? -1 : 30 * 60;
            if (ttl > 0) {
                redisTemplate.opsForValue().set(key, product, ttl, TimeUnit.SECONDS);
            } else {
                redisTemplate.opsForValue().set(key, product);
            }
            caffeineCache.put(key, product);
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
            redisTemplate.opsForValue().set(key, stock, 30, TimeUnit.SECONDS);
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
            
            // 预热到 Redis
            redisTemplate.opsForValue().set(key, product);
            redisTemplate.opsForValue().set(stockKey, product.getStock());
            
            // 预热到 Caffeine
            caffeineCache.put(key, product);
            caffeineCache.put(stockKey, product.getStock());
        }
    }
}
