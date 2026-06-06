package com.novel2script.backend.project;

import java.time.LocalDateTime;

/**
 * 小说改编项目对象，对应数据库中的 projects 表。
 */
public class Project {

    private String projectId;

    private String title;

    private ProjectStatus status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    public Project() {
    }

    public Project(String projectId, String title) {
        this.projectId = projectId;
        this.title = title;
        this.status = ProjectStatus.CREATED;
    }

    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public ProjectStatus getStatus() {
        return status;
    }

    public void setStatus(ProjectStatus status) {
        this.status = status;
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
