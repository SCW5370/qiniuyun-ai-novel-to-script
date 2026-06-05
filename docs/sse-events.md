# SSE Events

本文档定义生成过程中的服务端事件。B 线前端工作台只消费本文档中的事件名和数据形状。

## Event Names

- `job.started`
- `phase.changed`
- `outline.ready`
- `scene.done`
- `validation.warn`
- `job.completed`
- `job.failed`

## Event Payload

所有事件都使用：

```json
{
  "event": "phase.changed",
  "data": {}
}
```

### `job.started`

```json
{
  "event": "job.started",
  "data": {
    "projectId": "proj_20260606_001",
    "jobType": "full_generation",
    "progress": 0,
    "message": "任务已启动"
  }
}
```

### `phase.changed`

```json
{
  "event": "phase.changed",
  "data": {
    "projectId": "proj_20260606_001",
    "phase": "scene_generating",
    "progress": 70,
    "message": "正在生成第 3/8 个场景"
  }
}
```

### `outline.ready`

```json
{
  "event": "outline.ready",
  "data": {
    "projectId": "proj_20260606_001",
    "sceneCount": 8,
    "message": "场景大纲已生成"
  }
}
```

### `scene.done`

```json
{
  "event": "scene.done",
  "data": {
    "projectId": "proj_20260606_001",
    "sceneId": "S003",
    "progress": 78,
    "validationStatus": "PASSED",
    "message": "场景生成完成"
  }
}
```

### `validation.warn`

```json
{
  "event": "validation.warn",
  "data": {
    "projectId": "proj_20260606_001",
    "sceneId": "S004",
    "field": "dialogue",
    "message": "存在未定义角色"
  }
}
```

### `job.completed`

```json
{
  "event": "job.completed",
  "data": {
    "projectId": "proj_20260606_001",
    "progress": 100,
    "exportReady": true,
    "message": "任务已完成"
  }
}
```

### `job.failed`

```json
{
  "event": "job.failed",
  "data": {
    "projectId": "proj_20260606_001",
    "phase": "scene_generating",
    "errorCode": "SCENE_GENERATION_FAILED",
    "message": "第 5 个场景生成失败"
  }
}
```
