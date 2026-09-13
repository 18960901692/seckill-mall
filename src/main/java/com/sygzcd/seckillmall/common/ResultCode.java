package com.sygzcd.seckillmall.common;

import lombok.Getter;

/**
 * 业务状态码枚举
 */
@Getter
public enum ResultCode {
    SUCCESS(200, "success"),
    FAIL(500, "服务器内部错误"),
    PARAM_ERROR(400, "参数错误"),
    UNAUTHORIZED(401, "未登录"),
    FORBIDDEN(403, "无权限"),
    NOT_FOUND(404, "资源不存在"),
    RATE_LIMIT(429, "请求过于频繁"),
    // 商品 / 秒杀业务（1xxx）
    SECKILL_FAIL(1001, "秒杀失败"),
    STOCK_EMPTY(1002, "商品已售罄"),
    REPEAT_ORDER(1003, "重复下单"),
    ORDER_TIMEOUT(1004, "订单超时"),
    SECKILL_BUSY(1005, "系统繁忙，请稍后重试"),
    PRODUCT_NOT_FOUND(404, "商品不存在"),
    // 订单状态流转（1xxx）
    ORDER_ALREADY_HANDLED(1010, "订单已支付或已取消"),
    ORDER_STATUS_CHANGED(1011, "订单状态已变更，请刷新重试"),
    // 用户业务（2xxx）
    USERNAME_EXISTS(2001, "用户名已存在"),
    LOGIN_FAIL(2002, "用户名或密码错误"),
    BLACK_LISTED(403, "已被加入黑名单，请联系管理员");

    private final Integer code;
    private final String message;

    ResultCode(Integer code, String message) {
        this.code = code;
        this.message = message;
    }
}
