package com.novel2script.backend.story;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novel2script.backend.common.ProjectOperationLock;
import com.novel2script.backend.project.ProjectService;
import com.novel2script.backend.project.ProjectStatus;
import com.novel2script.backend.scene.OutlineSceneMapper;
import com.novel2script.backend.scene.SceneScriptMapper;
import com.novel2script.backend.source.SourceChapter;
import com.novel2script.backend.source.SourceChapterMapper;
import com.novel2script.backend.story.dto.StoryAnalysisResponse;
import com.novel2script.backend.story.dto.StoryEntityResponse;
import com.novel2script.backend.story.dto.StoryEventResponse;
import com.novel2script.backend.workflow.ProgressEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A 线故事结构分析服务。首版先用稳定规则生成中间资产，后续可替换为 LLM 抽取。
 */
@Service
public class StoryAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(StoryAnalysisService.class);

    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
    };

    private static final Pattern CHARACTER_PATTERN = Pattern.compile(
            "([\\p{IsHan}]{2,4})(?:说|问|道|推门|推|走|抬头|拿|看|笑|沉默|回头|进入|决定|寻找|离开|站)"
    );

    private static final Pattern LOCATION_PATTERN = Pattern.compile(
            "(旧书店|书店|雨夜|街道|房间|办公室|学校|医院|车站|门口|屋里|小巷|城市|仓库|码头|餐厅)"
    );

    private static final Set<String> STOP_WORDS = Set.of(
            "第一章", "第二章", "第三章", "第四章", "第五章", "第六章",
            "这个", "那个", "他们", "她们", "我们", "你们", "时候", "老板"
    );

    private final ProjectService projectService;

    private final SourceChapterMapper sourceChapterMapper;

    private final StoryEntityMapper storyEntityMapper;

    private final StoryEventMapper storyEventMapper;

    private final OutlineSceneMapper outlineSceneMapper;

    private final SceneScriptMapper sceneScriptMapper;

    private final AiStoryAssetExtractor aiStoryAssetExtractor;

    private final ObjectMapper objectMapper;

    private final ProjectOperationLock projectOperationLock;

    private final ProgressEventPublisher progressEventPublisher;

    public StoryAnalysisService(
            ProjectService projectService,
            SourceChapterMapper sourceChapterMapper,
            StoryEntityMapper storyEntityMapper,
            StoryEventMapper storyEventMapper,
            OutlineSceneMapper outlineSceneMapper,
            SceneScriptMapper sceneScriptMapper,
            AiStoryAssetExtractor aiStoryAssetExtractor,
            ObjectMapper objectMapper,
            ProjectOperationLock projectOperationLock,
            ProgressEventPublisher progressEventPublisher
    ) {
        this.projectService = projectService;
        this.sourceChapterMapper = sourceChapterMapper;
        this.storyEntityMapper = storyEntityMapper;
        this.storyEventMapper = storyEventMapper;
        this.outlineSceneMapper = outlineSceneMapper;
        this.sceneScriptMapper = sceneScriptMapper;
        this.aiStoryAssetExtractor = aiStoryAssetExtractor;
        this.objectMapper = objectMapper;
        this.projectOperationLock = projectOperationLock;
        this.progressEventPublisher = progressEventPublisher;
    }

    @Transactional
    public StoryAnalysisResponse analyze(String projectId) {
        return projectOperationLock.execute(projectId, () -> analyzeLocked(projectId));
    }

    private StoryAnalysisResponse analyzeLocked(String projectId) {
        long startedAt = System.currentTimeMillis();
        log.info("开始故事资产分析: projectId={}", projectId);
        progressEventPublisher.jobStarted(projectId, "story_analysis", "entity_extracting", 40, "开始执行 AI 故事资产分析");
        projectService.getProjectEntity(projectId);
        List<SourceChapter> chapters = sourceChapterMapper.findByProjectIdOrderByChapterNoAsc(projectId);
        if (chapters.isEmpty()) {
            throw new IllegalArgumentException("请先提交小说文本并完成章节切分");
        }

        storyEntityMapper.deleteByProjectId(projectId);
        storyEventMapper.deleteByProjectId(projectId);
        sceneScriptMapper.deleteByProjectId(projectId);
        outlineSceneMapper.deleteByProjectId(projectId);

        AssetBuildResult buildResult = buildStoryAssets(projectId, chapters);
        List<StoryEntity> entities = buildResult.assets().entities();
        List<StoryEvent> events = buildResult.assets().events();

        if (!entities.isEmpty()) {
            storyEntityMapper.insertBatch(entities);
        }
        if (!events.isEmpty()) {
            storyEventMapper.insertBatch(events);
        }

        projectService.updateStatus(projectId, ProjectStatus.ENTITY_READY);
        progressEventPublisher.jobCompleted(
                projectId,
                "entity_ready",
                45,
                false,
                "故事资产分析完成，实体 " + entities.size() + " 个，事件 " + events.size() + " 个"
        );
        log.info(
                "故事资产分析完成: projectId={}, mode={}, aiSuccess={}, entityCount={}, eventCount={}, elapsedMs={}",
                projectId,
                buildResult.generationMode(),
                buildResult.aiSuccess(),
                entities.size(),
                events.size(),
                System.currentTimeMillis() - startedAt
        );
        return new StoryAnalysisResponse(
                projectId,
                listEntities(projectId),
                listEvents(projectId),
                buildResult.generationMode(),
                buildResult.aiSuccess(),
                buildResult.fallbackUsed(),
                buildResult.message()
        );
    }

    private AssetBuildResult buildStoryAssets(String projectId, List<SourceChapter> chapters) {
        try {
            return new AssetBuildResult(
                    aiStoryAssetExtractor.extract(projectId, chapters),
                    "AI",
                    true,
                    false,
                    "故事资产由 AI 抽取生成"
            );
        } catch (Exception ex) {
            // AI 服务不可用或返回格式异常时，保留可运行的规则兜底结果，便于前后端继续联调。
            log.warn("AI 故事资产抽取失败，切换规则兜底: projectId={}, reason={}", projectId, rootCauseMessage(ex));
            progressEventPublisher.phaseChanged(projectId, "entity_extracting", 42, "AI 故事资产抽取失败，已切换规则兜底");
            return new AssetBuildResult(
                    new AiStoryAssetExtractor.Result(buildEntities(projectId, chapters), buildEvents(projectId, chapters)),
                    "FALLBACK",
                    false,
                    true,
                    "AI 抽取失败，已使用规则兜底生成故事资产：" + rootCauseMessage(ex)
            );
        }
    }

    private String rootCauseMessage(Exception ex) {
        Throwable current = ex;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        if (message == null || message.isBlank()) {
            return current.getClass().getSimpleName();
        }
        return message.length() > 160 ? message.substring(0, 160) : message;
    }

    @Transactional(readOnly = true)
    public List<StoryEntityResponse> listEntities(String projectId) {
        projectService.getProjectEntity(projectId);
        return storyEntityMapper.findByProjectId(projectId).stream()
                .map(entity -> StoryEntityResponse.from(
                        entity,
                        readStringList(entity.getAliasesJson()),
                        readStringList(entity.getSourceRefsJson())
                ))
                .toList();
    }

    @Transactional(readOnly = true)
    public List<StoryEventResponse> listEvents(String projectId) {
        projectService.getProjectEntity(projectId);
        return storyEventMapper.findByProjectIdOrderByEventOrderAsc(projectId).stream()
                .map(event -> StoryEventResponse.from(event, readStringList(event.getSourceRefsJson())))
                .toList();
    }

    private List<StoryEntity> buildEntities(String projectId, List<SourceChapter> chapters) {
        List<StoryEntity> entities = new ArrayList<>();
        entities.addAll(buildCharacterEntities(projectId, chapters));
        entities.addAll(buildLocationEntities(projectId, chapters));
        return entities;
    }

    private List<StoryEntity> buildCharacterEntities(String projectId, List<SourceChapter> chapters) {
        Map<String, Candidate> candidates = new LinkedHashMap<>();
        for (SourceChapter chapter : chapters) {
            Matcher matcher = CHARACTER_PATTERN.matcher(chapter.getCleanText());
            while (matcher.find()) {
                String name = matcher.group(1);
                if (isValidCharacterName(name)) {
                    String sourceRef = "ch" + chapter.getChapterNo();
                    candidates.computeIfAbsent(name, ignored -> new Candidate(name))
                            .addSourceRef(sourceRef);
                }
            }
        }

        if (candidates.isEmpty()) {
            Candidate fallback = new Candidate("主角");
            fallback.addSourceRef("ch" + chapters.get(0).getChapterNo());
            candidates.put(fallback.name(), fallback);
        }

        List<Candidate> sorted = candidates.values().stream()
                .sorted(Comparator.comparingInt(Candidate::count).reversed().thenComparing(Candidate::name))
                .limit(6)
                .toList();

        List<StoryEntity> entities = new ArrayList<>();
        for (int i = 0; i < sorted.size(); i++) {
            Candidate candidate = sorted.get(i);
            String entityId = "C" + String.format(Locale.ROOT, "%03d", i + 1);
            entities.add(new StoryEntity(
                    projectId,
                    entityId,
                    StoryEntityType.CHARACTER,
                    candidate.name(),
                    toJson(List.of(candidate.name())),
                    "规则抽取的角色候选，后续可由 AI 补充人物小传、目标和说话风格。",
                    toJson(candidate.sourceRefs())
            ));
        }
        return entities;
    }

    private List<StoryEntity> buildLocationEntities(String projectId, List<SourceChapter> chapters) {
        Map<String, Candidate> candidates = new LinkedHashMap<>();
        for (SourceChapter chapter : chapters) {
            Matcher matcher = LOCATION_PATTERN.matcher(chapter.getCleanText());
            while (matcher.find()) {
                String name = matcher.group(1);
                String sourceRef = "ch" + chapter.getChapterNo();
                candidates.computeIfAbsent(name, ignored -> new Candidate(name))
                        .addSourceRef(sourceRef);
            }
        }

        if (candidates.isEmpty()) {
            Candidate fallback = new Candidate("未命名地点");
            fallback.addSourceRef("ch" + chapters.get(0).getChapterNo());
            candidates.put(fallback.name(), fallback);
        }

        List<Candidate> sorted = candidates.values().stream()
                .sorted(Comparator.comparingInt(Candidate::count).reversed().thenComparing(Candidate::name))
                .limit(6)
                .toList();

        List<StoryEntity> entities = new ArrayList<>();
        for (int i = 0; i < sorted.size(); i++) {
            Candidate candidate = sorted.get(i);
            String entityId = "L" + String.format(Locale.ROOT, "%03d", i + 1);
            entities.add(new StoryEntity(
                    projectId,
                    entityId,
                    StoryEntityType.LOCATION,
                    candidate.name(),
                    toJson(List.of(candidate.name())),
                    "规则抽取的地点候选，后续可由 AI 补充视觉标签和场景气质。",
                    toJson(candidate.sourceRefs())
            ));
        }
        return entities;
    }

    private List<StoryEvent> buildEvents(String projectId, List<SourceChapter> chapters) {
        List<StoryEvent> events = new ArrayList<>();
        for (int i = 0; i < chapters.size(); i++) {
            SourceChapter chapter = chapters.get(i);
            String eventId = "E" + String.format(Locale.ROOT, "%03d", i + 1);
            String sourceRef = "ch" + chapter.getChapterNo();
            events.add(new StoryEvent(
                    projectId,
                    eventId,
                    chapter.getId(),
                    i + 1,
                    chapter.getTitle(),
                    buildEventSummary(chapter),
                    toJson(List.of(sourceRef))
            ));
        }
        return events;
    }

    private String buildEventSummary(SourceChapter chapter) {
        if (chapter.getSummary() != null && !chapter.getSummary().isBlank()) {
            return chapter.getSummary();
        }
        String content = chapter.getCleanText().replace(chapter.getTitle(), "").trim();
        if (content.isBlank()) {
            return chapter.getTitle();
        }
        String flattened = content.replaceAll("\\s+", " ");
        return flattened.length() > 160 ? flattened.substring(0, 160) : flattened;
    }

    private boolean isValidCharacterName(String name) {
        if (name == null || name.length() < 2 || name.length() > 4) {
            return false;
        }
        if (STOP_WORDS.contains(name)) {
            return false;
        }
        return !name.startsWith("第") && !name.endsWith("章");
    }

    private String toJson(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("序列化故事资产失败", ex);
        }
    }

    private List<String> readStringList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, STRING_LIST_TYPE);
        } catch (JsonProcessingException ex) {
            return List.of();
        }
    }

    private static final class Candidate {

        private final String name;

        private int count;

        private final Set<String> sourceRefs = new LinkedHashSet<>();

        private Candidate(String name) {
            this.name = name;
        }

        private Candidate addSourceRef(String sourceRef) {
            this.count++;
            this.sourceRefs.add(sourceRef);
            return this;
        }

        private String name() {
            return name;
        }

        private int count() {
            return count;
        }

        private List<String> sourceRefs() {
            return List.copyOf(sourceRefs);
        }
    }

    private record AssetBuildResult(
            AiStoryAssetExtractor.Result assets,
            String generationMode,
            Boolean aiSuccess,
            Boolean fallbackUsed,
            String message
    ) {
    }
}
