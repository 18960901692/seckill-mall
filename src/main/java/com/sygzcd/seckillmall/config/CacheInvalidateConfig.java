package com.sygzcd.seckillmall.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.sygzcd.seckillmall.common.UserDTO;
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
 * 支持商品缓存和用户缓存两个频道
 */
@Slf4j
@Configuration
public class CacheInvalidateConfig {

    public static final String CACHE_INVALIDATE_CHANNEL = "cache:invalidate";
    public static final String USER_CACHE_INVALIDATE_CHANNEL = "cache:invalidate:user";

    @Autowired
    private Cache<String, Product> caffeineCache;

    @Autowired
    private Cache<String, UserDTO> userCaffeineCache;

    /**
     * Redis 消息监听容器
     * 订阅商品缓存和用户缓存失效频道
     */
    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(
            RedisConnectionFactory connectionFactory) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);

        // 商品缓存失效监听
        container.addMessageListener(new ProductCacheInvalidateListener(),
                new ChannelTopic(CACHE_INVALIDATE_CHANNEL));

        // 用户缓存失效监听
        container.addMessageListener(new UserCacheInvalidateListener(),
                new ChannelTopic(USER_CACHE_INVALIDATE_CHANNEL));

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

    /**
     * 用户缓存失效消息监听器
     */
    private class UserCacheInvalidateListener implements MessageListener {
        @Override
        public void onMessage(Message message, byte[] pattern) {
            String key = new String(message.getBody());
            userCaffeineCache.invalidate(key);
            log.debug("收到用户缓存失效广播，清除本地缓存: {}", key);
        }
    }
}