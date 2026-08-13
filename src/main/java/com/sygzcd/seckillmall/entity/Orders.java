package com.sygzcd.seckillmall.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单实体
 */
@Data
@TableName("orders")
public class Orders {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 唯一订单号(幂等)
     */
    private String orderNo;

    /**
     * 用户ID
     */
    private Long userId;

    /**
     * 商品ID
     */
    private Long productId;

    /**
     * 订单状态: 0未支付 1已支付 2已取消
     */
    private Integer status;

    /**
     * 订单金额(下单时从商品表快照，避免商品调价影响历史订单)
     */
    private BigDecimal amount;

    /**
     * 支付时间
     */
    private LocalDateTime payTime;

    /**
     * 支付流水号(支付幂等唯一标识)
     */
    private String transactionId;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;
}
