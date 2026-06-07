package com.novel2script.backend.ai;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class AiProperties {

    private final String apiKey;

    private final String baseUrl;

    private final String modelId;

    private final int timeoutSeconds;

    private final int maxRetries;

    public AiProperties(
            @Value("${AI_API_KEY:}") String apiKey,
            @Value("${AI_BASE_URL:https://api.openai.com/v1}") String baseUrl,
            @Value("${AI_MODEL_ID:gpt-4.1-mini}") String modelId,
            @Value("${AI_TIMEOUT_SECONDS:90}") int timeoutSeconds,
            @Value("${AI_MAX_RETRIES:2}") int maxRetries
    ) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.modelId = modelId;
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
