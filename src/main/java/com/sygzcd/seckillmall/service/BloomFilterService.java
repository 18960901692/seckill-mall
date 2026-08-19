package com.sygzcd.seckillmall.service;

/**
 * 布隆过滤器服务接口
 * 用于缓存穿透防护，预热商品ID
 */
public interface BloomFilterService {

    /**
     * 初始化布隆过滤器，预热所有商品ID
     */
    void init();

    /**
     * 判断商品ID是否可能存在
     * @param productId 商品ID
     * @return true=可能存在，false=一定不存在
     */
    boolean mightContain(Long productId);

    /**
     * 添加商品ID到布隆过滤器
     * @param productId 商品ID
     */
    void put(Long productId);

    /**
     * 从 DB 全量重建布隆过滤器（定时刷新，覆盖启动后新增/外部直写库的商品）
     */
    void refresh();
}
