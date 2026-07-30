package com.sygzcd.seckillmall.controller;

import com.sygzcd.seckillmall.aop.annotation.RateLimit;
import com.sygzcd.seckillmall.common.Result;
import com.sygzcd.seckillmall.entity.Orders;
import com.sygzcd.seckillmall.entity.User;
import com.sygzcd.seckillmall.service.SeckillService;
import com.sygzcd.seckillmall.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@Tag(name = "秒杀模块", description = "秒杀下单接口")
@RestController
@RequestMapping("/api/seckill")
public class SeckillController {

    @Autowired
    private SeckillService seckillService;

    @Autowired
    private UserService userService;

    @Operation(summary = "秒杀下单")
    @RateLimit(windowSec = 10, maxCount = 5, keyPrefix = "ratelimit:seckill")
    @PostMapping("/{productId}")
    public Result<Orders> seckill(@PathVariable Long productId) {
        User user = userService.getCurrentUser();
        Orders order = seckillService.seckill(user.getId(), productId);
        return Result.success(order);
    }
}
