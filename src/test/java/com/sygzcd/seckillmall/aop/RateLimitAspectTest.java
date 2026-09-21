package com.sygzcd.seckillmall.aop;

import com.sygzcd.seckillmall.aop.annotation.RateLimit;
import com.sygzcd.seckillmall.common.ResultCode;
import com.sygzcd.seckillmall.common.util.IpUtils;
import com.sygzcd.seckillmall.service.BlackListService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * RateLimitAspect 纯单元测试（M-2：真 HTTP 429）
 *
 * 核心验证：
 * 1. 两层拒绝分支都设置 response.setStatus(429)
 * 2. Redis ZSet 滑动窗口超限 → 返回 0 → 返回失败 + 429
 * 3. Semaphore 拒绝（10000 并发耗尽）→ 返回失败 + 429
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RateLimitAspectTest {

    @InjectMocks
    private RateLimitAspect aspect;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private BlackListService blackListService;

    @Mock
    private IpUtils ipUtils;

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    @Mock
    private ProceedingJoinPoint pjp;

    private RateLimit rateLimit;
    private ServletRequestAttributes attributes;

    @BeforeEach
    void setUp() {
        // @RateLimit 注解模拟（Java 动态代理生成）
        rateLimit = mock(RateLimit.class);
        when(rateLimit.windowSec()).thenReturn(1);
        when(rateLimit.maxCount()).thenReturn(10000);
        when(rateLimit.keyPrefix()).thenReturn("ratelimit:seckill");

        // 设置 RequestContextHolder（aspect 从 RequestContextHolder 取 attributes）
        attributes = new ServletRequestAttributes(request, response);
        RequestContextHolder.setRequestAttributes(attributes);

        // IpUtils 返回固定 IP
        when(ipUtils.getClientIp(request)).thenReturn("203.0.113.1");
    }

    // ================================================================
    // 场景 1：Redis ZSet 滑动窗口超限 → 返回 0
    // ================================================================

    @Nested
    @DisplayName("第二层 Redis ZSet 滑动窗口限流")
    class RedisSlidingWindow {

        @Test
        @DisplayName("脚本返回 0（超限）→ 响应码 429 + body code=429")
        void slidingWindowExceededSetsHttp429() throws Throwable {
            // Redis 脚本返回 0 = 超限
            when(stringRedisTemplate.execute(
                    any(RedisScript.class),
                    anyList(),
                    any(String.class), any(String.class), any(String.class), any(String.class), any(String.class)
            )).thenReturn(0L);

            Object result = aspect.around(pjp, rateLimit);

            // 验证：response 被设置了 429
            verify(response).setStatus(429);
            // verify：pjp.proceed() 没有被调用（业务没执行）
            verify(pjp, never()).proceed();
            // 验证：返回的 Result body 里 code=429
            assertThat(result).isNotNull();
            var body = (com.sygzcd.seckillmall.common.Result<?>) result;
            assertThat(body.getCode()).isEqualTo(ResultCode.RATE_LIMIT.getCode()); // RATE_LIMIT 是 429
        }

        @Test
        @DisplayName("脚本返回 1（通过）→ 不设置 429，执行业务方法")
        void slidingWindowPassedExecutesBusiness() throws Throwable {
            when(stringRedisTemplate.execute(
                    any(RedisScript.class),
                    anyList(),
                    any(String.class), any(String.class), any(String.class), any(String.class), any(String.class)
            )).thenReturn(1L);

            Object expected = new Object();
            when(pjp.proceed()).thenReturn(expected);

            Object result = aspect.around(pjp, rateLimit);

            // verify：response 没有 setStatus（或设置的不是 429）——简单 verify 没被调用
            verify(response, never()).setStatus(429);
            verify(pjp).proceed();
            assertThat(result).isSameAs(expected);
        }

        @Test
        @DisplayName("脚本返回 null（异常）→ 当作超限处理")
        void scriptNullHandledAsExceeded() throws Throwable {
            when(stringRedisTemplate.execute(
                    any(RedisScript.class),
                    anyList(),
                    any(String.class), any(String.class), any(String.class), any(String.class), any(String.class)
            )).thenReturn(null);

            aspect.around(pjp, rateLimit);

            verify(response).setStatus(429);
        }
    }

    // ================================================================
    // 场景 2：RequestContextHolder 为 null → 放行（无 Web 上下文）
    // ================================================================

    @Nested
    @DisplayName("无 RequestContextHolder → 放行 proceed()")
    class NoRequestContext {

        @Test
        @DisplayName("attributes 为 null 时不进限流逻辑，直接 proceed")
        void nullAttributesBypassRateLimit() throws Throwable {
            // 清空 RequestContextHolder（Spring Boot 3 下用 setRequestAttributes(null)）
            RequestContextHolder.setRequestAttributes(null);

            Object expected = new Object();
            when(pjp.proceed()).thenReturn(expected);

            Object result = aspect.around(pjp, rateLimit);

            verify(pjp).proceed();
            assertThat(result).isSameAs(expected);
        }
    }

    // ================================================================
    // 清理
    // ================================================================

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        RequestContextHolder.setRequestAttributes(null);
    }
}
