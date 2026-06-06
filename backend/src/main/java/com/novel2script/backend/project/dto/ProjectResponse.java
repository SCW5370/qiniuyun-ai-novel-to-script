package com.novel2script.backend.project.dto;

import com.novel2script.backend.project.Project;
import com.novel2script.backend.project.ProjectStatus;

import java.time.LocalDateTime;

public class ProjectResponse {

    private String projectId;

    private String title;

    private ProjectStatus status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    public ProjectResponse(String projectId, String title, ProjectStatus status, LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.projectId = projectId;
        this.title = title;
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static ProjectResponse from(Project project) {
        return new ProjectResponse(
                project.getProjectId(),
                project.getTitle(),
                project.getStatus(),
                project.getCreatedAt(),
                project.getUpdatedAt()
        );
    }

    public String getProjectId() {
        return projectId;
    }

    public String getTitle() {
        return title;
    }

    public ProjectStatus getStatus() {
        return status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
