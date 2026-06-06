# Development Worklog

## Current Decisions

- 共享契约以 `docs/api-contract.md` 为准
- SSE 事件以 `docs/sse-events.md` 为准
- `projectId` 使用 `proj_YYYYMMDD_xxx` 格式
- 数据库、后端接口和前端路由统一只使用字符串 `projectId`，不再使用数字项目 ID
- `sceneId` 使用 `S001` 递增格式
- 空数组统一返回 `[]`

## A Line Status

- 已完成项目创建接口
- 已完成原文提交和章节切分接口
- 已完成基础后端工程骨架
- 已完成规则版角色/地点/事件中间资产接口
- 当前真实可用接口：`GET /api/projects/{projectId}`、`GET /api/projects/{projectId}/chapters`
- 当前 A 线新增接口：`POST /api/projects/{projectId}/analyze`、`GET /api/projects/{projectId}/entities`、`GET /api/projects/{projectId}/story-events`

## B Line Status

- 已完成前端工作台骨架
- 已完成 mock 场景工作台
- 已完成真实项目引导流
- 已完成真实章节面板接入
- 当前工作台状态：`真实项目/章节 + mock 场景/校验/YAML`

## Next A Line Dependencies

- 将规则抽取替换为 LLM 章节摘要、角色归一、地点表抽取
- 为 B 线场景大纲生成提供稳定的 `entities` 和 `story-events`
- `GET /api/projects/{projectId}/events` 保留给 SSE，不用于故事事件列表

## Next B Line Tasks

- 用真实 `outline` 替换场景大纲 mock
- 用真实 `scene detail` 替换 Scene 详情和 YAML 预览 mock
- 用真实 `events` 替换当前阶段进度静态状态
- 接入真实 `validate` 和导出交互
- 保持 mock 回退能力，避免 A 线未完成时阻塞演示

## Breaking Changes

- 暂无
