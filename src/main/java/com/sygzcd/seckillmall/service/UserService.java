package com.sygzcd.seckillmall.service;

import com.sygzcd.seckillmall.entity.User;

public interface UserService {
    
    /**
     * 用户注册
     */
    void register(String username, String password);
    
    /**
     * 用户登录
     */
    User login(String username, String password);
    
    /**
     * 获取当前登录用户
     */
    User getCurrentUser();
    
    /**
     * 退出登录
     */
    void logout();
}
