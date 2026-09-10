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
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 秒杀服务实现
 * 完整链路：布隆过滤器 → 用户防重(Redis SETNX + DB兜底) → 三级缓存查库存 → 分布式锁 → Redis预扣 → MySQL乐观锁 → 延时消息
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
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private RedissonClient redissonClient;

    @Autowired
    private OrderDelayProducer orderDelayProducer;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private static final String STOCK_KEY = "seckill:stock:";
    private static final String USER_SECKILL_KEY = "seckill:user:";
    private static final String LOCK_KEY = "seckill:lock:";

    /** 延时消息发送失败重试队列（必须与 DelayRetryService.DELAY_RETRY_KEY 保持一致） */
    private static final String DELAY_RETRY_KEY = "seckill:delay:retry";

    @Override
    public Orders seckill(Long userId, Long productId) {
        // 1. 布隆过滤器防缓存穿透
        if (!bloomFilterService.mightContain(productId)) {
            throw new BusinessException("商品不存在");
        }

        // 2. 用户防重检查（双重保障）
        // 2.1 Redis SETNX：高性能快速拦截，99% 的重复请求在此被挡住
        String userKey = USER_SECKILL_KEY + productId + ":" + userId;
        Boolean added = stringRedisTemplate.opsForValue()
                .setIfAbsent(userKey, "1", 1, TimeUnit.HOURS);
        if (added == null || !added) {
            throw new BusinessException("你已经抢过了，请勿重复操作");
        }

        // 2.2 DB 兜底检查：防止 Redis TTL 过期或故障导致的重复下单
        // 只查 status IN (0,1) 的有效订单，已取消（status=2）的允许重购
        Orders existing = ordersMapper.selectValidOrder(userId, productId);
        if (existing != null) {
            stringRedisTemplate.delete(userKey);
            throw new BusinessException("你已经有该商品的订单，请勿重复下单");
        }

        // 3. 三级缓存查库存（快速失败）
        Integer stock = productService.getStock(productId);
        if (stock == null || stock <= 0) {
            stringRedisTemplate.delete(userKey);
            throw new BusinessException("商品已售罄");
        }

        // 4. 加锁临界区：Redis 预扣 → MySQL 事务提交
        //    该方法返回即代表"订单已提交成功"，失败时由其内部完成补偿
        Orders order = seckillWithLock(userId, productId, userKey);

        // 5. 事务提交后的收尾动作（失效缓存 + 发延时消息）
        //    刻意放在补偿范围之外：订单已生效，这些动作失败既不能回补库存，也不该把成功伪装成失败
        afterCommit(productId, order);

        log.info("秒杀下单成功，订单号: {}, 商品ID: {}, 用户ID: {}", order.getOrderNo(), productId, userId);
        return order;
    }

    /**
     * 加锁临界区：Redis 预扣库存 → MySQL 事务（乐观锁扣库存 + 创建订单）。
     *
     * 本方法的核心约束（补偿规则）：
     * 只有「Redis 确实扣过」且「订单事务未提交」时才回补 Redis 库存。
     * 事务一旦提交，库存即为"已真实卖出"，任何后续异常都不得回补，否则会造成库存虚增（少卖）。
     *
     * @return 已提交的订单；抛出异常即代表下单失败且 Redis 侧已完成补偿
     */
    private Orders seckillWithLock(Long userId, Long productId, String userKey) {
        String lockKey = LOCK_KEY + productId;
        RLock lock = redissonClient.getLock(lockKey);
        String stockKey = STOCK_KEY + productId;
        boolean locked = false;
        // Redis 预扣是否已执行：决定失败时是否需要回补库存
        boolean redisDeducted = false;
        // 订单事务是否已提交：提交后禁止任何库存回补
        boolean committed = false;

        try {
            // 1. 分布式锁串行化（同一商品串行执行，防超卖第一层）
            locked = lock.tryLock(3, 10, TimeUnit.SECONDS);
            if (!locked) {
                throw new BusinessException("系统繁忙，请稍后重试");
            }

            // 2. Redis 预扣库存（原子操作，使用 StringRedisTemplate 保证值为纯数字字符串）
            Long remainStock = stringRedisTemplate.opsForValue().decrement(stockKey);
            redisDeducted = true;
            if (remainStock == null || remainStock < 0) {
                // 库存不足：此处不直接回补，交由 compensateRedis 统一处理，避免与 catch 中的回补重复叠加
                throw new BusinessException("商品已售罄");
            }

            // 3. MySQL 事务：扣库存 + 创建订单（事务提交是"订单生效"的唯一分界点）
            Orders order = transactionTemplate.execute(status -> {
                Product product = productMapper.selectById(productId);
                if (product == null) {
                    throw new BusinessException("商品不存在");
                }

                int affected = productMapper.decreaseStockWithVersion(productId, product.getVersion());
                if (affected == 0) {
                    throw new BusinessException("手慢了，商品已售罄");
                }

                // 创建订单（Redis防重key + DB状态检查 双重保障幂等）
                // 金额采用快照设计：下单时从商品表复制价格到订单表，避免商品调价影响历史订单
                String orderNo = UUID.randomUUID().toString().replace("-", "").substring(0, 32);
                Orders newOrder = new Orders();
                newOrder.setOrderNo(orderNo);
                newOrder.setUserId(userId);
                newOrder.setProductId(productId);
                newOrder.setAmount(product.getPrice()); // 订单快照：锁定下单时的商品价格
                newOrder.setStatus(0);
                newOrder.setCreateTime(LocalDateTime.now()); // 使用应用时区（Asia/Shanghai），避免数据库时区问题
                ordersMapper.insert(newOrder);

                return newOrder;
            });

            // 事务已提交：订单生效，从此处起不再允许回补 Redis 库存
            committed = true;
            return order;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            compensateRedis(stockKey, userKey, redisDeducted, committed);
            throw new BusinessException("系统繁忙");
        } catch (BusinessException e) {
            // 业务失败（商品不存在 / 乐观锁冲突 / 库存不足等）：回补 Redis 预扣 + 释放防重标记
            compensateRedis(stockKey, userKey, redisDeducted, committed);
            throw e;
        } catch (Exception e) {
            log.error("秒杀下单异常，用户ID: {}, 商品ID: {}", userId, productId, e);
            compensateRedis(stockKey, userKey, redisDeducted, committed);
            // 唯一索引冲突兜底：order_no唯一索引冲突（UUID碰撞概率极低，主要靠Redis防重key）
            if (e instanceof DuplicateKeyException) {
                throw new BusinessException(ResultCode.REPEAT_ORDER);
            }
            throw new BusinessException("秒杀失败，请稍后重试");
        } finally {
            if (locked) {
                try {
                    lock.unlock();
                } catch (Exception e) {
                    // 解锁失败不影响下单结果：锁设有 leaseTime，到期会自动释放
                    log.warn("释放分布式锁失败（锁将自动过期），商品ID: {}", productId, e);
                }
            }
        }
    }

    /**
     * 失败补偿：仅当「Redis 已预扣」且「订单未提交」时回补库存。
     *
     * 为什么必须判断 committed：
     * 旧实现在通用 catch 中无条件 increment，而 try 的范围包含事务提交之后的收尾动作
     * （失效缓存、发延时消息）。这些动作一旦抛异常，就会给一笔「已提交成功」的订单回补库存
     * → 库存虚增 → 少卖，并在窗口内可能超卖。
     * 结论：补偿的触发条件应该是"我知道业务没成功"，而不是"我看见抛异常了"。
     */
    private void compensateRedis(String stockKey, String userKey, boolean redisDeducted, boolean committed) {
        if (committed) {
            // 订单已生效、库存已真实卖出：不做任何回退
            return;
        }
        try {
            if (redisDeducted) {
                stringRedisTemplate.opsForValue().increment(stockKey);
                log.info("秒杀失败，已回补 Redis 预扣库存，stockKey={}", stockKey);
            }
            // 释放防重标记，允许用户重新下单
            stringRedisTemplate.delete(userKey);
        } catch (Exception e) {
            // 补偿自身失败不得覆盖原始异常；残留偏差由 StockReconcileService 每 60s 以 MySQL 为准校正
            log.error("Redis 补偿失败（库存偏差将由库存对账任务校正），stockKey={}", stockKey, e);
        }
    }

    /**
     * 事务提交后的收尾动作：失效商品缓存 + 发送延时消息。
     *
     * 订单此时已生效，因此本方法**只记录日志、绝不向外抛异常**——其失败不得改变下单结果：
     *  - 缓存失效失败：最坏是短时间内读到旧缓存，TTL 到期后自愈；
     *  - 延时消息发送失败：订单号写入 Redis 重试队列，由 DelayRetryService 定时补偿（第 2 层）；
     *    若连重试队列都写不进去，仍有 OrderReconcileService 每 60s 扫 DB 兜底取消（第 3 层）。
     */
    private void afterCommit(Long productId, Orders order) {
        try {
            productService.invalidateCache(productId);
        } catch (Exception e) {
            log.error("下单后失效商品缓存失败（不影响下单结果），商品ID: {}", productId, e);
        }

        try {
            orderDelayProducer.sendDelayMessage(order.getOrderNo());
        } catch (Exception e) {
            // 发送失败将订单号写入 Redis 延迟重试队列
            // （使用 StringRedisTemplate，与 DelayRetryService 读取端一致），由定时任务补偿
            log.error("延时消息发送失败，订单号: {}，已加入重试队列", order.getOrderNo(), e);
            try {
                stringRedisTemplate.opsForList().rightPush(DELAY_RETRY_KEY, order.getOrderNo());
            } catch (Exception ex) {
                log.error("延时消息重试队列写入失败，订单号: {}，将由全局对账任务兜底取消", order.getOrderNo(), ex);
            }
        }
    }
}
