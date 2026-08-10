package com.sygzcd.seckillmall.controller;

import com.sygzcd.seckillmall.annotation.RequireAdmin;
import com.sygzcd.seckillmall.common.Result;
import com.sygzcd.seckillmall.service.BlackListService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 黑名单管理接口
 * 提供黑名单查询、手动拉黑、移出黑名单等管理功能
 */
@Tag(name = "黑名单模块", description = "IP/用户防刷黑名单管理")
@RestController
@RequestMapping("/api/blacklist")
public class BlackListController {

    @Autowired
    private BlackListService blackListService;

    @Operation(summary = "检查是否在黑名单中")
    @GetMapping("/check")
    public Result<Map<String, Boolean>> check(
            @RequestParam String type,
            @RequestParam String key) {
        boolean blackListed = blackListService.isBlackListed(type, key);
        Map<String, Boolean> result = new HashMap<>();
        result.put("blackListed", blackListed);
        return Result.success(result);
    }

    @Operation(summary = "获取黑名单列表")
    @GetMapping("/list")
    public Result<Set<String>> list(@RequestParam String type) {
        Set<String> list = blackListService.getBlackList(type);
        return Result.success(list);
    }

    @Operation(summary = "手动加入黑名单")
    @PostMapping("/add")
    @RequireAdmin
    public Result<Void> add(
            @RequestParam String type,
            @RequestParam String key) {
        blackListService.addToBlackList(type, key);
        return Result.success();
    }

    @Operation(summary = "移出黑名单")
    @DeleteMapping("/remove")
    @RequireAdmin
    public Result<Void> remove(
            @RequestParam String type,
            @RequestParam String key) {
        blackListService.removeFromBlackList(type, key);
        return Result.success();
    }
}
