package com.novel2script.backend.scene;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Optional;

@Mapper
public interface SceneScriptMapper {

    int insert(SceneScript sceneScript);

    List<SceneScript> findByProjectIdOrderBySeqNoAsc(@Param("projectId") String projectId);

    Optional<SceneScript> findByProjectIdAndSceneId(@Param("projectId") String projectId, @Param("sceneId") String sceneId);

    int updateSeqNoByProjectIdAndSceneId(
            @Param("projectId") String projectId,
            @Param("sceneId") String sceneId,
            @Param("seqNo") Integer seqNo
    );

    int deleteByProjectIdAndSceneId(@Param("projectId") String projectId, @Param("sceneId") String sceneId);

    int deleteByProjectId(@Param("projectId") String projectId);
}
