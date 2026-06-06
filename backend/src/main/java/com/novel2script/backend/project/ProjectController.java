package com.novel2script.backend.project;

import com.novel2script.backend.common.ApiResponse;
import com.novel2script.backend.project.dto.CreateProjectRequest;
import com.novel2script.backend.project.dto.ProjectResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private final ProjectService projectService;

    public ProjectController(ProjectService projectService) {
        this.projectService = projectService;
    }

    @PostMapping
    public ApiResponse<ProjectResponse> createProject(@Valid @RequestBody CreateProjectRequest request) {
        return ApiResponse.ok(projectService.createProject(request));
    }

    @GetMapping
    public ApiResponse<List<ProjectResponse>> listProjects(@RequestParam(required = false) String keyword) {
        return ApiResponse.ok(projectService.listProjects(keyword));
    }

    @GetMapping("/{projectId}")
    public ApiResponse<ProjectResponse> getProject(@PathVariable String projectId) {
        return ApiResponse.ok(projectService.getProject(projectId));
    }
}
