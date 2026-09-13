package com.sygzcd.seckillmall.service.mq;

import com.sygzcd.seckillmall.config.RabbitMQConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 延时消息生产者
 * 秒杀下单成功后发送延时消息，30分钟后由消费者检查并取消未支付订单
 */
@Slf4j
@Service
public class OrderDelayProducer {

    /**
     * 等待 Broker Confirm 的最长时间。
     * 超时按"发送失败"处理：异常上抛由调用方写 Redis 重试队列，而不是让消息"自以为发送成功"。
     */
    private static final long CONFIRM_TIMEOUT_SECONDS = 3;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    /**
     * 发送延时消息（订单超时自动取消），并同步确认消息已被 Broker 可靠接收、成功路由到队列。
     *
     * 依赖（application.yaml 已开启）：
     *  - publisher-confirm-type=correlated：消息到达 Exchange 后 Broker 回 ack/nack；
     *  - mandatory=true：路由不到队列时消息被 Return 回生产者。
     * 注意：路由失败时 Confirm 往往仍是 ack（消息确实到达了 Exchange），因此 nack 与 Returned 必须分别检查。
     *
     * 失败语义：连接异常 / NACK / 路由退回 / 等待超时一律抛 AmqpException。
     *  - afterCommit catch 后写 seckill:delay:retry，由 DelayRetryService 定时补偿；
     *  - confirm 超时后消息可能仍已入队，重发会产生重复延时消息——消费端 cancelOrder 是
     *    WHERE status=0 条件更新，重复取消天然幂等，不重复回补库存（至少一次 + 消费端幂等）。
     *
     * @param orderNo 订单号
     * @throws AmqpException 未被 Broker 确认 / 路由失败 / 超时时抛出，由调用方决定补偿策略
     */
    public void sendDelayMessage(String orderNo) {
        CorrelationData correlationData = new CorrelationData(orderNo);
        rabbitTemplate.convertAndSend(
                RabbitMQConfig.ORDER_DELAY_EXCHANGE,
                RabbitMQConfig.ORDER_DELAY_ROUTING_KEY,
                orderNo,
                correlationData
        );

        CorrelationData.Confirm confirm;
        try {
            confirm = correlationData.getFuture().get(CONFIRM_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new AmqpException("延时消息等待 Broker Confirm 超时(" + CONFIRM_TIMEOUT_SECONDS
                    + "s)，按发送失败处理，订单号: " + orderNo, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AmqpException("等待延时消息 Broker Confirm 被中断，订单号: " + orderNo, e);
        } catch (ExecutionException e) {
            throw new AmqpException("延时消息 Broker Confirm 过程异常，订单号: " + orderNo, e);
        }

        if (confirm == null || !confirm.isAck()) {
            String reason = confirm == null ? "Confirm 返回 null" : confirm.getReason();
            throw new AmqpException("延时消息被 Broker NACK，订单号: " + orderNo + "，原因: " + reason);
        }

        // 路由不到队列时消息被 Return（mandatory=true）；此时 Confirm 往往仍是 ack，必须单独检查
        if (correlationData.getReturned() != null) {
            throw new AmqpException("延时消息路由失败被 Broker 退回，订单号: " + orderNo
                    + "（exchange/routingKey 配置错误或队列不存在）");
        }

        log.info("延时消息发送成功（已确认到达队列），订单号: {}", orderNo);
    }
}
