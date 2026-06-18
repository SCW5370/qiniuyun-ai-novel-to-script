package com.novel2script.backend.scene;

import com.novel2script.backend.common.ApiResponse;
import com.novel2script.backend.scene.dto.OutlineSceneResponse;
import com.novel2script.backend.scene.dto.SceneScriptResponse;
import com.novel2script.backend.workflow.WorkflowJobService;
import com.novel2script.backend.workflow.dto.WorkflowJobResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

@RestController
@RequestMapping("/api/projects/{projectId}")
public class SceneGenerationController {

    private final SceneGenerationService sceneGenerationService;

    private final WorkflowJobService workflowJobService;

    public SceneGenerationController(SceneGenerationService sceneGenerationService, WorkflowJobService workflowJobService) {
        this.sceneGenerationService = sceneGenerationService;
        this.workflowJobService = workflowJobService;
    }

    @GetMapping("/outline")
    public ApiResponse<List<OutlineSceneResponse>> listOutline(@PathVariable String projectId) {
        return ApiResponse.ok(sceneGenerationService.listExistingOutline(projectId));
    }

    @PostMapping("/outline/incremental")
    public ApiResponse<WorkflowJobResponse> generateIncrementalOutline(@PathVariable String projectId) {
        return ApiResponse.ok(workflowJobService.submitIncrementalOutlineGeneration(projectId));
    }

    @GetMapping("/scenes")
    public ApiResponse<List<SceneScriptResponse>> listSceneScripts(@PathVariable String projectId) {
        return ApiResponse.ok(sceneGenerationService.listSceneScripts(projectId));
    }

    @GetMapping("/scenes/{sceneId}")
    public ApiResponse<SceneScriptResponse> getSceneScript(@PathVariable String projectId, @PathVariable String sceneId) {
        return ApiResponse.ok(sceneGenerationService.getSceneScript(projectId, sceneId));
    }

    @PostMapping("/scenes/{sceneId}/regenerate")
    public ApiResponse<SceneScriptResponse> regenerateSceneScript(@PathVariable String projectId, @PathVariable String sceneId) {
        return ApiResponse.ok(sceneGenerationService.regenerateSceneScript(projectId, sceneId));
    }

    @GetMapping("/scenes/{sceneId}/stream")
    public SseEmitter streamSceneScript(@PathVariable String projectId, @PathVariable String sceneId) {
        return sceneGenerationService.streamSceneScript(projectId, sceneId);
    }
}
