package com.sygzcd.seckillmall.interceptor;

import com.sygzcd.seckillmall.common.Result;
import com.sygzcd.seckillmall.entity.User;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.concurrent.TimeUnit;

/**
 * 登录拦截器
 * 校验 Session 中是否有用户信息
 * 同时校验 Session ID 是否与 Redis 中一致，防止多地登录
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String LOGIN_SESSION_KEY = "login:session:";

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        HttpSession session = request.getSession(false);
        
        if (session == null || session.getAttribute("user") == null) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(objectMapper.writeValueAsString(
                Result.fail(401, "未登录，请先登录")
            ));
            return false;
        }

        // 防多地登录：校验当前 sessionId 是否与 Redis 中存储的一致
        User user = (User) session.getAttribute("user");
        String redisKey = LOGIN_SESSION_KEY + user.getId();
        String storedSessionId = stringRedisTemplate.opsForValue().get(redisKey);

        if (storedSessionId == null) {
            // Redis 中的映射已过期（24小时），重新设置当前 Session
            stringRedisTemplate.opsForValue().set(redisKey, session.getId(), 24, TimeUnit.HOURS);
        } else if (!storedSessionId.equals(session.getId())) {
            // sessionId 不一致，说明账号在其他设备登录，踢下线当前 Session
            session.invalidate();
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(objectMapper.writeValueAsString(
                Result.fail(401, "账号在其他设备登录，请重新登录")
            ));
            return false;
        }
        
        return true;
    }
}
