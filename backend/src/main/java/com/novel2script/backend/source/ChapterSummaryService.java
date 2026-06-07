package com.novel2script.backend.source;

import com.novel2script.backend.common.ProjectOperationLock;
import com.novel2script.backend.project.ProjectService;
import com.novel2script.backend.source.dto.ChapterResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ChapterSummaryService {

    private final ProjectService projectService;

    private final SourceChapterMapper sourceChapterMapper;

    private final ChapterSummaryGenerator chapterSummaryGenerator;

    private final ProjectOperationLock projectOperationLock;

    public ChapterSummaryService(
            ProjectService projectService,
            SourceChapterMapper sourceChapterMapper,
            ChapterSummaryGenerator chapterSummaryGenerator,
            ProjectOperationLock projectOperationLock
    ) {
        this.projectService = projectService;
        this.sourceChapterMapper = sourceChapterMapper;
        this.chapterSummaryGenerator = chapterSummaryGenerator;
        this.projectOperationLock = projectOperationLock;
    }

    @Transactional
    public List<ChapterResponse> summarizeChapters(String projectId) {
        return projectOperationLock.execute(projectId, () -> summarizeChaptersLocked(projectId));
    }

    private List<ChapterResponse> summarizeChaptersLocked(String projectId) {
        projectService.getProjectEntity(projectId);
        List<SourceChapter> chapters = sourceChapterMapper.findByProjectIdOrderByChapterNoAsc(projectId);
        if (chapters.isEmpty()) {
            throw new IllegalArgumentException("请先提交小说文本并完成章节切分");
        }

        for (SourceChapter chapter : chapters) {
            String summary = chapterSummaryGenerator.generate(chapter);
            sourceChapterMapper.updateSummary(chapter.getId(), summary);
        }

        return sourceChapterMapper.findByProjectIdOrderByChapterNoAsc(projectId).stream()
                .map(ChapterResponse::from)
                .toList();
    }
}
