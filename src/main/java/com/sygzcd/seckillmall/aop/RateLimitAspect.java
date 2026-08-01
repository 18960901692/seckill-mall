package com.sygzcd.seckillmall.aop;

import com.sygzcd.seckillmall.aop.annotation.RateLimit;
import com.sygzcd.seckillmall.common.Result;
import com.sygzcd.seckillmall.common.ResultCode;
import com.sygzcd.seckillmall.service.BlackListService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
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
 * 限流触发时记录违规次数，超过阈值自动加入黑名单
 */
@Aspect
@Component
public class RateLimitAspect {

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private BlackListService blackListService;

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
        String ip = getClientIp(request);
        String key = rateLimit.keyPrefix() + ":" + ip;

        // 第一层：本地 Semaphore 限流
        if (!semaphore.tryAcquire()) {
            recordViolation(request, ip);
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
                recordViolation(request, ip);
                return Result.fail(ResultCode.RATE_LIMIT);
            }

            // 执行业务逻辑
            return pjp.proceed();
        } finally {
            semaphore.release();
        }
    }

    /**
     * 记录违规次数，超过阈值自动拉黑
     * 同时记录 IP 和已登录用户的违规
     */
    private void recordViolation(HttpServletRequest request, String ip) {
        // 记录 IP 违规
        blackListService.recordViolation("ip", ip);

        // 如果已登录，同时记录用户违规
        HttpSession session = request.getSession(false);
        if (session != null && session.getAttribute("userId") != null) {
            blackListService.recordViolation("user", session.getAttribute("userId").toString());
        }
    }

    /**
     * 获取客户端真实 IP，处理代理穿透
     */
    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Real-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return ip;
    }
}
