package com.novel2script.backend.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novel2script.backend.ai.AiChatClient;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Primary
@Component
public class AiChapterSummaryGenerator implements ChapterSummaryGenerator {

    private static final int MAX_CHAPTER_CHARS = 5000;

    private final AiChatClient aiChatClient;

    private final RuleBasedChapterSummaryGenerator fallbackGenerator;

    private final ObjectMapper objectMapper;

    public AiChapterSummaryGenerator(
            AiChatClient aiChatClient,
            RuleBasedChapterSummaryGenerator fallbackGenerator,
            ObjectMapper objectMapper
    ) {
        this.aiChatClient = aiChatClient;
        this.fallbackGenerator = fallbackGenerator;
        this.objectMapper = objectMapper;
    }

    @Override
    public String generate(SourceChapter chapter) {
        try {
            String response = aiChatClient.completeJsonCheap(
                    "你是小说剧本化系统的阅读分析助手。只返回合法 JSON，不要输出解释。",
                    buildPrompt(chapter)
            );
            JsonNode root = objectMapper.readTree(response);
            String summary = root.path("summary").asText();
            if (summary == null || summary.isBlank()) {
                return fallbackGenerator.generate(chapter);
            }
            return summary.trim();
        } catch (Exception ex) {
            return fallbackGenerator.generate(chapter);
        }
    }

    private String buildPrompt(SourceChapter chapter) {
        return """
                请为下面小说章节生成一个适合剧本改编的章节摘要。
                要求：
                1. 摘要使用中文。
                2. 控制在 120 到 200 字。
                3. 保留主要人物、地点、冲突、关键行动。
                4. 不扩写原文没有出现的信息，不评价文本质量。
                5. 只返回 JSON：{"summary":"..."}
                6. 不要使用 Markdown 代码块包裹 JSON。

                章节标题：%s
                章节正文：
                %s
                """.formatted(chapter.getTitle(), truncate(chapter.getCleanText(), MAX_CHAPTER_CHARS));
    }

    private String truncate(String value, int maxChars) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxChars ? value : value.substring(0, maxChars);
    }
}
