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
- 已完成项目列表查询接口
- 已完成原文提交和章节切分接口
- 已完成章节摘要字段与摘要生成接口
- 已完成基础后端工程骨架
- 已完成 AI 优先、规则兜底的章节摘要与角色/地点/事件中间资产生成
- 已完成 AI 优先、规则兜底的场景大纲和 Scene 详情接口
- 已完成本地前后端跨域配置
- 当前真实可用接口：`POST /api/projects`、`GET /api/projects`、`GET /api/projects/{projectId}`、`POST /api/projects/{projectId}/source`、`GET /api/projects/{projectId}/chapters`
- 当前 A 线新增接口：`POST /api/projects/{projectId}/chapters/summarize`、`POST /api/projects/{projectId}/analyze`、`GET /api/projects/{projectId}/entities`、`GET /api/projects/{projectId}/story-events`、`GET /api/projects/{projectId}/outline`、`GET /api/projects/{projectId}/scenes`、`GET /api/projects/{projectId}/scenes/{sceneId}`、`POST /api/projects/{projectId}/scenes/{sceneId}/regenerate`

## B Line Status

- 已完成前端工作台骨架
- 已完成 mock 场景工作台
- 已完成真实项目引导流
- 已完成真实项目创建和项目列表接入
- 已完成真实小说正文提交流程
- 已完成真实章节面板接入
- 已完成真实故事资产分析触发
- 已完成故事资产 AI/兜底状态提示接入
- 已完成真实角色/地点面板接入
- 已完成真实故事事件面板接入
- 已完成真实场景大纲、Scene 详情与 Scene 重新生成接入
- 已完成真实校验与 YAML 导出接入
- 已完成项目级 `/events` SSE 进度流接入
- 已完成 Scene 级 `/scenes/{sceneId}/stream` 流式预览接入
- 已完成故事资产分析和场景大纲生成的 RabbitMQ 异步任务接口，长篇处理可提交 MQ 任务并通过 SSE 观察进度
- 已在 Docker Compose 中补充 RabbitMQ management 镜像，作为长任务队列基础设施
- 当前工作台状态：`真实项目管理/正文提交/章节/摘要/实体/事件/场景大纲/Scene 详情/Scene 流式预览/校验/导出/进度 SSE + mock 回退`

## Next A Line Dependencies

- 根据联调结果细化 AI 提示词和错误处理
- 已收紧章节摘要、故事资产抽取、场景大纲、Scene 详情和流式预览的 AI 提示词，要求按输入顺序输出、只使用契约 ID、不编造 sourceRefs，并避免 Markdown 包裹 JSON
- 已增强 AI JSON 响应清洗，支持从 Markdown 包裹或前后带说明的模型响应中提取第一个完整 JSON 对象
- 已实现真实 SSE 进度流，覆盖分析、outline、scene、校验、导出阶段
- `GET /api/projects/{projectId}/events` 保留给 SSE，不用于故事事件列表

## Next B Line Tasks

- 继续联调真实 SSE 进度流和 Scene 流式预览
- 收口真实状态与 mock 回退之间的消息提示
- 保持 mock 回退能力，避免接口瞬时失败时阻塞演示

## Breaking Changes

- 暂无
