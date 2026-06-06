# A/B Shared API Contract

本文档定义 A 线与 B 线共享的数据对象和接口契约。B 线在真实接口完成前可直接基于本文档和 `samples/` 目录中的 mock 数据开发。

## Response Envelope

所有接口统一返回以下结构：

```json
{
  "success": true,
  "message": "ok",
  "data": {}
}
```

## Project

```json
{
  "projectId": "proj_20260606_001",
  "title": "雨夜旧书店",
  "status": "OUTLINED",
  "currentPhase": "outlined",
  "progress": 45,
  "createdAt": "2026-06-06T10:00:00+08:00",
  "updatedAt": "2026-06-06T10:10:00+08:00"
}
```

Rules:

- `projectId` 使用字符串主键，格式 `proj_YYYYMMDD_xxx`
- 后端、数据库和前端路由统一只使用字符串 `projectId`
- `status` 枚举：`CREATED` `SOURCE_SUBMITTED` `CHAPTERED` `ENTITY_READY` `OUTLINED` `SCENE_GENERATING` `COMPLETED` `FAILED`
- `currentPhase` 给前端显示阶段文案
- `progress` 固定为 `0-100`

## OutlineScene

```json
{
  "sceneId": "S001",
  "seqNo": 1,
  "title": "雨夜闯店",
  "slugline": {
    "intExt": "INT",
    "locationId": "L001",
    "timeOfDay": "NIGHT"
  },
  "purpose": {
    "plot": "建立主角与旧书店的首次连接",
    "character": "展示林舟的狼狈和急迫"
  },
  "characters": ["C001", "C002"],
  "sourceRefs": ["ch1:p7-p15"],
  "status": "READY"
}
```

Rules:

- `sceneId` 使用 `S001` 递增格式
- `seqNo` 为唯一排序依据
- `status` 枚举：`READY` `GENERATING` `DONE` `WARNING` `FAILED`

## SceneScript

```json
{
  "sceneId": "S001",
  "seqNo": 1,
  "title": "雨夜闯店",
  "action": [
    "林舟推门而入，浑身湿透，门铃一响。"
  ],
  "dialogue": [
    {
      "characterId": "C001",
      "line": "老板，还营业吗？"
    },
    {
      "characterId": "C002",
      "line": "看你找什么。"
    }
  ],
  "sourceRefs": ["ch1:p7-p15"],
  "validationStatus": "PASSED",
  "warnings": []
}
```

Rules:

- `action` 始终为字符串数组
- `dialogue` 始终为对象数组，不混纯文本
- `validationStatus` 枚举：`PASSED` `WARNING` `FAILED`
- `warnings` 无内容时返回空数组

## StoryEntity

```json
{
  "entityId": "C001",
  "entityType": "CHARACTER",
  "canonicalName": "林舟",
  "aliases": ["林舟", "阿舟"],
  "profile": "角色小传或地点说明",
  "sourceRefs": ["ch1"],
  "createdAt": "2026-06-06T10:00:00+08:00",
  "updatedAt": "2026-06-06T10:00:00+08:00"
}
```

Rules:

- `entityId` 角色使用 `C001` 递增格式，地点使用 `L001` 递增格式
- `entityType` 枚举：`CHARACTER` `LOCATION`
- `aliases` 和 `sourceRefs` 无内容时返回空数组
- 当前 A 线首版为规则抽取结果，后续可替换为 LLM 抽取，但接口字段不变

## StoryEvent

```json
{
  "eventId": "E001",
  "chapterId": 1,
  "eventOrder": 1,
  "title": "第一章 雨夜",
  "summary": "林舟推门而入。",
  "sourceRefs": ["ch1"],
  "createdAt": "2026-06-06T10:00:00+08:00",
  "updatedAt": "2026-06-06T10:00:00+08:00"
}
```

Rules:

- `eventId` 使用 `E001` 递增格式
- `eventOrder` 为唯一排序依据
- 故事事件接口不得占用 `GET /api/projects/{projectId}/events`，该路径保留给 SSE

## StoryAnalysisResult

```json
{
  "projectId": "proj_20260606_001",
  "status": "ENTITY_READY",
  "entityCount": 2,
  "eventCount": 3,
  "entities": [],
  "events": []
}
```

Rules:

- `POST /api/projects/{projectId}/analyze` 返回该结构
- `entities` 使用 `StoryEntity` 数组结构
- `events` 使用 `StoryEvent` 数组结构
- 空数组返回 `[]`，不返回 `null`

## ValidationReport

```json
{
  "projectId": "proj_20260606_001",
  "status": "WARNING",
  "items": [
    {
      "sceneId": "S002",
      "level": "warning",
      "field": "dialogue",
      "message": "角色 C003 未在 character_bible 中定义"
    }
  ]
}
```

Rules:

- `status` 枚举：`PASSED` `WARNING` `FAILED`
- `level` 枚举：`warning` `error`

## API Surface

- `POST /api/projects`
- `GET /api/projects`
- `POST /api/projects/{projectId}/analyze`
- `GET /api/projects/{projectId}`
- `GET /api/projects/{projectId}/chapters`
- `POST /api/projects/{projectId}/chapters/summarize`
- `GET /api/projects/{projectId}/entities`
- `GET /api/projects/{projectId}/story-events`
- `GET /api/projects/{projectId}/outline`
- `GET /api/projects/{projectId}/scenes`
- `GET /api/projects/{projectId}/scenes/{sceneId}`
- `POST /api/projects/{projectId}/scenes/{sceneId}/regenerate`
- `POST /api/projects/{projectId}/validate`
- `GET /api/projects/{projectId}/export?format=yaml`
- `GET /api/projects/{projectId}/events`

## Current Integration Status

- 已在 `main` 真实接入：`POST /api/projects`、`GET /api/projects`、`GET /api/projects/{projectId}`、`POST /api/projects/{projectId}/source`、`GET /api/projects/{projectId}/chapters`、`POST /api/projects/{projectId}/analyze`、`GET /api/projects/{projectId}/entities`、`GET /api/projects/{projectId}/story-events`
- 已在 `main` 部分接入：前端已接入项目创建、项目列表、正文提交、章节列表、故事资产分析、角色地点面板、故事事件面板
- A 线进行中：`POST /api/projects/{projectId}/chapters/summarize`、`GET /api/projects/{projectId}/outline`、`GET /api/projects/{projectId}/scenes`、`GET /api/projects/{projectId}/scenes/{sceneId}`、`POST /api/projects/{projectId}/validate`、`GET /api/projects/{projectId}/export?format=yaml`、`GET /api/projects/{projectId}/events`
- 当前仍使用 mock 的区域：`outline`、`scene detail`、`validation`、`yaml export preview`、`events`
- 当前阶段策略：真实接项目管理与故事中间资产，场景与导出继续使用 mock，避免阻塞 A/B 并行开发

## Compatibility Rules

- 字段统一使用小驼峰：`projectId`、`sceneId`、`sourceRefs`
- URL 中的 `{projectId}` 只使用字符串项目 ID
- 时间统一 ISO 8601，时区为 `+08:00`
- 空列表返回 `[]`，不返回 `null`
- 契约一旦进入 `main`，改名必须先更新文档和 mock
