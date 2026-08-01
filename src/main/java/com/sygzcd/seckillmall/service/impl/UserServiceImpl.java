package com.sygzcd.seckillmall.service.impl;

import com.sygzcd.seckillmall.common.BusinessException;
import com.sygzcd.seckillmall.common.ResultCode;
import com.sygzcd.seckillmall.entity.User;
import com.sygzcd.seckillmall.mapper.UserMapper;
import com.sygzcd.seckillmall.service.UserService;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.concurrent.TimeUnit;

@Service
public class UserServiceImpl implements UserService {

    private static final String LOGIN_SESSION_KEY = "login:session:";

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private HttpSession session;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @Override
    public void register(String username, String password) {
        // 检查用户名是否已存在
        User existingUser = userMapper.selectByUsername(username);
        if (existingUser != null) {
            throw new BusinessException("用户名已存在");
        }

        // 密码加密
        String encodedPassword = passwordEncoder.encode(password);
        
        // 创建用户
        User user = new User();
        user.setUsername(username);
        user.setPassword(encodedPassword);
        
        userMapper.insert(user);
    }

    @Override
    public User login(String username, String password) {
        User user = userMapper.selectByUsername(username);
        if (user == null) {
            throw new BusinessException("用户名或密码错误");
        }

        // 验证密码
        if (!passwordEncoder.matches(password, user.getPassword())) {
            throw new BusinessException("用户名或密码错误");
        }

        // 登录成功，写入 Session（存 userId 供业务层使用）
        session.setAttribute("user", user);
        session.setAttribute("userId", user.getId());

        // 防多地登录：将 userId → sessionId 存入 Redis，后续请求校验一致性
        stringRedisTemplate.opsForValue().set(
                LOGIN_SESSION_KEY + user.getId(),
                session.getId(),
                24, TimeUnit.HOURS
        );

        return user;
    }

    @Override
    public User getCurrentUser() {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return null;
        }
        
        HttpSession session = attributes.getRequest().getSession(false);
        if (session == null) {
            return null;
        }
        
        return (User) session.getAttribute("user");
    }

    @Override
    public void logout() {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes != null) {
            HttpSession session = attributes.getRequest().getSession(false);
            if (session != null) {
                // 清除 Redis 中的登录映射
                User user = (User) session.getAttribute("user");
                if (user != null) {
                    stringRedisTemplate.delete(LOGIN_SESSION_KEY + user.getId());
                }
                session.invalidate();
            }
        }
    }
}
