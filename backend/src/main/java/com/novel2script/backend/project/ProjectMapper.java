package com.novel2script.backend.project;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Optional;

/**
 * 项目表的数据访问层，职责等价于传统三层结构里的 Mapper。
 */
@Mapper
public interface ProjectMapper {

    int insert(Project project);

    Optional<Project> findByProjectId(@Param("projectId") String projectId);

    int updateStatus(@Param("projectId") String projectId, @Param("status") ProjectStatus status);
}
