package com.sygzcd.seckillmall.config;

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
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns(
                        "/api/user/login",
                        "/api/user/register",
                        "/doc.html",
                        "/webjars/**",
                        "/swagger-resources/**",
                        "/v3/api-docs/**"
                )
                .order(1);
    }
}
