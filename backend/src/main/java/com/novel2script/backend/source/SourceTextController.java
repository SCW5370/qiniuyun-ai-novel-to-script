package com.novel2script.backend.source;

import com.novel2script.backend.common.ApiResponse;
import com.novel2script.backend.source.dto.ChapterResponse;
import com.novel2script.backend.source.dto.SubmitSourceRequest;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/projects/{projectId}")
public class SourceTextController {

    private final SourceTextService sourceTextService;

    private final ChapterSummaryService chapterSummaryService;

    public SourceTextController(SourceTextService sourceTextService, ChapterSummaryService chapterSummaryService) {
        this.sourceTextService = sourceTextService;
        this.chapterSummaryService = chapterSummaryService;
    }

    @PostMapping(value = "/source", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<List<ChapterResponse>> submitSource(
            @PathVariable String projectId,
            @Valid @RequestBody SubmitSourceRequest request
    ) {
        return ApiResponse.ok(sourceTextService.submitSource(projectId, request));
    }

    @PostMapping(value = "/source/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<List<ChapterResponse>> uploadSourceFile(
            @PathVariable String projectId,
            @RequestParam("file") MultipartFile file
    ) {
        return ApiResponse.ok(sourceTextService.submitSourceFile(projectId, file));
    }

    @PostMapping(value = "/chapters/append", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<List<ChapterResponse>> appendSource(
            @PathVariable String projectId,
            @Valid @RequestBody SubmitSourceRequest request
    ) {
        return ApiResponse.ok(sourceTextService.appendSource(projectId, request));
    }

    @PostMapping(value = "/chapters/append", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<List<ChapterResponse>> appendSourceFile(
            @PathVariable String projectId,
            @RequestParam("file") MultipartFile file
    ) {
        return ApiResponse.ok(sourceTextService.appendSourceFile(projectId, file));
    }

    @PostMapping(value = "/source", consumes = MediaType.TEXT_PLAIN_VALUE)
    public ApiResponse<List<ChapterResponse>> submitSourceText(
            @PathVariable String projectId,
            @RequestBody String content
    ) {
        // 兼容 IntelliJ HTTP Client 直接读取本地小说.txt 的测试方式，前端仍使用 JSON 接口。
        SubmitSourceRequest request = new SubmitSourceRequest();
        request.setContent(content);
        return ApiResponse.ok(sourceTextService.submitSource(projectId, request));
    }

    @GetMapping("/chapters")
    public ApiResponse<List<ChapterResponse>> listChapters(@PathVariable String projectId) {
        return ApiResponse.ok(sourceTextService.listChapters(projectId));
    }

    @PostMapping("/chapters/summarize")
    public ApiResponse<List<ChapterResponse>> summarizeChapters(@PathVariable String projectId) {
        return ApiResponse.ok(chapterSummaryService.summarizeChapters(projectId));
    }
}
