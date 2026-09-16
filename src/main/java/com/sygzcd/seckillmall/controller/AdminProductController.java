package com.sygzcd.seckillmall.controller;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.sygzcd.seckillmall.annotation.RequireAdmin;
import com.sygzcd.seckillmall.common.BusinessException;
import com.sygzcd.seckillmall.common.Result;
import com.sygzcd.seckillmall.common.ResultCode;
import com.sygzcd.seckillmall.entity.Product;
import com.sygzcd.seckillmall.mapper.ProductMapper;
import com.sygzcd.seckillmall.service.ProductService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;

/**
 * 商品管理接口（仅管理员可操作）
 * 修改商品属性后主动失效缓存，确保前台立即看到最新值
 */
@Tag(name = "商品管理（管理员）")
@Slf4j
@RestController
@RequestMapping("/api/admin/product")
@RequireAdmin
public class AdminProductController {

    /** 价格上界：product.price 列是 DECIMAL(10,2)，整数位 8 位 + 小数位 2 位 */
    private static final BigDecimal MAX_PRICE = new BigDecimal("99999999.99");

    @Autowired
    private ProductMapper productMapper;

    @Autowired
    private ProductService productService;

    @Operation(summary = "更新商品属性（name/price/hot），仅管理员可操作")
    @PostMapping("/{id}/update")
    public Result<Void> update(@PathVariable Long id, @RequestBody UpdateProductRequest req) {
        if (id == null || id <= 0) {
            throw new BusinessException(ResultCode.PARAM_ERROR, "商品ID非法");
        }
        if (productMapper.selectById(id) == null) {
            throw new BusinessException(ResultCode.PRODUCT_NOT_FOUND);
        }

        // 只更新请求里非 null 的字段；stock/version 不走这里，避免破坏库存计数器
        LambdaUpdateWrapper<Product> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(Product::getId, id);
        if (req.name != null) {
            if (req.name.isBlank() || req.name.length() > 128) {
                throw new BusinessException(ResultCode.PARAM_ERROR, "商品名不能为空且不超过 128 字");
            }
            wrapper.set(Product::getName, req.name);
        }
        if (req.price != null) {
            if (req.price.compareTo(BigDecimal.ZERO) <= 0
                    || req.price.compareTo(MAX_PRICE) > 0
                    || req.price.scale() > 2) {
                throw new BusinessException(ResultCode.PARAM_ERROR, "价格必须在 0.01 ~ 99999999.99 之间，最多两位小数");
            }
            wrapper.set(Product::getPrice, req.price);
        }
        if (req.hot != null) {
            if (req.hot != 0 && req.hot != 1) {
                throw new BusinessException(ResultCode.PARAM_ERROR, "hot 只能是 0 或 1");
            }
            wrapper.set(Product::getHot, req.hot);
        }
        if (wrapper.getSqlSet() == null || wrapper.getSqlSet().isEmpty()) {
            // 请求体里全 null，没有需要更新的字段
            return Result.success();
        }

        productMapper.update(null, wrapper);

        // 缓存失效：ProductDTO 包含 name/price/hot，这些字段刚变了必须清
        // 与秒杀 afterCommit 同理，缓存失效是优化动作，失败不影响 DB 写入
        try {
            productService.invalidateCache(id);
            log.info("管理员更新商品属性后已失效缓存，商品ID: {}", id);
        } catch (Exception e) {
            log.error("管理员更新商品属性后失效缓存失败（不影响DB写入），商品ID: {}", id, e);
        }

        return Result.success();
    }

    @Data
    public static class UpdateProductRequest {
        /** 新商品名（可选） */
        private String name;
        /** 新价格（可选） */
        private BigDecimal price;
        /** 新热点标记：1=热点（永不过期），0=普通（可选） */
        private Integer hot;
    }
}
