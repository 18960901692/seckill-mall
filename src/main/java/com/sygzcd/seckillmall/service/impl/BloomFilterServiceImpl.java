package com.sygzcd.seckillmall.service.impl;

import com.google.common.hash.BloomFilter;
import com.google.common.hash.Funnels;
import com.sygzcd.seckillmall.entity.Product;
import com.sygzcd.seckillmall.mapper.ProductMapper;
import com.sygzcd.seckillmall.service.BloomFilterService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.List;

/**
 * 布隆过滤器服务实现
 * 使用 Guava BloomFilter 防缓存穿透
 * 启动时自动预热全量商品ID；并支持「新增商品补位」与「定时全量刷新」
 */
@Slf4j
@Service
public class BloomFilterServiceImpl implements BloomFilterService, CommandLineRunner {

    @Autowired
    private ProductMapper productMapper;

    /**
     * 布隆过滤器实例（volatile：refresh 重建时原子替换，避免并发读旧/新实例不一致）
     * 预期插入量：10000个商品
     * 误判率：0.01（1%）
     */
    private volatile BloomFilter<Long> bloomFilter;

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
     * 项目启动后自动预热全量商品ID
     */
    @Override
    public void run(String... args) {
        init();
    }

    /**
     * 初始化布隆过滤器，预热所有商品ID
     */
    @Override
    public void init() {
        rebuildFromDb();
    }

    /**
     * 从 DB 全量重建布隆过滤器（原子替换实例）
     * 同时解决两个问题：
     *  1. 启动后新增的商品（SQL 直写 / 其他实例 / 管理端 warmup 之外的路径）能进入过滤器，避免误拒；
     *  2. 过滤器随 DB 数据自动刷新（原实现只在启动时预热一次，之后永远是旧全集）。
     */
    private void rebuildFromDb() {
        BloomFilter<Long> newFilter = BloomFilter.create(
                Funnels.longFunnel(),
                10000,
                0.01
        );
        List<Product> products = productMapper.selectList(null);
        for (Product product : products) {
            newFilter.put(product.getId());
        }
        // volatile 写：后续 mightContain 读取立即看到新实例（含全部当前商品）
        bloomFilter = newFilter;
        log.info("布隆过滤器重建完成，共加载 {} 个商品ID", products.size());
    }

    /**
     * 定时全量刷新（每 5 分钟）
     * 兜底覆盖「启动后新增、且尚未被 warmup/手动 put 的商品」，
     * 保证过滤器最终与 DB 一致（5 分钟内），杜绝新商品被误判为"不存在"。
     */
    @Scheduled(fixedDelay = 300_000)
    public void refresh() {
        try {
            rebuildFromDb();
        } catch (Exception e) {
            // 刷新失败不影响当前过滤器继续工作，下一轮重试
            log.error("布隆过滤器定时刷新失败，沿用上一版过滤器", e);
        }
    }

    /**
     * 判断商品ID是否可能存在
     * 过滤器未就绪（极短启动窗口）时返回 true（放行），避免误拒——布隆过滤器本身是防穿透的优化，不应成为拦截的正确性的唯一依赖
     */
    @Override
    public boolean mightContain(Long productId) {
        BloomFilter<Long> filter = bloomFilter;
        if (filter == null) {
            return true;
        }
        return filter.mightContain(productId);
    }

    /**
     * 添加商品ID到布隆过滤器
     * 用于「新增商品」即时补位（如管理端 warmup 一个新商品），无需等定时刷新
     */
    @Override
    public void put(Long productId) {
        BloomFilter<Long> filter = bloomFilter;
        if (filter == null) {
            // 过滤器尚未初始化（如启动顺序竞态），跳过；init/refresh 会从 DB 全量加载补齐
            return;
        }
        filter.put(productId);
    }
}
