package com.novel2script.backend.story.dto;

import com.novel2script.backend.story.StoryEvent;

import java.time.LocalDateTime;
import java.util.List;

public class StoryEventResponse {

    private String eventId;

    private Long chapterId;

    private Integer eventOrder;

    private String title;

    private String summary;

    private List<String> sourceRefs;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    public StoryEventResponse(
            String eventId,
            Long chapterId,
            Integer eventOrder,
            String title,
            String summary,
            List<String> sourceRefs,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
        this.eventId = eventId;
        this.chapterId = chapterId;
        this.eventOrder = eventOrder;
        this.title = title;
        this.summary = summary;
        this.sourceRefs = sourceRefs;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static StoryEventResponse from(StoryEvent event, List<String> sourceRefs) {
        return new StoryEventResponse(
                event.getEventId(),
                event.getChapterId(),
                event.getEventOrder(),
                event.getTitle(),
                event.getSummary(),
                sourceRefs,
                event.getCreatedAt(),
                event.getUpdatedAt()
        );
    }

    public String getEventId() {
        return eventId;
    }

    public Long getChapterId() {
        return chapterId;
    }

    public Integer getEventOrder() {
        return eventOrder;
    }

    public String getTitle() {
        return title;
    }

    public String getSummary() {
        return summary;
    }

    public List<String> getSourceRefs() {
        return sourceRefs;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
