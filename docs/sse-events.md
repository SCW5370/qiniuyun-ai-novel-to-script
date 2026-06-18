# SSE Events

本文档定义项目进度事件。当前 `main` 只有一次性状态快照，真正的长任务实时推送将在后续独立 PR 中实现。

## Current Implementation

当前接口：

```http
GET /api/projects/{projectId}/events
```

当前行为：

- 使用 SSE 响应格式。
- 建立连接后读取当前项目状态。
- 发送 `phase.changed`。
- 发送 `job.completed` 快照事件。
- 随后关闭连接。

当前 `phase.changed` 示例：

```json
{
  "projectId": "proj_20260606_001",
  "phase": "entity_ready",
  "message": "已连接项目进度流"
}
```

当前 `job.completed` 示例：

```json
{
  "projectId": "proj_20260606_001",
  "phase": "entity_ready",
  "progress": 100,
  "exportReady": false
}
```

## Target Real-Time Contract

以下事件是后续真实 SSE 进度流的目标契约。实现前端或后端时必须保持事件名和 payload 字段兼容。

## Event Names

- `job.started`
- `phase.changed`
- `assets.batch.ready`
- `outline.ready`
- `scene.done`
- `validation.warn`
- `job.completed`
- `job.failed`

## Event Payload

当前 `main` 的 SSE 实现使用“命名事件 + 直接 payload”：

- 事件名来自 SSE `event:` 字段，例如 `phase.changed`
- `data:` 中直接放该事件的 JSON payload
- 前端通过 `addEventListener("<eventName>", ...)` 读取 `MessageEvent.data`

### `job.started`

```json
{
  "projectId": "proj_20260606_001",
  "jobType": "full_generation",
  "progress": 0,
  "message": "任务已启动"
}
```

### `phase.changed`

```json
{
  "projectId": "proj_20260606_001",
  "phase": "scene_generating",
  "progress": 70,
  "message": "正在生成第 3/8 个场景"
}
```

### `outline.ready`

```json
{
  "projectId": "proj_20260606_001",
  "phase": "scene_generating",
  "sceneCount": 3,
  "batchSceneCount": 1,
  "sceneIds": ["S003"],
  "message": "新一批场景大纲已就绪，正在同步生成内容"
}
```

生产流水线会在每批大纲落库后发送一次 `outline.ready`。`sceneCount` 是当前累计场景数，
`sceneIds` 是本批新增场景。前端收到事件后应增量拉取/合并大纲，不应等待最终批次。

### `assets.batch.ready`

```json
{
  "projectId": "proj_20260606_001",
  "phase": "entity_extracting",
  "chapterNo": 1,
  "entityCount": 4,
  "eventCount": 3,
  "message": "第 1 章故事资产已就绪，正在构建对应场景"
}
```

全量和增量生产都会按章节发布此事件。收到事件后前端应立即刷新实体与事件，
而不是等待全部章节分析完成。

### `scene.done`

```json
{
  "projectId": "proj_20260606_001",
  "sceneId": "S003",
  "progress": 78,
  "validationStatus": "PASSED",
  "message": "场景生成完成"
}
```

### `validation.warn`

```json
{
  "projectId": "proj_20260606_001",
  "sceneId": "S004",
  "field": "dialogue",
  "message": "存在未定义角色"
}
```

`scene.done` 在单个 Scene 内容写库后立即发送；此时其他批次的大纲分析和 Scene 内容生成可能仍在进行。

### `job.completed`

```json
{
  "projectId": "proj_20260606_001",
  "progress": 100,
  "exportReady": true,
  "message": "任务已完成"
}
```

### `job.failed`

```json
{
  "projectId": "proj_20260606_001",
  "phase": "scene_generating",
  "errorCode": "SCENE_GENERATION_FAILED",
  "message": "第 5 个场景生成失败"
}
```
