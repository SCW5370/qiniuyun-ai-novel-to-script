# AI Novel to Script

AI Novel to Script 是一个面向小说改编剧本场景的智能创作工作台。项目支持用户上传小说文本或 txt 文件，自动完成章节切分、章节摘要、故事资产抽取、场景大纲生成、Scene 级剧本生成、结构校验和 YAML 导出，最终形成可编辑、可校验、可交付的结构化剧本资产。

本项目为七牛云实训项目，采用前后端分离架构。后端负责 AI 工作流编排、异步任务调度、数据库持久化和接口服务；前端负责项目工作台、创作流程展示、实时进度反馈和剧本资产可视化。

## 演示视频

项目完整演示视频已随 GitHub Release 发布：

- [点击观看 Demo 视频](https://github.com/zongzuoscc/ai-novel-to-script/releases/download/v1.0.0/demo.mp4)
- [查看 Release 页面](https://github.com/zongzuoscc/ai-novel-to-script/releases/tag/v1.0.0)

## 项目目标

传统小说改编剧本需要人工完成大量结构化整理工作，包括梳理人物、地点、关键事件、场次顺序、场景目的、动作描写和对白。本项目希望通过 AI 和工程化工作流，将小说文本自动转化为可供后续剧本生产使用的结构化中间资产。

系统重点解决以下问题：

- 将长篇或中篇小说拆解为可处理的章节单元。
- 从小说中抽取角色、地点和故事事件。
- 根据故事事件生成有顺序的场景大纲。
- 根据场景大纲生成 Scene 级动作和对白。
- 对生成结果进行基础结构校验。
- 导出可被后续系统消费的 YAML 数据。
- 通过 RabbitMQ 和 SSE 改善长任务的稳定性与用户反馈。

## 核心功能

### 1. 项目管理

- 创建小说改编项目。
- 查询项目列表和项目详情。
- 使用字符串形式的 `projectId` 作为统一项目主键。
- 支持前端通过项目列表进入不同项目。

### 2. 小说文本提交

- 支持直接粘贴小说正文。
- 支持上传 `.txt` 小说文件。
- 后端自动完成章节切分和清洗。
- 支持追加章节，适合分批上传或补充前置章节。

### 3. 章节摘要生成

- 对章节内容生成摘要。
- 优先使用 AI 生成摘要。
- AI 调用失败时可使用规则兜底，保证流程不中断。

### 4. 故事资产抽取

系统从章节文本中抽取故事中间资产：

- 角色。
- 地点。
- 故事事件。
- 来源引用。

抽取结果会进入数据库，为后续场景大纲和 Scene 剧本生成提供稳定输入。

### 5. 场景大纲生成

后端根据故事事件和角色地点信息生成场景大纲。场景大纲保存到 `outline_scenes` 表，主要包括：

- `sceneId`：场景编号，例如 `S001`。
- `seqNo`：场景排序号。
- 标题。
- 内景/外景。
- 地点。
- 时间。
- 出场角色。
- 剧情目的。
- 人物目的。
- 来源章节引用。

### 6. Scene 级剧本生成

后端根据 `outline_scenes` 按顺序生成 Scene 级剧本，并保存到 `scene_scripts` 表。Scene 剧本包括：

- 动作描写。
- 对白。
- 来源引用。
- 校验状态。
- warnings。

Scene 生成任务通过 RabbitMQ 排队执行，默认按项目串行处理，避免长任务并发导致数据库连接池耗尽。

### 7. 实时进度 SSE

前端通过 Server-Sent Events 接收项目级进度事件，包括：

- 任务已提交。
- 任务开始执行。
- 故事资产分析进度。
- 场景大纲生成完成。
- 单个 Scene 生成完成。
- 结构校验完成。
- 任务失败。

这使用户能够看到 AI 工作流正在执行，而不是误以为页面卡死。

### 8. Scene 流式预览

当某个 Scene 尚未写入结构化结果时，前端可以展示 AI 流式预览。流式预览用于提升交互体验，最终持久化结果仍以 `scene_scripts` 中的结构化数据为准。

### 9. 结构校验

系统支持对已生成 Scene 进行基础校验：

- 是否缺少动作描写。
- 是否缺少对白。
- 对白角色是否出现在场景大纲角色列表中。
- Scene 自身 warnings 是否存在。

### 10. YAML 导出

系统可将结构化剧本结果导出为 YAML，便于后续剧本生产、审阅或其他系统消费。

## 系统架构

整体架构如下：

```text
用户浏览器
  -> React 前端工作台
  -> Spring Boot REST API / SSE
  -> RabbitMQ 异步任务队列
  -> AI 模型服务
  -> MySQL 持久化
  -> YAML 导出
```

### 后端架构

后端基于 Spring Boot 构建，核心模块包括：

- `project`：项目管理。
- `source`：小说正文、章节切分、章节摘要。
- `story`：故事资产抽取，包括角色、地点和事件。
- `scene`：场景大纲和 Scene 剧本生成。
- `workflow`：工作流任务、RabbitMQ 消费、SSE 进度推送、校验和导出。
- `ai`：AI 调用封装。
- `common`：统一响应、异常处理、项目锁等通用能力。

### 前端架构

前端基于 React + Vite 构建，主要能力包括：

- 项目入口和项目列表。
- 小说文本提交和文件上传。
- 章节原文展示。
- 角色地点面板。
- 故事事件面板。
- 场景大纲视图。
- Scene 详情视图。
- 项目进度提示。
- 结构校验结果。
- YAML 导出。
- 3D/生产地图等可视化增强。

## 技术栈

### 后端

- Java 17
- Spring Boot 3.5.x
- Spring Web
- Spring AMQP
- MyBatis
- MySQL 8.4
- RabbitMQ 3.13
- Redis 7.4
- HikariCP
- Maven

### 前端

- React 18
- TypeScript
- Vite
- Zustand
- Three.js
- React Three Fiber
- Motion
- Lucide React

### 基础设施

- Docker Compose
- MySQL
- RabbitMQ Management
- Redis
- Adminer

## 数据库设计

当前核心表如下：

| 表名 | 说明 |
| --- | --- |
| `projects` | 项目主表，保存项目标题、状态、创建时间和更新时间 |
| `source_chapters` | 小说章节表，保存原文、清洗文本和章节摘要 |
| `story_entities` | 故事实体表，保存角色、地点等实体 |
| `story_events` | 故事事件表，保存事件顺序、标题、摘要和来源 |
| `outline_scenes` | 场景大纲表，保存 Scene 的顺序、标题、地点、时间、角色和场景目的 |
| `scene_scripts` | Scene 剧本表，保存动作、对白、来源引用、校验状态和 warnings |

数据处理主链路如下：

```text
projects
  -> source_chapters
  -> story_entities / story_events
  -> outline_scenes
  -> scene_scripts
  -> validation / yaml export
```

## AI 工作流

系统将小说转剧本流程拆分为多个阶段：

1. 小说文本上传。
2. 章节切分和清洗。
3. 章节摘要。
4. 故事资产抽取。
5. 场景大纲生成。
6. Scene 剧本生成。
7. 结构校验。
8. YAML 导出。

长耗时任务通过 RabbitMQ 排队执行，避免 HTTP 请求长时间阻塞。Scene 生成阶段采用短事务策略：

```text
短事务读取上下文
  -> AI 生成期间不占数据库连接
  -> 短事务写入 scene_scripts
```

这样可以显著降低长篇小说处理时数据库连接池被耗尽的风险。

## 目录结构

```text
ai-novel-to-script/
  backend/                 Spring Boot 后端服务
    src/main/java/
      com/novel2script/backend/
        ai/                AI 调用封装
        common/            通用响应、异常处理、项目锁
        project/           项目管理
        source/            小说正文、章节和摘要
        story/             故事资产抽取
        scene/             场景大纲和 Scene 剧本
        workflow/          MQ 工作流、SSE、校验、导出
    src/main/resources/
      mapper/              MyBatis XML 映射
      schema.sql           数据库建表脚本
      application.yml      后端配置
  frontend/                React 前端工作台
    src/
      api/                 API client 和类型
      components/          通用组件和可视化组件
      features/workbench/  主工作台功能
      store/               前端状态管理
  docs/                    接口契约和协作文档
  samples/                 mock 数据和示例数据
  docker-compose.yml       本地基础设施编排
  .env.example             环境变量示例
```

## 快速开始

### 1. 环境要求

请确保本机已安装：

- JDK 17
- Node.js 18 或更高版本
- npm
- Docker Desktop
- Git

### 2. 克隆项目

```bash
git clone <repository-url>
cd ai-novel-to-script
```

### 3. 配置环境变量

复制 `.env.example` 为 `.env`：

```bash
cp .env.example .env
```

Windows PowerShell 可使用：

```powershell
Copy-Item .env.example .env
```

根据实际情况填写 AI 配置：

```text
AI_API_KEY=your_api_key_here
AI_BASE_URL=https://api.qnaigc.com/v1
AI_MODEL_ID=deepseek/deepseek-v4-pro
```

注意：`.env` 中包含敏感信息，不应提交到 Git 仓库。

### 4. 启动基础设施

```bash
docker compose up -d
```

确认容器状态：

```bash
docker compose ps
```

正常情况下应看到 MySQL、RabbitMQ、Redis 和 Adminer 均处于运行状态。

### 5. 启动后端

进入后端目录：

```bash
cd backend
```

启动 Spring Boot：

```bash
./mvnw spring-boot:run
```

Windows PowerShell 可使用：

```powershell
.\mvnw.cmd spring-boot:run
```

后端默认地址：

```text
http://localhost:8080
```

### 6. 启动前端

进入前端目录：

```bash
cd frontend
npm install
npm run dev
```

前端默认地址：

```text
http://localhost:5173
```

## 常用服务地址

| 服务 | 地址 |
| --- | --- |
| 前端工作台 | `http://localhost:5173` |
| 后端 API | `http://localhost:8080/api` |
| Adminer | `http://localhost:8081` |
| RabbitMQ Management | `http://localhost:15672` |
| MySQL | `localhost:3306` |
| Redis | `localhost:6379` |

## 典型演示流程

1. 打开前端工作台。
2. 新建项目，输入项目标题。
3. 上传小说文本或 txt 文件。
4. 查看章节原文。
5. 触发故事分析任务。
6. 等待角色、地点和故事事件生成。
7. 生成场景大纲。
8. 等待 Scene 剧本生成。
9. 切换不同 Scene 查看动作和对白。
10. 执行结构校验。
11. 导出 YAML。

建议 Demo 使用 1 到 3 章的小说片段，能够更稳定地展示完整链路。

## API 文档

主要文档位于 `docs/` 目录：

- `docs/api.md`：当前后端已实现 API 的详细说明。
- `docs/api-contract.md`：前后端共用接口和数据结构契约。
- `docs/sse-events.md`：SSE 事件规范。
- `docs/dev-worklog.md`：开发记录、关键决策和当前状态。

部分核心接口如下：

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `POST` | `/api/projects` | 创建项目 |
| `GET` | `/api/projects` | 查询项目列表 |
| `POST` | `/api/projects/{projectId}/source` | 提交小说文本 |
| `POST` | `/api/projects/{projectId}/source/upload` | 上传 txt 文件 |
| `POST` | `/api/projects/{projectId}/chapters/append` | 追加章节 |
| `GET` | `/api/projects/{projectId}/chapters` | 查询章节 |
| `POST` | `/api/projects/{projectId}/jobs/analyze` | 提交故事资产分析任务 |
| `POST` | `/api/projects/{projectId}/jobs/outline` | 提交场景大纲生成任务 |
| `POST` | `/api/projects/{projectId}/jobs/scenes` | 提交 Scene 剧本生成任务 |
| `GET` | `/api/projects/{projectId}/entities` | 查询角色地点 |
| `GET` | `/api/projects/{projectId}/story-events` | 查询故事事件 |
| `GET` | `/api/projects/{projectId}/outline` | 查询场景大纲 |
| `GET` | `/api/projects/{projectId}/scenes` | 查询已生成 Scene 列表 |
| `GET` | `/api/projects/{projectId}/scenes/{sceneId}` | 查询 Scene 详情 |
| `GET` | `/api/projects/{projectId}/events` | 项目级 SSE 进度流 |
| `POST` | `/api/projects/{projectId}/validate` | 结构校验 |
| `GET` | `/api/projects/{projectId}/export?format=yaml` | 导出 YAML |

## 测试与构建

### 后端测试

```bash
cd backend
./mvnw test
```

Windows PowerShell：

```powershell
cd backend
.\mvnw.cmd test
```

### 前端构建

```bash
cd frontend
npm run build
```

### 提交前建议检查

```bash
git status --short --branch
```

建议确认：

- 后端测试通过。
- 前端构建通过。
- `.env` 未被提交。
- 控制台无明显连接池、SSE 或 JSON 解析异常。

## 开发约定

- `main` 分支应始终保持可运行。
- 每个 PR 只做一件事，保持改动粒度清晰。
- PR 标题和提交信息使用中文，便于团队复盘。
- 变更接口前先同步 `docs/` 下的契约文档。
- 前端和后端均以 `projectId` 字符串作为唯一项目标识。
- 长耗时任务应走 RabbitMQ，不应阻塞 HTTP 请求。
- AI 调用期间不应长时间持有数据库连接。

## 已实现能力概览

- 项目创建与项目列表。
- 小说文本提交和 txt 文件上传。
- 章节切分、章节追加和章节摘要。
- AI 故事资产抽取。
- AI 场景大纲生成。
- MQ 异步 Scene 剧本生成。
- Scene 流式预览。
- 项目级 SSE 进度推送。
- 结构校验。
- YAML 导出。
- 前端产品化工作台。
- RabbitMQ 异步任务队列。
- 数据库连接池优化和长事务隔离。

## 当前限制与后续优化方向

当前项目已经具备完整演示链路，但仍有进一步优化空间：

- 针对超长篇小说，可进一步引入更完善的分块调度和断点续跑。
- 可增加用户体系和项目权限隔离。
- 可增加更精细的 Prompt 版本管理。
- 可增加人工编辑 Scene 后再导出的能力。
- 可将任务状态持久化到数据库，而不是仅保存在内存中。
- 可增加更完善的端到端测试和接口自动化测试。

## 团队分工

项目开发过程中采用 A/B 线协作：

- A 线：后端工作流、MyBatis 数据持久化、AI 接入、MQ 异步任务、SSE、校验和导出。
- B 线：前端工作台、项目交互、资产展示、Scene 展示、进度反馈、视觉与体验优化。

## 许可证与说明

本项目为实训课程项目，用于学习和演示 AI 辅助内容生产工作流。项目中的示例文本和 mock 数据仅用于开发调试，不代表最终商业内容。
