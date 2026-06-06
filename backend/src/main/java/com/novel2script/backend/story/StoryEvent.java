package com.novel2script.backend.story;

import java.time.LocalDateTime;

/**
 * 从章节中抽取出的关键事件，对应 story_events 表。
 */
public class StoryEvent {

    private Long id;

    private Long projectId;

    private String eventId;

    private Long chapterId;

    private Integer eventOrder;

    private String title;

    private String summary;

    private String sourceRefsJson;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    public StoryEvent() {
    }

    public StoryEvent(Long projectId, String eventId, Long chapterId, Integer eventOrder, String title, String summary, String sourceRefsJson) {
        this.projectId = projectId;
        this.eventId = eventId;
        this.chapterId = chapterId;
        this.eventOrder = eventOrder;
        this.title = title;
        this.summary = summary;
        this.sourceRefsJson = sourceRefsJson;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getProjectId() {
        return projectId;
    }

    public void setProjectId(Long projectId) {
        this.projectId = projectId;
    }

    public String getEventId() {
        return eventId;
    }

    public void setEventId(String eventId) {
        this.eventId = eventId;
    }

    public Long getChapterId() {
        return chapterId;
    }

    public void setChapterId(Long chapterId) {
        this.chapterId = chapterId;
    }

    public Integer getEventOrder() {
        return eventOrder;
    }

    public void setEventOrder(Integer eventOrder) {
        this.eventOrder = eventOrder;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getSourceRefsJson() {
        return sourceRefsJson;
    }

    public void setSourceRefsJson(String sourceRefsJson) {
        this.sourceRefsJson = sourceRefsJson;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
