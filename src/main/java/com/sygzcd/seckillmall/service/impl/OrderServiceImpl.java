package com.sygzcd.seckillmall.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.sygzcd.seckillmall.common.BusinessException;
import com.sygzcd.seckillmall.config.RabbitMQConfig;
import com.sygzcd.seckillmall.entity.Orders;
import com.sygzcd.seckillmall.entity.Product;
import com.sygzcd.seckillmall.mapper.OrdersMapper;
import com.sygzcd.seckillmall.mapper.ProductMapper;
import com.sygzcd.seckillmall.service.OrderService;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class OrderServiceImpl implements OrderService {

    @Autowired
    private OrdersMapper ordersMapper;

    @Autowired
    private ProductMapper productMapper;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private RedissonClient redissonClient;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    private static final String STOCK_KEY = "product:stock:";
    private static final String USER_SECKILL_KEY = "seckill:user:";

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Orders createOrder(Long userId, Long productId) {
        String lockKey = "seckill:lock:" + productId;
        RLock lock = redissonClient.getLock(lockKey);

        try {
            // 获取分布式锁
            boolean locked = lock.tryLock(0, 5, TimeUnit.SECONDS);
            if (!locked) {
                throw new BusinessException("系统繁忙，请稍后重试");
            }

            // 检查用户是否已抢过
            String userKey = USER_SECKILL_KEY + productId + ":" + userId;
            Boolean added = redisTemplate.opsForValue().setIfAbsent(userKey, "1", 1, TimeUnit.HOURS);
            if (added == null || !added) {
                throw new BusinessException("你已经抢过了，请勿重复操作");
            }

            // Redis 预扣库存
            String stockKey = STOCK_KEY + productId;
            Long stock = redisTemplate.opsForValue().decrement(stockKey);
            if (stock == null || stock < 0) {
                // 库存不足，回滚 Redis
                redisTemplate.opsForValue().increment(stockKey);
                redisTemplate.delete(userKey);
                throw new BusinessException("库存不足");
            }

            // 生成唯一订单号
            String orderNo = UUID.randomUUID().toString().replace("-", "").substring(0, 32);

            // 创建订单
            Orders order = new Orders();
            order.setOrderNo(orderNo);
            order.setUserId(userId);
            order.setProductId(productId);
            order.setStatus(0); // 未支付
            ordersMapper.insert(order);

            // MySQL 乐观锁扣库存
            Product product = productMapper.selectById(productId);
            int affected = productMapper.decreaseStockWithVersion(productId, product.getVersion());
            if (affected == 0) {
                // 乐观锁失败，回滚 Redis
                redisTemplate.opsForValue().increment(stockKey);
                redisTemplate.delete(userKey);
                throw new BusinessException("库存不足");
            }

            // 发送延时消息（30分钟后自动取消）
            rabbitTemplate.convertAndSend(
                    RabbitMQConfig.ORDER_DELAY_EXCHANGE,
                    RabbitMQConfig.ORDER_DELAY_ROUTING_KEY,
                    orderNo
            );

            return order;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException("系统繁忙");
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    @Override
    public Orders getByOrderNo(String orderNo) {
        return ordersMapper.selectOne(
                new LambdaQueryWrapper<Orders>().eq(Orders::getOrderNo, orderNo)
        );
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void cancelOrder(String orderNo) {
        Orders order = getByOrderNo(orderNo);
        if (order == null || order.getStatus() != 0) {
            return; // 订单不存在或已处理
        }

        // 更新订单状态为已取消
        order.setStatus(2);
        ordersMapper.updateById(order);

        // 回滚 MySQL 库存
        productMapper.update(null, 
                new UpdateWrapper<Product>()
                        .eq("id", order.getProductId())
                        .setSql("stock = stock + 1")
        );

        // 回滚 Redis 库存
        String stockKey = STOCK_KEY + order.getProductId();
        redisTemplate.opsForValue().increment(stockKey);

        // 删除用户抢购记录
        String userKey = USER_SECKILL_KEY + order.getProductId() + ":" + order.getUserId();
        redisTemplate.delete(userKey);
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
