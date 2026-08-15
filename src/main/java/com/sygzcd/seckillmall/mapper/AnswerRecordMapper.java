package com.sygzcd.seckillmall.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sygzcd.seckillmall.entity.AnswerRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 答题记录 Mapper
 */
@Mapper
public interface AnswerRecordMapper extends BaseMapper<AnswerRecord> {

    /**
     * 批量插入答题记录
     * 单条 INSERT INTO ... VALUES (...),(...) 比多条 INSERT 性能更好
     * 因为 MySQL 只需解析一次 SQL 语句
     */
    int insertBatch(@Param("list") List<AnswerRecord> list);
}
