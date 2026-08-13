package com.sygzcd.seckillmall.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sygzcd.seckillmall.entity.Orders;
import com.sygzcd.seckillmall.entity.Product;
import com.sygzcd.seckillmall.mapper.OrdersMapper;
import com.sygzcd.seckillmall.mapper.ProductMapper;
import com.sygzcd.seckillmall.service.OrderService;
import com.sygzcd.seckillmall.service.ProductService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/**
 * 订单服务实现
 * 职责：订单查询、取消订单（释放库存）
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
     * 取消订单（编程式事务，确保 Redis 操作和缓存失效在事务提交后执行）
     * 1. 事务内：物理删除订单 + 回滚 MySQL 库存
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
        boolean[] deleted = {false};

        // 编程式事务：只包含 DB 操作，事务提交后才执行 Redis/缓存操作
        transactionTemplate.execute(status -> {
            // 物理删除订单（配合唯一索引 uk_user_product，允许用户取消后重新抢购）
            // 并发场景：MQ死信消息与手动取消同时触发时，先执行的事务删除成功并回滚库存，
            // 后执行的事务 deleteById 返回 0，必须直接返回，避免库存被回滚两次
            int d = ordersMapper.deleteById(order.getId());
            if (d == 0) {
                log.info("订单已被其他请求取消，跳过库存回滚，订单号: {}", orderNo);
                return null;
            }

            // 回滚 MySQL 库存（原子操作）+ 同步递增版本号
            // 保持 version 与库存更新次数一致，避免后续秒杀乐观锁因版本号滞后而误杀
            productMapper.update(null,
                    new UpdateWrapper<Product>()
                            .eq("id", productId)
                            .setSql("stock = stock + 1, version = version + 1")
            );

            deleted[0] = true;
            return null;
        });

        // 事务提交后执行以下操作（若事务回滚则不会执行）
        if (deleted[0]) {
            // 回滚 Redis 库存
            String stockKey = STOCK_KEY + productId;
            redisTemplate.opsForValue().increment(stockKey);

            // 失效商品缓存（Caffeine + Redis + 广播通知其他实例）
            productService.invalidateCache(productId);

            // 删除用户抢购记录，允许重新抢购
            String userKey = USER_SECKILL_KEY + productId + ":" + userId;
            redisTemplate.delete(userKey);

            log.info("订单取消成功（物理删除），订单号: {}, 商品ID: {}, 用户ID: {}", orderNo, productId, userId);
        }
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
