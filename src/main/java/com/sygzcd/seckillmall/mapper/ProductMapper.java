package com.sygzcd.seckillmall.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sygzcd.seckillmall.entity.Product;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

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

    /**
     * 查询所有商品ID和库存（用于定时对账）
     */
    List<Product> selectAllStock();
}
