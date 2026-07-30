package com.sygzcd.seckillmall.service.mq;

import com.sygzcd.seckillmall.config.RabbitMQConfig;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class OrderDelayProducer {

    @Autowired
    private RabbitTemplate rabbitTemplate;

    /**
     * 发送延时消息（订单超时自动取消）
     */
    public void sendDelayMessage(String orderNo) {
        rabbitTemplate.convertAndSend(
                RabbitMQConfig.ORDER_DELAY_EXCHANGE,
                RabbitMQConfig.ORDER_DELAY_ROUTING_KEY,
                orderNo
        );
    }
}
