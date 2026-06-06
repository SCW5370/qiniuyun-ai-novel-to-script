package com.novel2script.backend.story.dto;

import java.util.List;

public class StoryAnalysisResponse {

    private Long projectId;

    private List<StoryEntityResponse> entities;

    private List<StoryEventResponse> events;

    public StoryAnalysisResponse(Long projectId, List<StoryEntityResponse> entities, List<StoryEventResponse> events) {
        this.projectId = projectId;
        this.entities = entities;
        this.events = events;
    }

    public Long getProjectId() {
        return projectId;
    }

    public List<StoryEntityResponse> getEntities() {
        return entities;
    }

    public List<StoryEventResponse> getEvents() {
        return events;
    }
}
