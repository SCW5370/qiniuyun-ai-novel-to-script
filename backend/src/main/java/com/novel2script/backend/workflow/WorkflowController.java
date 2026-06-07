package com.novel2script.backend.workflow;

import com.novel2script.backend.common.ApiResponse;
import com.novel2script.backend.project.Project;
import com.novel2script.backend.project.ProjectService;
import com.novel2script.backend.workflow.dto.ValidationReportResponse;
import com.novel2script.backend.workflow.dto.WorkflowJobResponse;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/projects/{projectId}")
public class WorkflowController {

    private final WorkflowService workflowService;

    private final ProjectService projectService;

    private final ProgressEventPublisher progressEventPublisher;

    private final WorkflowJobService workflowJobService;

    public WorkflowController(
            WorkflowService workflowService,
            ProjectService projectService,
            ProgressEventPublisher progressEventPublisher,
            WorkflowJobService workflowJobService
    ) {
        this.workflowService = workflowService;
        this.projectService = projectService;
        this.progressEventPublisher = progressEventPublisher;
        this.workflowJobService = workflowJobService;
    }

    @PostMapping("/validate")
    public ApiResponse<ValidationReportResponse> validateProject(
            @PathVariable String projectId,
            @RequestParam(defaultValue = "false") boolean force
    ) {
        return ApiResponse.ok(workflowService.validateProject(projectId, force));
    }

    @GetMapping(value = "/export", produces = "text/yaml;charset=UTF-8")
    public ResponseEntity<String> exportProject(
            @PathVariable String projectId,
            @RequestParam(defaultValue = "yaml") String format,
            @RequestParam(defaultValue = "false") boolean force
    ) {
        if (!"yaml".equalsIgnoreCase(format)) {
            throw new IllegalArgumentException("暂只支持 YAML 导出");
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/yaml;charset=UTF-8"))
                .body(workflowService.exportYaml(projectId, force));
    }

    @GetMapping("/events")
    public SseEmitter streamEvents(@PathVariable String projectId) {
        Project project = projectService.getProjectEntity(projectId);
        return progressEventPublisher.subscribe(project);
    }

    @PostMapping("/jobs/analyze")
    public ApiResponse<WorkflowJobResponse> submitStoryAnalysisJob(@PathVariable String projectId) {
        return ApiResponse.ok(workflowJobService.submitStoryAnalysis(projectId));
    }

    @PostMapping("/jobs/analyze/incremental")
    public ApiResponse<WorkflowJobResponse> submitIncrementalStoryAnalysisJob(@PathVariable String projectId) {
        return ApiResponse.ok(workflowJobService.submitIncrementalStoryAnalysis(projectId));
    }

    @PostMapping("/jobs/outline")
    public ApiResponse<WorkflowJobResponse> submitOutlineGenerationJob(@PathVariable String projectId) {
        return ApiResponse.ok(workflowJobService.submitOutlineGeneration(projectId));
    }

    @PostMapping("/jobs/outline/incremental")
    public ApiResponse<WorkflowJobResponse> submitIncrementalOutlineGenerationJob(@PathVariable String projectId) {
        return ApiResponse.ok(workflowJobService.submitIncrementalOutlineGeneration(projectId));
    }

    @PostMapping("/jobs/scenes")
    public ApiResponse<WorkflowJobResponse> submitSceneScriptsGenerationJob(@PathVariable String projectId) {
        return ApiResponse.ok(workflowJobService.submitSceneScriptsGeneration(projectId));
    }

    @GetMapping("/jobs/{jobId}")
    public ApiResponse<WorkflowJobResponse> getWorkflowJob(@PathVariable String projectId, @PathVariable String jobId) {
        return ApiResponse.ok(workflowJobService.getJob(projectId, jobId));
    }
}
