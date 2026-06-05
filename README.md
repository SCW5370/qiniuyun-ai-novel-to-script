# ai-novel-to-script

七牛云实训项目，目标是将 3 章以上小说文本转为可编辑、可校验、可导出的剧本资产。

## Repository Layout

- `backend/`: Spring Boot 后端服务
- `frontend/`: React 工作台
- `docs/`: 接口契约、协作规范、事件说明
- `samples/`: mock 数据、示例输入和示例输出

## Collaboration Rules

- `main` 保持可运行，只通过功能分支合并
- 每个提交只做一件事，标题使用 `feat:` / `fix:` / `docs:`
- 契约变更先改 `docs/`，再改代码
- A 线负责上游生成链路，B 线负责工作台、SSE、导出和校验闭环

## Key Documents

- `docs/api.md`: 已实现的 A 线基础接口
- `docs/api-contract.md`: A/B 共用的数据与接口契约
- `docs/sse-events.md`: SSE 事件规范
- `docs/dev-worklog.md`: 当前决策、分工状态和破坏性变更记录
