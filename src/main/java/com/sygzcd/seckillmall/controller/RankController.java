package com.sygzcd.seckillmall.controller;

import com.sygzcd.seckillmall.common.Result;
import com.sygzcd.seckillmall.service.RankService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Tag(name = "排行榜模块", description = "积分排行榜接口")
@RestController
@RequestMapping("/api/rank")
public class RankController {

    @Autowired
    private RankService rankService;

    @Operation(summary = "获取 Top N 排行榜")
    @GetMapping("/top")
    public Result<List<Map<String, Object>>> getTopN(
            @RequestParam(defaultValue = "10") int n) {
        List<Map<String, Object>> list = rankService.getTopN(n);
        return Result.success(list);
    }

    @Operation(summary = "查询我的排名")
    @GetMapping("/my")
    public Result<Map<String, Object>> getMyRank() {
        // 这里需要从 Session 获取当前用户，简化处理
        return Result.success(null);
    }
}
