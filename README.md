# AI Novel to Script

**把一部长篇小说，转化成可编辑、可校验、可交付的剧本资产。**

AI Novel to Script 是一套小说改编创作工作台。它将长篇文本依次整理成章节、摘要、角色、地点、故事事件、场景大纲和结构化 Scene，而不是返回一整块无法继续修改的生成文本。

[观看完整 Demo](https://www.bilibili.com/video/BV1BpEh6YEpt/) · [产品说明](PRODUCT.md) · [接口契约](docs/api-contract.md)

## 从小说原稿进入剧本生产

上传 `.txt` 或直接粘贴正文，工作台会把改编过程拆成一条可见的生产链路：

```text
小说原文
   ↓
章节切分与摘要
   ↓
角色 · 地点 · 故事事件 · 来源引用
   ↓
有顺序的场景大纲
   ↓
Scene 动作 · 对白 · warnings
   ↓
结构校验
   ↓
可移植 YAML 导出
```

每个阶段都会形成可检查的中间资产，再进入下一步。来源引用始终跟随生成结果，角色、事件或场景出现偏差时，可以快速回到小说原文核对。

## 覆盖完整改编流程

- 创建并持续维护多个改编项目。
- 一次提交完整正文，或分批追加章节。
- 先生成章节摘要，再进入深层资产抽取。
- 建立可复用的角色、地点和故事事件档案。
- 将事件组织成有顺序的场景大纲。
- 生成 Scene 动作、对白和实时预览。
- 检查动作缺失、对白缺失与角色一致性。
- 将结构化结果导出为 YAML，继续编辑或交给下游系统。

系统负责消化重复的结构整理工作，作者仍然掌握叙事判断和最终改写权。

## 长任务也能看见真实进度

小说改编是一条持续数分钟的 AI 工作流。系统把长任务移出 HTTP 请求线程，并用真实阶段事件代替没有信息量的转圈动画。

```text
React 创作工作台
      │ REST + Server-Sent Events
Spring Boot 工作流服务
      ├── RabbitMQ：长任务排队
      ├── MySQL：项目与结构化资产
      ├── Redis：运行协调
      └── 模型服务：抽取与生成
```

项目级 SSE 会报告任务提交、资产抽取、大纲生成、单个 Scene 完成、校验和失败。Scene 流式预览让用户在正式结构化结果完成前就能开始阅读。

数据库访问被拆成短读事务和短写事务；外部模型调用期间不长期占用连接，从而避免慢调用拖垮连接池。

## 输出为什么能继续使用

### 中间资产就是产品对象

角色、地点、事件和大纲都拥有稳定结构，可以被人工审阅、修改或交给另一个系统消费，而不是隐藏在一次模型调用内部。

### 结构问题会被明确指出

校验器会检查 Scene 的基础完整性，并把 warnings 和结果一起保存。它不替代编剧判断，但能在导出前拦住明显缺口。

### Mock 与真实生成不会混淆

规则兜底和 Mock 路径都有明确标识，既保证开发与演示可继续，也不会把占位结果伪装成模型成功输出。

### 创作资产可以带走

YAML 导出让最终资产不依赖当前服务继续存在，可以进入后续编辑、审阅或生产流程。

## 技术栈

**后端：** Java 17、Spring Boot 3.5、MyBatis、RabbitMQ、MySQL、Redis、HikariCP。

**前端：** React 18、TypeScript、Vite、Zustand。

工程资料包括 [SSE 事件契约](docs/sse-events.md)、[开发记录](docs/dev-worklog.md)、[性能与可靠性审查](docs/performance-reliability-review.md)、[`samples/`](samples/) 中的样例结果和 [`bench/`](bench/) 中的量化材料。

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

在另一个终端启动前端：

```bash
cd frontend
npm install
npm run dev
```

按照 [`.env.example`](.env.example) 配置模型服务，不要提交 API Key 或生产凭证。

## 验证完整链路

```bash
cd backend && ./mvnw test
cd frontend && npm run build
```

## 项目状态

项目起源于七牛云实训，并继续补充了异步工作流、真实进度、结构校验、导出和可靠性分析。

当前队列配置优先保证项目级处理顺序，不代表已经验证高并发。流式预览与正式结构化生成仍是两次模型调用，结果可能存在差异。下一阶段将允许创作者在各阶段之间审核和修正中间资产，把线性生成管道升级成真正的人机协作生产流程。
