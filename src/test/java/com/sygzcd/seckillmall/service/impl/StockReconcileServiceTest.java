package com.sygzcd.seckillmall.service.impl;

import com.sygzcd.seckillmall.entity.Product;
import com.sygzcd.seckillmall.mapper.ProductMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * StockReconcileService 纯单元测试
 *
 * 核心验证（锁内重查 MySQL 逻辑）：
 * 1. 加锁成功后重查 MySQL，用锁内新鲜值 SET Redis
 * 2. tryLock 失败 → 跳过不校正
 * 3. Redis 不存在 → 跳过
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT) // setUp 里的 stub 某些场景不用到，用 lenient
class StockReconcileServiceTest {

    @InjectMocks
    private StockReconcileService service;

    @Mock
    private ProductMapper productMapper;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private RedissonClient redissonClient;

    @Mock
    private RLock lock;

    @Mock
    private ValueOperations<String, String> valueOps;

    private static final long PRODUCT_ID = 1001L;
    private static final String STOCK_KEY = "seckill:stock:" + PRODUCT_ID;
    private static final String LOCK_KEY = "seckill:lock:" + PRODUCT_ID;

    @BeforeEach
    void setUp() {
        when(redissonClient.getLock(LOCK_KEY)).thenReturn(lock);
        when(lock.isHeldByCurrentThread()).thenReturn(true); // 锁被持有时才会调 unlock()
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
    }

    // ================================================================
    // 场景 1：加锁成功 + 锁内 MySQL 新鲜值与 Redis 不一致 → SET
    // ================================================================

    @Nested
    @DisplayName("锁内重查 MySQL 校正 Redis")
    class FreshMysqlInLock {

        @Test
        @DisplayName("循环外 stock=10，秒杀 DECR 到 9，锁内重查 MySQL 也得 9 → 一致跳过")
        void lockFreshMysqlMatchesRedisSkip() throws InterruptedException {
            Product snapshot = new Product();
            snapshot.setId(PRODUCT_ID);
            snapshot.setStock(10);
            when(productMapper.selectAllStock()).thenReturn(List.of(snapshot));

            // Redis 当前值 = 9
            when(valueOps.get(STOCK_KEY)).thenReturn("9");

            when(lock.tryLock(anyLong(), anyLong(), eq(TimeUnit.MILLISECONDS))).thenReturn(true);

            // 锁内重查 MySQL → 9（秒杀事务已提交）
            Product fresh = new Product();
            fresh.setId(PRODUCT_ID);
            fresh.setStock(9);
            when(productMapper.selectById(PRODUCT_ID)).thenReturn(fresh);

            service.reconcileStock();

            // 验证：不 SET
            verify(valueOps, never()).set(anyString(), anyString());
            verify(lock).unlock();
        }

        @Test
        @DisplayName("锁内重查 MySQL 与 Redis 不一致 → SET Redis 为锁内新鲜值")
        void lockFreshMysqlDiffersRedisSet() throws InterruptedException {
            Product snapshot = new Product();
            snapshot.setId(PRODUCT_ID);
            snapshot.setStock(5);
            when(productMapper.selectAllStock()).thenReturn(List.of(snapshot));

            // Redis = 7（有偏差）
            when(valueOps.get(STOCK_KEY)).thenReturn("7");

            when(lock.tryLock(anyLong(), anyLong(), eq(TimeUnit.MILLISECONDS))).thenReturn(true);

            // 锁内重查 MySQL → 5
            Product fresh = new Product();
            fresh.setId(PRODUCT_ID);
            fresh.setStock(5);
            when(productMapper.selectById(PRODUCT_ID)).thenReturn(fresh);

            service.reconcileStock();

            // 验证：SET Redis = 锁内重查的 MySQL 新鲜值 5
            verify(valueOps).set(STOCK_KEY, "5");
        }
    }

    // ================================================================
    // 场景 2：tryLock 失败 → 跳过
    // ================================================================

    @Nested
    @DisplayName("tryLock 失败 → 跳过")
    class LockFailed {

        @Test
        @DisplayName("获取锁超时 → 不 SET，不抛异常")
        void lockTimeoutSkip() throws InterruptedException {
            Product snapshot = new Product();
            snapshot.setId(PRODUCT_ID);
            snapshot.setStock(10);
            when(productMapper.selectAllStock()).thenReturn(List.of(snapshot));
            when(valueOps.get(STOCK_KEY)).thenReturn("5");
            when(lock.tryLock(anyLong(), anyLong(), eq(TimeUnit.MILLISECONDS))).thenReturn(false);

            service.reconcileStock();

            verify(valueOps, never()).set(anyString(), anyString());
            verify(lock, never()).unlock();
        }
    }

    // ================================================================
    // 场景 3：边界
    // ================================================================

    @Nested
    @DisplayName("边界场景")
    class EdgeCases {

        @Test
        @DisplayName("Redis 不存在 → 跳过（连锁都不抢）")
        void redisKeyMissingSkip() {
            Product snapshot = new Product();
            snapshot.setId(PRODUCT_ID);
            snapshot.setStock(10);
            when(productMapper.selectAllStock()).thenReturn(List.of(snapshot));
            when(valueOps.get(STOCK_KEY)).thenReturn(null);

            service.reconcileStock();

            verify(redissonClient, never()).getLock(anyString());
        }

        @Test
        @DisplayName("MySQL 返回空 → 直接跳过")
        void noProductsSkip() {
            when(productMapper.selectAllStock()).thenReturn(Collections.emptyList());

            service.reconcileStock();

            verify(valueOps, never()).get(anyString());
            verify(redissonClient, never()).getLock(anyString());
        }

        @Test
        @DisplayName("加锁后商品被删除 → 跳过")
        void productDeletedUnderLockSkip() throws InterruptedException {
            Product snapshot = new Product();
            snapshot.setId(PRODUCT_ID);
            snapshot.setStock(10);
            when(productMapper.selectAllStock()).thenReturn(List.of(snapshot));
            when(valueOps.get(STOCK_KEY)).thenReturn("5");
            when(lock.tryLock(anyLong(), anyLong(), eq(TimeUnit.MILLISECONDS))).thenReturn(true);

            // 锁内重查 → 商品已不存在
            when(productMapper.selectById(PRODUCT_ID)).thenReturn(null);

            service.reconcileStock();

            verify(valueOps, never()).set(anyString(), anyString());
            verify(lock).unlock();
        }
    }
}
