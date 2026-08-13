package com.sygzcd.seckillmall.controller;

import com.sygzcd.seckillmall.common.BusinessException;
import com.sygzcd.seckillmall.common.Result;
import com.sygzcd.seckillmall.common.ResultCode;
import com.sygzcd.seckillmall.entity.Product;
import com.sygzcd.seckillmall.service.ProductService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@Tag(name = "商品模块", description = "商品查询接口")
@RestController
@RequestMapping("/api/product")
public class ProductController {

    @Autowired
    private ProductService productService;

    @Operation(summary = "查询商品详情")
    @GetMapping("/{id}")
    public Result<Product> getById(@PathVariable Long id) {
        Product product = productService.getById(id);
        if (product == null) {
            throw new BusinessException(ResultCode.NOT_FOUND);
        }
        return Result.success(product);
    }

    @Operation(summary = "查询商品库存")
    @GetMapping("/{id}/stock")
    public Result<Integer> getStock(@PathVariable Long id) {
        Integer stock = productService.getStock(id);
        return Result.success(stock);
    }

    @Operation(summary = "预热商品到缓存")
    @PostMapping("/{id}/warmup")
    public Result<Void> warmUp(@PathVariable Long id) {
        productService.warmUpProduct(id);
        return Result.success();
    }
}
