package com.novel2script.backend.workflow;

import com.novel2script.backend.project.Project;
import com.novel2script.backend.project.ProjectService;
import com.novel2script.backend.project.ProjectStatus;
import com.novel2script.backend.scene.SceneGenerationService;
import com.novel2script.backend.scene.dto.OutlineSceneResponse;
import com.novel2script.backend.scene.dto.SceneScriptResponse;
import com.novel2script.backend.workflow.dto.ValidationReportResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class WorkflowService {

    private final ProjectService projectService;

    private final SceneGenerationService sceneGenerationService;

    public WorkflowService(ProjectService projectService, SceneGenerationService sceneGenerationService) {
        this.projectService = projectService;
        this.sceneGenerationService = sceneGenerationService;
    }

    @Transactional
    public ValidationReportResponse validateProject(String projectId) {
        projectService.getProjectEntity(projectId);
        List<OutlineSceneResponse> outline = sceneGenerationService.listOutline(projectId);
        List<ValidationReportResponse.ValidationItemResponse> items = new ArrayList<>();

        for (OutlineSceneResponse outlineScene : outline) {
            SceneScriptResponse scene = sceneGenerationService.getSceneScript(projectId, outlineScene.getSceneId());
            if (scene.getAction().isEmpty()) {
                items.add(new ValidationReportResponse.ValidationItemResponse(
                        scene.getSceneId(), "error", "action", "Scene 缺少动作描写"
                ));
            }
            if (scene.getDialogue().isEmpty()) {
                items.add(new ValidationReportResponse.ValidationItemResponse(
                        scene.getSceneId(), "warning", "dialogue", "Scene 暂无对白"
                ));
            }
            Set<String> allowedCharacters = new HashSet<>(outlineScene.getCharacters());
            for (SceneScriptResponse.DialogueResponse dialogue : scene.getDialogue()) {
                if (!allowedCharacters.isEmpty() && !allowedCharacters.contains(dialogue.characterId())) {
                    items.add(new ValidationReportResponse.ValidationItemResponse(
                            scene.getSceneId(), "warning", "dialogue", "对白角色 " + dialogue.characterId() + " 未出现在场景大纲角色列表中"
                    ));
                }
            }
            for (String warning : scene.getWarnings()) {
                items.add(new ValidationReportResponse.ValidationItemResponse(
                        scene.getSceneId(), "warning", "warnings", warning
                ));
            }
        }

        boolean hasError = items.stream().anyMatch(item -> "error".equals(item.level()));
        String status = hasError ? "FAILED" : items.isEmpty() ? "PASSED" : "WARNING";
        return new ValidationReportResponse(projectId, status, items);
    }

    @Transactional
    public String exportYaml(String projectId) {
        Project project = projectService.getProjectEntity(projectId);
        List<OutlineSceneResponse> outline = sceneGenerationService.listOutline(projectId);
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
        }
        projectService.updateStatus(projectId, ProjectStatus.COMPLETED);
        return yaml.toString();
    }

    private String escapeYaml(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
