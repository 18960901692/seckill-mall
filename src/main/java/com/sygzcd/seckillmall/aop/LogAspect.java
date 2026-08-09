package com.sygzcd.seckillmall.aop;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 日志切面
 * 记录接口请求耗时、入参、出参
 */
@Aspect
@Component
@Order(2)
@Slf4j
public class LogAspect {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Around("execution(* com.sygzcd.seckillmall.controller..*.*(..))")
    public Object logExecutionTime(ProceedingJoinPoint pjp) throws Throwable {
        long start = System.currentTimeMillis();
        
        // 获取请求信息
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        String requestInfo = "";
        if (attributes != null) {
            HttpServletRequest request = attributes.getRequest();
            requestInfo = request.getMethod() + " " + request.getRequestURI();
        }

        // 记录入参
        String args = "";
        try {
            args = objectMapper.writeValueAsString(pjp.getArgs());
            if (args.length() > 500) {
                args = args.substring(0, 500) + "...";
            }
        } catch (Exception e) {
            args = "序列化失败";
        }

        log.info("接口开始: {} | 参数: {}", requestInfo, args);

        Object result = null;
        try {
            result = pjp.proceed();
            return result;
        } finally {
            long cost = System.currentTimeMillis() - start;
            log.info("接口结束: {} | 耗时: {}ms", requestInfo, cost);
            
            // 慢接口告警
            if (cost > 500) {
                log.warn("慢接口告警: {} | 耗时: {}ms", requestInfo, cost);
            }
        }
    }
}
