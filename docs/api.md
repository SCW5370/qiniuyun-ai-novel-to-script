# 后端接口规范

本文档描述当前后端已经实现的 A 线基础接口，覆盖项目管理、小说文本提交、故事资产分析、场景大纲和 Scene 级剧本查询。

## 基础信息

- 服务地址：`http://localhost:8080`
- 数据格式：`application/json`
- 字符编码：`UTF-8`
- 当前接口前缀：`/api`
- AI 配置从本地 `.env` 读取：`AI_API_KEY`、`AI_BASE_URL`、`AI_MODEL_ID`

## 通用响应结构

所有接口统一返回以下结构：

```json
{
  "success": true,
  "message": "ok",
  "data": {}
}
```

字段说明：

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `success` | boolean | 请求是否成功 |
| `message` | string | 响应消息；成功时通常为 `ok` |
| `data` | object / array / null | 业务数据；失败时通常为 `null` |

错误响应示例：

```json
{
  "success": false,
  "message": "项目不存在: proj_20260606_000001",
  "data": null
}
```

## 项目状态

| 状态 | 说明 |
| --- | --- |
| `CREATED` | 项目已创建，尚未提交小说文本 |
| `SOURCE_SUBMITTED` | 已提交小说文本，正在处理原文 |
| `CHAPTERED` | 已完成章节切分 |
| `ENTITY_READY` | 已完成角色、地点和事件等中间资产分析 |
| `OUTLINED` | 已生成场景大纲 |
| `SCENE_GENERATING` | 正在生成 Scene 级剧本 |
| `COMPLETED` | 已完成剧本生成与导出 |
| `FAILED` | 处理失败 |

## 创建项目

```http
POST /api/projects
```

请求体：

```json
{
  "title": "雨夜旧书店"
}
```

请求字段：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `title` | string | 是 | 项目标题，最长 120 个字符 |

成功响应：

```json
{
  "success": true,
  "message": "ok",
  "data": {
    "projectId": "proj_20260606_000001",
    "title": "雨夜旧书店",
    "status": "CREATED",
    "createdAt": "2026-06-06T00:00:00",
    "updatedAt": "2026-06-06T00:00:00"
  }
}
```

## 查询项目列表

```http
GET /api/projects?keyword=雨夜
```

查询参数：

| 参数 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `keyword` | string | 否 | 按项目标题或 `projectId` 模糊搜索 |

处理规则：

- 不传 `keyword` 时返回全部项目。
- 当前没有账号系统，默认返回当前数据库中的全部项目。
- 返回顺序按 `updatedAt`、`createdAt` 倒序，方便前端默认选择最近项目。

成功响应：

```json
{
  "success": true,
  "message": "ok",
  "data": [
    {
      "projectId": "proj_20260606_000001",
      "title": "雨夜旧书店",
      "status": "CHAPTERED",
      "createdAt": "2026-06-06T00:00:00",
      "updatedAt": "2026-06-06T00:01:00"
    }
  ]
}
```

## 查询项目

```http
GET /api/projects/{projectId}
```

路径参数：

| 参数 | 类型 | 说明 |
| --- | --- | --- |
| `projectId` | string | 项目 ID，格式 `proj_YYYYMMDD_xxxxxx` |

成功响应：

```json
{
  "success": true,
  "message": "ok",
  "data": {
    "projectId": "proj_20260606_000001",
    "title": "雨夜旧书店",
    "status": "CHAPTERED",
    "createdAt": "2026-06-06T00:00:00",
    "updatedAt": "2026-06-06T00:01:00"
  }
}
```

## 提交小说文本

```http
POST /api/projects/{projectId}/source
```

路径参数：

| 参数 | 类型 | 说明 |
| --- | --- | --- |
| `projectId` | string | 项目 ID，格式 `proj_YYYYMMDD_xxxxxx` |

请求体：

```json
{
  "content": "第一章 雨夜\n林舟推门而入。\n\n第二章 旧书店\n老板抬起头。"
}
```

请求字段：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `content` | string | 是 | 小说正文，不能为空 |

处理规则：

- 提交后会清洗文本换行和多余空行。
- 支持识别 `第一章`、`第1章`、`Chapter 1`、`CHAPTER 1`、`1. 标题`、`1、标题` 等章节标题。
- 如果没有识别到章节标题，会把全文作为第 1 章。
- 重新提交小说文本时，会删除该项目旧章节并保存新章节。

成功响应：

```json
{
  "success": true,
  "message": "ok",
  "data": [
    {
      "id": 1,
      "chapterNo": 1,
      "title": "第一章 雨夜",
      "cleanText": "第一章 雨夜\n林舟推门而入。",
      "summary": null,
      "createdAt": "2026-06-06T00:01:00"
    },
    {
      "id": 2,
      "chapterNo": 2,
      "title": "第二章 旧书店",
      "cleanText": "第二章 旧书店\n老板抬起头。",
      "summary": null,
      "createdAt": "2026-06-06T00:01:00"
    }
  ]
}
```

## 上传小说文件

```http
POST /api/projects/{projectId}/source/upload
Content-Type: multipart/form-data
```

路径参数：

| 参数 | 类型 | 说明 |
| --- | --- | --- |
| `projectId` | string | 项目 ID，格式 `proj_YYYYMMDD_xxxxxx` |

表单字段：

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `file` | file | 是 | 小说文件，仅支持 `.txt` 或 `.md` |

处理规则：

- 后端读取上传文件内容后，复用 `POST /api/projects/{projectId}/source` 的章节切分和入库逻辑。
- 文件大小不能超过 2MB。
- 编码优先按 UTF-8 解析，失败时使用 GB18030 兜底。
- 上传成功后会替换该项目旧章节，并清空旧的实体、事件、场景大纲和 Scene 结果。

成功响应与“提交小说文本”一致，返回切分后的章节列表。

## 追加小说章节

```http
POST /api/projects/{projectId}/chapters/append
```

支持两种请求形式：

JSON 文本：

```json
{
  "content": "第三章 新雨\n林舟再次回到旧书店。"
}
```

文件上传：

```http
Content-Type: multipart/form-data
```

| 字段 | 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `content` | string | JSON 请求必填 | 要追加的小说正文 |
| `file` | file | multipart 请求必填 | 要追加的 `.txt` 或 `.md` 小说文件 |

处理规则：

- 追加接口不会删除旧章节、旧实体、旧事件、旧场景大纲或旧 Scene 剧本。
- 后端会先切分本次提交内容，再从当前项目最大 `chapterNo` 后继续编号。
- 如果当前项目已有 3 章，本次切分出 2 章，则新章节编号为 4、5。
- 当前版本只负责追加章节入库；新增章节的增量分析和增量场景生成会在后续接口中实现。

成功响应与“提交小说文本”一致，返回追加后的完整章节列表。

## 查询章节列表

```http
GET /api/projects/{projectId}/chapters
```

路径参数：

| 参数 | 类型 | 说明 |
| --- | --- | --- |
| `projectId` | string | 项目 ID，格式 `proj_YYYYMMDD_xxxxxx` |

成功响应：

```json
{
  "success": true,
  "message": "ok",
  "data": [
    {
      "id": 1,
      "chapterNo": 1,
      "title": "第一章 雨夜",
      "cleanText": "第一章 雨夜\n林舟推门而入。",
      "summary": null,
      "createdAt": "2026-06-06T00:01:00"
    }
  ]
}
```

## 生成章节摘要

```http
POST /api/projects/{projectId}/chapters/summarize
```

路径参数：

| 参数 | 类型 | 说明 |
| --- | --- | --- |
| `projectId` | string | 项目 ID，格式 `proj_YYYYMMDD_xxxxxx` |

处理规则：

- 该接口依赖项目已经完成章节切分。
- 当前默认使用本地规则版摘要生成器；后续接入 AI 后，接口和响应结构保持不变。
- 摘要会写入 `source_chapters.summary` 字段。
- 空章节列表会返回业务错误。

成功响应：

```json
{
  "success": true,
  "message": "ok",
  "data": [
    {
      "id": 1,
      "chapterNo": 1,
      "title": "第一章 雨夜",
      "cleanText": "第一章 雨夜\n林舟推门而入。",
      "summary": "林舟推门而入。",
      "createdAt": "2026-06-06T00:01:00"
    }
  ]
}
```

## 分析故事中间资产

```http
POST /api/projects/{projectId}/analyze
```

路径参数：

| 参数 | 类型 | 说明 |
| --- | --- | --- |
| `projectId` | string | 项目 ID，格式 `proj_YYYYMMDD_xxxxxx` |

处理规则：

- 该接口依赖项目已经完成章节切分。
- 当前实现为 AI 优先、规则兜底。
- 执行时会删除该项目旧的实体、事件、场景大纲和 Scene 剧本，并写入新的故事资产结果。
- 成功后项目状态更新为 `ENTITY_READY`。
- AI 不可用时仍返回基础结构，避免联调中断。
- 响应中的 `aiSuccess`、`fallbackUsed`、`generationMode`、`message` 用于前端展示本次分析是否由 AI 完成。

成功响应：

```json
{
  "success": true,
  "message": "ok",
  "data": {
    "projectId": "proj_20260606_000001",
    "status": "ENTITY_READY",
    "entityCount": 1,
    "eventCount": 1,
    "generationMode": "AI",
    "aiSuccess": true,
    "fallbackUsed": false,
    "message": "故事资产由 AI 抽取生成",
    "entities": [
      {
        "entityId": "C001",
        "entityType": "CHARACTER",
        "canonicalName": "林舟",
        "aliases": ["林舟"],
        "profile": "青年调查者，因一本失踪手稿进入旧书店。",
        "sourceRefs": ["ch1"],
        "createdAt": "2026-06-06T00:01:00",
        "updatedAt": "2026-06-06T00:01:00"
      }
    ],
    "events": [
      {
        "eventId": "E001",
        "chapterId": 1,
        "eventOrder": 1,
        "title": "第一章 雨夜",
        "summary": "林舟推门而入。",
        "sourceRefs": ["ch1"],
        "createdAt": "2026-06-06T00:01:00",
        "updatedAt": "2026-06-06T00:01:00"
      }
    ]
  }
}
```

## 增量分析新增章节

```http
POST /api/projects/{projectId}/analyze/incremental
```

路径参数：

| 参数 | 类型 | 说明 |
| --- | --- | --- |
| `projectId` | string | 项目 ID，格式 `proj_YYYYMMDD_xxxxxx` |

处理规则：

- 该接口用于章节追加后的增量分析。
- 后端会找出尚未生成 `story_events` 的章节，只分析这些新增章节。
- 旧实体、旧事件、旧场景大纲和旧 Scene 剧本不会被删除。
- 新实体会按实体类型、名称和别名尝试合并到已有实体；无法匹配时才分配新的 `C###` 或 `L###`。
- 新事件会从当前最大 `eventOrder` 和最大 `E###` 后继续追加。
- 如果没有发现待分析的新章节，会返回现有实体和事件，并在 `message` 中说明。

成功响应结构与“分析故事中间资产”一致。`generationMode` 可能为：

| 值 | 说明 |
| --- | --- |
| `INCREMENTAL_AI` | 新增章节由 AI 抽取 |
| `INCREMENTAL_FALLBACK` | AI 失败后使用规则兜底 |
| `INCREMENTAL_NONE` | 没有待增量分析的新章节 |

## 查询故事实体

```http
GET /api/projects/{projectId}/entities
```

成功响应：

```json
{
  "success": true,
  "message": "ok",
  "data": [
    {
      "entityId": "C001",
      "entityType": "CHARACTER",
      "canonicalName": "林舟",
      "aliases": ["林舟"],
        "profile": "青年调查者，因一本失踪手稿进入旧书店。",
      "sourceRefs": ["ch1"],
      "createdAt": "2026-06-06T00:01:00",
      "updatedAt": "2026-06-06T00:01:00"
    }
  ]
}
```

## 查询故事事件

```http
GET /api/projects/{projectId}/story-events
```

说明：

- `GET /api/projects/{projectId}/events` 已保留给 SSE 进度流，故事事件列表使用 `/story-events`。

成功响应：

```json
{
  "success": true,
  "message": "ok",
  "data": [
    {
      "eventId": "E001",
      "chapterId": 1,
      "eventOrder": 1,
      "title": "第一章 雨夜",
      "summary": "林舟推门而入。",
      "sourceRefs": ["ch1"],
      "createdAt": "2026-06-06T00:01:00",
      "updatedAt": "2026-06-06T00:01:00"
    }
  ]
}
```

## 查询场景大纲

```http
GET /api/projects/{projectId}/outline
```

说明：

- 依赖已执行 `POST /api/projects/{projectId}/analyze`。
- 首次查询时会生成并保存真实场景大纲。
- 成功后项目状态更新为 `OUTLINED`。

成功响应：

```json
{
  "success": true,
  "message": "ok",
  "data": [
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
        "character": "展示林舟的急迫与困境"
      },
      "characters": ["C001", "C002"],
      "sourceRefs": ["ch1"],
      "status": "READY"
    }
  ]
}
```

## 查询 Scene 详情

```http
GET /api/projects/{projectId}/scenes/{sceneId}
```

说明：

- 依赖已生成场景大纲。
- 首次查询某个 `sceneId` 时会生成并保存该 Scene 的动作和对白。

成功响应：

```json
{
  "success": true,
  "message": "ok",
  "data": {
    "sceneId": "S001",
    "seqNo": 1,
    "title": "雨夜闯店",
    "action": ["林舟推门而入，雨水顺着衣角滴在旧木地板上。"],
    "dialogue": [
      {
        "characterId": "C001",
        "line": "老板，还营业吗？"
      }
    ],
    "sourceRefs": ["ch1"],
    "validationStatus": "PASSED",
    "warnings": []
  }
}
```

## 查询已生成 Scene 列表

```http
GET /api/projects/{projectId}/scenes
```

说明：

- 只返回已经生成并保存过的 Scene 详情。
- 如果尚未打开过具体 Scene，可能返回空数组。

## 重新生成 Scene

```http
POST /api/projects/{projectId}/scenes/{sceneId}/regenerate
```

## 结构校验

```http
POST /api/projects/{projectId}/validate
```

说明：

- 结构校验用于检查当前项目已生成 Scene 的基础可用性。
- 校验会读取场景大纲和 Scene 详情；如果 Scene 尚未生成，后端会按现有生成逻辑补齐后再校验。
- 当前校验规则包括：动作描写是否为空、对白是否为空、对白角色是否出现在该场景角色列表中、Scene 自身 warnings。
- 校验不会改写小说正文、角色、地点或故事事件。

成功响应：

```json
{
  "success": true,
  "message": "ok",
  "data": {
    "projectId": "proj_20260606_000001",
    "status": "WARNING",
    "items": [
      {
        "sceneId": "S001",
        "level": "warning",
        "field": "dialogue",
        "message": "对白角色 C001 未出现在场景大纲角色列表中"
      }
    ]
  }
}
```

## 导出 YAML

```http
GET /api/projects/{projectId}/export?format=yaml
```

说明：

- 当前只支持 `format=yaml`。
- 导出前会读取场景大纲和 Scene 详情；如果 Scene 尚未生成，后端会按现有生成逻辑补齐后导出。
- 导出成功后项目状态更新为 `COMPLETED`。
- 该接口直接返回 `text/yaml;charset=UTF-8`，不包裹通用 JSON 响应。

成功响应示例：

```yaml
schema_version: "1.0.0"
meta:
  project_id: "proj_20260606_000001"
  title: "雨夜旧书店"
  workflow: "reader-outline-writer-validator"
scenes:
  - scene_id: "S001"
    seq_no: 1
    title: "雨夜闯店"
    action:
      - "林舟推门而入，雨水顺着衣角滴在旧木地板上。"
    dialogue:
      - character_id: "C001"
        line: "老板，还营业吗？"
    source_refs:
      - "ch1"
    validation_status: "PASSED"
```

## 进度事件快照

```http
GET /api/projects/{projectId}/events
```

说明：

- 当前接口使用 SSE 响应格式，但只发送当前项目状态快照后关闭连接。
- 现阶段它不是完整实时进度流；真正的长任务阶段推送将在独立 SSE PR 中实现。
- 故事事件列表接口是 `/story-events`，不要使用 `/events` 查询故事事件。

当前会发送：

- `phase.changed`：当前项目状态对应的阶段。
- `job.completed`：当前快照完成事件，包含 `exportReady`。

## 调试示例

创建项目：

```bash
curl -X POST http://localhost:8080/api/projects ^
  -H "Content-Type: application/json" ^
  -d "{\"title\":\"雨夜旧书店\"}"
```

提交小说文本：

```bash
curl -X POST http://localhost:8080/api/projects/proj_20260606_000001/source ^
  -H "Content-Type: application/json" ^
  -d "{\"content\":\"第一章 雨夜\n林舟推门而入。\n\n第二章 旧书店\n老板抬起头。\"}"
```

查询章节：

```bash
curl http://localhost:8080/api/projects/proj_20260606_000001/chapters
```

分析故事中间资产：

```bash
curl -X POST http://localhost:8080/api/projects/proj_20260606_000001/chapters/summarize
```

```bash
curl -X POST http://localhost:8080/api/projects/proj_20260606_000001/analyze
```

查询故事实体：

```bash
curl http://localhost:8080/api/projects/proj_20260606_000001/entities
```

查询故事事件：

```bash
curl http://localhost:8080/api/projects/proj_20260606_000001/story-events
```

查询场景大纲：

```bash
curl http://localhost:8080/api/projects/proj_20260606_000001/outline
```

查询 Scene 详情：

```bash
curl http://localhost:8080/api/projects/proj_20260606_000001/scenes/S001
```
