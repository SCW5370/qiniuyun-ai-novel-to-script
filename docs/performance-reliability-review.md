# 性能与流式可靠性审查

## 1. 文档目的

本文档用于团队同步当前项目在流式输出、异步任务、AI 调用、数据库访问和前端渲染方面的风险，并为后续优化提供统一优先级。

本次结论来自静态代码审查，尚未实施修改，也未经过专项压测。当前最明显的问题不是单纯“模型或网络不稳定”，而是 Scene 流式链路在连接生命周期、超时、线程模型、异常判断和前端缓存方面存在多项确定性缺陷。

## 2. 结论摘要

建议按以下顺序处理：

1. 修复前端流生命周期、Scene 切换取消和缓存状态。
2. 统一 SSE 与 AI 超时，增加心跳、事件 ID 和真实完成判断。
3. 将阻塞式流请求移出 JVM 公共线程池，设置有界并发。
4. 只向模型传递当前 Scene 相关上下文，降低输入规模。
5. 评估合并“流式预览”和“正式 Scene 生成”，避免重复调用和结果不一致。
6. 对前端 chunk 更新节流，并隔离 Three.js 地图渲染。
7. 消除 Scene 生成 N+1 查询和任务完成后的重复刷新。
8. 再处理多项目并行、任务持久化、分布式锁和死信队列。

优先级定义：

- `P0`：直接导致断流、内容错误、假成功或资源失控，应优先修复。
- `P1`：显著增加延迟、成本或吞吐压力，影响真实使用体验。
- `P2`：当前演示规模可能不明显，但扩展后会成为稳定性问题。

## 3. P0：Scene 流式输出可靠性

### 3.1 Scene 或项目切换后旧流不会关闭

前端在 `startScenePreview` 内创建 `EventSource`，但没有保存为组件级 `ref`。连接只在 `done`、`failed` 或 `error` 时关闭，Scene 切换、项目切换和组件卸载不会主动终止旧流。

代码位置：

- `frontend/src/features/workbench/useWorkbench.ts:642-716`
- `frontend/src/features/workbench/useWorkbench.ts:948-993`

可能结果：

- 切换到 S002 后，S001 的 chunk 继续写入当前预览框。
- 切换项目后，旧项目内容继续影响新项目状态。
- 浏览器已经不需要旧结果，但服务端和模型请求仍在运行。
- 旧流没有结束时，`isStreamingScene` 会阻止用户启动新的预览。

建议方向：

- 使用 `useRef<EventSource | null>` 保存当前连接。
- Scene 切换、项目切换和组件卸载时关闭连接。
- 每次请求生成唯一 stream token；收到 chunk 时校验 token 和 projectId/sceneId。
- 服务端检测客户端断开后应尽快关闭上游模型响应流。

验收标准：

- 在流式过程中连续切换 Scene 20 次，不出现跨 Scene 内容混入。
- 切换项目后旧请求在可接受时间内终止。
- 页面卸载后没有残留 EventSource 和服务端流任务。

### 3.2 失败或半截内容会被永久当作成功缓存

缓存使用 `Record<string, string>`，只要返回过一个 chunk，缓存就变成非空。下次点击时会直接显示缓存并停止重新请求，无法区分完整结果、半截结果和失败结果。

代码位置：

- `frontend/src/features/workbench/useWorkbench.ts:643-649`
- `frontend/src/features/workbench/useWorkbench.ts:694-701`

此外，以下操作不会使缓存失效：

- Scene 重新生成。
- 原文替换或追加。
- 故事资产重新分析。
- 场景大纲重新生成。

建议方向：

- 缓存值改为带状态的对象：`idle / streaming / completed / failed`。
- 只有收到有效 `done` 后才标记为可复用。
- 重生成或上游数据变化时按项目、Scene 或版本号清理缓存。
- 页面提供“重新生成预览”，而不是永远复用上次结果。

验收标准：

- 流中断后再次点击会重新请求，而不是显示半截缓存。
- Scene 重生成后不会继续展示旧版本预览。

### 3.3 SSE 超时小于 AI 请求超时

Scene SSE 使用 120 秒超时，但 AI 请求默认允许等待 180 秒。

代码位置：

- `backend/src/main/java/com/novel2script/backend/scene/SceneGenerationService.java:248-250`
- `backend/src/main/java/com/novel2script/backend/ai/AiProperties.java:19-24`

因此模型仍在正常生成时，SSE 连接可能已经被 Web 容器关闭。Scene emitter 也没有显式注册 `onTimeout`、`onError` 和统一清理逻辑。

建议方向：

- 区分连接建立超时、首 token 超时、chunk 空闲超时和总生成超时。
- SSE 总超时应大于上游 AI 总超时，并留出清理余量。
- 超时后明确向任务状态写入 `TIMED_OUT`，并取消上游请求。

验收标准：

- 超过首 token 阈值时用户得到明确错误。
- 正常生成不会因为 SSE 先超时而被截断。
- 超时后没有继续运行的后台模型请求。

### 3.4 阻塞式模型流占用公共线程池

`CompletableFuture.runAsync()` 未指定执行器，因此使用 JVM 公共 ForkJoinPool；任务内部却通过同步 `HttpClient.send` 持续阻塞读取模型流。

代码位置：

- `backend/src/main/java/com/novel2script/backend/scene/SceneGenerationService.java:248-291`
- `backend/src/main/java/com/novel2script/backend/ai/AiChatClient.java:135-168`

风险：

- 多个流式请求长时间占用公共线程。
- 没有明确的队列容量、并发上限和拒绝策略。
- 公共池中的其他任务可能被拖慢。
- 无法稳定观察当前流式任务数量和积压。

建议方向：

- 使用独立、有界、具名的流式执行器；或使用真正异步的 HTTP 流客户端。
- 增加 active、queued、rejected、duration 等指标。
- 超过容量时快速返回“当前生成繁忙”，不要无限堆积。

验收标准：

- 并发流超过上限时行为可预测。
- 流式请求不会占满公共 ForkJoinPool。
- 能看到当前活动流、排队数和拒绝数。

### 3.5 流异常可能被误判为正常完成

当前流解析在 JSON chunk 解析失败时返回空字符串并继续；如果上游连接提前 EOF，即使没有收到 `[DONE]`，方法也会正常返回，随后服务端向浏览器发送 `done`。

代码位置：

- `backend/src/main/java/com/novel2script/backend/ai/AiChatClient.java:171-197`
- `backend/src/main/java/com/novel2script/backend/scene/SceneGenerationService.java:261-280`

可能结果：

- 页面提示“生成完成”，但正文缺失。
- 上游格式发生变化时部分 chunk 被静默丢弃。
- 无法区分模型正常结束、网络 EOF 和解析错误。

建议方向：

- 记录是否收到 `[DONE]` 或明确的 finish reason。
- 非正常 EOF 视为失败，不发送成功事件。
- chunk JSON 解析失败应统计并在超过阈值后终止。
- 对供应商的流协议建立契约测试。

验收标准：

- 人工截断上游连接时前端显示失败，不显示完成。
- 注入非法 chunk 后可以从日志和指标中定位。

### 3.6 项目级 SSE 没有心跳和事件恢复

项目级 SSE 使用无限超时，但没有定期心跳、事件 ID、`Last-Event-ID`、最近事件缓存或状态快照恢复。

代码位置：

- `backend/src/main/java/com/novel2script/backend/workflow/ProgressEventPublisher.java:19-132`
- `frontend/src/features/workbench/useWorkbench.ts:1001-1050`

风险：

- 代理或网络设备清理空闲连接后，服务端不能及时发现。
- 断线期间的 `scene.done` 和 `job.completed` 永久丢失。
- 重连后只能得到项目粗粒度状态，不能恢复准确任务进度。
- emitter 保存在本机内存，多实例部署时其他实例发布的事件无法到达当前连接。

建议方向：

- 每 10-20 秒发送 heartbeat/comment。
- 每个事件带递增 ID。
- 保存任务的最新状态快照和必要的近期事件。
- 重连时使用 `Last-Event-ID` 补发，或至少立即返回最新快照。
- 多实例时通过 Redis Pub/Sub、消息队列或其他事件总线分发。

验收标准：

- 人为断网 10 秒后恢复，页面最终状态正确。
- 任务完成事件不会因为重连而永久丢失。
- 空闲连接能维持或被服务端及时清理。

## 4. P1：主要性能与成本问题

### 4.1 每个 Scene 都携带全项目上下文

生成单个 Scene 或流式预览时，会读取全部故事实体和全部故事事件，并把它们序列化到 Prompt。

代码位置：

- `backend/src/main/java/com/novel2script/backend/scene/SceneGenerationService.java:301-313`
- `backend/src/main/java/com/novel2script/backend/scene/SceneGenerationService.java:379-393`
- `backend/src/main/java/com/novel2script/backend/scene/SceneGenerationService.java:805-885`

成本近似随以下规模增长：

```text
Scene 数 × 全项目实体和事件上下文
```

影响：

- 首 token 时间增长。
- 模型费用增长。
- 更容易触及上下文窗口。
- 无关事件过多可能降低当前 Scene 的生成质量。

建议方向：

- 按当前 Scene 的 `sourceRefs` 过滤事件。
- 按 `characters` 和 `locationId` 过滤实体。
- 只提供必要的相邻 Scene、人物全局摘要或项目级短摘要。
- 记录每次请求的输入字符数、估算 token 和模型耗时。

验收标准：

- 中型样例的平均 Prompt token 明显下降。
- Scene 事件覆盖率和人物一致性不下降。
- 首 token P95 和单 Scene 总耗时下降。

### 4.2 流式预览和正式 Scene 是两次模型调用

当前流式接口只生成不落库的自由文本预览；正式 Scene 生成使用另一条 JSON 请求。两次调用可能内容不同，并产生重复成本。

代码位置：

- 流式预览：`SceneGenerationService.streamScenePreview`
- 正式生成：`SceneGenerationService.generateSceneScript`

影响：

- 用户看到的预览不一定是最终结果。
- 相同 Scene 需要两次模型调用。
- 预览无法参与最终校验和持久化。

建议方向：

- 评估让流式请求成为正式生成过程。
- 服务端累积结构化内容或增量事件，结束后统一解析、校验和落库。
- 若仍保留独立预览，应在产品上明确其用途并控制调用成本。

### 4.3 Scene 批量生成存在 N+1 查询

批量生成缺失 Scene 时，每个 Scene 都查询是否存在；生成过程中又重复读取大纲、项目、全量实体、全量事件，保存后再查一次结果。

代码位置：

- `backend/src/main/java/com/novel2script/backend/scene/SceneGenerationService.java:215-241`
- `backend/src/main/java/com/novel2script/backend/scene/SceneGenerationService.java:340-399`

建议方向：

- 一次读取全部大纲和已生成 Scene ID。
- 每个批次只读取一次项目、实体和事件。
- 为 Scene 构建经过过滤的上下文索引。
- 不需要时避免写入后再次查询。

验收标准：

- 生成 N 个 Scene 时，数据库查询数不再与 N 成高倍数增长。
- SQL 日志或监控能证明查询次数下降。

### 4.4 RabbitMQ 默认形成全局队头阻塞

RabbitMQ 消费者默认并发和最大并发均为 1。一条长任务会阻塞所有其他项目，而不仅是当前项目。

代码位置：

- `backend/src/main/resources/application.yml:25-34`
- `backend/src/main/java/com/novel2script/backend/workflow/WorkflowJobService.java:145-202`

建议方向：

- 允许不同项目有限并行，同时保持同项目写操作串行。
- 在提高消费者并发前，先解决本地项目锁、多实例幂等和模型限流。
- 将不同类型或不同成本的任务分队列，避免短任务被长任务阻塞。

验收标准：

- 项目 A 长任务运行时，项目 B 的短任务仍能在目标时间内启动。
- 不出现同项目重复写入或 Scene 顺序冲突。

### 4.5 每个 chunk 都使整个工作台重新渲染

每收到一个 chunk，前端都会执行 `setSceneStreamContent`。`DirectorCommandRoom` 使用一个大型 hook 管理全部状态，因此整个页面会重新渲染，包括 Three.js 制作地图。

代码位置：

- `frontend/src/features/workbench/useWorkbench.ts:694-701`
- `frontend/src/features/workbench/DirectorCommandRoom.tsx:33-42`
- `frontend/src/components/production-map/ProductionMap.tsx:394-452`

建议方向：

- chunk 先写入 `ref`，按 30-50ms 或动画帧批量提交 React state。
- 将流式预览区拆成独立组件和独立状态。
- 对 `ProductionMap` 使用稳定 props 和 memo，避免无关状态导致图谱重算。
- 低性能设备可降低 Canvas DPR、暂停非必要动画。

验收标准：

- 流式输出期间 UI 输入和地图交互保持流畅。
- React Profiler 中 ProductionMap 不再随每个 chunk 重渲染。

### 4.6 完成事件触发重复刷新和任务推进

业务 Service 会发布阶段完成事件，RabbitMQ 外层完成后又发布一次 `job.completed`；`outline.ready` 同样会触发完整刷新。

前端每次刷新会读取实体、事件、大纲、项目、章节和项目列表，并可能继续提交 Scene 生成任务。

代码位置：

- `backend/src/main/java/com/novel2script/backend/workflow/WorkflowJobService.java:176-187`
- `backend/src/main/java/com/novel2script/backend/scene/SceneGenerationService.java:328-337`
- `frontend/src/features/workbench/useWorkbench.ts:328-381`
- `frontend/src/features/workbench/useWorkbench.ts:1022-1031`

建议方向：

- 明确每个 job 只有一个终态事件。
- 阶段事件和任务终态事件使用不同职责，不重复驱动完整刷新。
- 前端按事件类型刷新必要资源，并对短时间重复事件去重。

### 4.7 章节摘要完全串行

章节摘要逐章调用模型、逐章更新数据库，并在整个过程中持有项目操作锁。

代码位置：

- `backend/src/main/java/com/novel2script/backend/source/ChapterSummaryService.java:42-70`

建议方向：

- 跳过已有且输入未变化的摘要。
- 使用有限并发，而不是无限并发或完全串行。
- 数据库结果批量写入。
- 较大项目通过 MQ 执行并推送章节级进度。

## 5. P2：扩展后会放大的问题

### 5.1 项目锁内存增长且不支持多实例

`ProjectOperationLock` 将每个 projectId 对应的 `ReentrantLock` 永久保存在 `ConcurrentHashMap`，没有清理机制；该锁只在单 JVM 内有效。

代码位置：

- `backend/src/main/java/com/novel2script/backend/common/ProjectOperationLock.java:13-25`

影响：

- 项目持续增加时锁对象持续积累。
- 多实例部署时两个实例仍可能同时处理同一项目。

### 5.2 任务状态保存在内存且不会过期

`WorkflowJobService` 使用 `ConcurrentHashMap` 保存全部任务，不持久化、不清理；查找活动任务时扫描全部 values。

代码位置：

- `backend/src/main/java/com/novel2script/backend/workflow/WorkflowJobService.java:38`
- `backend/src/main/java/com/novel2script/backend/workflow/WorkflowJobService.java:121-129`

影响：

- 服务重启后任务查询状态丢失。
- 任务数量长期增长会增加内存和扫描成本。
- 无法建立可靠的任务审计、重试和恢复机制。

### 5.3 全量分析先删除旧资产

全量故事分析在 AI 调用前删除实体、事件、大纲和 Scene。如果进程在生成或写入前退出，项目会失去上一版可用结果。

代码位置：

- `backend/src/main/java/com/novel2script/backend/story/StoryAnalysisService.java:109-135`

更安全的方向是先生成新版本，验证完成后在短事务内切换版本或替换资产。

### 5.4 Scene 重生成先删除旧结果

当前重生成先删除旧 Scene，再进行模型调用。虽然一般失败会进入规则兜底，但进程崩溃或强制停止仍会造成旧结果丢失。

代码位置：

- `backend/src/main/java/com/novel2script/backend/scene/SceneGenerationService.java:295-299`

更安全的方向是保留旧版本，生成成功后再原子覆盖。

### 5.5 MQ 缺少完整失败治理

当前队列和交换机是持久化的，也配置了监听重试，但尚未看到以下能力：

- 任务状态持久化。
- 明确的业务幂等状态机。
- 死信队列和人工重放入口。
- 失败分类与不可重试错误隔离。
- 消息与数据库状态的一致性保障。

自动重试在部分步骤已经落库的情况下可能重复执行任务，因此必须结合幂等设计。

## 6. 建议的实施阶段

### 阶段一：流式可靠性止血

范围：

- 前端主动取消旧流。
- 缓存增加状态和失效机制。
- 统一超时和清理逻辑。
- 上游未正常结束时不发送 `done`。
- Scene 和项目 SSE 增加 heartbeat。

完成定义：

- Scene 切换不串流。
- 断流不假成功。
- 超时后任务能清理。
- 半截缓存可以重新生成。

### 阶段二：降低延迟和成本

范围：

- Scene Prompt 只包含相关实体和事件。
- 前端 chunk 更新节流。
- ProductionMap 隔离渲染。
- 消除 Scene N+1 查询。
- 合并重复刷新事件。

完成定义：

- 首 token 和总耗时下降。
- Prompt token 数下降。
- 流式输出时 UI 保持流畅。
- 单批 Scene 的 SQL 数量明显减少。

### 阶段三：任务系统生产化

范围：

- 任务表和状态机。
- 多项目有限并行。
- 同项目幂等和分布式互斥。
- 事件恢复、多实例广播和死信治理。
- 生成结果版本化。

完成定义：

- 服务重启后任务状态可恢复。
- 不同项目互不形成全局队头阻塞。
- 重复消息不会产生重复资产。
- 失败任务可定位、可重试、可审计。

## 7. 优化前需要补充的观测指标

没有指标时很难判断优化是否有效。建议至少记录：

### 流式指标

- 建立连接耗时。
- 首 token 时间 TTFT。
- chunk 间隔 P50/P95/P99。
- 正常完成率。
- 首 token 超时率。
- 总超时率。
- 客户端主动断开率。
- 非正常 EOF 和 chunk 解析失败数。
- 当前活动流、排队流和拒绝流数量。

### AI 指标

- 模型、阶段、projectId、sceneId。
- 输入字符数或 token 数。
- 输出 token 数。
- 请求耗时、重试次数和状态码。
- AI 成功、规则兜底和解析失败次数。

注意：日志不能记录密钥、完整原文、完整 Prompt 或模型完整响应。

### 数据库与任务指标

- 单个 Scene 生成的 SQL 数量和数据库耗时。
- MQ 等待时间、执行时间和重试次数。
- 队列深度和最老消息等待时间。
- 每个项目的活动任务数量。

### 前端指标

- 流式期间主线程长任务数量。
- DirectorCommandRoom 和 ProductionMap 重渲染次数。
- chunk 接收到显示的延迟。

## 8. 测试场景清单

后续优化至少应覆盖以下场景：

1. 流式开始后立即切换 Scene。
2. 流式开始后切换项目。
3. 流式过程中卸载页面或关闭浏览器标签。
4. 上游在首 token 前超时。
5. 上游返回部分 chunk 后断开。
6. 上游返回非法 JSON chunk。
7. 上游正常发送 `[DONE]`。
8. 项目级 SSE 断网后重连。
9. 多个用户同时订阅同一项目。
10. 多个项目同时生成 Scene。
11. 同一个任务被重复提交或重复消费。
12. 服务在任务执行中重启。
13. Scene 重生成失败时旧版本仍可用。
14. 中型项目验证 Prompt 大小、SQL 数和前端重渲染次数。

## 9. 团队同步时需要统一的口径

- 当前流式功能是“独立预览”，不是正式 Scene 生成结果。
- 流式不可靠不能只归因于网络，代码中存在明确的生命周期和完成判断问题。
- 目前最有价值的性能优化是减少模型上下文，而不是先调整数据库连接池。
- RabbitMQ 当前保证的是全局低并发稳定性，但代价是所有项目共享一个串行瓶颈。
- Redis 尚未参与当前核心任务状态、分布式锁或 SSE 广播，不能将其描述为已经解决这些问题。
- 在没有专项测试数据前，不承诺具体性能提升比例。

## 10. 建议的决策记录

实施前团队需要先确认以下设计选择：

1. 流式预览是否继续独立存在，还是升级为正式 Scene 生成通道。
2. Scene 流使用专用阻塞线程池，还是改为异步/响应式 HTTP 客户端。
3. 项目进度事件采用数据库快照、Redis 事件总线还是其他方案。
4. 任务状态持久化到 MySQL 还是 Redis；长期审计数据建议以 MySQL 为准。
5. 不同项目允许多少并发，同项目是否仍严格串行。
6. Scene Prompt 的最小上下文组成和质量回归标准。

上述决策确定后，再拆分具体开发任务，可以避免局部修补后重复返工。
