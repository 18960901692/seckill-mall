package com.sygzcd.seckillmall.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 商品实体
 */
@Data
@TableName("product")
public class Product {

    @TableId(type = IdType.AUTO)
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
     * 当前库存
     */
    private Integer stock;

    /**
     * 乐观锁版本号
     * 注意：不使用 MyBatis-Plus @Version 注解，因为该注解只对 updateById 生效，
     * 而秒杀扣库存需要 DB 原子递减（stock = stock - 1），updateById 做不到。
     * 版本号检查在手写 XML 的 decreaseStockWithVersion 中手动实现。
     */
    private Integer version;

    /**
     * 是否热点商品(1=永不过期)
     */
    private Integer hot;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;
}
