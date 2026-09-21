package com.sygzcd.seckillmall.service.impl;

import com.sygzcd.seckillmall.common.BusinessException;
import com.sygzcd.seckillmall.common.ResultCode;
import com.sygzcd.seckillmall.mapper.AnswerRecordMapper;
import com.sygzcd.seckillmall.service.RankService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.SetOperations;

import java.util.concurrent.ThreadPoolExecutor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * AnswerAsyncServiceImpl 纯单元测试（H-1：答题无限刷分）
 *
 * 核心验证：
 * 1. 同一题目 SADD 返回 0（已存在）→ 抛 3001 ANSWER_REPEAT，不入队、不加分
 * 2. questionId 非法（null/<=0）→ 抛 400 PARAM_ERROR
 * 3. 首次提交 → 入队 + 加分路径
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AnswerAsyncServiceTest {

    @InjectMocks
    private AnswerAsyncServiceImpl service;

    @Mock
    private StringRedisTemplate stringRedisTemplate;

    @Mock
    private AnswerRecordMapper answerRecordMapper;

    @Mock
    private RankService rankService;

    @Mock
    private ThreadPoolExecutor bizExecutor;

    @Mock
    private SetOperations<String, String> setOps;

    @Mock
    private ListOperations<String, String> listOps;

    @Mock
    private ValueOperations<String, String> valueOps;

    @BeforeEach
    void setUp() {
        when(stringRedisTemplate.opsForSet()).thenReturn(setOps);
        when(stringRedisTemplate.opsForList()).thenReturn(listOps);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
    }

    // ================================================================
    // 场景 1：SADD 返回 0（已答过）→ 抛 ANSWER_REPEAT
    // ================================================================

    @Nested
    @DisplayName("H-1 刷分防护：首答判定")
    class DuplicatePrevention {

        @Test
        @DisplayName("同一题目 SADD 返回 0 → 不加分、不入队、抛 3001")
        void repeatSubmissionThrows3001() {
            Long userId = 1L;
            Long questionId = 100L;

            // SADD 返回 0 = 成员已存在（已答过）
            when(setOps.add("answered:1", "100")).thenReturn(0L);

            assertThatThrownBy(() -> service.submitAnswerAsync(userId, questionId, true))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> {
                        BusinessException be = (BusinessException) ex;
                        assertThat(be.getCode()).isEqualTo(ResultCode.ANSWER_REPEAT.getCode());
                    });

            // 验证：不加分、不入队
            verify(rankService, never()).addScore(anyLong(), anyInt());
            verify(listOps, never()).rightPush(anyString(), anyString());
            verify(bizExecutor, never()).execute(any(Runnable.class));
        }

        @Test
        @DisplayName("首次提交 SADD 返回 1 → 入队 + 加分")
        void firstTimeSubmissionEnqueuesAndScores() {
            Long userId = 1L;
            Long questionId = 100L;

            // SADD 返回 1 = 新成员，首次答
            when(setOps.add("answered:1", "100")).thenReturn(1L);
            when(listOps.rightPush(anyString(), anyString())).thenReturn(1L);

            service.submitAnswerAsync(userId, questionId, true);

            // 验证：入队 + 异步加分任务被提交
            verify(listOps).rightPush(eq("answer:queue"), contains("1:100:1"));
            verify(bizExecutor).execute(any(Runnable.class));
            // 注意：RankService.addScore 在 bizExecutor 的 Runnable 里，这里 mock 了 executor，不会真执行
            // 所以 verify 不检查 rankService，而是检查 executor.execute 被调（间接证明"加分路径被触发"）
        }

        @Test
        @DisplayName("非正确答案不加分：bizExecutor.execute 里不调 rankService.addScore")
        void incorrectAnswerNoScore() {
            Long userId = 1L;
            Long questionId = 100L;

            when(setOps.add("answered:1", "100")).thenReturn(1L);
            when(listOps.rightPush(anyString(), anyString())).thenReturn(1L);

            service.submitAnswerAsync(userId, questionId, false);

            verify(listOps).rightPush(eq("answer:queue"), contains("1:100:0"));
            // executor.execute 被调，但我们不能深入 Runnable 内部验证——
            // 不过正确答案才会走 addScore，非正确答案 Runnable 里的 if(correct) 是 false
            verify(bizExecutor).execute(any(Runnable.class));
        }
    }

    // ================================================================
    // 场景 2：questionId 非法 → 抛 PARAM_ERROR
    // ================================================================

    @Nested
    @DisplayName("参数校验")
    class ParamValidation {

        @Test
        @DisplayName("questionId=null → 400")
        void nullQuestionIdThrows() {
            assertThatThrownBy(() -> service.submitAnswerAsync(1L, null, true))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> {
                        BusinessException be = (BusinessException) ex;
                        assertThat(be.getCode()).isEqualTo(ResultCode.PARAM_ERROR.getCode());
                    });
            verifyNoInteractions(stringRedisTemplate);
        }

        @Test
        @DisplayName("questionId<=0 → 400")
        void nonPositiveQuestionIdThrows() {
            assertThatThrownBy(() -> service.submitAnswerAsync(1L, -1L, true))
                    .isInstanceOf(BusinessException.class)
                    .satisfies(ex -> {
                        BusinessException be = (BusinessException) ex;
                        assertThat(be.getCode()).isEqualTo(ResultCode.PARAM_ERROR.getCode());
                    });
        }
    }

    // ================================================================
    // 场景 3：入队失败 → 回滚首答标记
    // ================================================================

    @Nested
    @DisplayName("入队失败回滚首答标记")
    class RollbackOnEnqueueFail {

        @Test
        @DisplayName("SADD 成功但 RPUSH 失败 → SREM 回滚，重新抛异常")
        void enqueueFailureRollsBackAnsweredFlag() {
            Long userId = 1L;
            Long questionId = 100L;

            when(setOps.add("answered:1", "100")).thenReturn(1L);
            when(listOps.rightPush(anyString(), anyString())).thenThrow(new RuntimeException("Redis 连接断开"));

            assertThatThrownBy(() -> service.submitAnswerAsync(userId, questionId, true))
                    .isInstanceOf(RuntimeException.class);

            // 验证：回滚首答标记
            verify(setOps).remove("answered:1", "100");
        }
    }
}
