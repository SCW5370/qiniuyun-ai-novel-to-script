package com.novel2script.backend.source;

import com.novel2script.backend.common.ProjectOperationLock;
import com.novel2script.backend.project.ProjectService;
import com.novel2script.backend.source.dto.ChapterResponse;
import com.novel2script.backend.workflow.ProgressEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

@Service
public class ChapterSummaryService {

    private static final Logger log = LoggerFactory.getLogger(ChapterSummaryService.class);

    private final ProjectService projectService;

    private final SourceChapterMapper sourceChapterMapper;

    private final ChapterSummaryGenerator chapterSummaryGenerator;

    private final ProjectOperationLock projectOperationLock;

    private final ProgressEventPublisher progressEventPublisher;

    private final ThreadPoolTaskExecutor chapterSummaryExecutor;

    public ChapterSummaryService(
            ProjectService projectService,
            SourceChapterMapper sourceChapterMapper,
            ChapterSummaryGenerator chapterSummaryGenerator,
            ProjectOperationLock projectOperationLock,
            ProgressEventPublisher progressEventPublisher,
            @Qualifier("chapterSummaryExecutor") ThreadPoolTaskExecutor chapterSummaryExecutor
    ) {
        this.projectService = projectService;
        this.sourceChapterMapper = sourceChapterMapper;
        this.chapterSummaryGenerator = chapterSummaryGenerator;
        this.projectOperationLock = projectOperationLock;
        this.progressEventPublisher = progressEventPublisher;
        this.chapterSummaryExecutor = chapterSummaryExecutor;
    }

    public List<ChapterResponse> summarizeChapters(String projectId) {
        return projectOperationLock.execute(projectId, () -> summarizeChaptersLocked(projectId));
    }

    private List<ChapterResponse> summarizeChaptersLocked(String projectId) {
        long startedAt = System.currentTimeMillis();
        log.info("开始生成章节摘要: projectId={}", projectId);
        progressEventPublisher.jobStarted(projectId, "chapter_summary", "summarizing", 32, "开始生成章节摘要");
        projectService.getProjectEntity(projectId);
        List<SourceChapter> chapters = sourceChapterMapper.findByProjectIdOrderByChapterNoAsc(projectId);
        if (chapters.isEmpty()) {
            throw new IllegalArgumentException("请先提交小说文本并完成章节切分");
        }

        List<Future<String>> futures = new ArrayList<>(chapters.size());
        for (SourceChapter chapter : chapters) {
            futures.add(chapterSummaryExecutor.submit(() -> chapterSummaryGenerator.generate(chapter)));
        }
        for (int i = 0; i < chapters.size(); i++) {
            SourceChapter chapter = chapters.get(i);
            String summary;
            try {
                summary = futures.get(i).get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("章节摘要生成被中断: projectId=" + projectId, e);
            } catch (ExecutionException e) {
                throw new IllegalStateException("章节摘要生成失败: projectId=" + projectId, e.getCause());
            }
            sourceChapterMapper.updateSummary(chapter.getId(), summary);
        }
        log.info(
                "章节摘要生成完成: projectId={}, chapterCount={}, elapsedMs={}",
                projectId,
                chapters.size(),
                System.currentTimeMillis() - startedAt
        );
        progressEventPublisher.jobCompleted(projectId, "chaptered", 38, false, "章节摘要生成完成，共 " + chapters.size() + " 章");

        return sourceChapterMapper.findByProjectIdOrderByChapterNoAsc(projectId).stream()
                .map(ChapterResponse::from)
                .toList();
    }
}
