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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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

    public SceneGenerationService(
            ProjectService projectService,
            StoryEntityMapper storyEntityMapper,
            StoryEventMapper storyEventMapper,
            OutlineSceneMapper outlineSceneMapper,
            SceneScriptMapper sceneScriptMapper,
            AiChatClient aiChatClient,
            ObjectMapper objectMapper,
            ProjectOperationLock projectOperationLock,
            ProgressEventPublisher progressEventPublisher
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
    }

    @Transactional
    public List<OutlineSceneResponse> listOutline(String projectId) {
        return projectOperationLock.execute(projectId, () -> listOutlineLocked(projectId));
    }

    private List<OutlineSceneResponse> listOutlineLocked(String projectId) {
        projectService.getProjectEntity(projectId);
        List<OutlineScene> scenes = outlineSceneMapper.findByProjectIdOrderBySeqNoAsc(projectId);
        if (scenes.isEmpty()) {
            scenes = generateOutline(projectId);
        }
        return scenes.stream().map(this::toOutlineResponse).toList();
    }

    @Transactional(readOnly = true)
    public List<SceneScriptResponse> listSceneScripts(String projectId) {
        projectService.getProjectEntity(projectId);
        return sceneScriptMapper.findByProjectIdOrderBySeqNoAsc(projectId).stream()
                .map(this::toSceneScriptResponse)
                .toList();
    }

    @Transactional
    public SceneScriptResponse getSceneScript(String projectId, String sceneId) {
        return projectOperationLock.execute(projectId, () -> getSceneScriptLocked(projectId, sceneId));
    }

    private SceneScriptResponse getSceneScriptLocked(String projectId, String sceneId) {
        projectService.getProjectEntity(projectId);
        return sceneScriptMapper.findByProjectIdAndSceneId(projectId, sceneId)
                .map(this::toSceneScriptResponse)
                .orElseGet(() -> toSceneScriptResponse(generateSceneScript(projectId, sceneId, false)));
    }

    @Transactional
    public SceneScriptResponse regenerateSceneScript(String projectId, String sceneId) {
        return projectOperationLock.execute(projectId, () -> regenerateSceneScriptLocked(projectId, sceneId));
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
        List<OutlineScene> scenes;
        try {
            scenes = parseOutline(projectId, aiChatClient.completeJson(
                    "你是专业影视编剧，负责把小说故事资产拆成 Scene 级场景大纲。只返回合法 JSON。",
                    buildOutlinePrompt(entities, events)
            ));
        } catch (Exception ex) {
            log.warn("AI 场景大纲生成失败，切换规则兜底: projectId={}, reason={}", projectId, rootCauseMessage(ex));
            progressEventPublisher.phaseChanged(projectId, "outline_generating", 56, "AI 场景大纲生成失败，已切换规则兜底");
            scenes = buildFallbackOutline(projectId, events);
        }

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
        SceneScript sceneScript;
        try {
            sceneScript = parseSceneScript(projectId, outlineScene, aiChatClient.completeJson(
                    "你是专业剧本写作助手，负责根据场景大纲生成 Scene 级动作和对白。只返回合法 JSON。",
                    buildScenePrompt(project, outlineScene, entities, events, regenerating)
            ));
        } catch (Exception ex) {
            log.warn("AI Scene 生成失败，切换规则兜底: projectId={}, sceneId={}, reason={}", projectId, sceneId, rootCauseMessage(ex));
            progressEventPublisher.phaseChanged(projectId, "scene_generating", 72, "AI Scene 生成失败，已切换规则兜底: " + sceneId);
            sceneScript = buildFallbackSceneScript(projectId, outlineScene);
        }

        sceneScriptMapper.insert(sceneScript);
        projectService.updateStatus(projectId, ProjectStatus.SCENE_GENERATING);
        progressEventPublisher.sceneDone(projectId, sceneId, sceneScript.getValidationStatus());
        log.info(
                "Scene 生成完成: projectId={}, sceneId={}, validationStatus={}, elapsedMs={}",
                projectId,
                sceneId,
                sceneScript.getValidationStatus(),
                System.currentTimeMillis() - startedAt
        );
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

    private List<OutlineScene> parseOutline(String projectId, String json) throws JsonProcessingException {
        JsonNode scenesNode = objectMapper.readTree(json).path("scenes");
        List<OutlineScene> scenes = new ArrayList<>();
        if (!scenesNode.isArray()) {
            return scenes;
        }
        int index = 1;
        for (JsonNode node : scenesNode) {
            String sceneId = "S" + String.format(Locale.ROOT, "%03d", index);
            JsonNode slugline = node.path("slugline");
            JsonNode purpose = node.path("purpose");
            scenes.add(new OutlineScene(
                    projectId,
                    sceneId,
                    index,
                    text(node.path("title").asText(), "场景 " + index),
                    text(slugline.path("intExt").asText(), "INT"),
                    text(slugline.path("locationId").asText(), "L001"),
                    text(slugline.path("timeOfDay").asText(), "DAY"),
                    text(purpose.path("plot").asText(), "推进主要情节"),
                    text(purpose.path("character").asText(), "展示角色选择"),
                    toJson(readStringArray(node.path("characters"), List.of())),
                    toJson(readStringArray(node.path("sourceRefs"), List.of("ch" + index))),
                    text(node.path("status").asText(), "READY")
            ));
            index++;
        }
        return scenes.isEmpty() ? buildFallbackOutline(projectId, storyEventMapper.findByProjectIdOrderByEventOrderAsc(projectId)) : scenes;
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
        List<OutlineScene> scenes = new ArrayList<>();
        int index = 1;
        for (StoryEvent event : events) {
            String sceneId = "S" + String.format(Locale.ROOT, "%03d", index);
            scenes.add(new OutlineScene(
                    projectId,
                    sceneId,
                    index,
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
            index++;
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
                只返回 JSON：
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
                1. 每个关键事件至少生成 1 个场景。
                2. characters 只能使用实体中的角色 ID。
                3. locationId 优先使用实体中的地点 ID。
                4. sourceRefs 沿用事件中的 sourceRefs。

                故事实体：
                %s

                故事事件：
                %s
                """.formatted(toJson(entities), toJson(events));
    }

    private String buildScenePrompt(Project project, OutlineScene outlineScene, List<StoryEntity> entities, List<StoryEvent> events, boolean regenerating) {
        return """
                请生成单个 Scene 级剧本片段。
                项目标题：%s
                是否重新生成：%s

                只返回 JSON：
                {
                  "title": "场景标题",
                  "action": ["动作描写"],
                  "dialogue": [{"characterId":"C001","line":"对白"}],
                  "sourceRefs": ["ch1"],
                  "validationStatus": "PASSED",
                  "warnings": []
                }
                要求：
                1. action 为 2 到 5 条，使用影视剧本动作描述。
                2. dialogue 为 1 到 6 条，只使用角色 ID。
                3. 不新增契约外字段。
                4. 如果信息不足，warnings 写明原因，validationStatus 使用 WARNING。

                场景大纲：
                %s

                故事实体：
                %s

                故事事件：
                %s
                """.formatted(project.getTitle(), regenerating, toJson(outlineScene), toJson(entities), toJson(events));
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
}
