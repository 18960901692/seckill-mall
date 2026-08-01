package com.sygzcd.seckillmall.controller;

import com.sygzcd.seckillmall.aop.annotation.RateLimit;
import com.sygzcd.seckillmall.common.Result;
import com.sygzcd.seckillmall.entity.Orders;
import com.sygzcd.seckillmall.service.SeckillService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

/**
 * 秒杀控制器
 * 提供秒杀下单接口
 */
@Slf4j
@RestController
@RequestMapping("/api/seckill")
@Tag(name = "秒杀接口", description = "秒杀下单相关接口")
public class SeckillController {

    @Autowired
    private SeckillService seckillService;

    /**
     * 秒杀下单
     * @param productId 商品ID
     * @param request HTTP请求（用于获取用户ID）
     * @return 订单信息
     */
    @PostMapping("/{productId}")
    @RateLimit(windowSec = 1, maxCount = 10000, keyPrefix = "ratelimit:seckill")
    @Operation(summary = "秒杀下单", description = "用户参与秒杀活动，成功则创建订单")
    public Result<Orders> seckill(
            @Parameter(description = "商品ID", required = true)
            @PathVariable Long productId,
            HttpServletRequest request) {
        
        // 从 Session 获取用户ID（登录拦截器已校验）
        Long userId = (Long) request.getSession().getAttribute("userId");
        if (userId == null) {
            return Result.fail(401, "未登录");
        }

        try {
            Orders order = seckillService.seckill(userId, productId);
            return Result.success(order);
        } catch (Exception e) {
            log.error("秒杀失败，用户ID: {}, 商品ID: {}", userId, productId, e);
            return Result.fail(500, e.getMessage());
        }
    }
}
