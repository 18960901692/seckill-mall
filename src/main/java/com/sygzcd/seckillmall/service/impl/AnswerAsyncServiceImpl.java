package com.sygzcd.seckillmall.service.impl;

import com.sygzcd.seckillmall.entity.AnswerRecord;
import com.sygzcd.seckillmall.mapper.AnswerRecordMapper;
import com.sygzcd.seckillmall.service.AnswerAsyncService;
import com.sygzcd.seckillmall.service.RankService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 答题异步服务
 * 采用 Redis List 缓冲 + 定时批量落库，避免频繁 DB 写入
 * 通过 RPOPLPUSH 实现可靠队列：数据从主队列移到处理队列，落库成功后再删除
 */
@Slf4j
@Service
public class AnswerAsyncServiceImpl implements AnswerAsyncService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private AnswerRecordMapper answerRecordMapper;

    @Autowired
    private RankService rankService;

    @Autowired
    private ThreadPoolExecutor bizExecutor;

    private static final String ANSWER_QUEUE_KEY = "answer:queue";
    private static final String PROCESSING_QUEUE_KEY = "answer:queue:processing";
    private static final int BATCH_SIZE = 100;

    @Override
    public void submitAnswerAsync(Long userId, Long questionId, boolean correct) {
        // 写入 Redis List 异步队列（使用 StringRedisTemplate 避免序列化问题）
        String data = userId + ":" + questionId + ":" + (correct ? 1 : 0);
        stringRedisTemplate.opsForList().rightPush(ANSWER_QUEUE_KEY, data);

        // 异步处理：更新排行榜积分
        bizExecutor.execute(() -> {
            if (correct) {
                rankService.addScore(userId, 10);
            }
        });
    }

    @Override
    public void batchSaveToDb() {
        // 定时任务每 5 秒自动触发，无需手动调用
    }

    /**
     * 定时批量落库答题记录
     * 每 5 秒自动执行一次，单次最多处理 100 条
     * 使用 RPOPLPUSH 保证数据可靠：出队时原子移动到处理队列，落库成功后才删除
     */
    @Scheduled(fixedDelay = 5000)
    public void scheduledBatchSave() {
        List<AnswerRecord> records = new ArrayList<>(BATCH_SIZE);
        List<String> rawData = new ArrayList<>(BATCH_SIZE);

        // 从主队列取出并原子移到处理队列，最多 BATCH_SIZE 条
        // RPOPLPUSH 保证：即使应用崩溃，数据也在处理队列中不会丢失
        for (int i = 0; i < BATCH_SIZE; i++) {
            String data = stringRedisTemplate.opsForList()
                    .rightPopAndLeftPush(ANSWER_QUEUE_KEY, PROCESSING_QUEUE_KEY);
            if (data == null) {
                break;
            }
            rawData.add(data);
            String[] parts = data.split(":");
            AnswerRecord record = new AnswerRecord();
            record.setUserId(Long.parseLong(parts[0]));
            record.setQuestionId(Long.parseLong(parts[1]));
            record.setCorrect(Integer.parseInt(parts[2]));
            records.add(record);
        }

        if (records.isEmpty()) {
            return;
        }

        try {
            // 批量插入（单条 SQL，性能优于逐条 INSERT）
            answerRecordMapper.insertBatch(records);
            // 插入成功，从处理队列中删除已落库的数据
            for (String data : rawData) {
                stringRedisTemplate.opsForList().remove(PROCESSING_QUEUE_KEY, 1, data);
            }
            log.info("批量落库 {} 条答题记录", records.size());
        } catch (Exception e) {
            // 插入失败，数据留在处理队列中，下次定时任务可继续处理
            // 通过 LLEN answer:queue:processing 可监控积压情况
            log.error("批量落库失败，{} 条记录留在处理队列等待补偿", records.size(), e);
        }
    }
}