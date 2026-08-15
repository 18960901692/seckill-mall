package com.sygzcd.seckillmall.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.sygzcd.seckillmall.common.ProductDTO;
import com.sygzcd.seckillmall.common.UserDTO;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * Caffeine 本地缓存配置
 * 缓存商品基本信息（ProductDTO）和用户脱敏信息（UserDTO），库存直接以 Redis 为准
 */
@Configuration
public class CaffeineConfig {

    @Bean
    public Cache<String, ProductDTO> caffeineCache() {
        return Caffeine.newBuilder()
                .maximumSize(1000)
                .expireAfterWrite(30, TimeUnit.SECONDS)
                .recordStats()
                .build();
    }

    @Bean
    public Cache<String, UserDTO> userCaffeineCache() {
        return Caffeine.newBuilder()
                .maximumSize(500)
                .expireAfterWrite(10, TimeUnit.MINUTES)
                .recordStats()
                .build();
    }
}
