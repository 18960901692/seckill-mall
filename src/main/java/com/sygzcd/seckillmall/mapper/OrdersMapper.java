package com.sygzcd.seckillmall.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sygzcd.seckillmall.entity.Orders;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 订单 Mapper
 */
@Mapper
public interface OrdersMapper extends BaseMapper<Orders> {

    /**
     * 支付订单（条件更新实现支付幂等）
     * WHERE status=0 保证重复支付只有一次成功
     */
    int payOrder(@Param("orderNo") String orderNo,
                 @Param("userId") Long userId,
                 @Param("transactionId") String transactionId);

    /**
     * 订单取消状态流转（条件更新实现并发控制）
     * WHERE status=0 保证支付与取消竞争时只有一个成功
     */
    int cancelOrderById(@Param("id") Long id);

    /**
     * 查询用户对某商品的有效订单（未支付或已支付）
     * 用于 Redis 防重 key 过期/故障后的 DB 兜底检查
     * 只查 status IN (0,1) 的订单，已取消（status=2）的不算
     */
    Orders selectValidOrder(@Param("userId") Long userId, @Param("productId") Long productId);
}
