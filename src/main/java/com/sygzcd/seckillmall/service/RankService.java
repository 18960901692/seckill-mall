package com.sygzcd.seckillmall.service;

import java.util.List;
import java.util.Map;

public interface RankService {
    
    /**
     * 添加积分
     */
    void addScore(Long userId, double score);
    
    /**
     * 获取用户积分
     */
    Double getUserScore(Long userId);
    
    /**
     * 获取 Top N 排行榜
     */
    List<Map<String, Object>> getTopN(int n);
    
    /**
     * 获取用户排名
     */
    Long getUserRank(Long userId);
}
