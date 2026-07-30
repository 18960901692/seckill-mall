package com.sygzcd.seckillmall.service;

import com.sygzcd.seckillmall.entity.Orders;

import java.util.List;

/**
 * 订单服务接口
 * 职责：订单CRUD、取消订单、查询订单
 */
public interface OrderService {
    
    /**
     * 根据订单号查询订单
     */
    Orders getByOrderNo(String orderNo);
    
    /**
     * 取消订单（超时未支付，释放库存）
     */
    void cancelOrder(String orderNo);
    
    /**
     * 查询用户订单列表（分页）
     */
    List<Orders> getUserOrders(Long userId, int page, int size);
}
