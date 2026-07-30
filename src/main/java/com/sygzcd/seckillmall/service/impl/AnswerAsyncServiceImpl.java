package com.sygzcd.seckillmall.service.impl;

import com.sygzcd.seckillmall.entity.AnswerRecord;
import com.sygzcd.seckillmall.mapper.AnswerRecordMapper;
import com.sygzcd.seckillmall.service.AnswerAsyncService;
import com.sygzcd.seckillmall.service.RankService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.ThreadPoolExecutor;

@Service
public class AnswerAsyncServiceImpl implements AnswerAsyncService {

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private AnswerRecordMapper answerRecordMapper;

    @Autowired
    private RankService rankService;

    @Autowired
    private ThreadPoolExecutor bizExecutor;

    private static final String ANSWER_QUEUE_KEY = "answer:queue";

    @Override
    public void submitAnswerAsync(Long userId, Long questionId, boolean correct) {
        // 写入 Redis List 异步队列
        String data = userId + ":" + questionId + ":" + (correct ? 1 : 0);
        redisTemplate.opsForList().rightPush(ANSWER_QUEUE_KEY, data);

        // 异步处理：更新排行榜积分
        bizExecutor.execute(() -> {
            if (correct) {
                rankService.addScore(userId, 10); // 答对加 10 分
            }
        });
    }

    @Override
    public void batchSaveToDb() {
        // 从 Redis List 批量读取并落库
        ListOperations<String, Object> ops = redisTemplate.opsForList();
        
        while (true) {
            Object data = ops.leftPop(ANSWER_QUEUE_KEY);
            if (data == null) {
                break;
            }

            String[] parts = data.toString().split(":");
            Long userId = Long.parseLong(parts[0]);
            Long questionId = Long.parseLong(parts[1]);
            Integer correct = Integer.parseInt(parts[2]);

            AnswerRecord record = new AnswerRecord();
            record.setUserId(userId);
            record.setQuestionId(questionId);
            record.setCorrect(correct);
            
            answerRecordMapper.insert(record);
        }
    }
}
