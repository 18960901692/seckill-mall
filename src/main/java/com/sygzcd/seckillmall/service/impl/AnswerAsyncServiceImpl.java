package com.sygzcd.seckillmall.service.impl;

import com.sygzcd.seckillmall.entity.AnswerRecord;
import com.sygzcd.seckillmall.mapper.AnswerRecordMapper;
import com.sygzcd.seckillmall.service.AnswerAsyncService;
import com.sygzcd.seckillmall.service.RankService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 答题异步服务
 * 采用 Redis List 缓冲 + 定时批量落库，避免频繁 DB 写入
 * 可靠队列设计：RPOPLPUSH 原子迁移 + processing 队列恢复 + DB 唯一索引幂等
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
     * 执行顺序：先恢复 processing 队列数据 → 再处理主队列新数据
     */
    @Scheduled(fixedDelay = 5000)
    public void scheduledBatchSave() {
        // 1. 先恢复 processing 队列中残留的数据（上次落库失败或宕机导致）
        recoverProcessingQueue();

        // 2. 再处理主队列中的新数据
        consumeMainQueue();
    }

    /**
     * 恢复处理队列中的残留数据
     * 使用 range 窥探（不弹出），落库成功后再删除，避免恢复过程中数据丢失
     * (user_id, question_id) 唯一索引保证幂等，重复消费不会产生重复数据
     */
    private void recoverProcessingQueue() {
        // 窥探处理队列中的数据（不弹出，防止恢复失败后丢失）
        List<String> rawData = stringRedisTemplate.opsForList()
                .range(PROCESSING_QUEUE_KEY, 0, BATCH_SIZE - 1);
        if (rawData == null || rawData.isEmpty()) {
            return;
        }

        List<AnswerRecord> records = parseRecords(rawData);
        if (records.isEmpty()) {
            // 数据格式异常，清空处理队列避免死锁
            stringRedisTemplate.delete(PROCESSING_QUEUE_KEY);
            log.warn("处理队列数据格式异常，已清空");
            return;
        }

        try {
            answerRecordMapper.insertBatch(records);
            // 落库成功，从处理队列中删除已恢复的数据
            for (String data : rawData) {
                stringRedisTemplate.opsForList().remove(PROCESSING_QUEUE_KEY, 1, data);
            }
            log.info("处理队列恢复 {} 条答题记录", records.size());
        } catch (DuplicateKeyException e) {
            // 唯一索引冲突：数据已存在，直接清空处理队列
            stringRedisTemplate.delete(PROCESSING_QUEUE_KEY);
            log.warn("处理队列数据重复，已清空（唯一索引幂等）");
        } catch (Exception e) {
            // 恢复失败，数据留在处理队列，下次定时任务继续重试
            log.error("处理队列恢复失败，{} 条记录等待下次重试", records.size(), e);
        }
    }

    /**
     * 消费主队列中的新数据
     * 使用 RPOPLPUSH 原子操作：从主队列取出并移到处理队列
     * 即使应用在落库前崩溃，数据也在处理队列中不会丢失
     */
    private void consumeMainQueue() {
        List<AnswerRecord> records = new ArrayList<>(BATCH_SIZE);
        List<String> rawData = new ArrayList<>(BATCH_SIZE);

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
            answerRecordMapper.insertBatch(records);
            // 落库成功，从处理队列中删除已落库的数据
            for (String data : rawData) {
                stringRedisTemplate.opsForList().remove(PROCESSING_QUEUE_KEY, 1, data);
            }
            log.info("批量落库 {} 条答题记录", records.size());
        } catch (DuplicateKeyException e) {
            // 唯一索引冲突：数据已存在（重复消费），直接清空处理队列
            stringRedisTemplate.delete(PROCESSING_QUEUE_KEY);
            log.warn("主队列数据重复，已清空处理队列（唯一索引幂等）");
        } catch (Exception e) {
            // 落库失败，数据留在处理队列，下次定时任务通过 recoverProcessingQueue 恢复
            log.error("批量落库失败，{} 条记录留在处理队列等待补偿", records.size(), e);
        }
    }

    /**
     * 将原始数据解析为 AnswerRecord 列表
     * 数据格式：userId:questionId:correct
     */
    private List<AnswerRecord> parseRecords(List<String> rawData) {
        List<AnswerRecord> records = new ArrayList<>(rawData.size());
        for (String data : rawData) {
            try {
                String[] parts = data.split(":");
                AnswerRecord record = new AnswerRecord();
                record.setUserId(Long.parseLong(parts[0]));
                record.setQuestionId(Long.parseLong(parts[1]));
                record.setCorrect(Integer.parseInt(parts[2]));
                records.add(record);
            } catch (Exception e) {
                log.warn("解析答题记录失败，跳过: {}", data, e);
            }
        }
        return records;
    }
}