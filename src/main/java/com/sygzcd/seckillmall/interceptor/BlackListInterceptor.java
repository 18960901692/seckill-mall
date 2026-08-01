package com.sygzcd.seckillmall.interceptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sygzcd.seckillmall.common.Result;
import com.sygzcd.seckillmall.common.ResultCode;
import com.sygzcd.seckillmall.service.BlackListService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 黑名单拦截器
 * 在 AuthInterceptor 之前执行，检查 IP 和用户是否在黑名单中
 */
@Slf4j
@Component
public class BlackListInterceptor implements HandlerInterceptor {

    @Autowired
    private BlackListService blackListService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 1. 检查 IP 黑名单
        String ip = getClientIp(request);
        if (blackListService.isBlackListed("ip", ip)) {
            log.warn("IP 黑名单拦截：ip={}, uri={}", ip, request.getRequestURI());
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write(objectMapper.writeValueAsString(
                    Result.fail(ResultCode.BLACK_LISTED)
            ));
            return false;
        }

        // 2. 检查用户黑名单（如果已登录）
        HttpSession session = request.getSession(false);
        if (session != null && session.getAttribute("userId") != null) {
            String userId = session.getAttribute("userId").toString();
            if (blackListService.isBlackListed("user", userId)) {
                log.warn("用户黑名单拦截：userId={}, uri={}", userId, request.getRequestURI());
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write(objectMapper.writeValueAsString(
                        Result.fail(ResultCode.BLACK_LISTED)
                ));
                return false;
            }
        }

        return true;
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
        // 多级代理时取第一个
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return ip;
    }
}
