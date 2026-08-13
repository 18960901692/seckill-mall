package com.sygzcd.seckillmall.service.impl;

import com.sygzcd.seckillmall.common.BusinessException;
import com.sygzcd.seckillmall.common.UserDTO;
import com.sygzcd.seckillmall.entity.User;
import com.sygzcd.seckillmall.mapper.UserMapper;
import com.sygzcd.seckillmall.service.UserService;
import jakarta.servlet.http.HttpSession;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
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

    @Autowired
    private UserCacheService userCacheService;

    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @Override
    public void register(String username, String password) {
        User existingUser = userMapper.selectByUsername(username);
        if (existingUser != null) {
            throw new BusinessException("用户名已存在");
        }

        String encodedPassword = passwordEncoder.encode(password);
        User user = new User();
        user.setUsername(username);
        user.setPassword(encodedPassword);

        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException e) {
            throw new BusinessException("用户名已存在");
        }
    }

    @Override
    public UserDTO login(String username, String password) {
        User user = userMapper.selectByUsername(username);
        if (user == null) {
            throw new BusinessException("用户名或密码错误");
        }

        if (!passwordEncoder.matches(password, user.getPassword())) {
            throw new BusinessException("用户名或密码错误");
        }

        // 登录成功，写入 Session（仅存 userId，避免保存密码 Hash 等敏感信息）
        session.setAttribute("userId", user.getId());

        // 管理员标志：用户名为 admin 的用户拥有管理员权限
        if ("admin".equals(username)) {
            session.setAttribute("isAdmin", true);
        }

        // 防多地登录：将 userId → sessionId 存入 Redis，后续请求校验一致性
        stringRedisTemplate.opsForValue().set(
                LOGIN_SESSION_KEY + user.getId(),
                session.getId(),
                24, TimeUnit.HOURS
        );

        // 写入三级缓存，后续查询直接走缓存
        userCacheService.putUser(user);

        return convertToDTO(user);
    }

    @Override
    public UserDTO getById(Long id) {
        return userCacheService.getById(id);
    }

    @Override
    public UserDTO getCurrentUser() {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes == null) {
            return null;
        }

        HttpSession session = attributes.getRequest().getSession(false);
        if (session == null) {
            return null;
        }

        Long userId = (Long) session.getAttribute("userId");
        if (userId == null) {
            return null;
        }

        return userCacheService.getById(userId);
    }

    @Override
    public void logout() {
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attributes != null) {
            HttpSession session = attributes.getRequest().getSession(false);
            if (session != null) {
                Long userId = (Long) session.getAttribute("userId");
                if (userId != null) {
                    stringRedisTemplate.delete(LOGIN_SESSION_KEY + userId);
                }
                session.invalidate();
            }
        }
    }

    private UserDTO convertToDTO(User user) {
        return new UserDTO(user.getId(), user.getUsername(), user.getCreateTime());
    }
}