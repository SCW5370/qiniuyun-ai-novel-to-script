# 后端接口规范

本文档描述当前后端已经实现的 A 线基础接口，覆盖项目创建、项目查询、小说文本提交和章节查询。

## 基础信息

- 服务地址：`http://localhost:8080`
- 数据格式：`application/json`
- 字符编码：`UTF-8`
- 当前接口前缀：`/api`

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
- 首版使用规则抽取角色、地点和章节事件，后续可替换为 LLM 抽取。
- 执行时会删除该项目旧的实体和事件分析结果，并写入新的结果。
- 成功后项目状态更新为 `ENTITY_READY`。

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
    "entities": [
      {
        "entityId": "C001",
        "entityType": "CHARACTER",
        "canonicalName": "林舟",
        "aliases": ["林舟"],
        "profile": "规则抽取的角色候选，后续可由 AI 补充人物小传、目标和说话风格。",
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
      "profile": "规则抽取的角色候选，后续可由 AI 补充人物小传、目标和说话风格。",
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
