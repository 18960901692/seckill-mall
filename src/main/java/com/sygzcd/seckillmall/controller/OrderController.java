package com.sygzcd.seckillmall.controller;

import com.sygzcd.seckillmall.common.Result;
import com.sygzcd.seckillmall.entity.Orders;
import com.sygzcd.seckillmall.service.OrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 订单控制器
 * 提供订单查询、取消等接口
 */
@Slf4j
@RestController
@RequestMapping("/api/order")
@Tag(name = "订单接口", description = "订单查询、取消相关接口")
public class OrderController {

    @Autowired
    private OrderService orderService;

    /**
     * 根据订单号查询订单
     * @param orderNo 订单号
     * @return 订单信息
     */
    @GetMapping("/{orderNo}")
    @Operation(summary = "查询订单", description = "根据订单号查询订单详情")
    public Result<Orders> getOrder(
            @Parameter(description = "订单号", required = true)
            @PathVariable String orderNo) {
        try {
            Orders order = orderService.getByOrderNo(orderNo);
            if (order == null) {
                return Result.fail(404, "订单不存在");
            }
            return Result.success(order);
        } catch (Exception e) {
            log.error("查询订单失败，订单号: {}", orderNo, e);
            return Result.fail(500, "查询失败");
        }
    }

    /**
     * 取消订单
     * @param orderNo 订单号
     * @return 操作结果
     */
    @PostMapping("/{orderNo}/cancel")
    @Operation(summary = "取消订单", description = "取消未支付的订单，释放库存")
    public Result<Void> cancelOrder(
            @Parameter(description = "订单号", required = true)
            @PathVariable String orderNo) {
        try {
            orderService.cancelOrder(orderNo);
            return Result.success(null);
        } catch (Exception e) {
            log.error("取消订单失败，订单号: {}", orderNo, e);
            return Result.fail(500, "取消失败");
        }
    }

    /**
     * 查询用户订单列表（分页）
     * @param page 页码（从1开始）
     * @param size 每页大小
     * @param request HTTP请求（用于获取用户ID）
     * @return 订单列表
     */
    @GetMapping("/my")
    @Operation(summary = "我的订单", description = "查询当前用户的订单列表（分页）")
    public Result<List<Orders>> getMyOrders(
            @Parameter(description = "页码", example = "1")
            @RequestParam(defaultValue = "1") int page,
            @Parameter(description = "每页大小", example = "10")
            @RequestParam(defaultValue = "10") int size,
            HttpServletRequest request) {
        
        Long userId = (Long) request.getSession().getAttribute("userId");
        if (userId == null) {
            return Result.fail(401, "未登录");
        }

        try {
            List<Orders> orders = orderService.getUserOrders(userId, page, size);
            return Result.success(orders);
        } catch (Exception e) {
            log.error("查询用户订单失败，用户ID: {}", userId, e);
            return Result.fail(500, "查询失败");
        }
    }
}
