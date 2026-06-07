package com.novel2script.backend.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@Component
public class AiChatClient {

    private final AiProperties aiProperties;

    private final ObjectMapper objectMapper;

    private final HttpClient httpClient;

    public AiChatClient(
            AiProperties aiProperties,
            ObjectMapper objectMapper
    ) {
        this.aiProperties = aiProperties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(aiProperties.getTimeoutSeconds()))
                .build();
    }

    public String completeJson(String systemPrompt, String userPrompt) {
        if (!aiProperties.isConfigured()) {
            throw new IllegalStateException("未配置 AI_API_KEY");
        }

        Map<String, Object> requestBody = Map.of(
                "model", aiProperties.getModelId(),
                "temperature", 0.2,
                "response_format", Map.of("type", "json_object"),
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt)
                )
        );

        return executeJsonWithRetry(requestBody);
    }

    private String executeJsonWithRetry(Map<String, Object> requestBody) {
        int maxAttempts = aiProperties.getMaxRetries() + 1;
        RuntimeException lastFailure = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                HttpResponse<String> response = httpClient.send(
                        buildJsonRequest(requestBody),
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
                );
                if (response.statusCode() >= 200 && response.statusCode() < 300) {
                    return extractContent(response.body());
                }
                RuntimeException failure = buildHttpFailure(response.statusCode(), response.body());
                if (!isRetryableStatus(response.statusCode())) {
                    throw new NonRetryableAiException(failure.getMessage(), failure);
                }
                if (attempt == maxAttempts) {
                    throw failure;
                }
                lastFailure = failure;
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("AI 请求被中断", ex);
            } catch (NonRetryableAiException ex) {
                throw ex;
            } catch (Exception ex) {
                RuntimeException failure = ex instanceof RuntimeException runtimeException
                        ? runtimeException
                        : new IllegalStateException("AI 请求失败", ex);
                if (attempt == maxAttempts) {
                    throw failure;
                }
                lastFailure = failure;
            }
            sleepBeforeRetry(attempt);
        }
        throw lastFailure == null ? new IllegalStateException("AI 请求失败") : lastFailure;
    }

    private HttpRequest buildJsonRequest(Map<String, Object> requestBody) throws Exception {
        return HttpRequest.newBuilder()
                .uri(URI.create(aiProperties.getBaseUrl() + "/chat/completions"))
                .timeout(Duration.ofSeconds(aiProperties.getTimeoutSeconds()))
                .header("Authorization", "Bearer " + aiProperties.getApiKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody), StandardCharsets.UTF_8))
                .build();
    }

    private RuntimeException buildHttpFailure(int statusCode, String responseBody) {
        String boundedBody = responseBody == null ? "" : responseBody;
        if (boundedBody.length() > 500) {
            boundedBody = boundedBody.substring(0, 500);
        }
        return new IllegalStateException("AI 响应失败: HTTP " + statusCode + " " + boundedBody);
    }

    private boolean isRetryableStatus(int statusCode) {
        return statusCode == 429 || statusCode >= 500;
    }

    private void sleepBeforeRetry(int attempt) {
        try {
            Thread.sleep(Math.min(1000L * attempt, 3000L));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("AI 重试等待被中断", ex);
        }
    }

    private static final class NonRetryableAiException extends RuntimeException {

        private NonRetryableAiException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public void streamText(String systemPrompt, String userPrompt, Consumer<String> chunkConsumer) {
        if (!aiProperties.isConfigured()) {
            throw new IllegalStateException("未配置 AI_API_KEY");
        }

        try {
            Map<String, Object> requestBody = Map.of(
                    "model", aiProperties.getModelId(),
                    "temperature", 0.4,
                    "stream", true,
                    "messages", List.of(
                            Map.of("role", "system", "content", systemPrompt),
                            Map.of("role", "user", "content", userPrompt)
                    )
            );

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(aiProperties.getBaseUrl() + "/chat/completions"))
                    .timeout(Duration.ofSeconds(aiProperties.getTimeoutSeconds()))
                    .header("Authorization", "Bearer " + aiProperties.getApiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(requestBody), StandardCharsets.UTF_8))
                    .build();

            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() >= 400) {
                String errorBody = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
                throw new IllegalStateException("AI 流式响应失败: HTTP " + response.statusCode() + " " + errorBody);
            }

            readStreamChunks(response.body(), chunkConsumer);
        } catch (Exception ex) {
            throw new IllegalStateException("AI 流式生成失败", ex);
        }
    }

    private void readStreamChunks(InputStream inputStream, Consumer<String> chunkConsumer) throws Exception {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) {
                    continue;
                }
                String data = line.substring("data:".length()).trim();
                if ("[DONE]".equals(data)) {
                    return;
                }
                String content = extractDeltaContent(data);
                if (content != null && !content.isEmpty()) {
                    chunkConsumer.accept(content);
                }
            }
        }
    }

    private String extractDeltaContent(String data) {
        try {
            JsonNode root = objectMapper.readTree(data);
            return root.path("choices").path(0).path("delta").path("content").asText("");
        } catch (Exception ex) {
            return "";
        }
    }

    private String extractContent(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            String content = root.path("choices").path(0).path("message").path("content").asText();
            if (content == null || content.isBlank()) {
                throw new IllegalStateException("AI 响应中没有 content 字段");
            }
            return normalizeJsonContent(content);
        } catch (Exception ex) {
            throw new IllegalStateException("解析 AI 响应失败", ex);
        }
    }

    String normalizeJsonContent(String content) {
        String trimmed = content.trim();
        if (trimmed.startsWith("```")) {
            int firstLineBreak = trimmed.indexOf('\n');
            int lastFence = trimmed.lastIndexOf("```");
            if (firstLineBreak >= 0 && lastFence > firstLineBreak) {
                trimmed = trimmed.substring(firstLineBreak + 1, lastFence).trim();
            }
        }
        if (isValidJsonObject(trimmed)) {
            return trimmed;
        }
        String extracted = extractFirstJsonObject(trimmed);
        if (isValidJsonObject(extracted)) {
            return extracted;
        }
        return trimmed;
    }

    private boolean isValidJsonObject(String content) {
        if (content == null || content.isBlank()) {
            return false;
        }
        try {
            return objectMapper.readTree(content).isObject();
        } catch (Exception ex) {
            return false;
        }
    }

    private String extractFirstJsonObject(String content) {
        int start = content.indexOf('{');
        if (start < 0) {
            return content;
        }

        boolean inString = false;
        boolean escaped = false;
        int depth = 0;
        for (int i = start; i < content.length(); i++) {
            char current = content.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (current == '\\' && inString) {
                escaped = true;
                continue;
            }
            if (current == '"') {
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }
            if (current == '{') {
                depth++;
            } else if (current == '}') {
                depth--;
                if (depth == 0) {
                    return content.substring(start, i + 1).trim();
                }
            }
        }
        return content;
    }
}
