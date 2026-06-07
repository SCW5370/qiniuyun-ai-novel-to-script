package com.novel2script.backend.source;

import com.novel2script.backend.common.ProjectOperationLock;
import com.novel2script.backend.project.Project;
import com.novel2script.backend.project.ProjectService;
import com.novel2script.backend.project.ProjectStatus;
import com.novel2script.backend.scene.OutlineSceneMapper;
import com.novel2script.backend.scene.SceneScriptMapper;
import com.novel2script.backend.source.dto.ChapterResponse;
import com.novel2script.backend.source.dto.SubmitSourceRequest;
import com.novel2script.backend.story.StoryEntityMapper;
import com.novel2script.backend.story.StoryEventMapper;
import com.novel2script.backend.workflow.ProgressEventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class SourceTextService {

    private static final Logger log = LoggerFactory.getLogger(SourceTextService.class);

    private static final long MAX_SOURCE_FILE_SIZE = 2 * 1024 * 1024;

    private static final Charset GB18030 = Charset.forName("GB18030");

    private final ProjectService projectService;

    private final SourceChapterMapper sourceChapterMapper;

    private final StoryEntityMapper storyEntityMapper;

    private final StoryEventMapper storyEventMapper;

    private final OutlineSceneMapper outlineSceneMapper;

    private final SceneScriptMapper sceneScriptMapper;

    private final ChapterSplitter chapterSplitter;

    private final ProjectOperationLock projectOperationLock;

    private final ProgressEventPublisher progressEventPublisher;

    public SourceTextService(
            ProjectService projectService,
            SourceChapterMapper sourceChapterMapper,
            StoryEntityMapper storyEntityMapper,
            StoryEventMapper storyEventMapper,
            OutlineSceneMapper outlineSceneMapper,
            SceneScriptMapper sceneScriptMapper,
            ChapterSplitter chapterSplitter,
            ProjectOperationLock projectOperationLock,
            ProgressEventPublisher progressEventPublisher
    ) {
        this.projectService = projectService;
        this.sourceChapterMapper = sourceChapterMapper;
        this.storyEntityMapper = storyEntityMapper;
        this.storyEventMapper = storyEventMapper;
        this.outlineSceneMapper = outlineSceneMapper;
        this.sceneScriptMapper = sceneScriptMapper;
        this.chapterSplitter = chapterSplitter;
        this.projectOperationLock = projectOperationLock;
        this.progressEventPublisher = progressEventPublisher;
    }

    @Transactional
    public List<ChapterResponse> submitSource(String projectId, SubmitSourceRequest request) {
        return projectOperationLock.execute(projectId, () -> submitSourceLocked(projectId, request));
    }

    @Transactional
    public List<ChapterResponse> appendSource(String projectId, SubmitSourceRequest request) {
        return projectOperationLock.execute(projectId, () -> appendSourceLocked(projectId, request));
    }

    @Transactional
    public List<ChapterResponse> submitSourceFile(String projectId, MultipartFile file) {
        SubmitSourceRequest request = new SubmitSourceRequest();
        request.setContent(readSourceFileContent(file));
        log.info(
                "收到小说文件上传: projectId={}, filename={}, size={}",
                projectId,
                file == null ? "" : file.getOriginalFilename(),
                file == null ? 0 : file.getSize()
        );
        return projectOperationLock.execute(projectId, () -> submitSourceLocked(projectId, request));
    }

    @Transactional
    public List<ChapterResponse> appendSourceFile(String projectId, MultipartFile file) {
        SubmitSourceRequest request = new SubmitSourceRequest();
        request.setContent(readSourceFileContent(file));
        log.info(
                "收到追加小说文件上传: projectId={}, filename={}, size={}",
                projectId,
                file == null ? "" : file.getOriginalFilename(),
                file == null ? 0 : file.getSize()
        );
        return projectOperationLock.execute(projectId, () -> appendSourceLocked(projectId, request));
    }

    private List<ChapterResponse> submitSourceLocked(String projectId, SubmitSourceRequest request) {
        long startedAt = System.currentTimeMillis();
        log.info("开始提交小说正文: projectId={}", projectId);
        progressEventPublisher.jobStarted(projectId, "source_submit", "source_submitted", 10, "开始提交小说正文");
        Project project = projectService.getProjectEntity(projectId);
        projectService.updateStatus(projectId, ProjectStatus.SOURCE_SUBMITTED);
        progressEventPublisher.phaseChanged(projectId, "source_submitted", 18, "小说正文已接收，正在切分章节");

        List<ChapterSplitter.ChapterSegment> segments = chapterSplitter.split(request.getContent());
        sceneScriptMapper.deleteByProjectId(projectId);
        outlineSceneMapper.deleteByProjectId(projectId);
        storyEntityMapper.deleteByProjectId(projectId);
        storyEventMapper.deleteByProjectId(projectId);
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
        progressEventPublisher.jobCompleted(projectId, "chaptered", 30, false, "章节切分完成，共 " + chapters.size() + " 章");
        log.info(
                "小说正文提交完成: projectId={}, chapterCount={}, elapsedMs={}",
                projectId,
                chapters.size(),
                System.currentTimeMillis() - startedAt
        );

        return sourceChapterMapper.findByProjectIdOrderByChapterNoAsc(projectId).stream()
                .map(ChapterResponse::from)
                .toList();
    }

    private List<ChapterResponse> appendSourceLocked(String projectId, SubmitSourceRequest request) {
        long startedAt = System.currentTimeMillis();
        log.info("开始追加小说章节: projectId={}", projectId);
        progressEventPublisher.jobStarted(projectId, "source_append", "source_submitted", 10, "开始追加小说章节");
        Project project = projectService.getProjectEntity(projectId);
        projectService.updateStatus(projectId, ProjectStatus.SOURCE_SUBMITTED);

        List<ChapterSplitter.ChapterSegment> segments = chapterSplitter.split(request.getContent());
        normalizeExplicitChapterNos(projectId);
        List<SourceChapter> existingChapters = sourceChapterMapper.findByProjectIdOrderByChapterNoAsc(projectId);
        Set<Integer> usedChapterNos = new LinkedHashSet<>();
        for (SourceChapter chapter : existingChapters) {
            usedChapterNos.add(chapter.getChapterNo());
        }
        int nextChapterNo = usedChapterNos.stream().max(Integer::compareTo).orElse(0) + 1;
        List<SourceChapter> chapters = new ArrayList<>();
        for (ChapterSplitter.ChapterSegment segment : segments) {
            int chapterNo = segment.explicitChapterNo() && !usedChapterNos.contains(segment.chapterNo())
                    ? segment.chapterNo()
                    : nextAvailableChapterNo(usedChapterNos, nextChapterNo);
            usedChapterNos.add(chapterNo);
            nextChapterNo = Math.max(nextChapterNo, chapterNo + 1);
            chapters.add(new SourceChapter(
                    project,
                    chapterNo,
                    segment.title(),
                    segment.rawText(),
                    segment.cleanText()
            ));
        }

        if (!chapters.isEmpty()) {
            sourceChapterMapper.insertBatch(chapters);
        }
        normalizeExplicitChapterNos(projectId);
        projectService.updateStatus(projectId, ProjectStatus.CHAPTERED);
        progressEventPublisher.jobCompleted(projectId, "chaptered", 30, false, "章节追加完成，新增 " + chapters.size() + " 章");
        log.info(
                "小说章节追加完成: projectId={}, appendCount={}, elapsedMs={}",
                projectId,
                chapters.size(),
                System.currentTimeMillis() - startedAt
        );

        return sourceChapterMapper.findByProjectIdOrderByChapterNoAsc(projectId).stream()
                .map(ChapterResponse::from)
                .toList();
    }

    private int nextAvailableChapterNo(Set<Integer> usedChapterNos, int start) {
        int candidate = Math.max(1, start);
        while (usedChapterNos.contains(candidate)) {
            candidate++;
        }
        return candidate;
    }

    private void normalizeExplicitChapterNos(String projectId) {
        List<SourceChapter> chapters = sourceChapterMapper.findByProjectIdOrderByChapterNoAsc(projectId);
        Map<Long, Integer> updates = new LinkedHashMap<>();
        Set<Integer> targetNos = new LinkedHashSet<>();
        for (SourceChapter chapter : chapters) {
            Integer parsedChapterNo = chapterSplitter.parseChapterNo(chapter.getTitle());
            if (parsedChapterNo == null) {
                continue;
            }
            if (!targetNos.add(parsedChapterNo)) {
                return;
            }
            updates.put(chapter.getId(), parsedChapterNo);
        }

        if (updates.isEmpty()) {
            return;
        }
        for (SourceChapter chapter : chapters) {
            Integer targetNo = updates.get(chapter.getId());
            if (targetNo != null && !targetNo.equals(chapter.getChapterNo())) {
                sourceChapterMapper.updateChapterNo(chapter.getId(), targetNo);
            }
        }
    }

    private String readSourceFileContent(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("上传的小说文件不能为空");
        }
        if (file.getSize() > MAX_SOURCE_FILE_SIZE) {
            throw new IllegalArgumentException("小说文件不能超过 2MB");
        }

        String filename = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        String lowerFilename = filename.toLowerCase(Locale.ROOT);
        if (!lowerFilename.endsWith(".txt") && !lowerFilename.endsWith(".md")) {
            throw new IllegalArgumentException("仅支持上传 .txt 或 .md 小说文件");
        }

        try {
            byte[] bytes = file.getBytes();
            String content = decodeText(bytes);
            if (content.isBlank()) {
                throw new IllegalArgumentException("小说文件内容不能为空");
            }
            return content;
        } catch (IOException ex) {
            throw new IllegalArgumentException("读取小说文件失败", ex);
        }
    }

    private String decodeText(byte[] bytes) {
        try {
            return decodeStrict(bytes, StandardCharsets.UTF_8);
        } catch (CharacterCodingException ex) {
            try {
                return decodeStrict(bytes, GB18030);
            } catch (CharacterCodingException nestedEx) {
                throw new IllegalArgumentException("小说文件编码不支持，请使用 UTF-8 或 GB18030 编码");
            }
        }
    }

    private String decodeStrict(byte[] bytes, Charset charset) throws CharacterCodingException {
        return charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
    }

    @Transactional
    public List<ChapterResponse> listChapters(String projectId) {
        projectService.getProjectEntity(projectId);
        normalizeExplicitChapterNos(projectId);
        return sourceChapterMapper.findByProjectIdOrderByChapterNoAsc(projectId).stream()
                .map(ChapterResponse::from)
                .toList();
    }
}
