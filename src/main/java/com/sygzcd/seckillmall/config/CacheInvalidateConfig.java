package com.sygzcd.seckillmall.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.sygzcd.seckillmall.common.ProductDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * 商品缓存失效广播配置
 * 用 Redis Pub/Sub 让多实例的 Caffeine 本地缓存保持一致
 * 订阅频道：cache:invalidate（由 AdminProductController 修改商品属性时触发）
 */
@Slf4j
@Configuration
public class CacheInvalidateConfig {

    public static final String CACHE_INVALIDATE_CHANNEL = "cache:invalidate";

    @Autowired
    private Cache<String, ProductDTO> caffeineCache;

    /**
     * Redis 消息监听容器（商品缓存失效）
     */
    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(
            RedisConnectionFactory connectionFactory) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);

        container.addMessageListener(new ProductCacheInvalidateListener(),
                new ChannelTopic(CACHE_INVALIDATE_CHANNEL));

        return container;
    }

    /**
     * 商品缓存失效消息监听器
     */
    private class ProductCacheInvalidateListener implements MessageListener {
        @Override
        public void onMessage(Message message, byte[] pattern) {
            String key = new String(message.getBody());
            caffeineCache.invalidate(key);
            log.debug("收到商品缓存失效广播，清除本地缓存: {}", key);
        }
    }
}