package com.novel2script.backend.source;

import com.novel2script.backend.project.Project;

import java.time.LocalDateTime;

/**
 * 小说章节对象，对应数据库中的 source_chapters 表。
 */
public class SourceChapter {

    private Long id;

    private Project project;

    private String projectId;

    private Integer chapterNo;

    private String title;

    private String rawText;

    private String cleanText;

    private String summary;

    private LocalDateTime createdAt;

    public SourceChapter() {
    }

    public SourceChapter(Project project, Integer chapterNo, String title, String rawText, String cleanText) {
        this.project = project;
        this.projectId = project.getProjectId();
        this.chapterNo = chapterNo;
        this.title = title;
        this.rawText = rawText;
        this.cleanText = cleanText;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Project getProject() {
        return project;
    }

    public void setProject(Project project) {
        this.project = project;
    }

    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }

    public Integer getChapterNo() {
        return chapterNo;
    }

    public void setChapterNo(Integer chapterNo) {
        this.chapterNo = chapterNo;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getRawText() {
        return rawText;
    }

    public void setRawText(String rawText) {
        this.rawText = rawText;
    }

    public String getCleanText() {
        return cleanText;
    }

    public void setCleanText(String cleanText) {
        this.cleanText = cleanText;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }
}
