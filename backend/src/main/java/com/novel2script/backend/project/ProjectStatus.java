package com.novel2script.backend.project;

/**
 * 项目状态，和 docs/api-contract.md 中的状态枚举保持一致。
 */
public enum ProjectStatus {
    CREATED,
    SOURCE_SUBMITTED,
    CHAPTERED,
    ENTITY_READY,
    OUTLINED,
    SCENE_GENERATING,
    COMPLETED,
    FAILED
}
