package com.sygzcd.seckillmall.controller;

import com.sygzcd.seckillmall.common.Result;
import com.sygzcd.seckillmall.entity.User;
import com.sygzcd.seckillmall.service.RankService;
import com.sygzcd.seckillmall.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Tag(name = "排行榜模块", description = "积分排行榜接口")
@RestController
@RequestMapping("/api/rank")
public class RankController {

    @Autowired
    private RankService rankService;

    @Autowired
    private UserService userService;

    @Operation(summary = "获取 Top N 排行榜")
    @GetMapping("/top")
    public Result<List<Map<String, Object>>> getTopN(
            @RequestParam(defaultValue = "10") int n) {
        List<Map<String, Object>> list = rankService.getTopN(n);
        return Result.success(list);
    }

    @Operation(summary = "查询我的排名和积分")
    @GetMapping("/my")
    public Result<Map<String, Object>> getMyRank() {
        User user = userService.getCurrentUser();
        if (user == null) {
            return Result.fail(401, "未登录");
        }
        Double score = rankService.getUserScore(user.getId());
        Long rank = rankService.getUserRank(user.getId());

        Map<String, Object> result = new HashMap<>();
        result.put("userId", user.getId());
        result.put("username", user.getUsername());
        result.put("score", score != null ? score : 0);
        result.put("rank", rank != null ? rank : -1);

        return Result.success(result);
    }
}
