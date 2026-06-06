package com.novel2script.backend.project;

import com.novel2script.backend.project.dto.CreateProjectRequest;
import com.novel2script.backend.project.dto.ProjectResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

@Service
public class ProjectService {

    private static final DateTimeFormatter PROJECT_UID_DATE_FORMAT = DateTimeFormatter.BASIC_ISO_DATE;

    private final ProjectMapper projectMapper;

    public ProjectService(ProjectMapper projectMapper) {
        this.projectMapper = projectMapper;
    }

    @Transactional
    public ProjectResponse createProject(CreateProjectRequest request) {
        Project project = new Project(request.getTitle().trim());
        projectMapper.insert(project);
        String projectUid = buildProjectUid(project.getId());
        projectMapper.updateProjectUid(project.getId(), projectUid);
        return ProjectResponse.from(getProjectEntity(project.getId()));
    }

    @Transactional(readOnly = true)
    public Project getProjectEntity(Long projectId) {
        return projectMapper.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("项目不存在: " + projectId));
    }

    @Transactional(readOnly = true)
    public Project getProjectEntity(String projectKey) {
        if (projectKey == null || projectKey.isBlank()) {
            throw new IllegalArgumentException("项目 ID 不能为空");
        }
        if (projectKey.chars().allMatch(Character::isDigit)) {
            return getProjectEntity(Long.valueOf(projectKey));
        }
        return projectMapper.findByProjectUid(projectKey)
                .orElseThrow(() -> new IllegalArgumentException("项目不存在: " + projectKey));
    }

    @Transactional(readOnly = true)
    public ProjectResponse getProject(String projectKey) {
        return ProjectResponse.from(getProjectEntity(projectKey));
    }

    @Transactional
    public void updateStatus(Long projectId, ProjectStatus status) {
        int affectedRows = projectMapper.updateStatus(projectId, status);
        if (affectedRows == 0) {
            throw new IllegalArgumentException("项目不存在: " + projectId);
        }
    }

    private String buildProjectUid(Long id) {
        String date = LocalDate.now().format(PROJECT_UID_DATE_FORMAT);
        return "proj_" + date + "_" + String.format("%03d", id);
    }
}
