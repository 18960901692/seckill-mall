package com.sygzcd.seckillmall.service.mq;

import com.rabbitmq.client.Channel;
import com.sygzcd.seckillmall.config.RabbitMQConfig;
import com.sygzcd.seckillmall.entity.Orders;
import com.sygzcd.seckillmall.service.OrderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 订单取消消费者
 * 监听死信队列，30分钟后处理未支付订单：释放库存 + 更新订单状态
 */
@Slf4j
@Component
public class OrderCancelConsumer {

    @Autowired
    private OrderService orderService;

    /**
     * 消费超时消息，取消未支付订单
     * 消息可靠性：手动 ACK + 幂等判断
     */
    @RabbitListener(queues = RabbitMQConfig.ORDER_CANCEL_QUEUE)
    public void onMessage(Message message, Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        String orderNo = new String(message.getBody());

        try {
            log.info("收到订单超时消息，订单号: {}", orderNo);

            // 幂等判断：查询订单状态，已处理的订单直接 ACK
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

            // 执行取消逻辑：回滚 MySQL + Redis 库存
            orderService.cancelOrder(orderNo);
            log.info("订单超时取消成功，订单号: {}", orderNo);

            // 手动 ACK
            channel.basicAck(deliveryTag, false);

        } catch (Exception e) {
            log.error("订单取消失败，订单号: {}", orderNo, e);
            // 拒绝消息，不重新入队（避免无限重试，需人工介入或记录补偿日志）
            channel.basicNack(deliveryTag, false, false);
        }
    }
}
