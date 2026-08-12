package com.sygzcd.seckillmall.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.sygzcd.seckillmall.entity.Product;
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
 * 缓存失效广播配置
 * 通过 Redis Pub/Sub 实现多实例 Caffeine 缓存一致性
 * 当某实例更新商品缓存时，发布失效消息，其他实例收到后清除本地 Caffeine
 */
@Slf4j
@Configuration
public class CacheInvalidateConfig {

    public static final String CACHE_INVALIDATE_CHANNEL = "cache:invalidate";

    @Autowired
    private Cache<String, Product> caffeineCache;

    /**
     * Redis 消息监听容器
     * 订阅 cache:invalidate 频道，收到消息后清除对应 Caffeine 缓存
     */
    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(
            RedisConnectionFactory connectionFactory) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(new CacheInvalidateListener(),
                new ChannelTopic(CACHE_INVALIDATE_CHANNEL));
        return container;
    }

    /**
     * 缓存失效消息监听器
     * 收到消息后清除本地 Caffeine 中对应的 Key
     */
    private class CacheInvalidateListener implements MessageListener {
        @Override
        public void onMessage(Message message, byte[] pattern) {
            String key = new String(message.getBody());
            caffeineCache.invalidate(key);
            log.debug("收到缓存失效广播，清除本地缓存: {}", key);
        }
    }
}
