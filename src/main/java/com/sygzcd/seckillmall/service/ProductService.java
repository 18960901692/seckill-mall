package com.sygzcd.seckillmall.service;

import com.sygzcd.seckillmall.entity.Product;

public interface ProductService {
    
    /**
     * 根据ID查询商品
     */
    Product getById(Long id);
    
    /**
     * 查询商品库存（三级缓存）
     */
    Integer getStock(Long id);
    
    /**
     * 预热商品到缓存
     */
    void warmUpProduct(Long id);
    
    /**
     * 失效商品缓存（Caffeine + Redis + 广播通知其他实例）
     */
    void invalidateCache(Long id);
}
