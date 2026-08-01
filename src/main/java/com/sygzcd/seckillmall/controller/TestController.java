package com.sygzcd.seckillmall.controller;

import com.sygzcd.seckillmall.aop.annotation.RateLimit;
import com.sygzcd.seckillmall.common.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

/**
 * 测试接口
 * 用于验证限流和黑名单机制
 */
@Tag(name = "测试模块", description = "限流与黑名单测试接口")
@RestController
@RequestMapping("/api/test")
public class TestController {

    @Operation(summary = "Hello测试接口", description = "10秒内限5次，超限自动记录违规，违规5次自动拉黑")
    @GetMapping("/hello")
    @RateLimit(windowSec = 10, maxCount = 5, keyPrefix = "ratelimit:test")
    public Result<Map<String, String>> hello() {
        Map<String, String> result = new HashMap<>();
        result.put("message", "hello");
        return Result.success(result);
    }
}
