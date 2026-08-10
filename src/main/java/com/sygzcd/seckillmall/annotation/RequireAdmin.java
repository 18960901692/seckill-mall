package com.sygzcd.seckillmall.annotation;

import java.lang.annotation.*;

/**
 * 管理员权限校验注解
 * 标记在 Controller 方法上，表示该接口需要管理员权限
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequireAdmin {
}
