package com.sygzcd.seckillmall.service.mq;

import com.sygzcd.seckillmall.config.RabbitMQConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 死信消费失败补偿服务
 * 定时扫描 Redis 补偿队列，将重试耗尽的订单号重新投递到死信队列
 */
@Slf4j
@Component
public class DeadLetterRetryService {

    private static final String DEAD_LETTER_RETRY_KEY = "seckill:dead:retry";
    private static final int MAX_BATCH = 50;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    /**
     * 每 60 秒扫描一次补偿队列，重新投递失败的订单到死信队列
     */
    @Scheduled(fixedDelay = 60_000)
    public void retryDeadLetters() {
        int processed = 0;

        while (processed < MAX_BATCH) {
            Object orderNoObj = redisTemplate.opsForList().leftPop(DEAD_LETTER_RETRY_KEY);
            if (orderNoObj == null) {
                break;
            }

            String orderNo = orderNoObj.toString();
            try {
                // 重新投递到死信队列（模拟延时消息到达）
                rabbitTemplate.convertAndSend(
                        RabbitMQConfig.ORDER_CANCEL_EXCHANGE,
                        RabbitMQConfig.ORDER_CANCEL_ROUTING_KEY,
                        orderNo
                );
                log.info("补偿队列重新投递成功，订单号: {}", orderNo);
                processed++;
            } catch (Exception e) {
                log.error("补偿队列重新投递失败，订单号: {}，已永久丢弃，需人工核查", orderNo, e);
                processed++;
            }
        }

        if (processed > 0) {
            log.info("死信补偿队列扫描完成，本次处理 {} 条", processed);
        }
    }
}