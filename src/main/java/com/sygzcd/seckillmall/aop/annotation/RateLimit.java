package com.sygzcd.seckillmall.aop.annotation;

import java.lang.annotation.*;

/**
 * 限流注解
 * 基于 Redis ZSet 滑动窗口实现
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RateLimit {
    
    /**
     * 时间窗口大小（秒）
     */
    int windowSec() default 10;
    
    /**
     * 窗口内最大请求次数
     */
    int maxCount() default 5;
    
    /**
     * 限流 Key 前缀
     */
    String keyPrefix() default "ratelimit:ip";
}
