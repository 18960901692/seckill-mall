package com.sygzcd.seckillmall.controller;

import com.sygzcd.seckillmall.common.Result;
import com.sygzcd.seckillmall.common.UserDTO;
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
        // /api/rank/** 已在 AuthInterceptor 拦截范围内，未登录会被拦截器以 HTTP 401 拦掉，
        // 到这里的请求必然已登录，无需再判空（M-3：删除原不可达的 401 死分支）
        UserDTO user = userService.getCurrentUser();
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
