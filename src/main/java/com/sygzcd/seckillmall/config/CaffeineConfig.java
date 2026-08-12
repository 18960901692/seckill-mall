package com.sygzcd.seckillmall.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.sygzcd.seckillmall.entity.Product;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * Caffeine 本地缓存配置
 * 仅缓存商品基本信息（Product），库存直接以 Redis 为准
 */
@Configuration
public class CaffeineConfig {

    @Bean
    public Cache<String, Product> caffeineCache() {
        return Caffeine.newBuilder()
                // 最大缓存条目数
                .maximumSize(1000)
                // 写入后 30 秒过期（短 TTL 兜底，配合 Redis Pub/Sub 广播失效）
                .expireAfterWrite(30, TimeUnit.SECONDS)
                // 开启统计
                .recordStats()
                .build();
    }
}
