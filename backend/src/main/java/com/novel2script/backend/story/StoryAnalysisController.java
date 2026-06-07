package com.novel2script.backend.story;

import com.novel2script.backend.common.ApiResponse;
import com.novel2script.backend.story.dto.StoryAnalysisResponse;
import com.novel2script.backend.story.dto.StoryEntityResponse;
import com.novel2script.backend.story.dto.StoryEventResponse;
import com.novel2script.backend.workflow.WorkflowJobService;
import com.novel2script.backend.workflow.dto.WorkflowJobResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/projects/{projectId}")
public class StoryAnalysisController {

    private final StoryAnalysisService storyAnalysisService;

    private final WorkflowJobService workflowJobService;

    public StoryAnalysisController(StoryAnalysisService storyAnalysisService, WorkflowJobService workflowJobService) {
        this.storyAnalysisService = storyAnalysisService;
        this.workflowJobService = workflowJobService;
    }

    @PostMapping("/analyze")
    public ApiResponse<WorkflowJobResponse> analyze(@PathVariable String projectId) {
        return ApiResponse.ok(workflowJobService.submitStoryAnalysis(projectId));
    }

    @PostMapping("/analyze/incremental")
    public ApiResponse<WorkflowJobResponse> analyzeIncremental(@PathVariable String projectId) {
        return ApiResponse.ok(workflowJobService.submitIncrementalStoryAnalysis(projectId));
    }

    @GetMapping("/entities")
    public ApiResponse<List<StoryEntityResponse>> listEntities(@PathVariable String projectId) {
        return ApiResponse.ok(storyAnalysisService.listEntities(projectId));
    }

    @GetMapping("/story-events")
    public ApiResponse<List<StoryEventResponse>> listEvents(@PathVariable String projectId) {
        return ApiResponse.ok(storyAnalysisService.listEvents(projectId));
    }
}
