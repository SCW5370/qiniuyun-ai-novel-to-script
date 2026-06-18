package com.novel2script.backend.story;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
public class StoryAssetConfig {

    // 实体/事件抽取原本逐个章节批次串行调用 AI，是分析阶段仅次于大纲的"长等"来源。
    // 各批次相互独立（只各自解析后汇总，最终统一重排重编号），用一个有界线程池做批内并行，
    // 可把抽取总耗时压到接近"单批耗时×并发批次"，同时把对模型的并发控制在可控范围。
    @Bean(name = "storyAssetExecutor")
    public ThreadPoolTaskExecutor storyAssetExecutor(
            @Value("${STORY_ASSET_CONCURRENCY:4}") int concurrency
    ) {
        int bounded = Math.max(1, concurrency);
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(bounded);
        executor.setMaxPoolSize(bounded);
        executor.setQueueCapacity(Integer.MAX_VALUE);
        executor.setThreadNamePrefix("story-asset-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
