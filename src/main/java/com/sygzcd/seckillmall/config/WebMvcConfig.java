package com.sygzcd.seckillmall.config;

import com.sygzcd.seckillmall.interceptor.AdminInterceptor;
import com.sygzcd.seckillmall.interceptor.AuthInterceptor;
import com.sygzcd.seckillmall.interceptor.BlackListInterceptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置
 * 注册黑名单拦截器（最先执行）和登录拦截器
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Autowired
    private AuthInterceptor authInterceptor;

    @Autowired
    private BlackListInterceptor blackListInterceptor;

    @Autowired
    private AdminInterceptor adminInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 黑名单拦截器：最先执行，拦截所有 /api/** 请求
        registry.addInterceptor(blackListInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns(
                        "/api/user/login",
                        "/api/user/register",
                        "/api/blacklist/**",
                        "/doc.html",
                        "/webjars/**",
                        "/swagger-resources/**",
                        "/v3/api-docs/**"
                )
                .order(0);

        // 登录拦截器：在黑名单之后执行
        // 注意：/api/blacklist/** 仅从黑名单拦截器排除（否则被拉黑后无法管理），
        // 但必须经过登录拦截器，防止匿名用户拉黑/解除他人
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns(
                        "/api/user/login",
                        "/api/user/register",
                        "/api/test/**",
                        "/doc.html",
                        "/webjars/**",
                        "/swagger-resources/**",
                        "/v3/api-docs/**"
                )
                .order(1);

        // 管理员权限拦截器：在登录拦截器之后执行
        // 仅拦截黑名单管理接口中的敏感操作（通过 @RequireAdmin 注解标记）
        registry.addInterceptor(adminInterceptor)
                .addPathPatterns("/api/blacklist/**")
                .order(2);
    }
}
