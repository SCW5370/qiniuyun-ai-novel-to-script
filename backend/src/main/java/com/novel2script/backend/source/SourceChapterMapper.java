package com.novel2script.backend.source;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 小说章节表的数据访问层。
 */
@Mapper
public interface SourceChapterMapper {

    int insertBatch(@Param("chapters") List<SourceChapter> chapters);

    Integer findMaxChapterNoByProjectId(@Param("projectId") String projectId);

    List<SourceChapter> findByProjectIdOrderByChapterNoAsc(@Param("projectId") String projectId);

    int updateChapterNo(@Param("id") Long id, @Param("chapterNo") Integer chapterNo);

    int updateSummary(@Param("id") Long id, @Param("summary") String summary);

    int deleteByProjectId(@Param("projectId") String projectId);
}
