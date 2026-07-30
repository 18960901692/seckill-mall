package com.sygzcd.seckillmall.service.mq;

import com.rabbitmq.client.Channel;
import com.sygzcd.seckillmall.config.RabbitMQConfig;
import com.sygzcd.seckillmall.service.OrderService;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class OrderCancelConsumer {

    @Autowired
    private OrderService orderService;

    @RabbitListener(queues = RabbitMQConfig.ORDER_CANCEL_QUEUE)
    public void onMessage(Message message, Channel channel) throws IOException {
        try {
            String orderNo = new String(message.getBody());
            
            // 幂等判断：查询订单状态
            var order = orderService.getByOrderNo(orderNo);
            if (order == null || order.getStatus() != 0) {
                // 订单不存在或已处理，直接 ACK
                channel.basicAck(message.getMessageProperties().getDeliveryTag(), false);
                return;
            }

            // 执行取消逻辑
            orderService.cancelOrder(orderNo);

            // 手动 ACK
            channel.basicAck(message.getMessageProperties().getDeliveryTag(), false);

        } catch (Exception e) {
            // 拒绝消息，不重新入队
            channel.basicNack(message.getMessageProperties().getDeliveryTag(), false, false);
        }
    }
}
