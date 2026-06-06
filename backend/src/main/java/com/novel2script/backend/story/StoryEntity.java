package com.novel2script.backend.story;

import java.time.LocalDateTime;

/**
 * 角色、地点等故事实体，对应 story_entities 表。
 */
public class StoryEntity {

    private Long id;

    private Long projectId;

    private String entityId;

    private StoryEntityType entityType;

    private String canonicalName;

    private String aliasesJson;

    private String profile;

    private String sourceRefsJson;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    public StoryEntity() {
    }

    public StoryEntity(Long projectId, String entityId, StoryEntityType entityType, String canonicalName, String aliasesJson, String profile, String sourceRefsJson) {
        this.projectId = projectId;
        this.entityId = entityId;
        this.entityType = entityType;
        this.canonicalName = canonicalName;
        this.aliasesJson = aliasesJson;
        this.profile = profile;
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

    public String getEntityId() {
        return entityId;
    }

    public void setEntityId(String entityId) {
        this.entityId = entityId;
    }

    public StoryEntityType getEntityType() {
        return entityType;
    }

    public void setEntityType(StoryEntityType entityType) {
        this.entityType = entityType;
    }

    public String getCanonicalName() {
        return canonicalName;
    }

    public void setCanonicalName(String canonicalName) {
        this.canonicalName = canonicalName;
    }

    public String getAliasesJson() {
        return aliasesJson;
    }

    public void setAliasesJson(String aliasesJson) {
        this.aliasesJson = aliasesJson;
    }

    public String getProfile() {
        return profile;
    }

    public void setProfile(String profile) {
        this.profile = profile;
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
