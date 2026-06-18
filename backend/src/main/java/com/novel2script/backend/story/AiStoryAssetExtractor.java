package com.novel2script.backend.story;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novel2script.backend.ai.AiChatClient;
import com.novel2script.backend.source.SourceChapter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

@Component
public class AiStoryAssetExtractor {

    private static final int MAX_BATCH_CHARS = 12000;

    private static final int MAX_CHAPTER_CHARS = 6000;

    private final AiChatClient aiChatClient;

    private final ObjectMapper objectMapper;

    private final ThreadPoolTaskExecutor storyAssetExecutor;

    public AiStoryAssetExtractor(
            AiChatClient aiChatClient,
            ObjectMapper objectMapper,
            @Qualifier("storyAssetExecutor") ThreadPoolTaskExecutor storyAssetExecutor
    ) {
        this.aiChatClient = aiChatClient;
        this.objectMapper = objectMapper;
        this.storyAssetExecutor = storyAssetExecutor;
    }

    public Result extract(String projectId, List<SourceChapter> chapters) {
        try {
            List<List<SourceChapter>> batches = buildChapterBatches(chapters);
            List<Future<BatchAssets>> futures = new ArrayList<>(batches.size());
            for (List<SourceChapter> batch : batches) {
                futures.add(storyAssetExecutor.submit(() -> extractBatch(projectId, batch)));
            }

            List<StoryEntity> collectedEntities = new ArrayList<>();
            List<StoryEvent> collectedEvents = new ArrayList<>();
            for (int i = 0; i < futures.size(); i++) {
                BatchAssets batchAssets;
                try {
                    batchAssets = futures.get(i).get();
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    futures.forEach(future -> future.cancel(true));
                    throw new IllegalStateException("AI 故事资产抽取被中断: projectId=" + projectId, ex);
                } catch (ExecutionException ex) {
                    Throwable cause = ex.getCause();
                    throw new IllegalStateException("AI 故事资产抽取失败: projectId=" + projectId,
                            cause != null ? cause : ex);
                }
                collectedEntities.addAll(batchAssets.entities());
                collectedEvents.addAll(batchAssets.events());
            }

            List<StoryEntity> entities = normalizeEntities(projectId, collectedEntities);
            List<StoryEvent> events = normalizeEvents(projectId, collectedEvents, chapters);
            if (entities.isEmpty() || events.isEmpty()) {
                throw new IllegalStateException("AI 故事资产为空");
            }
            return new Result(entities, events);
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("AI 故事资产抽取失败", ex);
        }
    }

    private BatchAssets extractBatch(String projectId, List<SourceChapter> batch) throws Exception {
        String response = aiChatClient.completeJson(
                "你是小说剧本化系统的故事资产分析助手。只返回合法 JSON，不要输出解释。",
                buildPrompt(batch)
        );
        JsonNode root = objectMapper.readTree(response);
        return new BatchAssets(
                parseEntities(projectId, root.path("entities")),
                parseEvents(projectId, batch, root.path("events"))
        );
    }

    private record BatchAssets(List<StoryEntity> entities, List<StoryEvent> events) {
    }

    private List<List<SourceChapter>> buildChapterBatches(List<SourceChapter> chapters) {
        List<List<SourceChapter>> batches = new ArrayList<>();
        List<SourceChapter> currentBatch = new ArrayList<>();
        int currentLength = 0;
        for (SourceChapter chapter : chapters) {
            int chapterLength = Math.min(chapter.getCleanText().length(), MAX_CHAPTER_CHARS) + chapter.getTitle().length() + 16;
            if (!currentBatch.isEmpty() && currentLength + chapterLength > MAX_BATCH_CHARS) {
                batches.add(currentBatch);
                currentBatch = new ArrayList<>();
                currentLength = 0;
            }
            currentBatch.add(chapter);
            currentLength += chapterLength;
        }
        if (!currentBatch.isEmpty()) {
            batches.add(currentBatch);
        }
        return batches;
    }

    private List<StoryEntity> parseEntities(String projectId, JsonNode entitiesNode) {
        List<StoryEntity> entities = new ArrayList<>();
        int characterIndex = 1;
        int locationIndex = 1;
        if (!entitiesNode.isArray()) {
            return entities;
        }
        for (JsonNode node : entitiesNode) {
            StoryEntityType type = parseEntityType(node.path("entityType").asText());
            String entityId = type == StoryEntityType.LOCATION
                    ? "L" + String.format(Locale.ROOT, "%03d", locationIndex++)
                    : "C" + String.format(Locale.ROOT, "%03d", characterIndex++);
            String canonicalName = normalizeText(node.path("canonicalName").asText(), type == StoryEntityType.LOCATION ? "未命名地点" : "未命名角色");
            entities.add(new StoryEntity(
                    projectId,
                    entityId,
                    type,
                    canonicalName,
                    writeJson(readStringArray(node.path("aliases"), List.of(canonicalName))),
                    normalizeText(node.path("profile").asText(), "暂无说明"),
                    writeJson(readStringArray(node.path("sourceRefs"), List.of()))
            ));
        }
        return entities;
    }

    private List<StoryEvent> parseEvents(String projectId, List<SourceChapter> chapters, JsonNode eventsNode) {
        List<StoryEvent> events = new ArrayList<>();
        if (!eventsNode.isArray()) {
            return events;
        }
        int index = 1;
        for (JsonNode node : eventsNode) {
            int chapterNo = Math.max(1, node.path("chapterNo").asInt(index));
            SourceChapter chapter = findChapterByNo(chapters, chapterNo);
            String eventId = "E" + String.format(Locale.ROOT, "%03d", index);
            events.add(new StoryEvent(
                    projectId,
                    eventId,
                    chapter.getId(),
                    index,
                    normalizeText(node.path("title").asText(), chapter.getTitle()),
                    normalizeText(node.path("summary").asText(), chapter.getSummary() == null ? chapter.getTitle() : chapter.getSummary()),
                    writeJson(readStringArray(node.path("sourceRefs"), List.of("ch" + chapter.getChapterNo())))
            ));
            index++;
        }
        return events;
    }

    private StoryEntityType parseEntityType(String value) {
        return "LOCATION".equalsIgnoreCase(value) ? StoryEntityType.LOCATION : StoryEntityType.CHARACTER;
    }

    private List<StoryEntity> normalizeEntities(String projectId, List<StoryEntity> rawEntities) {
        Map<String, EntityAccumulator> accumulators = new LinkedHashMap<>();
        for (StoryEntity entity : rawEntities) {
            String key = entityKey(entity.getEntityType(), entity.getCanonicalName());
            EntityAccumulator accumulator = accumulators.computeIfAbsent(
                    key,
                    ignored -> new EntityAccumulator(entity.getEntityType(), entity.getCanonicalName())
            );
            accumulator.aliases.addAll(readJsonStringList(entity.getAliasesJson()));
            accumulator.sourceRefs.addAll(readJsonStringList(entity.getSourceRefsJson()));
            if (entity.getProfile() != null && !entity.getProfile().isBlank() && !"暂无说明".equals(entity.getProfile())) {
                accumulator.profiles.add(entity.getProfile().trim());
            }
        }

        List<EntityAccumulator> sorted = accumulators.values().stream()
                .sorted(Comparator
                        .comparing(EntityAccumulator::type)
                        .thenComparing(EntityAccumulator::canonicalName))
                .toList();
        List<StoryEntity> entities = new ArrayList<>();
        int characterIndex = 1;
        int locationIndex = 1;
        for (EntityAccumulator accumulator : sorted) {
            String entityId = accumulator.type == StoryEntityType.LOCATION
                    ? "L" + String.format(Locale.ROOT, "%03d", locationIndex++)
                    : "C" + String.format(Locale.ROOT, "%03d", characterIndex++);
            List<String> aliases = accumulator.aliases.isEmpty()
                    ? List.of(accumulator.canonicalName)
                    : List.copyOf(accumulator.aliases);
            String profile = accumulator.profiles.isEmpty()
                    ? "暂无说明"
                    : String.join("\n", accumulator.profiles);
            entities.add(new StoryEntity(
                    projectId,
                    entityId,
                    accumulator.type,
                    accumulator.canonicalName,
                    writeJson(aliases),
                    profile,
                    writeJson(List.copyOf(accumulator.sourceRefs))
            ));
        }
        return entities;
    }

    private List<StoryEvent> normalizeEvents(String projectId, List<StoryEvent> rawEvents, List<SourceChapter> chapters) {
        Map<Long, Integer> chapterNoById = new LinkedHashMap<>();
        for (SourceChapter chapter : chapters) {
            chapterNoById.put(chapter.getId(), chapter.getChapterNo());
        }
        List<StoryEvent> sorted = rawEvents.stream()
                .sorted(Comparator
                        .comparingInt((StoryEvent event) -> chapterNoById.getOrDefault(event.getChapterId(), Integer.MAX_VALUE))
                        .thenComparing(event -> event.getEventOrder() == null ? Integer.MAX_VALUE : event.getEventOrder()))
                .toList();
        List<StoryEvent> events = new ArrayList<>();
        for (int i = 0; i < sorted.size(); i++) {
            StoryEvent event = sorted.get(i);
            event.setProjectId(projectId);
            event.setEventId("E" + String.format(Locale.ROOT, "%03d", i + 1));
            event.setEventOrder(i + 1);
            events.add(event);
        }
        return events;
    }

    private String entityKey(StoryEntityType type, String name) {
        return type.name() + ":" + (name == null ? "" : name.trim().toLowerCase(Locale.ROOT));
    }

    private SourceChapter findChapterByNo(List<SourceChapter> chapters, int chapterNo) {
        return chapters.stream()
                .filter(chapter -> chapter.getChapterNo() == chapterNo)
                .findFirst()
                .orElse(chapters.get(0));
    }

    private List<String> readStringArray(JsonNode node, List<String> fallback) {
        if (!node.isArray()) {
            return fallback;
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : node) {
            String value = item.asText();
            if (value != null && !value.isBlank()) {
                values.add(value.trim());
            }
        }
        return values.isEmpty() ? fallback : values;
    }

    private String normalizeText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private String writeJson(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (Exception ex) {
            throw new IllegalStateException("序列化 AI 故事资产失败", ex);
        }
    }

    private List<String> readJsonStringList(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            return readStringArray(node, List.of());
        } catch (Exception ex) {
            return List.of();
        }
    }

    private String buildPrompt(List<SourceChapter> chapters) {
        return """
                请从小说文本中抽取剧本改编需要的故事资产。
                只返回 JSON，不要使用 Markdown 代码块，不要输出解释。
                格式如下：
                {
                  "entities": [
                    {
                      "entityType": "CHARACTER 或 LOCATION",
                      "canonicalName": "名称",
                      "aliases": ["别名"],
                      "profile": "人物小传或地点说明",
                      "sourceRefs": ["ch1"]
                    }
                  ],
                  "events": [
                    {
                      "chapterNo": 1,
                      "title": "事件标题",
                      "summary": "事件摘要",
                      "sourceRefs": ["ch1"]
                    }
                  ]
                }
                要求：
                1. 只抽取对剧情推进或场景调度有用的角色和地点，角色和地点总数控制在 4 到 12 个。
                2. entityType 只能使用 CHARACTER 或 LOCATION，canonicalName 使用原文中最稳定的中文名称。
                3. aliases 只放原文出现过的称呼，不要编造外号。
                4. 事件必须严格按输入章节顺序和章节内发生顺序排列，每章至少 1 个关键事件，最多 4 个关键事件。
                5. sourceRefs 只使用 ch + 章节号，例如 ch1，不要使用页码、段落号或不存在的章节号。
                6. summary 用 1 到 2 句中文说明事件的起因、行动和结果，不要写成分析评论。
                7. 不要抽取和当前批次无关的旧章节内容。

                小说文本：
                %s
                """.formatted(buildChapterText(chapters));
    }

    private String buildChapterText(List<SourceChapter> chapters) {
        StringBuilder builder = new StringBuilder();
        for (SourceChapter chapter : chapters) {
            String cleanText = chapter.getCleanText();
            String boundedText = cleanText.length() > MAX_CHAPTER_CHARS
                    ? cleanText.substring(0, MAX_CHAPTER_CHARS)
                    : cleanText;
            builder.append("ch").append(chapter.getChapterNo())
                    .append(" ").append(chapter.getTitle()).append('\n')
                    .append(boundedText).append("\n\n");
        }
        return builder.toString();
    }

    private static final class EntityAccumulator {

        private final StoryEntityType type;

        private final String canonicalName;

        private final Set<String> aliases = new LinkedHashSet<>();

        private final Set<String> sourceRefs = new LinkedHashSet<>();

        private final Set<String> profiles = new LinkedHashSet<>();

        private EntityAccumulator(StoryEntityType type, String canonicalName) {
            this.type = type;
            this.canonicalName = canonicalName;
            this.aliases.add(canonicalName);
        }

        private StoryEntityType type() {
            return type;
        }

        private String canonicalName() {
            return canonicalName;
        }
    }

    public record Result(List<StoryEntity> entities, List<StoryEvent> events) {
    }
}
