package com.sygzcd.seckillmall.service.impl;

import com.sygzcd.seckillmall.service.RankService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class RankServiceImpl implements RankService {

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    private static final String RANK_KEY = "rank:score";

    @Override
    public void addScore(Long userId, double score) {
        redisTemplate.opsForZSet().add(RANK_KEY, userId.toString(), score);
    }

    @Override
    public Double getUserScore(Long userId) {
        return redisTemplate.opsForZSet().score(RANK_KEY, userId.toString());
    }

    @Override
    public List<Map<String, Object>> getTopN(int n) {
        Set<ZSetOperations.TypedTuple<Object>> tuples = 
                redisTemplate.opsForZSet().reverseRangeWithScores(RANK_KEY, 0, n - 1);
        
        List<Map<String, Object>> result = new ArrayList<>();
        if (tuples != null) {
            for (ZSetOperations.TypedTuple<Object> tuple : tuples) {
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
        Long rank = redisTemplate.opsForZSet().reverseRank(RANK_KEY, userId.toString());
        return rank != null ? rank + 1 : null;
    }
}
