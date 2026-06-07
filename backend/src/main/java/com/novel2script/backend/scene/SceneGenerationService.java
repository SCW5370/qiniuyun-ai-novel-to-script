package com.novel2script.backend.scene;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novel2script.backend.ai.AiChatClient;
import com.novel2script.backend.common.ProjectOperationLock;
import com.novel2script.backend.project.Project;
import com.novel2script.backend.project.ProjectService;
import com.novel2script.backend.project.ProjectStatus;
import com.novel2script.backend.scene.dto.OutlineSceneResponse;
import com.novel2script.backend.scene.dto.SceneScriptResponse;
import com.novel2script.backend.story.StoryEntity;
import com.novel2script.backend.story.StoryEntityMapper;
import com.novel2script.backend.story.StoryEvent;
import com.novel2script.backend.story.StoryEventMapper;
import com.novel2script.backend.workflow.ProgressEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

@Service
public class SceneGenerationService {

    private static final Logger log = LoggerFactory.getLogger(SceneGenerationService.class);

    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {
    };

    private static final TypeReference<List<SceneScriptResponse.DialogueResponse>> DIALOGUE_LIST_TYPE = new TypeReference<>() {
    };

    private final ProjectService projectService;
    private final StoryEntityMapper storyEntityMapper;
    private final StoryEventMapper storyEventMapper;
    private final OutlineSceneMapper outlineSceneMapper;
    private final SceneScriptMapper sceneScriptMapper;
    private final AiChatClient aiChatClient;
    private final ObjectMapper objectMapper;
    private final ProjectOperationLock projectOperationLock;
    private final ProgressEventPublisher progressEventPublisher;
    private final TransactionTemplate readOnlyTransactionTemplate;
    private final TransactionTemplate writeTransactionTemplate;
    private final int maxOutlineEventsPerBatch;

    public SceneGenerationService(
            ProjectService projectService,
            StoryEntityMapper storyEntityMapper,
            StoryEventMapper storyEventMapper,
            OutlineSceneMapper outlineSceneMapper,
            SceneScriptMapper sceneScriptMapper,
            AiChatClient aiChatClient,
            ObjectMapper objectMapper,
            ProjectOperationLock projectOperationLock,
            ProgressEventPublisher progressEventPublisher,
            PlatformTransactionManager transactionManager,
            @Value("${OUTLINE_EVENTS_PER_BATCH:6}") int maxOutlineEventsPerBatch
    ) {
        this.projectService = projectService;
        this.storyEntityMapper = storyEntityMapper;
        this.storyEventMapper = storyEventMapper;
        this.outlineSceneMapper = outlineSceneMapper;
        this.sceneScriptMapper = sceneScriptMapper;
        this.aiChatClient = aiChatClient;
        this.objectMapper = objectMapper;
        this.projectOperationLock = projectOperationLock;
        this.progressEventPublisher = progressEventPublisher;
        this.readOnlyTransactionTemplate = new TransactionTemplate(transactionManager);
        this.readOnlyTransactionTemplate.setReadOnly(true);
        this.readOnlyTransactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.writeTransactionTemplate = new TransactionTemplate(transactionManager);
        this.writeTransactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.maxOutlineEventsPerBatch = Math.max(1, maxOutlineEventsPerBatch);
    }

    public List<OutlineSceneResponse> listOutline(String projectId) {
        return projectOperationLock.execute(projectId, () -> listOutlineLocked(projectId));
    }

    @Transactional(readOnly = true)
    public List<OutlineSceneResponse> listExistingOutline(String projectId) {
        projectService.getProjectEntity(projectId);
        return outlineSceneMapper.findByProjectIdOrderBySeqNoAsc(projectId).stream()
                .map(this::toOutlineResponse)
                .toList();
    }

    public List<OutlineSceneResponse> generateIncrementalOutline(String projectId) {
        return projectOperationLock.execute(projectId, () -> generateIncrementalOutlineLocked(projectId));
    }

    private List<OutlineSceneResponse> listOutlineLocked(String projectId) {
        projectService.getProjectEntity(projectId);
        List<OutlineScene> scenes = outlineSceneMapper.findByProjectIdOrderBySeqNoAsc(projectId);
        if (scenes.isEmpty()) {
            scenes = generateOutline(projectId);
        } else {
            reconcileOutlineAndSceneScriptOrder(projectId);
            scenes = outlineSceneMapper.findByProjectIdOrderBySeqNoAsc(projectId);
        }
        return scenes.stream().map(this::toOutlineResponse).toList();
    }

    private List<OutlineSceneResponse> generateIncrementalOutlineLocked(String projectId) {
        long startedAt = System.currentTimeMillis();
        log.info("开始增量生成场景大纲: projectId={}", projectId);
        progressEventPublisher.jobStarted(projectId, "outline_generation_incremental", "outline_generating", 55, "开始为新增事件生成场景大纲");
        projectService.getProjectEntity(projectId);

        List<StoryEvent> events = storyEventMapper.findByProjectIdOrderByEventOrderAsc(projectId);
        if (events.isEmpty()) {
            throw new IllegalArgumentException("请先执行故事中间资产分析");
        }

        List<OutlineScene> existingScenes = outlineSceneMapper.findByProjectIdOrderBySeqNoAsc(projectId);
        if (existingScenes.isEmpty()) {
            return generateOutline(projectId).stream().map(this::toOutlineResponse).toList();
        }

        List<StoryEvent> pendingEvents = findPendingOutlineEvents(existingScenes, events);
        if (pendingEvents.isEmpty()) {
            progressEventPublisher.jobCompleted(projectId, "outlined", 60, false, "没有发现待生成场景大纲的新事件");
            return existingScenes.stream().map(this::toOutlineResponse).toList();
        }

        List<StoryEntity> entities = storyEntityMapper.findByProjectId(projectId);
        int nextSeqNo = nextOutlineSeqNo(existingScenes);
        int nextSceneNo = nextSceneIdSequence(existingScenes);
        List<OutlineScene> newScenes = generateOutlineByEventBatches(
                projectId,
                entities,
                pendingEvents,
                existingScenes,
                nextSeqNo,
                nextSceneNo,
                true
        );

        outlineSceneMapper.insertBatch(newScenes);
        int reorderedSceneCount = reconcileOutlineAndSceneScriptOrder(projectId);
        projectService.updateStatus(projectId, ProjectStatus.OUTLINED);
        progressEventPublisher.outlineReady(projectId, existingScenes.size() + newScenes.size());
        log.info(
                "增量场景大纲生成完成: projectId={}, newSceneCount={}, reorderedSceneCount={}, elapsedMs={}",
                projectId,
                newScenes.size(),
                reorderedSceneCount,
                System.currentTimeMillis() - startedAt
        );
        return outlineSceneMapper.findByProjectIdOrderBySeqNoAsc(projectId).stream()
                .map(this::toOutlineResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<SceneScriptResponse> listSceneScripts(String projectId) {
        projectService.getProjectEntity(projectId);
        return sceneScriptMapper.findByProjectIdOrderBySeqNoAsc(projectId).stream()
                .map(this::toSceneScriptResponse)
                .toList();
    }

    public SceneScriptResponse getSceneScript(String projectId, String sceneId) {
        SceneScriptResponse response = readOnlyTransactionTemplate.execute(status -> getSceneScriptLocked(projectId, sceneId));
        if (response == null) {
            throw new IllegalStateException("无法读取 Scene: " + sceneId);
        }
        return response;
    }

    public List<SceneScriptResponse> generateMissingSceneScripts(String projectId) {
        return projectOperationLock.execute(projectId, () -> generateMissingSceneScriptsLocked(projectId));
    }

    private SceneScriptResponse getSceneScriptLocked(String projectId, String sceneId) {
        projectService.getProjectEntity(projectId);
        return sceneScriptMapper.findByProjectIdAndSceneId(projectId, sceneId)
                .map(this::toSceneScriptResponse)
                .orElseThrow(() -> new IllegalArgumentException("Scene 尚未生成: " + sceneId));
    }

    private List<SceneScriptResponse> generateMissingSceneScriptsLocked(String projectId) {
        long startedAt = System.currentTimeMillis();
        log.info("开始按顺序生成缺失 Scene 剧本: projectId={}", projectId);
        progressEventPublisher.jobStarted(projectId, "scene_scripts_generation", "scene_generating", 70, "开始按顺序生成 Scene 剧本");
        projectService.getProjectEntity(projectId);
        List<OutlineScene> outlineScenes = outlineSceneMapper.findByProjectIdOrderBySeqNoAsc(projectId);
        if (outlineScenes.isEmpty()) {
            throw new IllegalArgumentException("请先生成场景大纲");
        }

        for (OutlineScene outlineScene : outlineScenes) {
            boolean exists = sceneScriptMapper.findByProjectIdAndSceneId(projectId, outlineScene.getSceneId()).isPresent();
            if (!exists) {
                generateSceneScript(projectId, outlineScene.getSceneId(), false);
            }
        }

        projectService.updateStatus(projectId, ProjectStatus.COMPLETED);
        List<SceneScriptResponse> scripts = listSceneScripts(projectId);
        progressEventPublisher.jobCompleted(projectId, "completed", 100, true, "全部 Scene 剧本已按顺序生成");
        log.info(
                "缺失 Scene 剧本生成完成: projectId={}, scriptCount={}, elapsedMs={}",
                projectId,
                scripts.size(),
                System.currentTimeMillis() - startedAt
        );
        return scripts;
    }

    public SceneScriptResponse regenerateSceneScript(String projectId, String sceneId) {
        return projectOperationLock.execute(projectId, () -> regenerateSceneScriptLocked(projectId, sceneId));
    }

    public SseEmitter streamScenePreview(String projectId, String sceneId) {
        SseEmitter emitter = new SseEmitter(120_000L);
        CompletableFuture.runAsync(() -> {
            try {
                SceneStreamContext context = projectOperationLock.execute(projectId, () -> buildSceneStreamContext(projectId, sceneId));
                if (!sendStreamEvent(emitter, "started", Map.of(
                        "projectId", projectId,
                        "sceneId", sceneId,
                        "message", "开始流式生成 Scene 预览"
                ))) {
                    return;
                }

                aiChatClient.streamText(
                        "你是专业剧本写作助手。请按影视剧本风格输出，不要返回 JSON。",
                        buildScenePreviewPrompt(context.project(), context.outlineScene(), context.entities(), context.events()),
                        chunk -> {
                            if (!sendStreamEvent(emitter, "chunk", Map.of(
                                    "projectId", projectId,
                                    "sceneId", sceneId,
                                    "content", chunk
                            ))) {
                                throw new StreamClientDisconnectedException();
                            }
                        }
                );

                sendStreamEvent(emitter, "done", Map.of(
                        "projectId", projectId,
                        "sceneId", sceneId,
                        "message", "Scene 预览流式生成完成"
                ));
                emitter.complete();
            } catch (StreamClientDisconnectedException ex) {
                completeQuietly(emitter);
            } catch (Exception ex) {
                sendStreamEvent(emitter, "failed", Map.of(
                        "projectId", projectId,
                        "sceneId", sceneId,
                        "message", rootCauseMessage(ex)
                ));
                completeQuietly(emitter);
            }
        });
        return emitter;
    }

    private SceneScriptResponse regenerateSceneScriptLocked(String projectId, String sceneId) {
        projectService.getProjectEntity(projectId);
        sceneScriptMapper.deleteByProjectIdAndSceneId(projectId, sceneId);
        return toSceneScriptResponse(generateSceneScript(projectId, sceneId, true));
    }

    private SceneStreamContext buildSceneStreamContext(String projectId, String sceneId) {
        Project project = projectService.getProjectEntity(projectId);
        OutlineScene outlineScene = outlineSceneMapper.findByProjectIdAndSceneId(projectId, sceneId)
                .orElseGet(() -> {
                    List<OutlineScene> scenes = generateOutline(projectId);
                    return scenes.stream()
                            .filter(scene -> scene.getSceneId().equals(sceneId))
                            .findFirst()
                            .orElseThrow(() -> new IllegalArgumentException("场景不存在: " + sceneId));
                });
        List<StoryEntity> entities = storyEntityMapper.findByProjectId(projectId);
        List<StoryEvent> events = storyEventMapper.findByProjectIdOrderByEventOrderAsc(projectId);
        return new SceneStreamContext(project, outlineScene, entities, events);
    }

    private List<OutlineScene> generateOutline(String projectId) {
        long startedAt = System.currentTimeMillis();
        log.info("开始生成场景大纲: projectId={}", projectId);
        progressEventPublisher.jobStarted(projectId, "outline_generation", "outline_generating", 55, "开始生成场景大纲");
        List<StoryEvent> events = storyEventMapper.findByProjectIdOrderByEventOrderAsc(projectId);
        if (events.isEmpty()) {
            throw new IllegalArgumentException("请先执行故事中间资产分析");
        }

        List<StoryEntity> entities = storyEntityMapper.findByProjectId(projectId);
        List<OutlineScene> scenes = generateOutlineByEventBatches(projectId, entities, events, List.of(), 1, 1, false);

        outlineSceneMapper.insertBatch(scenes);
        projectService.updateStatus(projectId, ProjectStatus.OUTLINED);
        progressEventPublisher.outlineReady(projectId, scenes.size());
        log.info(
                "场景大纲生成完成: projectId={}, sceneCount={}, elapsedMs={}",
                projectId,
                scenes.size(),
                System.currentTimeMillis() - startedAt
        );
        return outlineSceneMapper.findByProjectIdOrderBySeqNoAsc(projectId);
    }

    private SceneScript generateSceneScript(String projectId, String sceneId, boolean regenerating) {
        long startedAt = System.currentTimeMillis();
        log.info("开始生成 Scene: projectId={}, sceneId={}, regenerating={}", projectId, sceneId, regenerating);
        progressEventPublisher.jobStarted(projectId, "scene_generation", "scene_generating", 70, "开始生成 Scene: " + sceneId);
        SceneGenerationContext context = readOnlyTransactionTemplate.execute(status -> buildSceneGenerationContext(projectId, sceneId));
        if (context == null) {
            throw new IllegalStateException("无法读取 Scene 生成上下文: " + sceneId);
        }

        SceneScript sceneScript;
        try {
            sceneScript = parseSceneScript(projectId, context.outlineScene(), aiChatClient.completeJson(
                    "你是专业剧本写作助手，负责根据场景大纲生成 Scene 级动作和对白。只返回合法 JSON。",
                    buildScenePrompt(context.project(), context.outlineScene(), context.entities(), context.events(), regenerating)
            ));
        } catch (Exception ex) {
            log.warn("AI Scene 生成失败，切换规则兜底: projectId={}, sceneId={}, reason={}", projectId, sceneId, rootCauseMessage(ex));
            progressEventPublisher.phaseChanged(projectId, "scene_generating", 72, "AI Scene 生成失败，已切换规则兜底: " + sceneId);
            sceneScript = buildFallbackSceneScript(projectId, context.outlineScene());
        }

        SceneScript generatedSceneScript = sceneScript;
        SceneScript savedSceneScript = writeTransactionTemplate.execute(
                status -> saveGeneratedSceneScript(projectId, sceneId, generatedSceneScript)
        );
        if (savedSceneScript == null) {
            throw new IllegalStateException("无法保存 Scene: " + sceneId);
        }
        progressEventPublisher.sceneDone(projectId, sceneId, sceneScript.getValidationStatus());
        log.info(
                "Scene 生成完成: projectId={}, sceneId={}, validationStatus={}, elapsedMs={}",
                projectId,
                sceneId,
                sceneScript.getValidationStatus(),
                System.currentTimeMillis() - startedAt
        );
        return savedSceneScript;
    }

    private SceneGenerationContext buildSceneGenerationContext(String projectId, String sceneId) {
        OutlineScene outlineScene = outlineSceneMapper.findByProjectIdAndSceneId(projectId, sceneId)
                .orElseGet(() -> {
                    List<OutlineScene> scenes = generateOutline(projectId);
                    return scenes.stream()
                            .filter(scene -> scene.getSceneId().equals(sceneId))
                            .findFirst()
                            .orElseThrow(() -> new IllegalArgumentException("场景不存在: " + sceneId));
                });

        Project project = projectService.getProjectEntity(projectId);
        List<StoryEntity> entities = storyEntityMapper.findByProjectId(projectId);
        List<StoryEvent> events = storyEventMapper.findByProjectIdOrderByEventOrderAsc(projectId);
        log.debug("Scene 生成上下文读取完成: projectId={}, sceneId={}", projectId, sceneId);
        return new SceneGenerationContext(project, outlineScene, entities, events);
    }

    private SceneScript saveGeneratedSceneScript(String projectId, String sceneId, SceneScript sceneScript) {
        sceneScriptMapper.insert(sceneScript);
        projectService.updateStatus(projectId, ProjectStatus.SCENE_GENERATING);
        return sceneScriptMapper.findByProjectIdAndSceneId(projectId, sceneId).orElse(sceneScript);
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

    private List<StoryEvent> findPendingOutlineEvents(List<OutlineScene> existingScenes, List<StoryEvent> events) {
        Set<String> coveredSourceRefs = new LinkedHashSet<>();
        for (OutlineScene scene : existingScenes) {
            coveredSourceRefs.addAll(readStringList(scene.getSourceRefsJson()));
        }
        return events.stream()
                .filter(event -> readStringList(event.getSourceRefsJson()).stream().noneMatch(coveredSourceRefs::contains))
                .toList();
    }

    private List<OutlineScene> generateOutlineByEventBatches(
            String projectId,
            List<StoryEntity> entities,
            List<StoryEvent> events,
            List<OutlineScene> existingScenes,
            int startSeqNo,
            int startSceneNo,
            boolean incremental
    ) {
        List<OutlineScene> scenes = new ArrayList<>();
        List<OutlineScene> contextScenes = new ArrayList<>(existingScenes);
        int nextSeqNo = startSeqNo;
        int nextSceneNo = startSceneNo;
        for (List<StoryEvent> batch : buildEventBatches(events)) {
            List<OutlineScene> batchScenes = generateOutlineBatchWithSplit(
                    projectId,
                    entities,
                    batch,
                    contextScenes,
                    nextSeqNo,
                    nextSceneNo,
                    incremental
            );
            scenes.addAll(batchScenes);
            contextScenes.addAll(batchScenes);
            nextSeqNo += batchScenes.size();
            nextSceneNo += batchScenes.size();
        }
        return scenes;
    }

    private List<OutlineScene> generateOutlineBatchWithSplit(
            String projectId,
            List<StoryEntity> entities,
            List<StoryEvent> batch,
            List<OutlineScene> contextScenes,
            int startSeqNo,
            int startSceneNo,
            boolean incremental
    ) {
        try {
            List<OutlineScene> scenes = generateOutlineBatchByAi(
                    projectId,
                    entities,
                    batch,
                    contextScenes,
                    startSeqNo,
                    startSceneNo,
                    incremental
            );
            if (!scenes.isEmpty()) {
                return scenes;
            }
            throw new IllegalStateException("AI 场景大纲返回空结果");
        } catch (Exception ex) {
            if (batch.size() > 1) {
                int middle = batch.size() / 2;
                List<StoryEvent> firstHalf = batch.subList(0, middle);
                List<StoryEvent> secondHalf = batch.subList(middle, batch.size());
                log.warn(
                        "AI 场景大纲批次失败，自动拆分重试: projectId={}, incremental={}, batchSize={}, reason={}",
                        projectId,
                        incremental,
                        batch.size(),
                        rootCauseMessage(ex)
                );
                progressEventPublisher.phaseChanged(
                        projectId,
                        "outline_generating",
                        56,
                        "AI 场景大纲批次较大，已自动拆分为更小批次重试"
                );

                List<OutlineScene> firstScenes = generateOutlineBatchWithSplit(
                        projectId,
                        entities,
                        firstHalf,
                        contextScenes,
                        startSeqNo,
                        startSceneNo,
                        incremental
                );
                List<OutlineScene> nextContextScenes = new ArrayList<>(contextScenes);
                nextContextScenes.addAll(firstScenes);
                List<OutlineScene> secondScenes = generateOutlineBatchWithSplit(
                        projectId,
                        entities,
                        secondHalf,
                        nextContextScenes,
                        startSeqNo + firstScenes.size(),
                        startSceneNo + firstScenes.size(),
                        incremental
                );

                List<OutlineScene> mergedScenes = new ArrayList<>(firstScenes);
                mergedScenes.addAll(secondScenes);
                return mergedScenes;
            }

            log.warn(
                    "AI 场景大纲单事件生成失败，切换规则兜底: projectId={}, incremental={}, reason={}",
                    projectId,
                    incremental,
                    rootCauseMessage(ex)
            );
            progressEventPublisher.phaseChanged(projectId, "outline_generating", 56, "单个事件 AI 场景大纲生成失败，已切换规则兜底");
            return buildFallbackOutline(projectId, batch, startSeqNo, startSceneNo);
        }
    }

    private List<OutlineScene> generateOutlineBatchByAi(
            String projectId,
            List<StoryEntity> entities,
            List<StoryEvent> batch,
            List<OutlineScene> contextScenes,
            int startSeqNo,
            int startSceneNo,
            boolean incremental
    ) throws JsonProcessingException {
        String systemPrompt = incremental
                ? "你是专业影视编剧，负责把新增故事事件拆成追加 Scene 场景大纲。只返回合法 JSON。"
                : "你是专业影视编剧，负责把小说故事资产拆成 Scene 级场景大纲。只返回合法 JSON。";
        String userPrompt = incremental
                ? buildIncrementalOutlinePrompt(entities, batch, contextScenes)
                : buildOutlinePrompt(entities, batch);
        return parseOutline(
                projectId,
                aiChatClient.completeJson(systemPrompt, userPrompt),
                startSeqNo,
                startSceneNo
        );
    }

    private List<List<StoryEvent>> buildEventBatches(List<StoryEvent> events) {
        List<List<StoryEvent>> batches = new ArrayList<>();
        for (int i = 0; i < events.size(); i += maxOutlineEventsPerBatch) {
            batches.add(events.subList(i, Math.min(i + maxOutlineEventsPerBatch, events.size())));
        }
        return batches;
    }

    private int nextOutlineSeqNo(List<OutlineScene> existingScenes) {
        return existingScenes.stream()
                .map(OutlineScene::getSeqNo)
                .filter(seqNo -> seqNo != null)
                .max(Integer::compareTo)
                .orElse(0) + 1;
    }

    private int nextSceneIdSequence(List<OutlineScene> existingScenes) {
        return existingScenes.stream()
                .map(OutlineScene::getSceneId)
                .map(sceneId -> parseSequence(sceneId, "S"))
                .max(Integer::compareTo)
                .orElse(0) + 1;
    }

    private int reconcileOutlineAndSceneScriptOrder(String projectId) {
        List<OutlineScene> scenes = new ArrayList<>(outlineSceneMapper.findByProjectIdOrderBySeqNoAsc(projectId));
        scenes.sort((first, second) -> {
            int firstChapterNo = minChapterNoFromSourceRefs(readStringList(first.getSourceRefsJson()));
            int secondChapterNo = minChapterNoFromSourceRefs(readStringList(second.getSourceRefsJson()));
            int chapterCompare = Integer.compare(firstChapterNo, secondChapterNo);
            if (chapterCompare != 0) {
                return chapterCompare;
            }
            int seqCompare = Integer.compare(
                    first.getSeqNo() == null ? Integer.MAX_VALUE : first.getSeqNo(),
                    second.getSeqNo() == null ? Integer.MAX_VALUE : second.getSeqNo()
            );
            if (seqCompare != 0) {
                return seqCompare;
            }
            return Long.compare(
                    first.getId() == null ? Long.MAX_VALUE : first.getId(),
                    second.getId() == null ? Long.MAX_VALUE : second.getId()
            );
        });

        int updatedCount = 0;
        for (int i = 0; i < scenes.size(); i++) {
            int nextSeqNo = i + 1;
            OutlineScene scene = scenes.get(i);
            if (!Integer.valueOf(nextSeqNo).equals(scene.getSeqNo())) {
                outlineSceneMapper.updateSeqNo(scene.getId(), nextSeqNo);
                sceneScriptMapper.updateSeqNoByProjectIdAndSceneId(projectId, scene.getSceneId(), nextSeqNo);
                updatedCount++;
            }
        }
        if (updatedCount > 0) {
            log.info("场景顺序已按章节重排: projectId={}, updatedCount={}", projectId, updatedCount);
        }
        return updatedCount;
    }

    private int minChapterNoFromSourceRefs(List<String> sourceRefs) {
        return sourceRefs.stream()
                .map(this::chapterNoFromSourceRef)
                .min(Integer::compareTo)
                .orElse(Integer.MAX_VALUE);
    }

    private Integer chapterNoFromSourceRef(String sourceRef) {
        if (sourceRef == null) {
            return Integer.MAX_VALUE;
        }
        String normalized = sourceRef.trim().toLowerCase(Locale.ROOT);
        if (!normalized.startsWith("ch")) {
            return Integer.MAX_VALUE;
        }
        StringBuilder digits = new StringBuilder();
        for (int i = 2; i < normalized.length(); i++) {
            char value = normalized.charAt(i);
            if (Character.isDigit(value)) {
                digits.append(value);
            } else {
                break;
            }
        }
        if (digits.isEmpty()) {
            return Integer.MAX_VALUE;
        }
        try {
            return Integer.parseInt(digits.toString());
        } catch (NumberFormatException ex) {
            return Integer.MAX_VALUE;
        }
    }

    private int parseSequence(String value, String prefix) {
        if (value == null || !value.startsWith(prefix)) {
            return 0;
        }
        try {
            return Integer.parseInt(value.substring(prefix.length()));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    private List<OutlineScene> parseOutline(String projectId, String json) throws JsonProcessingException {
        return parseOutline(projectId, json, 1, 1);
    }

    private List<OutlineScene> parseOutline(String projectId, String json, int startSeqNo, int startSceneNo) throws JsonProcessingException {
        JsonNode scenesNode = objectMapper.readTree(json).path("scenes");
        List<OutlineScene> scenes = new ArrayList<>();
        if (!scenesNode.isArray()) {
            return scenes;
        }
        int index = 0;
        for (JsonNode node : scenesNode) {
            int seqNo = startSeqNo + index;
            String sceneId = "S" + String.format(Locale.ROOT, "%03d", startSceneNo + index);
            JsonNode slugline = node.path("slugline");
            JsonNode purpose = node.path("purpose");
            scenes.add(new OutlineScene(
                    projectId,
                    sceneId,
                    seqNo,
                    text(node.path("title").asText(), "场景 " + seqNo),
                    text(slugline.path("intExt").asText(), "INT"),
                    text(slugline.path("locationId").asText(), "L001"),
                    text(slugline.path("timeOfDay").asText(), "DAY"),
                    text(purpose.path("plot").asText(), "推进主要情节"),
                    text(purpose.path("character").asText(), "展示角色选择"),
                    toJson(readStringArray(node.path("characters"), List.of())),
                    toJson(readStringArray(node.path("sourceRefs"), List.of("ch" + seqNo))),
                    text(node.path("status").asText(), "READY")
            ));
            index++;
        }
        return scenes;
    }

    private SceneScript parseSceneScript(String projectId, OutlineScene outlineScene, String json) throws JsonProcessingException {
        JsonNode root = objectMapper.readTree(json);
        return new SceneScript(
                projectId,
                outlineScene.getSceneId(),
                outlineScene.getSeqNo(),
                text(root.path("title").asText(), outlineScene.getTitle()),
                toJson(readStringArray(root.path("action"), List.of(outlineScene.getPurposePlot()))),
                toJson(readDialogueArray(root.path("dialogue"))),
                toJson(readStringArray(root.path("sourceRefs"), readStringList(outlineScene.getSourceRefsJson()))),
                text(root.path("validationStatus").asText(), "PASSED"),
                toJson(readStringArray(root.path("warnings"), List.of()))
        );
    }

    private List<OutlineScene> buildFallbackOutline(String projectId, List<StoryEvent> events) {
        return buildFallbackOutline(projectId, events, 1, 1);
    }

    private List<OutlineScene> buildFallbackOutline(String projectId, List<StoryEvent> events, int startSeqNo, int startSceneNo) {
        List<OutlineScene> scenes = new ArrayList<>();
        for (int i = 0; i < events.size(); i++) {
            StoryEvent event = events.get(i);
            int seqNo = startSeqNo + i;
            String sceneId = "S" + String.format(Locale.ROOT, "%03d", startSceneNo + i);
            scenes.add(new OutlineScene(
                    projectId,
                    sceneId,
                    seqNo,
                    event.getTitle(),
                    "INT",
                    "L001",
                    "DAY",
                    event.getSummary(),
                    "展示角色在事件中的目标和选择",
                    toJson(List.of("C001")),
                    event.getSourceRefsJson(),
                    "READY"
            ));
        }
        return scenes;
    }

    private SceneScript buildFallbackSceneScript(String projectId, OutlineScene outlineScene) {
        return new SceneScript(
                projectId,
                outlineScene.getSceneId(),
                outlineScene.getSeqNo(),
                outlineScene.getTitle(),
                toJson(List.of(outlineScene.getPurposePlot(), "人物在场景中完成关键行动，推动故事进入下一阶段。")),
                toJson(List.of(new SceneScriptResponse.DialogueResponse("C001", "这件事必须继续查下去。"))),
                outlineScene.getSourceRefsJson(),
                "WARNING",
                toJson(List.of("当前 Scene 由规则兜底生成，建议接入 AI 后重新生成。"))
        );
    }

    private String buildOutlinePrompt(List<StoryEntity> entities, List<StoryEvent> events) {
        return """
                请根据故事实体和事件生成 Scene 级场景大纲。
                只返回 JSON，不要使用 Markdown 代码块，不要输出解释：
                {
                  "scenes": [
                    {
                      "title": "场景标题",
                      "slugline": {"intExt":"INT 或 EXT","locationId":"L001","timeOfDay":"DAY/NIGHT/LATE_NIGHT"},
                      "purpose": {"plot":"剧情目的","character":"角色目的"},
                      "characters": ["C001"],
                      "sourceRefs": ["ch1"],
                      "status": "READY"
                    }
                  ]
                }
                要求：
                1. 场景必须严格按故事事件输入顺序排列，不要倒叙重排。
                2. 每个关键事件至少生成 1 个场景；同一事件只有在发生明显地点、时间或人物目标变化时才拆成多个场景。
                3. characters 只能使用实体中的角色 ID，不要使用角色姓名或新增 ID。
                4. locationId 必须优先使用实体中的地点 ID；无法判断时使用最接近事件发生地的地点 ID。
                5. sourceRefs 必须沿用对应事件中的 sourceRefs，不要编造不存在的章节引用。
                6. slugline.intExt 只能是 INT 或 EXT，timeOfDay 只能是 DAY、NIGHT、LATE_NIGHT。
                7. purpose.plot 写清本场推动的剧情结果，purpose.character 写清角色选择或关系变化。

                故事实体：
                %s

                故事事件：
                %s
                """.formatted(toJson(entities), toJson(events));
    }

    private String buildIncrementalOutlinePrompt(List<StoryEntity> entities, List<StoryEvent> pendingEvents, List<OutlineScene> existingScenes) {
        return """
                请只根据新增故事事件生成追加 Scene 场景大纲。
                不要重写已有场景，不要引用旧事件生成重复场景。
                只返回 JSON，不要使用 Markdown 代码块，不要输出解释：
                {
                  "scenes": [
                    {
                      "title": "场景标题",
                      "slugline": {"intExt":"INT 或 EXT","locationId":"L001","timeOfDay":"DAY/NIGHT/LATE_NIGHT"},
                      "purpose": {"plot":"剧情目的","character":"角色目的"},
                      "characters": ["C001"],
                      "sourceRefs": ["ch4"],
                      "status": "READY"
                    }
                  ]
                }
                要求：
                1. 新增场景必须严格按新增故事事件输入顺序排列，并接在已有场景之后。
                2. 每个新增关键事件至少生成 1 个场景；同一事件只有在发生明显地点、时间或人物目标变化时才拆成多个场景。
                3. characters 只能使用实体中的角色 ID，不要使用角色姓名或新增 ID。
                4. locationId 必须优先使用实体中的地点 ID；无法判断时使用最接近事件发生地的地点 ID。
                5. sourceRefs 必须沿用新增事件中的 sourceRefs，不要编造不存在的章节引用。
                6. slugline.intExt 只能是 INT 或 EXT，timeOfDay 只能是 DAY、NIGHT、LATE_NIGHT。
                7. 输出只包含新增场景，不包含已有场景。

                已有场景：
                %s

                故事实体：
                %s

                新增故事事件：
                %s
                """.formatted(toJson(existingScenes), toJson(entities), toJson(pendingEvents));
    }

    private String buildScenePrompt(Project project, OutlineScene outlineScene, List<StoryEntity> entities, List<StoryEvent> events, boolean regenerating) {
        return """
                请生成单个 Scene 级剧本片段。
                项目标题：%s
                是否重新生成：%s

                只返回 JSON，不要使用 Markdown 代码块，不要输出解释：
                {
                  "title": "场景标题",
                  "action": ["动作描写"],
                  "dialogue": [{"characterId":"C001","line":"对白"}],
                  "sourceRefs": ["ch1"],
                  "validationStatus": "PASSED",
                  "warnings": []
                }
                要求：
                1. 严格围绕场景大纲和 sourceRefs 对应事件生成，不要续写其他章节内容。
                2. action 为 2 到 5 条，使用可拍摄的影视剧本动作描述，避免心理分析和旁白解释。
                3. dialogue 为 1 到 6 条，characterId 只能使用场景大纲 characters 中出现的角色 ID。
                4. sourceRefs 必须沿用场景大纲中的 sourceRefs。
                5. 不新增契约外字段。
                6. 如果信息不足，warnings 写明原因，validationStatus 使用 WARNING；否则使用 PASSED。

                场景大纲：
                %s

                故事实体：
                %s

                故事事件：
                %s
                """.formatted(project.getTitle(), regenerating, toJson(outlineScene), toJson(entities), toJson(events));
    }

    private String buildScenePreviewPrompt(Project project, OutlineScene outlineScene, List<StoryEntity> entities, List<StoryEvent> events) {
        return """
                请流式生成单个 Scene 级剧本预览。
                项目标题：%s

                输出格式：
                场景标题：...
                动作：
                - ...
                对白：
                C001：...
                C002：...

                要求：
                1. 使用中文影视剧本表达，实时输出时保持自然段完整。
                2. 动作描写 2 到 5 条，对白 1 到 6 条。
                3. 对白角色只能使用场景大纲 characters 中出现的角色 ID，不要新增未给出的角色 ID。
                4. 严格围绕场景大纲和 sourceRefs 对应事件生成，不要续写其他章节内容。
                5. 这是流式预览，不需要输出 JSON，不需要 Markdown 代码块。

                场景大纲：
                %s

                故事实体：
                %s

                故事事件：
                %s
                """.formatted(project.getTitle(), toJson(outlineScene), toJson(entities), toJson(events));
    }

    private boolean sendStreamEvent(SseEmitter emitter, String eventName, Map<String, Object> payload) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(payload));
            return true;
        } catch (IOException | IllegalStateException ex) {
            completeQuietly(emitter);
            return false;
        }
    }

    private void completeQuietly(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (IllegalStateException ignored) {
            // 客户端已经断开时无需继续处理。
        }
    }

    public OutlineSceneResponse toOutlineResponse(OutlineScene scene) {
        return OutlineSceneResponse.from(
                scene,
                readStringList(scene.getCharactersJson()),
                readStringList(scene.getSourceRefsJson())
        );
    }

    public SceneScriptResponse toSceneScriptResponse(SceneScript sceneScript) {
        return SceneScriptResponse.from(
                sceneScript,
                readStringList(sceneScript.getActionJson()),
                readDialogueList(sceneScript.getDialogueJson()),
                readStringList(sceneScript.getSourceRefsJson()),
                readStringList(sceneScript.getWarningsJson())
        );
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

    private List<SceneScriptResponse.DialogueResponse> readDialogueArray(JsonNode node) {
        if (!node.isArray()) {
            return List.of();
        }
        List<SceneScriptResponse.DialogueResponse> dialogue = new ArrayList<>();
        for (JsonNode item : node) {
            String characterId = item.path("characterId").asText();
            String line = item.path("line").asText();
            if (!characterId.isBlank() && !line.isBlank()) {
                dialogue.add(new SceneScriptResponse.DialogueResponse(characterId.trim(), line.trim()));
            }
        }
        return dialogue;
    }

    private List<String> readStringList(String json) {
        try {
            return json == null || json.isBlank() ? List.of() : objectMapper.readValue(json, STRING_LIST_TYPE);
        } catch (Exception ex) {
            return List.of();
        }
    }

    private List<SceneScriptResponse.DialogueResponse> readDialogueList(String json) {
        try {
            return json == null || json.isBlank() ? List.of() : objectMapper.readValue(json, DIALOGUE_LIST_TYPE);
        } catch (Exception ex) {
            return List.of();
        }
    }

    private String text(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("序列化场景数据失败", ex);
        }
    }

    private record SceneStreamContext(
            Project project,
            OutlineScene outlineScene,
            List<StoryEntity> entities,
            List<StoryEvent> events
    ) {
    }

    private record SceneGenerationContext(
            Project project,
            OutlineScene outlineScene,
            List<StoryEntity> entities,
            List<StoryEvent> events
    ) {
    }

    private static final class StreamClientDisconnectedException extends RuntimeException {
    }
}
