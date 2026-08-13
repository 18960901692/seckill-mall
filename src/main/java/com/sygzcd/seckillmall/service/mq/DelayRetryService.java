package com.sygzcd.seckillmall.service.mq;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 延时消息发送失败补偿服务
 * 定时从 Redis 重试队列中取出发送失败的订单号，重新发送延时消息
 */
@Slf4j
@Component
public class DelayRetryService {

    private static final String DELAY_RETRY_KEY = "seckill:delay:retry";
    private static final int MAX_RETRY_COUNT = 3;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private OrderDelayProducer orderDelayProducer;

    /**
     * 每 30 秒扫描一次重试队列，重新发送失败的延时消息
     * 每次最多处理 50 条，避免单次执行时间过长
     */
    @Scheduled(fixedDelay = 30_000)
    public void retryFailedMessages() {
        int processed = 0;
        int maxBatch = 50;

        while (processed < maxBatch) {
            // LPOP 取出一个待重试的订单号
            Object orderNoObj = redisTemplate.opsForList().leftPop(DELAY_RETRY_KEY);
            if (orderNoObj == null) {
                break; // 队列为空
            }

            String orderNo = orderNoObj.toString();
            try {
                orderDelayProducer.sendDelayMessage(orderNo);
                log.info("重试发送延时消息成功，订单号: {}", orderNo);
                processed++;
            } catch (Exception e) {
                log.error("重试发送延时消息失败，订单号: {}，已丢弃", orderNo, e);
                // 重试仍失败，丢弃该消息（避免无限重试积压）
                // 该订单不会自动取消，可人工介入处理
                processed++;
            }
        }

        if (processed > 0) {
            log.info("延时消息重试完成，本次处理 {} 条", processed);
        }
    }
}