package com.sygzcd.seckillmall.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * RabbitMQ 配置
 * 死信队列 + TTL 实现 30 分钟未支付自动取消
 * 消息可靠性三板斧：Confirm + 持久化 + 手动ACK
 */
@Slf4j
@Configuration
public class RabbitMQConfig {

    // 交换机
    public static final String ORDER_DELAY_EXCHANGE = "order.delay.exchange";
    public static final String ORDER_CANCEL_EXCHANGE = "order.cancel.exchange";

    // 队列
    public static final String ORDER_DELAY_QUEUE = "order.delay.queue";
    public static final String ORDER_CANCEL_QUEUE = "order.cancel.queue";

    // 路由键
    public static final String ORDER_DELAY_ROUTING_KEY = "order.delay.routing.key";
    public static final String ORDER_CANCEL_ROUTING_KEY = "order.cancel.routing.key";

    // TTL: 30 分钟
    public static final int ORDER_TTL = 30 * 60 * 1000;

    /**
     * 延时队列交换机（普通 direct 交换机）
     */
    @Bean
    public DirectExchange orderDelayExchange() {
        return new DirectExchange(ORDER_DELAY_EXCHANGE, true, false);
    }

    /**
     * 死信交换机
     */
    @Bean
    public DirectExchange orderCancelExchange() {
        return new DirectExchange(ORDER_CANCEL_EXCHANGE, true, false);
    }

    /**
     * 延时队列（持久化 + 设置 TTL + 死信交换机）
     */
    @Bean
    public Queue orderDelayQueue() {
        return QueueBuilder.durable(ORDER_DELAY_QUEUE)
                .withArgument("x-message-ttl", ORDER_TTL)
                .withArgument("x-dead-letter-exchange", ORDER_CANCEL_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", ORDER_CANCEL_ROUTING_KEY)
                .build();
    }

    /**
     * 死信队列（持久化，接收超时消息）
     */
    @Bean
    public Queue orderCancelQueue() {
        return QueueBuilder.durable(ORDER_CANCEL_QUEUE).build();
    }

    /**
     * 绑定延时队列到交换机
     */
    @Bean
    public Binding bindingOrderDelay() {
        return BindingBuilder.bind(orderDelayQueue())
                .to(orderDelayExchange())
                .with(ORDER_DELAY_ROUTING_KEY);
    }

    /**
     * 绑定死信队列到死信交换机
     */
    @Bean
    public Binding bindingOrderCancel() {
        return BindingBuilder.bind(orderCancelQueue())
                .to(orderCancelExchange())
                .with(ORDER_CANCEL_ROUTING_KEY);
    }

    /**
     * RabbitTemplate 配置：开启 Confirm + Return 机制
     * Confirm：消息是否到达交换机
     * Return：消息是否到达队列（路由失败时回调）
     */
    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMandatory(true);

        // 生产者 Confirm 回调：消息是否到达交换机
        template.setConfirmCallback((correlationData, ack, cause) -> {
            if (!ack) {
                log.error("消息发送到交换机失败，原因: {}", cause);
            }
        });

        // 生产者 Return 回调：消息路由失败（交换机到队列失败）
        template.setReturnsCallback(returned -> {
            log.error("消息路由失败，消息: {}，回复码: {}，回复文本: {}",
                    returned.getMessage(),
                    returned.getReplyCode(),
                    returned.getReplyText());
        });

        return template;
    }
}
