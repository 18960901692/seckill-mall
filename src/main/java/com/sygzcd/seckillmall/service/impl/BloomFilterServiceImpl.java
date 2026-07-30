package com.sygzcd.seckillmall.service.impl;

import com.google.common.hash.BloomFilter;
import com.google.common.hash.Funnels;
import com.sygzcd.seckillmall.entity.Product;
import com.sygzcd.seckillmall.mapper.ProductMapper;
import com.sygzcd.seckillmall.service.BloomFilterService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.nio.charset.Charset;
import java.util.List;

/**
 * 布隆过滤器服务实现
 * 使用 Guava BloomFilter 防缓存穿透
 */
@Slf4j
@Service
public class BloomFilterServiceImpl implements BloomFilterService {

    @Autowired
    private ProductMapper productMapper;

    /**
     * 布隆过滤器实例
     * 预期插入量：10000个商品
     * 误判率：0.01（1%）
     */
    private BloomFilter<Long> bloomFilter;

    @PostConstruct
    public void initBloomFilter() {
        bloomFilter = BloomFilter.create(
                Funnels.longFunnel(),
                10000,  // 预期插入量
                0.01    // 误判率
        );
        log.info("布隆过滤器初始化完成，预期容量：10000，误判率：0.01");
    }

    /**
     * 初始化布隆过滤器，预热所有商品ID
     */
    @Override
    public void init() {
        List<Product> products = productMapper.selectList(null);
        for (Product product : products) {
            bloomFilter.put(product.getId());
        }
        log.info("布隆过滤器预热完成，共加载 {} 个商品ID", products.size());
    }

    /**
     * 判断商品ID是否可能存在
     */
    @Override
    public boolean mightContain(Long productId) {
        return bloomFilter.mightContain(productId);
    }

    /**
     * 添加商品ID到布隆过滤器
     */
    @Override
    public void put(Long productId) {
        bloomFilter.put(productId);
    }
}
