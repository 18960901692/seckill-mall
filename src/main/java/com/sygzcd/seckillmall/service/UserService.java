package com.sygzcd.seckillmall.service;

import com.sygzcd.seckillmall.common.UserDTO;

public interface UserService {

    /**
     * 用户注册
     */
    void register(String username, String password);

    /**
     * 用户登录，返回脱敏用户信息
     */
    UserDTO login(String username, String password);

    /**
     * 根据ID查询用户（返回脱敏 DTO）
     */
    UserDTO getById(Long id);

    /**
     * 获取当前登录用户（返回脱敏 DTO）
     */
    UserDTO getCurrentUser();

    /**
     * 退出登录
     */
    void logout();
}