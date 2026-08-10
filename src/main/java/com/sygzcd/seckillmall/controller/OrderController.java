package com.sygzcd.seckillmall.controller;

import com.sygzcd.seckillmall.common.BusinessException;
import com.sygzcd.seckillmall.common.Result;
import com.sygzcd.seckillmall.common.ResultCode;
import com.sygzcd.seckillmall.entity.Orders;
import com.sygzcd.seckillmall.service.OrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 订单控制器
 * 提供订单查询、取消等接口
 */
@RestController
@RequestMapping("/api/order")
@Tag(name = "订单接口", description = "订单查询、取消相关接口")
public class OrderController {

    @Autowired
    private OrderService orderService;

    /**
     * 根据订单号查询订单
     * @param orderNo 订单号
     * @param request HTTP请求（获取当前用户ID做归属校验）
     * @return 订单信息
     */
    @GetMapping("/{orderNo}")
    @Operation(summary = "查询订单", description = "根据订单号查询订单详情")
    public Result<Orders> getOrder(
            @Parameter(description = "订单号", required = true)
            @PathVariable String orderNo,
            HttpServletRequest request) {
        Orders order = orderService.getByOrderNo(orderNo);
        if (order == null) {
            throw new BusinessException(ResultCode.NOT_FOUND);
        }
        // 归属校验：只能查看自己的订单
        Long userId = (Long) request.getSession().getAttribute("userId");
        if (!order.getUserId().equals(userId)) {
            throw new BusinessException(ResultCode.FORBIDDEN);
        }
        return Result.success(order);
    }

    /**
     * 取消订单
     * @param orderNo 订单号
     * @param request HTTP请求（获取当前用户ID做归属校验）
     * @return 操作结果
     */
    @PostMapping("/{orderNo}/cancel")
    @Operation(summary = "取消订单", description = "取消未支付的订单，释放库存")
    public Result<Void> cancelOrder(
            @Parameter(description = "订单号", required = true)
            @PathVariable String orderNo,
            HttpServletRequest request) {
        // 归属校验：只能取消自己的订单
        Orders order = orderService.getByOrderNo(orderNo);
        if (order == null) {
            throw new BusinessException(ResultCode.NOT_FOUND);
        }
        Long userId = (Long) request.getSession().getAttribute("userId");
        if (!order.getUserId().equals(userId)) {
            throw new BusinessException(ResultCode.FORBIDDEN);
        }
        orderService.cancelOrder(orderNo);
        return Result.success(null);
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

        // 异常交由 GlobalExceptionHandler 统一处理
        List<Orders> orders = orderService.getUserOrders(userId, page, size);
        return Result.success(orders);
    }
}
