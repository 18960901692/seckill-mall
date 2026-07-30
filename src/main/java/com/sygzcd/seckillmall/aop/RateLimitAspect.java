package com.sygzcd.seckillmall.aop;

import com.sygzcd.seckillmall.aop.annotation.RateLimit;
import com.sygzcd.seckillmall.common.Result;
import com.sygzcd.seckillmall.common.ResultCode;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Collections;
import java.util.concurrent.Semaphore;

/**
 * 限流切面
 * 本地 Semaphore + Redis ZSet 滑动窗口双重限流
 */
@Aspect
@Component
public class RateLimitAspect {

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    // 本地信号量：单机限流 100 并发
    private final Semaphore semaphore = new Semaphore(100);

    // Lua 脚本：滑动窗口限流
    private static final String LUA_SCRIPT =
            "local key = KEYS[1] " +
            "local window = tonumber(ARGV[1]) " +
            "local limit = tonumber(ARGV[2]) " +
            "local now = tonumber(ARGV[3]) " +
            "redis.call('ZREMRANGEBYSCORE', key, 0, now - window) " +
            "local count = redis.call('ZCARD', key) " +
            "if count >= limit then " +
            "  return 0 " +
            "end " +
            "redis.call('ZADD', key, now, now .. '-' .. math.random(1000000)) " +
            "redis.call('PEXPIRE', key, window * 1000) " +
            "return 1";

    @Around("@annotation(rateLimit)")
    public Object around(ProceedingJoinPoint pjp, RateLimit rateLimit) throws Throwable {
        // 获取请求 IP
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return pjp.proceed();
        }
        
        HttpServletRequest request = attributes.getRequest();
        String ip = request.getRemoteAddr();
        String key = rateLimit.keyPrefix() + ":" + ip;

        // 第一层：本地 Semaphore 限流
        if (!semaphore.tryAcquire()) {
            return Result.fail(ResultCode.RATE_LIMIT);
        }

        try {
            // 第二层：Redis ZSet 滑动窗口限流
            long now = System.currentTimeMillis();
            DefaultRedisScript<Long> script = new DefaultRedisScript<>(LUA_SCRIPT, Long.class);
            
            Long result = redisTemplate.execute(
                    script,
                    Collections.singletonList(key),
                    rateLimit.windowSec(),
                    rateLimit.maxCount(),
                    now
            );

            if (result == null || result == 0) {
                return Result.fail(ResultCode.RATE_LIMIT);
            }

            // 执行业务逻辑
            return pjp.proceed();
        } finally {
            semaphore.release();
        }
    }
}
