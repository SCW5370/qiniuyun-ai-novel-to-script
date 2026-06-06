package com.novel2script.backend.story;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 故事事件表的数据访问层。
 */
@Mapper
public interface StoryEventMapper {

    int insertBatch(@Param("events") List<StoryEvent> events);

    List<StoryEvent> findByProjectIdOrderByEventOrderAsc(@Param("projectId") Long projectId);

    int deleteByProjectId(@Param("projectId") Long projectId);
}
