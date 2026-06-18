package com.novel2script.backend.scene;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novel2script.backend.ai.AiChatClient;
import com.novel2script.backend.ai.AiProperties;
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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

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
    private final ThreadPoolTaskExecutor sceneStreamExecutor;
    private final ThreadPoolTaskExecutor sceneScriptExecutor;
    private final long sceneStreamTimeoutMillis;

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
            AiProperties aiProperties,
            @Qualifier("sceneStreamExecutor") ThreadPoolTaskExecutor sceneStreamExecutor,
            @Qualifier("sceneScriptExecutor") ThreadPoolTaskExecutor sceneScriptExecutor,
            @Value("${OUTLINE_EVENTS_PER_BATCH:6}") int maxOutlineEventsPerBatch,
            @Value("${SCENE_STREAM_TIMEOUT_MILLIS:210000}") long configuredSceneStreamTimeoutMillis
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
        this.sceneStreamExecutor = sceneStreamExecutor;
        this.sceneScriptExecutor = sceneScriptExecutor;
        this.sceneStreamTimeoutMillis = Math.max(
                configuredSceneStreamTimeoutMillis,
                (aiProperties.getTimeoutSeconds() + 15L) * 1000L
        );
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

    public List<SceneScriptResponse> generateProductionPipeline(String projectId, boolean incremental) {
        return projectOperationLock.execute(
                projectId,
                () -> generateProductionPipelineLocked(projectId, incremental)
        );
    }

    /**
     * 在上层持有项目操作锁时，为当前已落库但尚未生成大纲的事件启动一批生产任务。
     * 方法只等待大纲 AI，Scene 内容任务提交到有界线程池后立即返回，使上层可以继续分析下一章。
     */
    public ProductionBatchHandle generatePendingProductionBatch(String projectId) {
        projectService.getProjectEntity(projectId);
        List<OutlineScene> existingScenes = new ArrayList<>(
                outlineSceneMapper.findByProjectIdOrderBySeqNoAsc(projectId)
        );
        List<StoryEvent> pendingEvents = findPendingOutlineEvents(
                existingScenes,
                storyEventMapper.findByProjectIdOrderByEventOrderAsc(projectId)
        );
        if (pendingEvents.isEmpty()) {
            return new ProductionBatchHandle(List.of(), List.of());
        }

        List<StoryEntity> entities = storyEntityMapper.findByProjectId(projectId);
        Set<String> generatedSceneIds = generatedUsableSceneIds(projectId);
        List<Future<SceneScript>> futures = new ArrayList<>();
        List<String> submittedSceneIds = new ArrayList<>();
        List<OutlineScene> contextScenes = new ArrayList<>(existingScenes);
        int nextSeqNo = nextOutlineSeqNo(existingScenes);
        int nextSceneNo = nextSceneIdSequence(existingScenes);

        for (List<StoryEvent> eventBatch : buildProductionEventBatches(pendingEvents)) {
            List<OutlineScene> batchScenes = generateOutlineBatchWithSplit(
                    projectId,
                    entities,
                    eventBatch,
                    contextScenes,
                    nextSeqNo,
                    nextSceneNo,
                    !existingScenes.isEmpty()
            );
            persistOutlineBatch(projectId, batchScenes);
            contextScenes.addAll(batchScenes);
            nextSeqNo += batchScenes.size();
            nextSceneNo += batchScenes.size();

            List<String> batchSceneIds = batchScenes.stream().map(OutlineScene::getSceneId).toList();
            progressEventPublisher.outlineBatchReady(projectId, contextScenes.size(), batchSceneIds);
            submitSceneGenerationTasks(
                    projectId,
                    batchSceneIds,
                    generatedSceneIds,
                    futures,
                    submittedSceneIds
            );
        }
        return new ProductionBatchHandle(submittedSceneIds, futures);
    }

    public List<SceneScriptResponse> completeProductionBatches(
            String projectId,
            List<ProductionBatchHandle> batchHandles
    ) {
        List<String> sceneIds = new ArrayList<>();
        List<Future<SceneScript>> futures = new ArrayList<>();
        for (ProductionBatchHandle handle : batchHandles) {
            sceneIds.addAll(handle.sceneIds);
            futures.addAll(handle.futures);
        }
        SceneTaskReport taskReport = waitForSceneGenerationTasks(projectId, sceneIds, futures);
        reconcileOutlineAndSceneScriptOrder(projectId);
        projectService.updateStatus(projectId, ProjectStatus.COMPLETED);
        List<SceneScriptResponse> scripts = listSceneScripts(projectId);
        progressEventPublisher.jobCompleted(
                projectId,
                "completed",
                100,
                true,
                completionMessage("故事资产、场景大纲与 Scene 内容已逐章生成完成", taskReport)
        );
        return scripts;
    }

    public static final class ProductionBatchHandle {

        private final List<String> sceneIds;
        private final List<Future<SceneScript>> futures;

        private ProductionBatchHandle(List<String> sceneIds, List<Future<SceneScript>> futures) {
            this.sceneIds = List.copyOf(sceneIds);
            this.futures = List.copyOf(futures);
        }
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

    public boolean hasMissingSceneScripts(String projectId) {
        Boolean hasMissing = readOnlyTransactionTemplate.execute(status -> {
            projectService.getProjectEntity(projectId);
            List<OutlineScene> outlineScenes = outlineSceneMapper.findByProjectIdOrderBySeqNoAsc(projectId);
            if (outlineScenes.isEmpty()) {
                return true;
            }
            Set<String> generatedSceneIds = generatedUsableSceneIds(projectId);
            return outlineScenes.stream()
                    .map(OutlineScene::getSceneId)
                    .anyMatch(sceneId -> !generatedSceneIds.contains(sceneId));
        });
        return Boolean.TRUE.equals(hasMissing);
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

        Set<String> existingSceneIds = generatedUsableSceneIds(projectId);
        List<String> missingSceneIds = outlineScenes.stream()
                .map(OutlineScene::getSceneId)
                .filter(sceneId -> !existingSceneIds.contains(sceneId))
                .toList();

        // 各场景生成相互独立，用有界线程池并行补齐，把"场景数×单场景耗时"压到接近"单场景耗时×批次"。
        // 仍按大纲顺序收集结果，任何一个场景失败都让整体失败（与原串行语义一致），不静默吞掉。
        List<Future<SceneScript>> futures = new ArrayList<>(missingSceneIds.size());
        for (String sceneId : missingSceneIds) {
            futures.add(sceneScriptExecutor.submit(() -> generateSceneScript(projectId, sceneId, false)));
        }
        SceneTaskReport taskReport = waitForSceneGenerationTasks(projectId, missingSceneIds, futures);

        projectService.updateStatus(projectId, ProjectStatus.COMPLETED);
        List<SceneScriptResponse> scripts = listSceneScripts(projectId);
        progressEventPublisher.jobCompleted(
                projectId,
                "completed",
                100,
                true,
                completionMessage("全部 Scene 剧本已按顺序生成", taskReport)
        );
        log.info(
                "缺失 Scene 剧本生成完成: projectId={}, scriptCount={}, failedCount={}, elapsedMs={}",
                projectId,
                scripts.size(),
                taskReport.failedCount(),
                System.currentTimeMillis() - startedAt
        );
        return scripts;
    }

    private List<SceneScriptResponse> generateProductionPipelineLocked(String projectId, boolean incremental) {
        long startedAt = System.currentTimeMillis();
        String jobType = incremental ? "production_pipeline_incremental" : "production_pipeline";
        progressEventPublisher.jobStarted(
                projectId,
                jobType,
                "outline_generating",
                55,
                "开始逐批分析场景，并同步生成 Scene 内容"
        );
        projectService.getProjectEntity(projectId);

        List<StoryEvent> allEvents = storyEventMapper.findByProjectIdOrderByEventOrderAsc(projectId);
        if (allEvents.isEmpty()) {
            throw new IllegalArgumentException("请先执行故事中间资产分析");
        }

        List<StoryEntity> entities = storyEntityMapper.findByProjectId(projectId);
        List<OutlineScene> existingScenes = new ArrayList<>(
                outlineSceneMapper.findByProjectIdOrderBySeqNoAsc(projectId)
        );
        List<StoryEvent> eventsToOutline = incremental || !existingScenes.isEmpty()
                ? findPendingOutlineEvents(existingScenes, allEvents)
                : allEvents;
        Set<String> generatedSceneIds = generatedUsableSceneIds(projectId);
        List<Future<SceneScript>> sceneFutures = new ArrayList<>();
        List<String> submittedSceneIds = new ArrayList<>();
        List<OutlineScene> contextScenes = new ArrayList<>(existingScenes);
        int nextSeqNo = nextOutlineSeqNo(existingScenes);
        int nextSceneNo = nextSceneIdSequence(existingScenes);

        for (List<StoryEvent> eventBatch : buildProductionEventBatches(eventsToOutline)) {
            List<OutlineScene> batchScenes = generateOutlineBatchWithSplit(
                    projectId,
                    entities,
                    eventBatch,
                    contextScenes,
                    nextSeqNo,
                    nextSceneNo,
                    incremental || !existingScenes.isEmpty()
            );
            persistOutlineBatch(projectId, batchScenes);
            contextScenes.addAll(batchScenes);
            nextSeqNo += batchScenes.size();
            nextSceneNo += batchScenes.size();

            List<String> batchSceneIds = batchScenes.stream().map(OutlineScene::getSceneId).toList();
            progressEventPublisher.outlineBatchReady(projectId, contextScenes.size(), batchSceneIds);
            submitSceneGenerationTasks(
                    projectId,
                    batchSceneIds,
                    generatedSceneIds,
                    sceneFutures,
                    submittedSceneIds
            );
        }

        // 支持任务恢复：大纲可能已全部落库，但上次执行在 Scene 内容生成阶段中断。
        submitSceneGenerationTasks(
                projectId,
                contextScenes.stream().map(OutlineScene::getSceneId).toList(),
                generatedSceneIds,
                sceneFutures,
                submittedSceneIds
        );
        SceneTaskReport taskReport = waitForSceneGenerationTasks(projectId, submittedSceneIds, sceneFutures);

        if (incremental) {
            reconcileOutlineAndSceneScriptOrder(projectId);
        }
        projectService.updateStatus(projectId, ProjectStatus.COMPLETED);
        List<SceneScriptResponse> scripts = listSceneScripts(projectId);
        progressEventPublisher.jobCompleted(
                projectId,
                "completed",
                100,
                true,
                completionMessage("场景分析与 Scene 内容已逐批生成完成", taskReport)
        );
        log.info(
                "场景生产流水线完成: projectId={}, incremental={}, outlineCount={}, scriptCount={}, failedCount={}, elapsedMs={}",
                projectId,
                incremental,
                contextScenes.size(),
                scripts.size(),
                taskReport.failedCount(),
                System.currentTimeMillis() - startedAt
        );
        return scripts;
    }

    private void persistOutlineBatch(String projectId, List<OutlineScene> batchScenes) {
        if (batchScenes.isEmpty()) {
            return;
        }
        writeTransactionTemplate.executeWithoutResult(status -> outlineSceneMapper.insertBatch(batchScenes));
        projectService.updateStatus(projectId, ProjectStatus.SCENE_GENERATING);
    }

    private void submitSceneGenerationTasks(
            String projectId,
            List<String> sceneIds,
            Set<String> generatedSceneIds,
            List<Future<SceneScript>> futures,
            List<String> submittedSceneIds
    ) {
        for (String sceneId : sceneIds) {
            if (!generatedSceneIds.add(sceneId)) {
                continue;
            }
            futures.add(sceneScriptExecutor.submit(() -> generateSceneScript(projectId, sceneId, false)));
            submittedSceneIds.add(sceneId);
        }
    }

    private SceneTaskReport waitForSceneGenerationTasks(
            String projectId,
            List<String> sceneIds,
            List<Future<SceneScript>> futures
    ) {
        List<String> failedSceneIds = new ArrayList<>();
        for (int i = 0; i < futures.size(); i++) {
            String sceneId = i < sceneIds.size() ? sceneIds.get(i) : "UNKNOWN";
            try {
                futures.get(i).get();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                futures.forEach(future -> future.cancel(true));
                throw new IllegalStateException(
                        "Scene 生产流水线被中断: projectId=" + projectId + ", sceneId=" + sceneId,
                        ex
                );
            } catch (ExecutionException ex) {
                Throwable cause = ex.getCause();
                Throwable failure = cause != null ? cause : ex;
                failedSceneIds.add(sceneId);
                log.warn(
                        "Scene 生产任务失败，写入失败占位并继续等待其他 Scene: projectId={}, sceneId={}, reason={}",
                        projectId,
                        sceneId,
                        rootCauseMessage(failure)
                );
                SceneScript failedScene = persistFailedSceneScript(projectId, sceneId, failure);
                progressEventPublisher.sceneDone(projectId, sceneId, failedScene.getValidationStatus());
            }
        }
        return new SceneTaskReport(futures.size(), failedSceneIds);
    }

    private Set<String> generatedUsableSceneIds(String projectId) {
        return sceneScriptMapper.findByProjectIdOrderBySeqNoAsc(projectId).stream()
                .filter(scene -> !"FAILED".equalsIgnoreCase(scene.getValidationStatus()))
                .map(SceneScript::getSceneId)
                .collect(java.util.stream.Collectors.toSet());
    }

    private SceneScript persistFailedSceneScript(String projectId, String sceneId, Throwable failure) {
        SceneScript existingScene = sceneScriptMapper.findByProjectIdAndSceneId(projectId, sceneId).orElse(null);
        if (existingScene != null && !"FAILED".equalsIgnoreCase(existingScene.getValidationStatus())) {
            return existingScene;
        }

        OutlineScene outlineScene = outlineSceneMapper.findByProjectIdAndSceneId(projectId, sceneId)
                .orElseThrow(() -> new IllegalStateException("无法写入失败占位，场景大纲不存在: " + sceneId, failure));
        SceneScript failedScene = buildFailedSceneScript(projectId, outlineScene, rootCauseMessage(failure));
        SceneScript savedScene = writeTransactionTemplate.execute(
                status -> saveGeneratedSceneScript(projectId, sceneId, failedScene)
        );
        if (savedScene == null) {
            throw new IllegalStateException("无法保存失败 Scene 占位: " + sceneId, failure);
        }
        return savedScene;
    }

    private SceneScript buildFailedSceneScript(String projectId, OutlineScene outlineScene, String reason) {
        return new SceneScript(
                projectId,
                outlineScene.getSceneId(),
                outlineScene.getSeqNo(),
                outlineScene.getTitle(),
                toJson(List.of(
                        outlineScene.getPurposePlot(),
                        "当前 Scene 生成失败，系统已保留失败占位，后续可单独重新生成。"
                )),
                toJson(List.of()),
                outlineScene.getSourceRefsJson(),
                "FAILED",
                toJson(List.of("Scene 生成失败：" + reason))
        );
    }

    private String completionMessage(String successMessage, SceneTaskReport taskReport) {
        if (taskReport.failedCount() == 0) {
            return successMessage;
        }
        return successMessage + "，其中 " + taskReport.failedCount()
                + " 个 Scene 生成失败，已写入失败占位，可单独重试："
                + String.join("、", taskReport.failedSceneIds());
    }

    private record SceneTaskReport(int totalCount, List<String> failedSceneIds) {

        private int failedCount() {
            return failedSceneIds.size();
        }
    }

    public SceneScriptResponse regenerateSceneScript(String projectId, String sceneId) {
        return projectOperationLock.execute(projectId, () -> regenerateSceneScriptLocked(projectId, sceneId));
    }

    public SseEmitter streamSceneScript(String projectId, String sceneId) {
        SseEmitter emitter = new SseEmitter(sceneStreamTimeoutMillis);
        AtomicBoolean clientConnected = new AtomicBoolean(true);
        Runnable markDisconnected = () -> {
            clientConnected.set(false);
            completeQuietly(emitter);
        };
        emitter.onTimeout(markDisconnected);
        emitter.onError(ignored -> markDisconnected.run());
        emitter.onCompletion(() -> clientConnected.set(false));

        try {
            sceneStreamExecutor.submit(() -> {
                try {
                    SceneScriptResponse scene = projectOperationLock.execute(
                            projectId,
                            () -> streamAndSaveSceneScript(projectId, sceneId, emitter, clientConnected)
                    );
                    sendStreamEventIfConnected(clientConnected, emitter, "done", Map.of(
                            "projectId", projectId,
                            "sceneId", sceneId,
                            "message", "正式 Scene 已流式生成并落库",
                            "scene", scene
                    ));
                    completeQuietly(emitter);
                } catch (Exception ex) {
                    sendStreamEventIfConnected(clientConnected, emitter, "failed", Map.of(
                            "projectId", projectId,
                            "sceneId", sceneId,
                            "message", rootCauseMessage(ex)
                    ));
                    completeQuietly(emitter);
                }
            });
        } catch (RejectedExecutionException ex) {
            clientConnected.set(false);
            sendStreamEvent(emitter, "failed", Map.of(
                    "projectId", projectId,
                    "sceneId", sceneId,
                    "message", "当前流式生成任务较多，请稍后重试"
            ));
            completeQuietly(emitter);
        }
        return emitter;
    }

    private SceneScriptResponse streamAndSaveSceneScript(
            String projectId,
            String sceneId,
            SseEmitter emitter,
            AtomicBoolean clientConnected
    ) {
        long startedAt = System.currentTimeMillis();
        log.info("开始正式流式生成 Scene: projectId={}, sceneId={}", projectId, sceneId);
        progressEventPublisher.jobStarted(projectId, "scene_generation_stream", "scene_generating", 70, "开始正式流式生成 Scene: " + sceneId);
        SceneGenerationContext context = readOnlyTransactionTemplate.execute(status -> buildSceneGenerationContext(projectId, sceneId));
        if (context == null) {
            throw new IllegalStateException("无法读取 Scene 生成上下文: " + sceneId);
        }

        sendStreamEventIfConnected(clientConnected, emitter, "started", Map.of(
                "projectId", projectId,
                "sceneId", sceneId,
                "message", "开始流式生成正式 Scene"
        ));

        SceneScript sceneScript;
        try {
            String json = aiChatClient.streamJson(
                    "你是专业剧本写作助手，负责根据场景大纲生成 Scene 级动作和对白。只返回合法 JSON。",
                    buildScenePrompt(context.project(), context.outlineScene(), context.entities(), context.events(), true),
                    chunk -> sendStreamEventIfConnected(clientConnected, emitter, "chunk", Map.of(
                            "projectId", projectId,
                            "sceneId", sceneId,
                            "content", chunk
                    ))
            );
            sceneScript = parseSceneScript(projectId, context.outlineScene(), json);
        } catch (Exception ex) {
            log.warn("正式流式 Scene 生成失败，切换规则兜底: projectId={}, sceneId={}, reason={}", projectId, sceneId, rootCauseMessage(ex));
            progressEventPublisher.phaseChanged(projectId, "scene_generating", 72, "正式流式 Scene 失败，已切换规则兜底: " + sceneId);
            sendStreamEventIfConnected(clientConnected, emitter, "chunk", Map.of(
                    "projectId", projectId,
                    "sceneId", sceneId,
                    "content", "\n\nAI 正式流式生成失败，已保存规则兜底 Scene。"
            ));
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
                "正式流式 Scene 生成并落库完成: projectId={}, sceneId={}, validationStatus={}, elapsedMs={}",
                projectId,
                sceneId,
                sceneScript.getValidationStatus(),
                System.currentTimeMillis() - startedAt
        );
        return toSceneScriptResponse(savedSceneScript);
    }

    private SceneScriptResponse regenerateSceneScriptLocked(String projectId, String sceneId) {
        projectService.getProjectEntity(projectId);
        sceneScriptMapper.deleteByProjectIdAndSceneId(projectId, sceneId);
        return toSceneScriptResponse(generateSceneScript(projectId, sceneId, true));
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
        List<StoryEntity> entities = filterSceneEntities(outlineScene, storyEntityMapper.findByProjectId(projectId));
        List<StoryEvent> events = filterSceneEvents(outlineScene, storyEventMapper.findByProjectIdOrderByEventOrderAsc(projectId));
        log.debug("Scene 生成上下文读取完成: projectId={}, sceneId={}, entityCount={}, eventCount={}", projectId, sceneId, entities.size(), events.size());
        return new SceneGenerationContext(project, outlineScene, entities, events);
    }

    private SceneScript saveGeneratedSceneScript(String projectId, String sceneId, SceneScript sceneScript) {
        sceneScriptMapper.insert(sceneScript);
        projectService.updateStatus(projectId, ProjectStatus.SCENE_GENERATING);
        return sceneScriptMapper.findByProjectIdAndSceneId(projectId, sceneId).orElse(sceneScript);
    }

    private String rootCauseMessage(Throwable ex) {
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

    // 单个 Scene 只需要它自己引用的角色/地点和来源章节事件。把全项目上下文裁剪到这个范围，
    // 可以显著降低输入 token、首 token 等待和费用，同时更贴合"严格围绕场景大纲生成"的约束。
    // 任何一步裁剪为空时回退到全量，避免上下文缺失影响生成质量。
    private List<StoryEntity> filterSceneEntities(OutlineScene outlineScene, List<StoryEntity> allEntities) {
        Set<String> wantedIds = new LinkedHashSet<>(readStringList(outlineScene.getCharactersJson()));
        String locationId = outlineScene.getLocationId();
        if (locationId != null && !locationId.isBlank()) {
            wantedIds.add(locationId.trim());
        }
        if (wantedIds.isEmpty()) {
            return allEntities;
        }
        List<StoryEntity> relevant = allEntities.stream()
                .filter(entity -> wantedIds.contains(entity.getEntityId()))
                .toList();
        return relevant.isEmpty() ? allEntities : relevant;
    }

    private List<StoryEvent> filterSceneEvents(OutlineScene outlineScene, List<StoryEvent> allEvents) {
        Set<String> sceneRefs = new LinkedHashSet<>(readStringList(outlineScene.getSourceRefsJson()));
        if (sceneRefs.isEmpty()) {
            return allEvents;
        }
        List<StoryEvent> relevant = allEvents.stream()
                .filter(event -> readStringList(event.getSourceRefsJson()).stream().anyMatch(sceneRefs::contains))
                .toList();
        return relevant.isEmpty() ? allEvents : relevant;
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

    private List<List<StoryEvent>> buildProductionEventBatches(List<StoryEvent> events) {
        if (events.isEmpty()) {
            return List.of();
        }
        List<List<StoryEvent>> batches = new ArrayList<>();
        batches.add(events.subList(0, 1));
        for (int i = 1; i < events.size(); i += maxOutlineEventsPerBatch) {
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

    private boolean sendStreamEventIfConnected(
            AtomicBoolean clientConnected,
            SseEmitter emitter,
            String eventName,
            Map<String, Object> payload
    ) {
        if (!clientConnected.get()) {
            return false;
        }
        boolean sent = sendStreamEvent(emitter, eventName, payload);
        if (!sent) {
            clientConnected.set(false);
        }
        return sent;
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

    private record SceneGenerationContext(
            Project project,
            OutlineScene outlineScene,
            List<StoryEntity> entities,
            List<StoryEvent> events
    ) {
    }
}
