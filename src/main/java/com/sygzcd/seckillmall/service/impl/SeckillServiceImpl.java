package com.sygzcd.seckillmall.service.impl;

import com.sygzcd.seckillmall.entity.Orders;
import com.sygzcd.seckillmall.service.OrderService;
import com.sygzcd.seckillmall.service.ProductService;
import com.sygzcd.seckillmall.service.SeckillService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class SeckillServiceImpl implements SeckillService {

    @Autowired
    private OrderService orderService;

    @Autowired
    private ProductService productService;

    @Override
    public Orders seckill(Long userId, Long productId) {
        // 检查库存
        Integer stock = productService.getStock(productId);
        if (stock == null || stock <= 0) {
            throw new RuntimeException("库存不足");
        }

        // 创建订单
        return orderService.createOrder(userId, productId);
    }
}
