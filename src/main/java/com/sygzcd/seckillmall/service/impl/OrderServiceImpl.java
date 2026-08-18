package com.sygzcd.seckillmall.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sygzcd.seckillmall.common.BusinessException;
import com.sygzcd.seckillmall.common.PayResultDTO;
import com.sygzcd.seckillmall.common.ResultCode;
import com.sygzcd.seckillmall.entity.Orders;
import com.sygzcd.seckillmall.entity.Product;
import com.sygzcd.seckillmall.mapper.OrdersMapper;
import com.sygzcd.seckillmall.mapper.ProductMapper;
import com.sygzcd.seckillmall.service.OrderService;
import com.sygzcd.seckillmall.service.ProductService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 订单服务实现
 * 职责：订单查询、订单取消（状态流转+释放库存）、订单支付（状态流转）
 */
@Slf4j
@Service
public class OrderServiceImpl implements OrderService {

    @Autowired
    private OrdersMapper ordersMapper;

    @Autowired
    private ProductMapper productMapper;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private ProductService productService;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private static final String STOCK_KEY = "seckill:stock:";
    private static final String USER_SECKILL_KEY = "seckill:user:";

    @Override
    public Orders getByOrderNo(String orderNo) {
        return ordersMapper.selectOne(
                new LambdaQueryWrapper<Orders>().eq(Orders::getOrderNo, orderNo)
        );
    }

    /**
     * 取消订单（订单状态流转 + 释放库存）
     * 1. 事务内：取消订单状态流转（UPDATE status=2 WHERE status=0）+ 回滚 MySQL 库存
     * 2. 事务外：回滚 Redis 库存 + 失效商品缓存 + 删除用户抢购记录
     */
    @Override
    public void cancelOrder(String orderNo) {
        Orders order = getByOrderNo(orderNo);
        if (order == null || order.getStatus() != 0) {
            return;
        }

        Long productId = order.getProductId();
        Long userId = order.getUserId();
        boolean[] cancelled = {false};

        // 编程式事务：只包含 DB 操作，事务提交后才执行 Redis/缓存操作
        transactionTemplate.execute(status -> {
            // 订单取消状态流转：条件更新 WHERE status=0 保证支付与取消竞争时只有一个成功
            int d = ordersMapper.cancelOrderById(order.getId());
            if (d == 0) {
                log.info("订单已被其他请求处理（支付或取消），跳过，订单号: {}", orderNo);
                return null;
            }

            // 回滚 MySQL 库存（原子操作）+ 同步递增版本号
            productMapper.update(null,
                    new UpdateWrapper<Product>()
                            .eq("id", productId)
                            .setSql("stock = stock + 1, version = version + 1")
            );

            cancelled[0] = true;
            return null;
        });

        // 事务提交后执行以下操作（若事务回滚则不会执行）
        if (cancelled[0]) {
            // 回滚 Redis 库存（使用 StringRedisTemplate 保证值为纯数字字符串）
            String stockKey = STOCK_KEY + productId;
            stringRedisTemplate.opsForValue().increment(stockKey);

            // 失效商品缓存（Caffeine + Redis + 广播通知其他实例）
            productService.invalidateCache(productId);

            // 删除用户抢购记录，允许重新抢购
            String userKey = USER_SECKILL_KEY + productId + ":" + userId;
            stringRedisTemplate.delete(userKey);

            log.info("订单取消成功（状态流转），订单号: {}, 商品ID: {}, 用户ID: {}", orderNo, productId, userId);
        }
    }

    /**
     * 支付订单（订单状态流转：status=0 → status=1）
     * 数据库条件更新 WHERE status=0 保证支付幂等：
     * - 重复支付只有一次成功
     * - 与超时取消竞争时，只有一个状态流转成功
     */
    @Override
    public PayResultDTO payOrder(String orderNo, Long userId) {
        // 1. 查询订单
        Orders order = getByOrderNo(orderNo);
        if (order == null) {
            throw new BusinessException(ResultCode.NOT_FOUND);
        }

        // 2. 用户归属校验（防止支付他人订单）
        if (!order.getUserId().equals(userId)) {
            throw new BusinessException(ResultCode.FORBIDDEN);
        }

        // 3. 状态校验（给用户友好的错误提示）
        if (order.getStatus() != 0) {
            throw new BusinessException("订单已支付或已取消");
        }

        // 4. 生成支付流水号（UUID 保证全局唯一）
        String transactionId = UUID.randomUUID().toString().replace("-", "");

        // 5. 数据库条件更新实现支付幂等
        //    两个并发支付请求只有一个能更新成功
        int affected = ordersMapper.payOrder(orderNo, userId, transactionId);
        if (affected == 0) {
            throw new BusinessException("订单状态已变更，请刷新重试");
        }

        // 6. 返回支付结果
        PayResultDTO result = new PayResultDTO();
        result.setOrderNo(orderNo);
        result.setStatus(1);
        result.setPayTime(LocalDateTime.now());

        log.info("订单支付成功，订单号: {}, 用户ID: {}, 流水号: {}", orderNo, userId, transactionId);
        return result;
    }

    @Override
    public List<Orders> getUserOrders(Long userId, int page, int size) {
        Page<Orders> pageObj = new Page<>(page, size);
        Page<Orders> result = ordersMapper.selectPage(
                pageObj,
                new LambdaQueryWrapper<Orders>()
                        .eq(Orders::getUserId, userId)
                        .orderByDesc(Orders::getCreateTime)
        );
        return result.getRecords();
    }
}