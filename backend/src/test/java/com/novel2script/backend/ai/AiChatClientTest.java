package com.novel2script.backend.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AiChatClientTest {

    private final AiChatClient aiChatClient = new AiChatClient(
            new AiProperties("", "https://example.com/v1", "test-model", 10, 0),
            new ObjectMapper()
    );

    @Test
    void normalizeJsonContentKeepsPlainJsonObject() {
        String json = "{\"summary\":\"雨夜旧书店\"}";

        assertEquals(json, aiChatClient.normalizeJsonContent(json));
    }

    @Test
    void normalizeJsonContentStripsMarkdownFence() {
        String content = """
                ```json
                {"summary":"雨夜旧书店"}
                ```
                """;

        assertEquals("{\"summary\":\"雨夜旧书店\"}", aiChatClient.normalizeJsonContent(content));
    }

    @Test
    void normalizeJsonContentExtractsJsonObjectFromExtraText() {
        String content = "下面是结果：{\"summary\":\"雨夜旧书店\"} 以上。";

        assertEquals("{\"summary\":\"雨夜旧书店\"}", aiChatClient.normalizeJsonContent(content));
    }

    @Test
    void normalizeJsonContentIgnoresBracesInsideString() {
        String content = "结果：{\"summary\":\"角色看到{锈雨}落下\",\"ok\":true}。";

        assertEquals("{\"summary\":\"角色看到{锈雨}落下\",\"ok\":true}", aiChatClient.normalizeJsonContent(content));
    }
}
