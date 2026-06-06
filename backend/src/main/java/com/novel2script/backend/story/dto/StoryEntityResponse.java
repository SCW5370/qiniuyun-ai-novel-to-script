package com.novel2script.backend.story.dto;

import com.novel2script.backend.story.StoryEntity;
import com.novel2script.backend.story.StoryEntityType;

import java.time.LocalDateTime;
import java.util.List;

public class StoryEntityResponse {

    private String entityId;

    private StoryEntityType entityType;

    private String canonicalName;

    private List<String> aliases;

    private String profile;

    private List<String> sourceRefs;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    public StoryEntityResponse(
            String entityId,
            StoryEntityType entityType,
            String canonicalName,
            List<String> aliases,
            String profile,
            List<String> sourceRefs,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
        this.entityId = entityId;
        this.entityType = entityType;
        this.canonicalName = canonicalName;
        this.aliases = aliases;
        this.profile = profile;
        this.sourceRefs = sourceRefs;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static StoryEntityResponse from(StoryEntity entity, List<String> aliases, List<String> sourceRefs) {
        return new StoryEntityResponse(
                entity.getEntityId(),
                entity.getEntityType(),
                entity.getCanonicalName(),
                aliases,
                entity.getProfile(),
                sourceRefs,
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    public String getEntityId() {
        return entityId;
    }

    public StoryEntityType getEntityType() {
        return entityType;
    }

    public String getCanonicalName() {
        return canonicalName;
    }

    public List<String> getAliases() {
        return aliases;
    }

    public String getProfile() {
        return profile;
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
