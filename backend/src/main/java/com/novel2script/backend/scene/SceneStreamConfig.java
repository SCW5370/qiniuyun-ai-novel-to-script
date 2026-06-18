package com.novel2script.backend.scene;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

@Configuration
@EnableScheduling
public class SceneStreamConfig {

    @Bean(name = "sceneStreamExecutor")
    public ThreadPoolTaskExecutor sceneStreamExecutor(
            @Value("${SCENE_STREAM_CORE_POOL_SIZE:2}") int corePoolSize,
            @Value("${SCENE_STREAM_MAX_POOL_SIZE:4}") int maxPoolSize,
            @Value("${SCENE_STREAM_QUEUE_CAPACITY:20}") int queueCapacity
    ) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(Math.max(1, corePoolSize));
        executor.setMaxPoolSize(Math.max(Math.max(1, corePoolSize), maxPoolSize));
        executor.setQueueCapacity(Math.max(0, queueCapacity));
        executor.setThreadNamePrefix("scene-stream-");
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }

    // 批量补齐缺失 Scene 剧本时，原本是逐个串行调用 AI，整本书的等待时间≈场景数×单场景耗时。
    // 各场景的生成相互独立（各自独立读写事务、自带规则兜底），用一个有界线程池做并行，
    // 可把总时长压到接近"单场景耗时×批次数"，同时把对模型的并发控制在可控范围内。
    @Bean(name = "sceneScriptExecutor")
    public ThreadPoolTaskExecutor sceneScriptExecutor(
            @Value("${SCENE_SCRIPT_CONCURRENCY:4}") int concurrency
    ) {
        int bounded = Math.max(1, concurrency);
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(bounded);
        executor.setMaxPoolSize(bounded);
        executor.setQueueCapacity(Integer.MAX_VALUE);
        executor.setThreadNamePrefix("scene-script-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }
}
