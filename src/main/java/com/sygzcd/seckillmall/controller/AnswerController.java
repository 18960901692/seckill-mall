package com.sygzcd.seckillmall.controller;

import com.sygzcd.seckillmall.common.Result;
import com.sygzcd.seckillmall.entity.User;
import com.sygzcd.seckillmall.service.AnswerAsyncService;
import com.sygzcd.seckillmall.service.RankService;
import com.sygzcd.seckillmall.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@Tag(name = "答题模块", description = "答题记录接口")
@RestController
@RequestMapping("/api/answer")
public class AnswerController {

    @Autowired
    private AnswerAsyncService answerAsyncService;

    @Autowired
    private UserService userService;

    @Autowired
    private RankService rankService;

    @Operation(summary = "提交答题记录")
    @PostMapping("/submit")
    public Result<Void> submitAnswer(
            @RequestParam Long questionId,
            @RequestParam boolean correct) {
        User user = userService.getCurrentUser();
        if (user == null) {
            return Result.fail(401, "未登录");
        }
        // 异步写入 Redis List，不阻塞主线程
        answerAsyncService.submitAnswerAsync(user.getId(), questionId, correct);
        // 答对加分到排行榜
        if (correct) {
            rankService.addScore(user.getId(), 10);
        }
        return Result.success();
    }

    @Operation(summary = "批量落库答题记录")
    @PostMapping("/batch-save")
    public Result<Void> batchSave() {
        answerAsyncService.batchSaveToDb();
        return Result.success();
    }
}
