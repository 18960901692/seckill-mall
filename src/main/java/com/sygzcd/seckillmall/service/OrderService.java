package com.sygzcd.seckillmall.service;

import com.sygzcd.seckillmall.entity.Orders;

public interface OrderService {
    
    /**
     * 创建订单
     */
    Orders createOrder(Long userId, Long productId);
    
    /**
     * 根据订单号查询订单
     */
    Orders getByOrderNo(String orderNo);
    
    /**
     * 取消订单（超时未支付）
     */
    void cancelOrder(String orderNo);
    
    /**
     * 查询用户订单列表
     */
    java.util.List<Orders> getUserOrders(Long userId, int page, int size);
}
