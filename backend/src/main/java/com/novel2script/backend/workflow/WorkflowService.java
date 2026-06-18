package com.novel2script.backend.workflow;

import com.novel2script.backend.common.ProjectOperationLock;
import com.novel2script.backend.project.Project;
import com.novel2script.backend.project.ProjectService;
import com.novel2script.backend.project.ProjectStatus;
import com.novel2script.backend.scene.SceneGenerationService;
import com.novel2script.backend.scene.dto.OutlineSceneResponse;
import com.novel2script.backend.scene.dto.SceneScriptResponse;
import com.novel2script.backend.workflow.dto.ValidationReportResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
public class WorkflowService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowService.class);

    private final ProjectService projectService;

    private final SceneGenerationService sceneGenerationService;

    private final ProjectOperationLock projectOperationLock;

    private final ProgressEventPublisher progressEventPublisher;

    private final int maxAutoGenerateScenes;

    public WorkflowService(
            ProjectService projectService,
            SceneGenerationService sceneGenerationService,
            ProjectOperationLock projectOperationLock,
            ProgressEventPublisher progressEventPublisher,
            @Value("${WORKFLOW_MAX_AUTO_GENERATE_SCENES:30}") int maxAutoGenerateScenes
    ) {
        this.projectService = projectService;
        this.sceneGenerationService = sceneGenerationService;
        this.projectOperationLock = projectOperationLock;
        this.progressEventPublisher = progressEventPublisher;
        this.maxAutoGenerateScenes = Math.max(0, maxAutoGenerateScenes);
    }

    public ValidationReportResponse validateProject(String projectId, boolean force) {
        return projectOperationLock.execute(projectId, () -> validateProjectLocked(projectId, force));
    }

    private ValidationReportResponse validateProjectLocked(String projectId, boolean force) {
        long startedAt = System.currentTimeMillis();
        log.info("开始校验项目: projectId={}", projectId);
        progressEventPublisher.jobStarted(projectId, "validation", "validating", 86, "开始执行结构校验");
        projectService.getProjectEntity(projectId);
        List<OutlineSceneResponse> outline = sceneGenerationService.listOutline(projectId);
        ensureAutoGenerationAllowed(projectId, outline, force, "结构校验");
        List<ValidationReportResponse.ValidationItemResponse> items = new ArrayList<>();

        for (OutlineSceneResponse outlineScene : outline) {
            SceneScriptResponse scene = sceneGenerationService.getSceneScript(projectId, outlineScene.getSceneId());
            if ("FAILED".equalsIgnoreCase(scene.getValidationStatus())) {
                ValidationReportResponse.ValidationItemResponse item = new ValidationReportResponse.ValidationItemResponse(
                        scene.getSceneId(), "error", "generation", "Scene 生成失败，请重新生成该 Scene"
                );
                items.add(item);
                progressEventPublisher.validationWarn(projectId, item.sceneId(), item.field(), item.message());
            }
            if (scene.getAction().isEmpty()) {
                ValidationReportResponse.ValidationItemResponse item = new ValidationReportResponse.ValidationItemResponse(
                        scene.getSceneId(), "error", "action", "Scene 缺少动作描写"
                );
                items.add(item);
                progressEventPublisher.validationWarn(projectId, item.sceneId(), item.field(), item.message());
            }
            if (scene.getDialogue().isEmpty()) {
                ValidationReportResponse.ValidationItemResponse item = new ValidationReportResponse.ValidationItemResponse(
                        scene.getSceneId(), "warning", "dialogue", "Scene 暂无对白"
                );
                items.add(item);
                progressEventPublisher.validationWarn(projectId, item.sceneId(), item.field(), item.message());
            }
            Set<String> allowedCharacters = new HashSet<>(outlineScene.getCharacters());
            for (SceneScriptResponse.DialogueResponse dialogue : scene.getDialogue()) {
                if (!allowedCharacters.isEmpty() && !allowedCharacters.contains(dialogue.characterId())) {
                    ValidationReportResponse.ValidationItemResponse item = new ValidationReportResponse.ValidationItemResponse(
                            scene.getSceneId(), "warning", "dialogue", "对白角色 " + dialogue.characterId() + " 未出现在场景大纲角色列表中"
                    );
                    items.add(item);
                    progressEventPublisher.validationWarn(projectId, item.sceneId(), item.field(), item.message());
                }
            }
            for (String warning : scene.getWarnings()) {
                ValidationReportResponse.ValidationItemResponse item = new ValidationReportResponse.ValidationItemResponse(
                        scene.getSceneId(), "warning", "warnings", warning
                );
                items.add(item);
                progressEventPublisher.validationWarn(projectId, item.sceneId(), item.field(), item.message());
            }
        }

        boolean hasError = items.stream().anyMatch(item -> "error".equals(item.level()));
        String status = hasError ? "FAILED" : items.isEmpty() ? "PASSED" : "WARNING";
        log.info(
                "项目校验完成: projectId={}, status={}, itemCount={}, elapsedMs={}",
                projectId,
                status,
                items.size(),
                System.currentTimeMillis() - startedAt
        );
        progressEventPublisher.jobCompleted(projectId, "validated", 90, false, "结构校验完成，状态：" + status);
        return new ValidationReportResponse(projectId, status, items);
    }

    public String exportYaml(String projectId, boolean force) {
        return projectOperationLock.execute(projectId, () -> exportYamlLocked(projectId, force));
    }

    private String exportYamlLocked(String projectId, boolean force) {
        long startedAt = System.currentTimeMillis();
        log.info("开始导出 YAML: projectId={}", projectId);
        progressEventPublisher.jobStarted(projectId, "yaml_export", "exporting", 95, "开始导出 YAML");
        Project project = projectService.getProjectEntity(projectId);
        List<OutlineSceneResponse> outline = sceneGenerationService.listOutline(projectId);
        ensureAutoGenerationAllowed(projectId, outline, force, "YAML 导出");
        List<SceneScriptResponse> scenes = new ArrayList<>();
        for (OutlineSceneResponse outlineScene : outline) {
            scenes.add(sceneGenerationService.getSceneScript(projectId, outlineScene.getSceneId()));
        }

        StringBuilder yaml = new StringBuilder();
        yaml.append("schema_version: \"1.0.0\"\n");
        yaml.append("meta:\n");
        yaml.append("  project_id: \"").append(escapeYaml(project.getProjectId())).append("\"\n");
        yaml.append("  title: \"").append(escapeYaml(project.getTitle())).append("\"\n");
        yaml.append("  workflow: \"reader-outline-writer-validator\"\n");
        yaml.append("scenes:\n");
        for (SceneScriptResponse scene : scenes) {
            yaml.append("  - scene_id: \"").append(escapeYaml(scene.getSceneId())).append("\"\n");
            yaml.append("    seq_no: ").append(scene.getSeqNo()).append("\n");
            yaml.append("    title: \"").append(escapeYaml(scene.getTitle())).append("\"\n");
            yaml.append("    action:\n");
            for (String action : scene.getAction()) {
                yaml.append("      - \"").append(escapeYaml(action)).append("\"\n");
            }
            yaml.append("    dialogue:\n");
            for (SceneScriptResponse.DialogueResponse dialogue : scene.getDialogue()) {
                yaml.append("      - character_id: \"").append(escapeYaml(dialogue.characterId())).append("\"\n");
                yaml.append("        line: \"").append(escapeYaml(dialogue.line())).append("\"\n");
            }
            yaml.append("    source_refs:\n");
            for (String sourceRef : scene.getSourceRefs()) {
                yaml.append("      - \"").append(escapeYaml(sourceRef)).append("\"\n");
            }
            yaml.append("    validation_status: \"").append(escapeYaml(scene.getValidationStatus())).append("\"\n");
            if (scene.getWarnings().isEmpty()) {
                yaml.append("    warnings: []\n");
            } else {
                yaml.append("    warnings:\n");
                for (String warning : scene.getWarnings()) {
                    yaml.append("      - \"").append(escapeYaml(warning)).append("\"\n");
                }
            }
        }
        projectService.updateStatus(projectId, ProjectStatus.COMPLETED);
        String yamlContent = yaml.toString();
        log.info(
                "YAML 导出完成: projectId={}, sceneCount={}, yamlLength={}, elapsedMs={}",
                projectId,
                scenes.size(),
                yamlContent.length(),
                System.currentTimeMillis() - startedAt
        );
        progressEventPublisher.jobCompleted(projectId, "completed", 100, true, "YAML 导出完成");
        return yamlContent;
    }

    private void ensureAutoGenerationAllowed(String projectId, List<OutlineSceneResponse> outline, boolean force, String operationName) {
        Set<String> generatedSceneIds = new LinkedHashSet<>();
        for (SceneScriptResponse scene : sceneGenerationService.listSceneScripts(projectId)) {
            generatedSceneIds.add(scene.getSceneId());
        }
        long missingCount = outline.stream()
                .filter(scene -> !generatedSceneIds.contains(scene.getSceneId()))
                .count();
        if (!force && missingCount > maxAutoGenerateScenes) {
            throw new IllegalArgumentException(
                    operationName + "需要自动生成 " + missingCount + " 个 Scene，超过当前上限 "
                            + maxAutoGenerateScenes + "。请先分批生成 Scene，或使用 force=true 明确执行。"
            );
        }
    }

    private String escapeYaml(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
