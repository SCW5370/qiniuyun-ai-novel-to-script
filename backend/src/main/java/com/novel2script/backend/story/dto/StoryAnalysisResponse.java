package com.novel2script.backend.story.dto;

import java.util.List;

public class StoryAnalysisResponse {

    private String projectId;

    private String status;

    private Integer entityCount;

    private Integer eventCount;

    private List<StoryEntityResponse> entities;

    private List<StoryEventResponse> events;

    public StoryAnalysisResponse(String projectId, List<StoryEntityResponse> entities, List<StoryEventResponse> events) {
        this.projectId = projectId;
        this.status = "ENTITY_READY";
        this.entityCount = entities.size();
        this.eventCount = events.size();
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

    public List<StoryEntityResponse> getEntities() {
        return entities;
    }

    public List<StoryEventResponse> getEvents() {
        return events;
    }
}
