package com.novel2script.backend.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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

    @Test
    void readStreamChunksEmitsContentUntilDone() throws Exception {
        String stream = """
                data: {"choices":[{"delta":{"content":"第一段"},"finish_reason":null}]}

                data: {"choices":[{"delta":{"content":"第二段"},"finish_reason":null}]}

                data: [DONE]

                """;
        List<String> chunks = new ArrayList<>();

        aiChatClient.readStreamChunks(
                new ByteArrayInputStream(stream.getBytes(StandardCharsets.UTF_8)),
                chunks::add
        );

        assertEquals(List.of("第一段", "第二段"), chunks);
    }

    @Test
    void readStreamChunksRejectsPrematureEof() {
        String stream = "data: {\"choices\":[{\"delta\":{\"content\":\"半截内容\"},\"finish_reason\":null}]}\n\n";

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> aiChatClient.readStreamChunks(
                        new ByteArrayInputStream(stream.getBytes(StandardCharsets.UTF_8)),
                        ignored -> { }
                )
        );

        assertEquals("AI 流式响应在完成标记前中断", failure.getMessage());
    }

    @Test
    void readStreamChunksAcceptsTerminalFinishReasonWithoutDoneSentinel() throws Exception {
        String stream = "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n";

        aiChatClient.readStreamChunks(
                new ByteArrayInputStream(stream.getBytes(StandardCharsets.UTF_8)),
                ignored -> { }
        );
    }

    @Test
    void readStreamChunksRejectsMalformedData() {
        String stream = "data: not-json\n\n";

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> aiChatClient.readStreamChunks(
                        new ByteArrayInputStream(stream.getBytes(StandardCharsets.UTF_8)),
                        ignored -> { }
                )
        );

        assertEquals("解析 AI 流式响应片段失败", failure.getMessage());
    }
}
