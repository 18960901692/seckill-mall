package com.sygzcd.seckillmall.service.impl;

import com.sygzcd.seckillmall.service.RankService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 排行榜服务实现
 * 使用 StringRedisTemplate 操作 ZSet，避免 Jackson 序列化导致 member 带双引号
 */
@Service
public class RankServiceImpl implements RankService {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    private static final String RANK_KEY = "rank:score";

    @Override
    public void addScore(Long userId, double score) {
        stringRedisTemplate.opsForZSet().incrementScore(RANK_KEY, userId.toString(), score);
    }

    @Override
    public Double getUserScore(Long userId) {
        return stringRedisTemplate.opsForZSet().score(RANK_KEY, userId.toString());
    }

    @Override
    public List<Map<String, Object>> getTopN(int n) {
        Set<ZSetOperations.TypedTuple<String>> tuples =
                stringRedisTemplate.opsForZSet().reverseRangeWithScores(RANK_KEY, 0, n - 1);

        List<Map<String, Object>> result = new ArrayList<>();
        if (tuples != null) {
            for (ZSetOperations.TypedTuple<String> tuple : tuples) {
                Map<String, Object> item = new HashMap<>();
                item.put("userId", tuple.getValue());
                item.put("score", tuple.getScore());
                result.add(item);
            }
        }
        return result;
    }

    @Override
    public Long getUserRank(Long userId) {
        Long rank = stringRedisTemplate.opsForZSet().reverseRank(RANK_KEY, userId.toString());
        return rank != null ? rank + 1 : null;
    }
}
