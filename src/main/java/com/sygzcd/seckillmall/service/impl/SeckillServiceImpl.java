package com.sygzcd.seckillmall.service.impl;

import com.sygzcd.seckillmall.common.BusinessException;
import com.sygzcd.seckillmall.common.ResultCode;
import com.sygzcd.seckillmall.entity.Orders;
import com.sygzcd.seckillmall.entity.Product;
import com.sygzcd.seckillmall.mapper.OrdersMapper;
import com.sygzcd.seckillmall.mapper.ProductMapper;
import com.sygzcd.seckillmall.service.BloomFilterService;
import com.sygzcd.seckillmall.service.ProductService;
import com.sygzcd.seckillmall.service.SeckillService;
import com.sygzcd.seckillmall.service.mq.OrderDelayProducer;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 秒杀服务实现
 * 完整链路：布隆过滤器 → 用户防重 → 三级缓存查库存 → 分布式锁 → Redis预扣 → MySQL乐观锁 → 延时消息
 */
@Slf4j
@Service
public class SeckillServiceImpl implements SeckillService {

    @Autowired
    private BloomFilterService bloomFilterService;

    @Autowired
    private ProductService productService;

    @Autowired
    private ProductMapper productMapper;

    @Autowired
    private OrdersMapper ordersMapper;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private RedissonClient redissonClient;

    @Autowired
    private OrderDelayProducer orderDelayProducer;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private static final String STOCK_KEY = "seckill:stock:";
    private static final String USER_SECKILL_KEY = "seckill:user:";
    private static final String LOCK_KEY = "seckill:lock:";

    @Override
    public Orders seckill(Long userId, Long productId) {
        // 1. 布隆过滤器防缓存穿透
        if (!bloomFilterService.mightContain(productId)) {
            throw new BusinessException("商品不存在");
        }

        // 2. 用户防重检查（同一用户同一商品只能抢一次）
        String userKey = USER_SECKILL_KEY + productId + ":" + userId;
        Boolean added = redisTemplate.opsForValue().setIfAbsent(userKey, "1", 1, TimeUnit.HOURS);
        if (added == null || !added) {
            throw new BusinessException("你已经抢过了，请勿重复操作");
        }

        // 3. 三级缓存查库存（快速失败）
        Integer stock = productService.getStock(productId);
        if (stock == null || stock <= 0) {
            redisTemplate.delete(userKey);
            throw new BusinessException("商品已售罄");
        }

        // 4. 分布式锁串行化
        String lockKey = LOCK_KEY + productId;
        RLock lock = redissonClient.getLock(lockKey);
        String stockKey = STOCK_KEY + productId;
        boolean locked = false;
        boolean decremented = false;

        try {
            locked = lock.tryLock(3, 10, TimeUnit.SECONDS);
            if (!locked) {
                redisTemplate.delete(userKey);
                throw new BusinessException("系统繁忙，请稍后重试");
            }

            // 5. Redis 预扣库存（原子操作）
            Long remainStock = redisTemplate.opsForValue().decrement(stockKey);
            decremented = true;
            if (remainStock == null || remainStock < 0) {
                redisTemplate.opsForValue().increment(stockKey);
                redisTemplate.delete(userKey);
                throw new BusinessException("商品已售罄");
            }

            // 6. MySQL 操作（编程式事务，事务提交后才解锁，避免超卖窗口）
            Orders order;
            try {
                order = transactionTemplate.execute(status -> {
                    Product product = productMapper.selectById(productId);
                    if (product == null) {
                        throw new BusinessException("商品不存在");
                    }

                    int affected = productMapper.decreaseStockWithVersion(productId, product.getVersion());
                    if (affected == 0) {
                        throw new BusinessException("手慢了，商品已售罄");
                    }

                    // 7. 创建订单（Redis防重key 兜底幂等，保证同一用户同一商品只能下一单）
                    //    金额采用快照设计：下单时从商品表复制价格到订单表，避免商品调价影响历史订单
                    String orderNo = UUID.randomUUID().toString().replace("-", "").substring(0, 32);
                    Orders newOrder = new Orders();
                    newOrder.setOrderNo(orderNo);
                    newOrder.setUserId(userId);
                    newOrder.setProductId(productId);
                    newOrder.setAmount(product.getPrice()); // 订单快照：锁定下单时的商品价格
                    newOrder.setStatus(0);
                    ordersMapper.insert(newOrder);

                    return newOrder;
                });
                
                // 事务提交后：失效商品缓存 + 发送延时消息
                // 事务已提交，此时消息发送不会导致"事务回滚但消息已发出"的不一致
                productService.invalidateCache(productId);
                try {
                    orderDelayProducer.sendDelayMessage(order.getOrderNo());
                } catch (Exception e) {
                    // 发送失败将订单号写入 Redis 延迟重试队列，由定时任务补偿
                    log.error("延时消息发送失败，订单号: {}，已加入重试队列", order.getOrderNo(), e);
                    redisTemplate.opsForList().rightPush("seckill:delay:retry", order.getOrderNo());
                }
                log.info("秒杀下单成功，订单号: {}, 商品ID: {}, 用户ID: {}", order.getOrderNo(), productId, userId);
            } catch (BusinessException e) {
                // MySQL 操作失败（乐观锁冲突等），回滚 Redis 预扣和防重标记
                redisTemplate.opsForValue().increment(stockKey);
                redisTemplate.delete(userKey);
                throw e;
            }

            return order;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            // 防御性补偿：如果 DECR 已执行，须回补 Redis 库存
            if (decremented) {
                redisTemplate.opsForValue().increment(stockKey);
            }
            redisTemplate.delete(userKey);
            throw new BusinessException("系统繁忙");
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("秒杀下单异常，用户ID: {}, 商品ID: {}", userId, productId, e);
            redisTemplate.opsForValue().increment(stockKey); // 回滚 Redis 预扣库存
            redisTemplate.delete(userKey);
            // 唯一索引冲突兜底：order_no唯一索引冲突（UUID碰撞概率极低，主要靠Redis防重key）
            if (e instanceof org.springframework.dao.DuplicateKeyException) {
                throw new BusinessException(ResultCode.REPEAT_ORDER);
            }
            throw new BusinessException("秒杀失败，请稍后重试");
        } finally {
            if (locked) {
                lock.unlock();
            }
        }
    }
}
