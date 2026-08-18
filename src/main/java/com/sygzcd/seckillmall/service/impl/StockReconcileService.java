package com.sygzcd.seckillmall.service.impl;

import com.sygzcd.seckillmall.entity.Product;
import com.sygzcd.seckillmall.mapper.ProductMapper;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 库存对账服务
 * 兜底机制：以 MySQL 为 Source of Truth，定时校正 Redis 库存
 * 解决 JVM 宕机、机器断电等极端异常导致的 Redis 预扣库存丢失问题
 *
 * 三层兜底体系：
 * 1. 代码异常补偿（try-catch 回滚 Redis）—— JVM 正常但业务失败
 * 2. MQ 重试 / Redis 补偿队列 —— 临时性故障
 * 3. 库存对账（本服务）—— JVM 宕机、断电等代码补偿无法覆盖的异常
 */
@Slf4j
@Service
public class StockReconcileService {

    private static final String STOCK_KEY_PREFIX = "seckill:stock:";
    private static final String LOCK_KEY_PREFIX = "seckill:lock:";
    private static final long LOCK_WAIT_MS = 100;
    private static final long LOCK_LEASE_MS = 5000;

    @Autowired
    private ProductMapper productMapper;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private RedissonClient redissonClient;

    /**
     * 每 60 秒执行一次库存对账
     * 以 MySQL 为准，校正 Redis 库存
     */
    @Scheduled(fixedDelay = 60_000)
    public void reconcileStock() {
        log.info("===== 开始库存对账 =====");
        int total = 0;
        int fixed = 0;
        int skipped = 0;

        List<Product> products = productMapper.selectAllStock();
        if (products == null || products.isEmpty()) {
            log.info("无商品数据，跳过对账");
            return;
        }

        for (Product product : products) {
            total++;
            Long productId = product.getId();
            Integer mysqlStock = product.getStock();
            String stockKey = STOCK_KEY_PREFIX + productId;

            String redisStockStr = stringRedisTemplate.opsForValue().get(stockKey);
            if (redisStockStr == null) {
                log.warn("商品[{}] Redis 库存不存在，跳过对账（可能尚未预热）", productId);
                skipped++;
                continue;
            }

            int redisStock;
            try {
                redisStock = Integer.parseInt(redisStockStr);
            } catch (NumberFormatException e) {
                log.error("商品[{}] Redis 库存格式异常: {}", productId, redisStockStr);
                skipped++;
                continue;
            }

            if (mysqlStock.equals(redisStock)) {
                continue;
            }

            log.warn("商品[{}] 库存不一致 - MySQL: {}, Redis: {}，尝试校正", productId, mysqlStock, redisStock);

            RLock lock = redissonClient.getLock(LOCK_KEY_PREFIX + productId);
            boolean locked = false;
            try {
                locked = lock.tryLock(LOCK_WAIT_MS, LOCK_LEASE_MS, TimeUnit.MILLISECONDS);
                if (!locked) {
                    log.info("商品[{}] 有秒杀进行中，跳过本次对账，下次再校验", productId);
                    skipped++;
                    continue;
                }

                // 双检：加锁后再查一次，确认仍不一致
                String redisStockStr2 = stringRedisTemplate.opsForValue().get(stockKey);
                if (redisStockStr2 != null) {
                    int redisStock2 = Integer.parseInt(redisStockStr2);
                    if (mysqlStock.equals(redisStock2)) {
                        log.info("商品[{}] 加锁后复查已一致（可能秒杀事务已提交），跳过", productId);
                        continue;
                    }
                }

                // 校正 Redis 库存为 MySQL 值
                stringRedisTemplate.opsForValue().set(stockKey, String.valueOf(mysqlStock));
                log.warn("商品[{}] 库存校正完成 - Redis: {} -> {}（MySQL 为准）", productId, redisStock, mysqlStock);
                fixed++;

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("商品[{}] 获取锁时被中断", productId, e);
            } finally {
                if (locked && lock.isHeldByCurrentThread()) {
                    lock.unlock();
                }
            }
        }

        log.info("===== 库存对账完成 - 总计: {}, 校正: {}, 跳过: {} =====", total, fixed, skipped);
    }
}