package com.novel2script.backend.workflow;

import com.novel2script.backend.common.ApiResponse;
import com.novel2script.backend.project.Project;
import com.novel2script.backend.project.ProjectService;
import com.novel2script.backend.workflow.dto.ValidationReportResponse;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

@RestController
@RequestMapping("/api/projects/{projectId}")
public class WorkflowController {

    private final WorkflowService workflowService;

    private final ProjectService projectService;

    private final ProgressEventPublisher progressEventPublisher;

    public WorkflowController(
            WorkflowService workflowService,
            ProjectService projectService,
            ProgressEventPublisher progressEventPublisher
    ) {
        this.workflowService = workflowService;
        this.projectService = projectService;
        this.progressEventPublisher = progressEventPublisher;
    }

    @PostMapping("/validate")
    public ApiResponse<ValidationReportResponse> validateProject(@PathVariable String projectId) {
        return ApiResponse.ok(workflowService.validateProject(projectId));
    }

    @GetMapping(value = "/export", produces = "text/yaml;charset=UTF-8")
    public ResponseEntity<String> exportProject(@PathVariable String projectId, @RequestParam(defaultValue = "yaml") String format) {
        if (!"yaml".equalsIgnoreCase(format)) {
            throw new IllegalArgumentException("暂只支持 YAML 导出");
        }
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/yaml;charset=UTF-8"))
                .body(workflowService.exportYaml(projectId));
    }

    @GetMapping("/events")
    public SseEmitter streamEvents(@PathVariable String projectId) throws IOException {
        Project project = projectService.getProjectEntity(projectId);
        return progressEventPublisher.subscribe(project);
    }
}
