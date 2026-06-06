package com.novel2script.backend.source;

import com.novel2script.backend.common.ApiResponse;
import com.novel2script.backend.source.dto.ChapterResponse;
import com.novel2script.backend.source.dto.SubmitSourceRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/projects/{projectId}")
public class SourceTextController {

    private final SourceTextService sourceTextService;

    public SourceTextController(SourceTextService sourceTextService) {
        this.sourceTextService = sourceTextService;
    }

    @PostMapping("/source")
    public ApiResponse<List<ChapterResponse>> submitSource(
            @PathVariable String projectId,
            @Valid @RequestBody SubmitSourceRequest request
    ) {
        return ApiResponse.ok(sourceTextService.submitSource(projectId, request));
    }

    @GetMapping("/chapters")
    public ApiResponse<List<ChapterResponse>> listChapters(@PathVariable String projectId) {
        return ApiResponse.ok(sourceTextService.listChapters(projectId));
    }
}
