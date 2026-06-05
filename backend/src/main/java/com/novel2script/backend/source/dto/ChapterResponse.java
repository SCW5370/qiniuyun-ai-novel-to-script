package com.novel2script.backend.source.dto;

import com.novel2script.backend.source.SourceChapter;

import java.time.LocalDateTime;

public class ChapterResponse {

    private Long id;

    private Integer chapterNo;

    private String title;

    private String cleanText;

    private String summary;

    private LocalDateTime createdAt;

    public ChapterResponse(Long id, Integer chapterNo, String title, String cleanText, String summary, LocalDateTime createdAt) {
        this.id = id;
        this.chapterNo = chapterNo;
        this.title = title;
        this.cleanText = cleanText;
        this.summary = summary;
        this.createdAt = createdAt;
    }

    public static ChapterResponse from(SourceChapter chapter) {
        return new ChapterResponse(
                chapter.getId(),
                chapter.getChapterNo(),
                chapter.getTitle(),
                chapter.getCleanText(),
                chapter.getSummary(),
                chapter.getCreatedAt()
        );
    }

    public Long getId() {
        return id;
    }

    public Integer getChapterNo() {
        return chapterNo;
    }

    public String getTitle() {
        return title;
    }

    public String getCleanText() {
        return cleanText;
    }

    public String getSummary() {
        return summary;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
