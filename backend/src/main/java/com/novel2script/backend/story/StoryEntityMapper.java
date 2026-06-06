package com.novel2script.backend.story;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 故事实体表的数据访问层。
 */
@Mapper
public interface StoryEntityMapper {

    int insertBatch(@Param("entities") List<StoryEntity> entities);

    List<StoryEntity> findByProjectId(@Param("projectId") Long projectId);

    int deleteByProjectId(@Param("projectId") Long projectId);
}
