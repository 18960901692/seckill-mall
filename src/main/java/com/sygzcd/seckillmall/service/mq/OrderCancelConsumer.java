package com.sygzcd.seckillmall.service.mq;

import com.rabbitmq.client.Channel;
import com.sygzcd.seckillmall.config.RabbitMQConfig;
import com.sygzcd.seckillmall.entity.Orders;
import com.sygzcd.seckillmall.service.OrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 订单取消消费者
 * 监听死信队列，30分钟后处理未支付订单：释放库存 + 更新订单状态
 *
 * 失败重试策略（M-7 重构）：
 * 不再在消费端做毫秒级快速重试（典型失败原因是 DB 短暂不可用，3 次瞬时重试没有恢复窗口，
 * 且"先 ACK 再重发"在重发失败时会造成消息两边皆无）。
 * 消费失败统一写入 Redis 补偿队列 seckill:dead:retry，由 DeadLetterRetryService 每 60s
 * 带退避重新投递；重投仍失败时最终由 OrderReconcileService 扫 DB 兜底取消。
 * 关键顺序：先写补偿队列、成功后才 ACK；补偿队列写失败则不 ACK，消息保持 unacked，
 * 连接断开后 Broker 会重新投递，杜绝消息丢失。
 */
@Slf4j
@Component
public class OrderCancelConsumer {

    private static final String DEAD_LETTER_RETRY_KEY = "seckill:dead:retry";

    @Autowired
    private OrderService orderService;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    /**
     * 消费超时消息，取消未支付订单
     * 消息可靠性：手动 ACK + 幂等判断（cancelOrderById WHERE status=0）+ 失败进补偿队列退避重投
     */
    @RabbitListener(queues = RabbitMQConfig.ORDER_CANCEL_QUEUE)
    public void onMessage(Message message, Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        String orderNo = new String(message.getBody(), StandardCharsets.UTF_8);

        try {
            log.info("收到订单超时消息，订单号: {}", orderNo);

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
            log.error("订单取消失败，转入 Redis 补偿队列等待 60s 退避重投，订单号: {}", orderNo, e);
            // 必须先写补偿队列再 ACK：
            //  - rightPush 成功 + ACK：消息安全转移到补偿队列，DeadLetterRetryService 定时重投；
            //  - rightPush 抛异常：跳过 ACK，消息仍是 unacked，消费者连接断开/重启后 Broker 重新投递。
            redisTemplate.opsForList().rightPush(DEAD_LETTER_RETRY_KEY, orderNo);
            channel.basicAck(deliveryTag, false);
            log.warn("订单取消消息已转入补偿队列，订单号: {}", orderNo);
        }
    }
}
