package com.sygzcd.seckillmall.service.mq;

import com.sygzcd.seckillmall.config.RabbitMQConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 延时消息生产者
 * 秒杀下单成功后发送延时消息，30分钟后由消费者检查并取消未支付订单
 */
@Slf4j
@Service
public class OrderDelayProducer {

    @Autowired
    private RabbitTemplate rabbitTemplate;

    /**
     * 发送延时消息（订单超时自动取消）
     * @param orderNo 订单号
     * @throws Exception 发送失败时抛出，由调用方决定补偿策略
     */
    public void sendDelayMessage(String orderNo) {
        rabbitTemplate.convertAndSend(
                RabbitMQConfig.ORDER_DELAY_EXCHANGE,
                RabbitMQConfig.ORDER_DELAY_ROUTING_KEY,
                orderNo
        );
        log.info("延时消息发送成功，订单号: {}", orderNo);
    }
}
