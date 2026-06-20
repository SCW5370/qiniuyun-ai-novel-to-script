package com.novel2script.backend.ai;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class AiProperties {

    private final String apiKey;

    private final String baseUrl;

    private final String modelId;

    private final String cheapModelId;

    private final int timeoutSeconds;

    private final int maxRetries;

    public AiProperties(
            @Value("${AI_API_KEY:}") String apiKey,
            @Value("${AI_BASE_URL:https://api.openai.com/v1}") String baseUrl,
            @Value("${AI_MODEL_ID:gpt-4.1-mini}") String modelId,
            @Value("${AI_MODEL_ID_CHEAP:}") String cheapModelId,
            @Value("${AI_TIMEOUT_SECONDS:180}") int timeoutSeconds,
            @Value("${AI_MAX_RETRIES:2}") int maxRetries
    ) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.modelId = modelId;
        this.cheapModelId = cheapModelId;
        this.timeoutSeconds = timeoutSeconds;
        this.maxRetries = maxRetries;
    }

    public String getApiKey() {
        return apiKey;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public String getModelId() {
        return modelId;
    }

    // 结构化、容错高的阶段(摘要/抽取)用的廉价模型。未配置时回退到主模型，保证默认行为不变。
    public String getCheapModelId() {
        return (cheapModelId == null || cheapModelId.isBlank()) ? modelId : cheapModelId;
    }

    public int getTimeoutSeconds() {
        return Math.max(10, timeoutSeconds);
    }

    public int getMaxRetries() {
        return Math.max(0, maxRetries);
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }
}
