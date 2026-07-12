# AI Novel to Script

> 一部长小说变成剧本之前，机器究竟应该先理解什么？

AI Novel to Script 是一个把小说整理成可编辑剧本资产的创作工作台。它不会把整篇小说一次塞给模型等待奇迹，而是把过程拆成章节、摘要、角色与地点、故事事件、场景大纲和 Scene 剧本。

本项目来自七牛云实训。它更像一次长任务工作流实验：怎样让模型输出可追踪、让中间结果可检查，并让几分钟的等待不再只剩一个转圈图标。

[观看 Demo 视频](https://www.bilibili.com/video/BV1BpEh6YEpt/) · [产品原则](PRODUCT.md) · [开发记录](docs/dev-worklog.md) · [性能与可靠性审查](docs/performance-reliability-review.md)

## 核心想法

小说转剧本不是一次文本改写。真正困难的是建立一组可以继续编辑、校验和交付的中间资产：

```text
小说原文
  → 章节切分与摘要
  → 角色 / 地点 / 故事事件
  → 场景大纲
  → Scene 动作与对白
  → 结构校验
  → YAML 导出
```

每一步都保存来源引用。最终结果不是一段只能复制的模型回答，而是一份可以继续被系统或人修改的结构化资产。

## 当前能做什么

- 创建项目，粘贴小说正文或上传 `.txt`。
- 切分章节并生成摘要。
- 抽取角色、地点、故事事件和来源引用。
- 生成有顺序的场景大纲。
- 按 Scene 生成动作、对白和 warnings。
- 校验动作缺失、对白缺失与角色一致性。
- 导出 YAML，保留后续系统可消费的结构。
- 通过项目级 SSE 展示阶段进度，通过 Scene 流提供生成预览。
- 在真实接口不可用时保留明确标注的 mock 回退。

## 改变架构的不是“想用消息队列”

第一版长任务暴露了几个很具体的问题：HTTP 请求等待太久、页面看起来像卡死、模型调用期间数据库连接可能被长期占用。

因此当前链路把长任务提交到 RabbitMQ，使用 SSE 把任务开始、资产分析、场景生成、校验和失败事件推给前端；Scene 生成采用短事务读取、模型调用期间释放连接、短事务写回的方式。

这并不意味着可靠性问题已经结束。仓库里的审查文档仍记录着一些待验证问题：旧 SSE 连接清理、半截结果缓存、超时边界、公共线程池阻塞和流式预览与正式生成不一致。它们是下一轮工作的输入，不是被隐藏的缺陷。

## 用户真正看到的等待

一个假进度条无法解释模型正在做什么。当前前端展示真实阶段事件和已经产生的中间资产，让用户知道任务正在切章节、抽角色，还是生成某个 Scene。

下一步更值得试的是：等待能否变成共同编辑。角色抽取完成后先让用户纠正，场景大纲生成后先删改，再继续生成对白。这会让线性管道变成更复杂的状态机，但可能比单纯换一个更快的模型更有价值。

## 架构

```text
React + TypeScript 工作台
        │ REST / SSE
Spring Boot
工作流编排 / 校验 / 持久化 / AI 调用
        │
RabbitMQ ─ MySQL ─ Redis
```

主要技术：Java 17、Spring Boot 3.5、MyBatis、React 18、Vite、Zustand、RabbitMQ、MySQL、Redis。

## 当前边界

- AI 输出仍可能不稳定，规则兜底与 mock 会明确区分，不冒充真实生成。
- Scene 流式预览与正式结构化生成目前是两条调用，可能出现内容差异。
- RabbitMQ 默认串行消费适合当前演示规模，不代表已经验证多项目高并发。
- 项目提供结构校验，但不替代编剧判断与人工改写。

## 本地启动

环境要求：JDK 17、Node.js 18+、Docker Desktop。

```bash
git clone https://github.com/SCW5370/qiniuyun-ai-novel-to-script.git
cd qiniuyun-ai-novel-to-script
cp .env.example .env
docker compose up -d
```

启动后端：

```bash
cd backend
./mvnw spring-boot:run
```

启动前端：

```bash
cd frontend
npm install
npm run dev
```

根据 [`.env.example`](.env.example) 配置模型接口。不要提交 `.env` 或真实 API Key。

## 验证与样例

- 接口契约：[`docs/api-contract.md`](docs/api-contract.md)
- SSE 事件：[`docs/sse-events.md`](docs/sse-events.md)
- Mock 样例：[`samples/`](samples/)
- 性能基准：[`bench/`](bench/)

```bash
cd backend && ./mvnw test
cd frontend && npm run build
```

## 接下来想验证

- 中间结果能否成为人与 AI 共同编辑的接口，而不只是调试产物？
- 如果服务停止，YAML 导出是否足够让创作资产继续存在？
- 流式预览能否直接成为正式生成，减少重复调用与结果分叉？

这个项目不是“自动写完一部剧本”的承诺。它是在测试：复杂创作能不能被拆成一条可理解、可修正、可继续的工作流。
