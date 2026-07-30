package com.sygzcd.seckillmall.controller;

import com.sygzcd.seckillmall.common.Result;
import com.sygzcd.seckillmall.entity.Orders;
import com.sygzcd.seckillmall.entity.User;
import com.sygzcd.seckillmall.service.OrderService;
import com.sygzcd.seckillmall.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "订单模块", description = "订单查询接口")
@RestController
@RequestMapping("/api/order")
public class OrderController {

    @Autowired
    private OrderService orderService;

    @Autowired
    private UserService userService;

    @Operation(summary = "查询订单详情")
    @GetMapping("/{orderNo}")
    public Result<Orders> getByOrderNo(@PathVariable String orderNo) {
        Orders order = orderService.getByOrderNo(orderNo);
        return Result.success(order);
    }

    @Operation(summary = "查询我的订单列表")
    @GetMapping("/my")
    public Result<List<Orders>> getMyOrders(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        User user = userService.getCurrentUser();
        List<Orders> orders = orderService.getUserOrders(user.getId(), page, size);
        return Result.success(orders);
    }
}
