package com.sygzcd.seckillmall.common;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 商品脱敏数据传输对象
 * 不含 stock、version 等高频变更字段，用于缓存层传输
 * stock 独立走 Redis 实时库存，不混入 Caffeine 本地缓存
 */
@Data
public class ProductDTO {

    /**
     * 商品ID
     */
    private Long id;

    /**
     * 商品名
     */
    private String name;

    /**
     * 价格
     */
    private BigDecimal price;

    /**
     * 是否热点商品(1=永不过期)
     */
    private Integer hot;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;
}