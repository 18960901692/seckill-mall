package com.sygzcd.seckillmall.interceptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sygzcd.seckillmall.common.Result;
import com.sygzcd.seckillmall.common.ResultCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import com.sygzcd.seckillmall.annotation.RequireAdmin;

/**
 * 管理员权限拦截器
 * 检查 Session 中 isAdmin 标志，非管理员返回 403
 */
@Component
public class AdminInterceptor implements HandlerInterceptor {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }

        // 检查方法或类上是否有 @RequireAdmin 注解
        RequireAdmin methodAnnotation = handlerMethod.getMethodAnnotation(RequireAdmin.class);
        RequireAdmin classAnnotation = handlerMethod.getBeanType().getAnnotation(RequireAdmin.class);
        if (methodAnnotation == null && classAnnotation == null) {
            return true;
        }

        // 校验管理员身份
        HttpSession session = request.getSession(false);
        if (session == null || !Boolean.TRUE.equals(session.getAttribute("isAdmin"))) {
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(objectMapper.writeValueAsString(
                    Result.fail(ResultCode.FORBIDDEN)
            ));
            return false;
        }

        return true;
    }
}
