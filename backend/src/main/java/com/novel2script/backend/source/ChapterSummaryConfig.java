package com.novel2script.backend.source;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class ChapterSummaryConfig {

    // 章节摘要逐章串行是分析阶段最直接的"长等"来源。每章摘要相互独立、生成器自带规则兜底，
    // 用一个有界线程池做章内并行即可显著缩短整本书的摘要耗时，同时把对模型的并发压在可控范围。
    @Bean(name = "chapterSummaryExecutor")
    public ThreadPoolTaskExecutor chapterSummaryExecutor(
            @Value("${CHAPTER_SUMMARY_CONCURRENCY:4}") int concurrency
    ) {
        int bounded = Math.max(1, concurrency);
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(bounded);
        executor.setMaxPoolSize(bounded);
        executor.setQueueCapacity(Integer.MAX_VALUE);
        executor.setThreadNamePrefix("chapter-summary-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
