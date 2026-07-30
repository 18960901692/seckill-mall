package com.sygzcd.seckillmall.service;

import java.util.List;

public interface AnswerAsyncService {
    
    /**
     * 异步提交答题记录
     */
    void submitAnswerAsync(Long userId, Long questionId, boolean correct);
    
    /**
     * 批量落库
     */
    void batchSaveToDb();
}
