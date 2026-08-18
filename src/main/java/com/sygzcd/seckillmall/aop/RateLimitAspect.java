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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.Semaphore;

/**
 * 限流切面
 * 本地 Semaphore + Redis ZSet 滑动窗口双重限流
 * 限流触发时记录违规次数，超过阈值自动加入黑名单
 */
@Aspect
@Component
@Order(1)
public class RateLimitAspect {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private BlackListService blackListService;

    // 本地信号量：单机限流 10000 并发（本地快速降级，主要限流依赖 Redis 分布式限流）
    private final Semaphore semaphore = new Semaphore(10000);

    // Lua 脚本：滑动窗口限流（使用 StringRedisTemplate，参数全部传字符串）
    private static final RedisScript<Long> SLIDING_WINDOW_SCRIPT = new DefaultRedisScript<>(
            "redis.call('ZREMRANGEBYSCORE', KEYS[1], 0, ARGV[1] - ARGV[2]) " +
            "local cnt = redis.call('ZCARD', KEYS[1]) " +
            "if cnt < tonumber(ARGV[3]) then " +
            "  redis.call('ZADD', KEYS[1], ARGV[1], ARGV[4]) " +
            "  redis.call('EXPIRE', KEYS[1], ARGV[5]) " +
            "  return 1 " +
            "else " +
            "  return 0 " +
            "end", Long.class);

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
            // 第二层：Redis ZSet 滑动窗口限流（参数全部转字符串，避免序列化问题）
            long now = System.currentTimeMillis();
            Long result = stringRedisTemplate.execute(
                    SLIDING_WINDOW_SCRIPT,
                    Collections.singletonList(key),
                    String.valueOf(now),                      // ARGV[1] 当前时间戳(ms)
                    String.valueOf(rateLimit.windowSec() * 1000L),  // ARGV[2] 窗口(ms)
                    String.valueOf(rateLimit.maxCount()),     // ARGV[3] 最大次数
                    UUID.randomUUID().toString(),             // ARGV[4] 唯一标识
                    String.valueOf(rateLimit.windowSec())     // ARGV[5] 过期时间(秒)
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
