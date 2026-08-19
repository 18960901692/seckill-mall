package com.sygzcd.seckillmall.service.mq;

import com.sygzcd.seckillmall.entity.Orders;
import com.sygzcd.seckillmall.service.OrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 延时消息发送失败补偿服务。
 * 定时从 Redis 重试队列中取出发送失败的订单号，重新发送延时消息。
 * 核心约束：绝不直接丢弃——未支付订单的库存必须最终被释放，杜绝库存泄漏。
 */
@Slf4j
@Component
public class DelayRetryService {

    private static final String DELAY_RETRY_KEY = "seckill:delay:retry";
    /** 兜底死信队列：重试耗尽 / 兜底取消仍失败的订单号，留待对账（不再静默丢弃） */
    private static final String DELAY_DEAD_KEY = "seckill:delay:dead";
    private static final int MAX_BATCH = 50;
    /** 单个订单最大重新发送次数，超过则转入死信队列，避免单条毒消息无限占队 */
    private static final int MAX_SEND_RETRY = 5;
    /** 未支付订单的自动取消时限，与延时消息 TTL 对齐 */
    private static final int ORDER_TIMEOUT_MINUTES = 30;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private OrderDelayProducer orderDelayProducer;

    @Autowired
    private OrderService orderService;

    /**
     * 每 30 秒扫描重试队列，重新发送失败的延时消息。
     */
    @Scheduled(fixedDelay = 30_000)
    public void retryFailedMessages() {
        int processed = 0;
        while (processed < MAX_BATCH) {
            String orderNo = stringRedisTemplate.opsForList().leftPop(DELAY_RETRY_KEY);
            if (orderNo == null) break;
            try {
                orderDelayProducer.sendDelayMessage(orderNo);
                log.info("重试发送延时消息成功，订单号: {}", orderNo);
            } catch (Exception e) {
                handleSendFailure(orderNo, e);
            }
            processed++;
        }
        if (processed > 0) log.info("延时消息重试完成，本次处理 {} 条", processed);
    }

    /**
     * 发送失败处理：按订单状态决定「继续重试 / 兜底取消 / 入死信队列」，绝不直接丢弃。
     */
    private void handleSendFailure(String orderNo, Exception e) {
        Orders order = orderService.getByOrderNo(orderNo);
        // 订单已不存在 / 已支付 / 已取消：库存已释放或无需释放，安全跳过（无泄漏）
        if (order == null) {
            log.warn("延时消息发送失败且订单不存在（可能已清理），跳过，订单号: {}", orderNo, e);
            return;
        }
        if (order.getStatus() != 0) {
            log.info("延时消息发送失败但订单已非待支付(status={})，无需取消，跳过，订单号: {}", order.getStatus(), orderNo, e);
            return;
        }
        // 仍为未支付：库存仍被占用，必须保证最终释放
        boolean due = order.getCreateTime().plusMinutes(ORDER_TIMEOUT_MINUTES).isBefore(LocalDateTime.now());
        if (due) {
            // 已到自动取消时限：消息发不出去也必须释放库存 → 直接兜底取消（幂等，已支付则 no-op）
            forceCancel(orderNo);
        } else {
            // 仍在 30 分钟可支付窗口内：继续重试发送延时消息，不提前取消（保障用户可支付）
            long cnt = incrRetryCount(orderNo);
            if (cnt <= MAX_SEND_RETRY) {
                stringRedisTemplate.opsForList().rightPush(DELAY_RETRY_KEY, orderNo);
                log.warn("延时消息发送失败（未超时，继续重试），订单号: {}，第{}次", orderNo, cnt, e);
            } else {
                // 重试次数耗尽仍发不出：转入死信队列，由 reconcileDeadQueue 在超时后兜底取消
                stringRedisTemplate.opsForList().rightPush(DELAY_DEAD_KEY, orderNo);
                log.error("延时消息重发{}次仍失败，转入死信队列待兜底取消，订单号: {}", cnt, orderNo, e);
            }
        }
    }

    /**
     * 兜底取消（释放库存）。失败则转入死信队列，由 reconcileDeadQueue 稍后重试，绝不静默丢弃。
     */
    private void forceCancel(String orderNo) {
        try {
            orderService.cancelOrder(orderNo);
            log.warn("延时消息无法发送且订单已超时，已兜底取消未支付订单释放库存，订单号: {}", orderNo);
        } catch (Exception ex) {
            stringRedisTemplate.opsForList().rightPush(DELAY_DEAD_KEY, orderNo);
            log.error("兜底取消未支付订单失败，转入死信队列待人工核查（疑似库存泄漏），订单号: {}", orderNo, ex);
        }
    }

    /**
     * 死信队列对账：每 5 分钟扫描，对「超时未支付」订单兜底取消释放库存；未超时则放回等超时。
     * 保证即使 RabbitMQ 长期不可用，库存也不会被永久占用（彻底消灭原"直接丢弃→库存泄漏"）。
     */
    @Scheduled(fixedDelay = 300_000)
    public void reconcileDeadQueue() {
        int processed = 0;
        while (processed < MAX_BATCH) {
            String orderNo = stringRedisTemplate.opsForList().leftPop(DELAY_DEAD_KEY);
            if (orderNo == null) break;
            Orders order = orderService.getByOrderNo(orderNo);
            if (order == null || order.getStatus() != 0) {
                // 已不存在 / 已支付 / 已取消：无需处理
                log.info("死信队列订单无需兜底取消（已不存在/已支付/已取消），订单号: {}", orderNo);
            } else if (order.getCreateTime().plusMinutes(ORDER_TIMEOUT_MINUTES).isBefore(LocalDateTime.now())) {
                // 已超时未支付：兜底取消释放库存
                try {
                    orderService.cancelOrder(orderNo);
                    log.warn("死信队列兜底取消未支付订单释放库存成功，订单号: {}", orderNo);
                } catch (Exception ex) {
                    stringRedisTemplate.opsForList().rightPush(DELAY_DEAD_KEY, orderNo);
                    log.error("死信队列兜底取消失败，放回死信队列，订单号: {}", orderNo, ex);
                }
            } else {
                // 还没到超时：放回死信队列，等超时后再兜底取消（保障用户可支付窗口）
                stringRedisTemplate.opsForList().rightPush(DELAY_DEAD_KEY, orderNo);
            }
            processed++;
        }
        if (processed > 0) log.info("死信队列对账完成，本次处理 {} 条", processed);
    }

    /** 记录单个订单的重发次数（带 2h 过期，避免 key 堆积）；用于判断是否转入死信队列 */
    private long incrRetryCount(String orderNo) {
        String key = "seckill:delay:retry:cnt:" + orderNo;
        Long cnt = stringRedisTemplate.opsForValue().increment(key);
        if (cnt != null && cnt == 1) {
            stringRedisTemplate.expire(key, Duration.ofHours(2));
        }
        return cnt == null ? 1L : cnt;
    }
}
