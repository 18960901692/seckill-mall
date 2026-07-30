package com.sygzcd.seckillmall.service.impl;

import com.sygzcd.seckillmall.common.BusinessException;
import com.sygzcd.seckillmall.config.RabbitMQConfig;
import com.sygzcd.seckillmall.entity.Orders;
import com.sygzcd.seckillmall.entity.Product;
import com.sygzcd.seckillmall.mapper.OrdersMapper;
import com.sygzcd.seckillmall.mapper.ProductMapper;
import com.sygzcd.seckillmall.service.BloomFilterService;
import com.sygzcd.seckillmall.service.ProductService;
import com.sygzcd.seckillmall.service.SeckillService;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    private RabbitTemplate rabbitTemplate;

    private static final String STOCK_KEY = "seckill:stock:";
    private static final String USER_SECKILL_KEY = "seckill:user:";
    private static final String LOCK_KEY = "seckill:lock:";

    @Override
    @Transactional(rollbackFor = Exception.class)
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
            redisTemplate.delete(userKey); // 回滚防重标记
            throw new BusinessException("商品已售罄");
        }

        // 4. 分布式锁串行化（Redisson 可重入锁 + 看门狗自动续期）
        String lockKey = LOCK_KEY + productId;
        RLock lock = redissonClient.getLock(lockKey);

        try {
            // 尝试获取锁，最多等待3秒，持有锁最多10秒
            boolean locked = lock.tryLock(3, 10, TimeUnit.SECONDS);
            if (!locked) {
                redisTemplate.delete(userKey); // 回滚防重标记
                throw new BusinessException("系统繁忙，请稍后重试");
            }

            // 5. Redis 预扣库存（原子操作）
            String stockKey = STOCK_KEY + productId;
            Long remainStock = redisTemplate.opsForValue().decrement(stockKey);
            if (remainStock == null || remainStock < 0) {
                // 库存不足，回滚 Redis 和防重标记
                redisTemplate.opsForValue().increment(stockKey);
                redisTemplate.delete(userKey);
                throw new BusinessException("商品已售罄");
            }

            // 6. MySQL 乐观锁扣库存（最终兜底）
            Product product = productMapper.selectById(productId);
            if (product == null) {
                throw new BusinessException("商品不存在");
            }

            int affected = productMapper.decreaseStockWithVersion(productId, product.getVersion());
            if (affected == 0) {
                // 乐观锁失败，回滚 Redis 预扣和防重标记
                redisTemplate.opsForValue().increment(stockKey);
                redisTemplate.delete(userKey);
                throw new BusinessException("手慢了，商品已售罄");
            }

            // 7. 创建订单（唯一索引保证幂等）
            String orderNo = UUID.randomUUID().toString().replace("-", "").substring(0, 32);
            Orders order = new Orders();
            order.setOrderNo(orderNo);
            order.setUserId(userId);
            order.setProductId(productId);
            order.setStatus(0); // 0=未支付
            ordersMapper.insert(order);

            // 8. 发送延时消息（30分钟未支付自动取消）
            try {
                rabbitTemplate.convertAndSend(
                        RabbitMQConfig.ORDER_DELAY_EXCHANGE,
                        RabbitMQConfig.ORDER_DELAY_ROUTING_KEY,
                        orderNo
                );
                log.info("秒杀下单成功，订单号: {}, 商品ID: {}, 用户ID: {}", orderNo, productId, userId);
            } catch (Exception e) {
                log.error("发送延时消息失败，订单号: {}", orderNo, e);
                // 消息发送失败不影响下单，可记录补偿日志后续重试
            }

            return order;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            redisTemplate.delete(userKey); // 回滚防重标记
            throw new BusinessException("系统繁忙");
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("秒杀下单异常，用户ID: {}, 商品ID: {}", userId, productId, e);
            redisTemplate.delete(userKey); // 回滚防重标记
            throw new BusinessException("秒杀失败，请稍后重试");
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
