package com.sygzcd.seckillmall.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;

/**
 * Spring Session Redis 配置
 * 分布式 Session，多实例会话共享
 */
@Configuration
@EnableRedisHttpSession(
        // Session 过期时间：30 分钟
        maxInactiveIntervalInSeconds = 1800,
        // Redis 命名空间
        redisNamespace = "spring:session"
)
public class SessionConfig {
}
