package com.novel2script.backend.story.dto;

import java.util.List;

public class StoryAnalysisResponse {

    private String projectId;

    private String status;

    private Integer entityCount;

    private Integer eventCount;

    private String generationMode;

    private Boolean aiSuccess;

    private Boolean fallbackUsed;

    private String message;

    private List<StoryEntityResponse> entities;

    private List<StoryEventResponse> events;

    public StoryAnalysisResponse(String projectId, List<StoryEntityResponse> entities, List<StoryEventResponse> events) {
        this(projectId, entities, events, "AI", true, false, "故事资产由 AI 抽取生成");
    }

    public StoryAnalysisResponse(
            String projectId,
            List<StoryEntityResponse> entities,
            List<StoryEventResponse> events,
            String generationMode,
            Boolean aiSuccess,
            Boolean fallbackUsed,
            String message
    ) {
        this.projectId = projectId;
        this.status = "ENTITY_READY";
        this.entityCount = entities.size();
        this.eventCount = events.size();
        this.generationMode = generationMode;
        this.aiSuccess = aiSuccess;
        this.fallbackUsed = fallbackUsed;
        this.message = message;
        this.entities = entities;
        this.events = events;
    }

    public String getProjectId() {
        return projectId;
    }

    public String getStatus() {
        return status;
    }

    public Integer getEntityCount() {
        return entityCount;
    }

    public Integer getEventCount() {
        return eventCount;
    }

    public String getGenerationMode() {
        return generationMode;
    }

    public Boolean getAiSuccess() {
        return aiSuccess;
    }

    public Boolean getFallbackUsed() {
        return fallbackUsed;
    }

    public String getMessage() {
        return message;
    }

    public List<StoryEntityResponse> getEntities() {
        return entities;
    }

    public List<StoryEventResponse> getEvents() {
        return events;
    }
}
