package com.novel2script.backend.project;

import com.novel2script.backend.project.dto.CreateProjectRequest;
import com.novel2script.backend.project.dto.ProjectResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class ProjectService {

    private static final DateTimeFormatter PROJECT_UID_DATE_FORMAT = DateTimeFormatter.BASIC_ISO_DATE;

    private final ProjectMapper projectMapper;

    public ProjectService(ProjectMapper projectMapper) {
        this.projectMapper = projectMapper;
    }

    @Transactional
    public ProjectResponse createProject(CreateProjectRequest request) {
        Project project = new Project(buildProjectId(), request.getTitle().trim());
        projectMapper.insert(project);
        return ProjectResponse.from(getProjectEntity(project.getProjectId()));
    }

    @Transactional(readOnly = true)
    public Project getProjectEntity(String projectId) {
        if (projectId == null || projectId.isBlank()) {
            throw new IllegalArgumentException("项目 ID 不能为空");
        }
        return projectMapper.findByProjectId(projectId)
                .orElseThrow(() -> new IllegalArgumentException("项目不存在: " + projectId));
    }

    @Transactional(readOnly = true)
    public ProjectResponse getProject(String projectId) {
        return ProjectResponse.from(getProjectEntity(projectId));
    }

    @Transactional(readOnly = true)
    public List<ProjectResponse> listProjects(String keyword) {
        String normalizedKeyword = keyword == null ? null : keyword.trim();
        return projectMapper.findAll(normalizedKeyword).stream()
                .map(ProjectResponse::from)
                .toList();
    }

    @Transactional
    public void updateStatus(String projectId, ProjectStatus status) {
        int affectedRows = projectMapper.updateStatus(projectId, status);
        if (affectedRows == 0) {
            throw new IllegalArgumentException("项目不存在: " + projectId);
        }
    }

    private String buildProjectId() {
        String date = LocalDate.now().format(PROJECT_UID_DATE_FORMAT);
        int suffix = ThreadLocalRandom.current().nextInt(1, 1_000_000);
        return "proj_" + date + "_" + String.format("%06d", suffix);
    }
}
