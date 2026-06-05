package com.novel2script.backend.project;

import com.novel2script.backend.project.dto.CreateProjectRequest;
import com.novel2script.backend.project.dto.ProjectResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProjectService {

    private final ProjectMapper projectMapper;

    public ProjectService(ProjectMapper projectMapper) {
        this.projectMapper = projectMapper;
    }

    @Transactional
    public ProjectResponse createProject(CreateProjectRequest request) {
        Project project = new Project(request.getTitle().trim());
        projectMapper.insert(project);
        return ProjectResponse.from(getProjectEntity(project.getId()));
    }

    @Transactional(readOnly = true)
    public Project getProjectEntity(Long projectId) {
        return projectMapper.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("项目不存在: " + projectId));
    }

    @Transactional(readOnly = true)
    public ProjectResponse getProject(Long projectId) {
        return ProjectResponse.from(getProjectEntity(projectId));
    }

    @Transactional
    public void updateStatus(Long projectId, ProjectStatus status) {
        int affectedRows = projectMapper.updateStatus(projectId, status);
        if (affectedRows == 0) {
            throw new IllegalArgumentException("项目不存在: " + projectId);
        }
    }
}
