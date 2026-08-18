package com.sygzcd.seckillmall.service.mq;

import com.rabbitmq.client.Channel;
import com.sygzcd.seckillmall.config.RabbitMQConfig;
import com.sygzcd.seckillmall.entity.Orders;
import com.sygzcd.seckillmall.service.OrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 订单取消消费者
 * 监听死信队列，30分钟后处理未支付订单：释放库存 + 更新订单状态
 * 消费失败时自动重试（最多 3 次），最终失败写入 Redis 补偿队列
 *
 * 重试机制：ACK 旧消息 + 重新发布带新 retryCount Header 的消息，确保 Broker 中的 Header 正确更新
 * 替代原 basicNack(requeue=true) 方案（该方案不会更新 Broker 中原消息的 Header，导致重试计数器永远为 0）
 */
@Slf4j
@Component
public class OrderCancelConsumer {

    private static final int MAX_RETRY = 3;
    private static final String DEAD_LETTER_RETRY_KEY = "seckill:dead:retry";

    @Autowired
    private OrderService orderService;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    /**
     * 消费超时消息，取消未支付订单
     * 消息可靠性：手动 ACK + 幂等判断 + 失败重试
     */
    @RabbitListener(queues = RabbitMQConfig.ORDER_CANCEL_QUEUE)
    public void onMessage(Message message, Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        String orderNo = new String(message.getBody());

        Integer retryCount = (Integer) message.getMessageProperties().getHeaders().get("retryCount");
        int currentRetry = (retryCount == null) ? 0 : retryCount;

        try {
            log.info("收到订单超时消息，订单号: {}，当前重试次数: {}", orderNo, currentRetry);

            Orders order = orderService.getByOrderNo(orderNo);
            if (order == null) {
                log.warn("订单不存在，直接 ACK，订单号: {}", orderNo);
                channel.basicAck(deliveryTag, false);
                return;
            }
            if (order.getStatus() != 0) {
                log.info("订单已处理（状态={}），直接 ACK，订单号: {}", order.getStatus(), orderNo);
                channel.basicAck(deliveryTag, false);
                return;
            }

            orderService.cancelOrder(orderNo);
            log.info("订单超时取消成功，订单号: {}", orderNo);

            channel.basicAck(deliveryTag, false);

        } catch (Exception e) {
            log.error("订单取消失败，订单号: {}，重试次数: {}", orderNo, currentRetry, e);

            if (currentRetry < MAX_RETRY) {
                int nextRetry = currentRetry + 1;
                // ACK 旧消息，重新发布带新 retryCount Header 的消息
                channel.basicAck(deliveryTag, false);
                rabbitTemplate.convertAndSend(
                        RabbitMQConfig.ORDER_CANCEL_EXCHANGE,
                        RabbitMQConfig.ORDER_CANCEL_ROUTING_KEY,
                        orderNo,
                        msg -> {
                            msg.getMessageProperties().getHeaders().put("retryCount", nextRetry);
                            return msg;
                        }
                );
                log.info("订单取消重试中，订单号: {}，下次重试次数: {}", orderNo, nextRetry);
            } else {
                log.error("订单取消重试耗尽，订单号: {}，已写入补偿队列", orderNo);
                redisTemplate.opsForList().rightPush(DEAD_LETTER_RETRY_KEY, orderNo);
                channel.basicAck(deliveryTag, false);
                log.warn("订单取消消息已转补偿队列，订单号: {}，请人工核查", orderNo);
            }
        }
    }
}