package com.novel2script.backend.source;

import com.novel2script.backend.project.Project;
import com.novel2script.backend.project.ProjectService;
import com.novel2script.backend.project.ProjectStatus;
import com.novel2script.backend.source.dto.ChapterResponse;
import com.novel2script.backend.source.dto.SubmitSourceRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class SourceTextService {

    private final ProjectService projectService;

    private final SourceChapterMapper sourceChapterMapper;

    private final ChapterSplitter chapterSplitter;

    public SourceTextService(
            ProjectService projectService,
            SourceChapterMapper sourceChapterMapper,
            ChapterSplitter chapterSplitter
    ) {
        this.projectService = projectService;
        this.sourceChapterMapper = sourceChapterMapper;
        this.chapterSplitter = chapterSplitter;
    }

    @Transactional
    public List<ChapterResponse> submitSource(Long projectId, SubmitSourceRequest request) {
        Project project = projectService.getProjectEntity(projectId);
        projectService.updateStatus(projectId, ProjectStatus.SOURCE_SUBMITTED);

        List<ChapterSplitter.ChapterSegment> segments = chapterSplitter.split(request.getContent());
        sourceChapterMapper.deleteByProjectId(projectId);

        List<SourceChapter> chapters = segments.stream()
                .map(segment -> new SourceChapter(
                        project,
                        segment.chapterNo(),
                        segment.title(),
                        segment.rawText(),
                        segment.cleanText()
                ))
                .toList();

        if (!chapters.isEmpty()) {
            sourceChapterMapper.insertBatch(chapters);
        }
        projectService.updateStatus(projectId, ProjectStatus.CHAPTERED);

        return sourceChapterMapper.findByProjectIdOrderByChapterNoAsc(projectId).stream()
                .map(ChapterResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ChapterResponse> listChapters(Long projectId) {
        projectService.getProjectEntity(projectId);
        return sourceChapterMapper.findByProjectIdOrderByChapterNoAsc(projectId).stream()
                .map(ChapterResponse::from)
                .toList();
    }
}
