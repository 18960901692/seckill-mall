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
    SECKILL_FAIL(1001, "秒杀失败"),
    STOCK_EMPTY(1002, "库存不足"),
    REPEAT_ORDER(1003, "重复下单"),
    ORDER_TIMEOUT(1004, "订单超时"),
    BLACK_LISTED(403, "已被加入黑名单，请联系管理员");

    private final Integer code;
    private final String message;

    ResultCode(Integer code, String message) {
        this.code = code;
        this.message = message;
    }
}
