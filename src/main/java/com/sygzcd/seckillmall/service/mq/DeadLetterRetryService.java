package com.sygzcd.seckillmall.service.mq;

import com.sygzcd.seckillmall.config.RabbitMQConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 死信消费失败补偿服务
 * 定时扫描 Redis 补偿队列，将消费失败的订单号带 60s 退避重新投递到死信队列。
 *
 * 可靠队列设计（M-7）：RPOPLPUSH 原子迁移到 processing 队列 + 重投成功后 LREM 删除。
 * 旧实现"先 leftPop 再 convertAndSend，失败只打日志"会在重投异常时造成 Redis/MQ 两边皆无；
 * 现在重投失败的数据保留在 processing 队列，下一轮 recoverProcessing 继续重试，绝不静默丢弃。
 * 消费端 cancelOrderById 是 WHERE status=0 条件更新，重复重投天然幂等。
 */
@Slf4j
@Component
public class DeadLetterRetryService {

    private static final String DEAD_LETTER_RETRY_KEY = "seckill:dead:retry";
    /** 处理队列：已从补偿队列取出、等待确认重投成功的订单号（宕机/重投失败时的兜底位置） */
    private static final String DEAD_LETTER_PROCESSING_KEY = "seckill:dead:retry:processing";
    private static final int MAX_BATCH = 50;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    /**
     * 每 60 秒扫描一次补偿队列，重新投递失败的订单到死信队列（天然 60s 退避）。
     * 执行顺序：先恢复 processing 残留 → 再处理补偿队列新数据。
     */
    @Scheduled(fixedDelay = 60_000)
    public void retryDeadLetters() {
        // 1. 先恢复上轮残留在 processing 队列的数据（重投失败或应用宕机）
        recoverProcessing();

        // 2. 原子迁移补偿队列数据到 processing，再逐条重投
        int processed = 0;
        while (processed < MAX_BATCH) {
            Object orderNoObj = redisTemplate.opsForList()
                    .rightPopAndLeftPush(DEAD_LETTER_RETRY_KEY, DEAD_LETTER_PROCESSING_KEY);
            if (orderNoObj == null) {
                break;
            }

            String orderNo = orderNoObj.toString();
            if (republish(orderNo)) {
                // 重投成功：从 processing 队列删除该条
                redisTemplate.opsForList().remove(DEAD_LETTER_PROCESSING_KEY, 1, orderNoObj);
                log.info("补偿队列重新投递成功，订单号: {}", orderNo);
            } else {
                // 重投失败：数据保留在 processing 队列，下一轮 recoverProcessing 继续，绝不丢弃
                log.error("补偿队列重新投递失败，保留在处理队列下轮重试，订单号: {}", orderNo);
            }
            processed++;
        }

        if (processed > 0) {
            log.info("死信补偿队列扫描完成，本次处理 {} 条", processed);
        }
    }

    /**
     * 恢复 processing 队列中的残留数据（窥探不弹出，重投成功后才删除）。
     */
    private void recoverProcessing() {
        List<Object> pending = redisTemplate.opsForList()
                .range(DEAD_LETTER_PROCESSING_KEY, 0, MAX_BATCH - 1);
        if (pending == null || pending.isEmpty()) {
            return;
        }
        for (Object orderNoObj : pending) {
            String orderNo = orderNoObj.toString();
            if (republish(orderNo)) {
                redisTemplate.opsForList().remove(DEAD_LETTER_PROCESSING_KEY, 1, orderNoObj);
                log.info("处理队列残留消息重新投递成功，订单号: {}", orderNo);
            } else {
                log.error("处理队列残留消息重投失败，继续保留，订单号: {}", orderNo);
            }
        }
    }

    /**
     * 重新投递到死信队列（模拟延时消息到达）。
     * @return true=已送达 Broker；false=投递异常，调用方必须保留数据待下轮重试
     */
    private boolean republish(String orderNo) {
        try {
            rabbitTemplate.convertAndSend(
                    RabbitMQConfig.ORDER_CANCEL_EXCHANGE,
                    RabbitMQConfig.ORDER_CANCEL_ROUTING_KEY,
                    orderNo
            );
            return true;
        } catch (Exception e) {
            log.error("重新投递到死信队列异常，订单号: {}", orderNo, e);
            return false;
        }
    }
}
