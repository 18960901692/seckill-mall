package com.sygzcd.seckillmall.service;

import com.sygzcd.seckillmall.entity.Orders;

public interface SeckillService {
    
    /**
     * 秒杀下单
     */
    Orders seckill(Long userId, Long productId);
}
