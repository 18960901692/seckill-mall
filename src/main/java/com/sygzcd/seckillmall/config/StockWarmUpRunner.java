package com.sygzcd.seckillmall.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sygzcd.seckillmall.common.ProductDTO;
import com.sygzcd.seckillmall.entity.Product;
import com.sygzcd.seckillmall.mapper.ProductMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 热点商品库存预热
 * 启动时自动将 hot=1 的商品库存写入 Redis，避免秒杀时库存 Key 不存在导致全部售罄
 * 普通商品采用三级缓存懒加载，不在此预热
 */
@Slf4j
@Component
public class StockWarmUpRunner implements CommandLineRunner {

    @Autowired
    private ProductMapper productMapper;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    private static final String STOCK_KEY = "seckill:stock:";
    private static final String PRODUCT_KEY = "product:";

    @Override
    public void run(String... args) {
        // 查询所有热点商品
        List<Product> hotProducts = productMapper.selectList(
                new LambdaQueryWrapper<Product>().eq(Product::getHot, 1)
        );

        if (hotProducts.isEmpty()) {
            log.info("无热点商品需要预热库存");
            return;
        }

        for (Product product : hotProducts) {
            // 预热库存到 Redis（使用 StringRedisTemplate 保证纯数字字符串，秒杀预扣使用 DECR/INCR 命令）
            stringRedisTemplate.opsForValue().set(STOCK_KEY + product.getId(), String.valueOf(product.getStock()));
            // 预热商品信息到 Redis（存 ProductDTO，与三级缓存 Caffeine 一致，不含库存字段）
            ProductDTO dto = new ProductDTO();
            dto.setId(product.getId());
            dto.setName(product.getName());
            dto.setPrice(product.getPrice());
            dto.setHot(product.getHot());
            dto.setCreateTime(product.getCreateTime());
            redisTemplate.opsForValue().set(PRODUCT_KEY + product.getId(), dto);
        }

        log.info("热点商品库存预热完成，共预热 {} 个商品", hotProducts.size());
    }
}
