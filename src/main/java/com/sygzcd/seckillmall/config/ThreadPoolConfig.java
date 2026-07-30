package com.sygzcd.seckillmall.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 自定义线程池配置
 * 用于异步任务：答题记录落库、排行榜更新等
 */
@Configuration
public class ThreadPoolConfig {

    @Bean("bizExecutor")
    public ThreadPoolExecutor bizExecutor() {
        return new ThreadPoolExecutor(
                // 核心线程数
                4,
                // 最大线程数
                8,
                // 空闲线程存活时间
                60,
                TimeUnit.SECONDS,
                // 工作队列
                new LinkedBlockingQueue<>(1000),
                // 自定义线程工厂
                new ThreadFactory() {
                    private final AtomicInteger i = new AtomicInteger();
                    @Override
                    public Thread newThread(Runnable r) {
                        return new Thread(r, "biz-" + i.incrementAndGet());
                    }
                },
                // 拒绝策略：调用方执行，不丢任务
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }
}
