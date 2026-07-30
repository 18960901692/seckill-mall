package com.sygzcd.seckillmall.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sygzcd.seckillmall.entity.Product;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 商品 Mapper
 */
@Mapper
public interface ProductMapper extends BaseMapper<Product> {

    /**
     * 乐观锁扣减库存
     * @param id 商品ID
     * @param version 版本号
     * @return 影响行数
     */
    int decreaseStockWithVersion(@Param("id") Long id, @Param("version") Integer version);
}
