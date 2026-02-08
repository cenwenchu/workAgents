# WorkAgents 项目结构与架构说明

本项目是一个基于大模型（LLM）驱动的自动化智能体（Agent）框架，支持钉钉机器人与控制台两种交互入口，集成了播客下载与处理、微信公众号发布、富途证券行情分析、ERP 订单查询以及 Android 自动化操作等多种能力。

## 1. 整体目录结构

```text
src/main/java/com/qiyi
├── agent           # 智能体启动入口
│   ├── AbstractAgent.java      # Agent 基类：单实例与生命周期控制
│   ├── IAgent.java             # Agent 对外统一接口
│   ├── DingTalkAgent.java      # 钉钉入口：启动回调消费、保持进程运行
│   ├── ConsoleAgent.java       # 控制台入口：支持自然语言测试
│   └── PodwiseAgent.java       # 播客批处理入口：下载/处理流程编排
├── component       # 组件（可复用外部连接/客户端）
│   ├── ComponentId.java        # 组件枚举 ID（类型安全）
│   ├── ComponentManager.java   # 组件注册/启动/停止/状态
│   └── impl                   # 组件实现（DingTalk/Futu 等）
├── skills          # LLM 规划阶段的可选 prompt 注入
│   └── SkillPrompts.java       # 按业务域加载 markdown prompt（resources）
├── tools           # 工具集实现（Agent 的“手”）
│   ├── Tool.java           # 工具接口定义
│   ├── TaskProcessor.java  # 任务规划与工具执行主链路
│   ├── TaskPlanEnricher.java# 规划任务补参分发器（调用各 Tool.enrichPlannedTask）
│   ├── ToolManager.java    # 工具注册与 Schema 导出
│   ├── ToolContext.java    # 工具执行上下文接口
│   ├── ToolMessenger.java  # 工具消息输出通道
│   ├── agent       # Agent/组件管理工具（list/start/stop/status/capabilities/shutdown）
│   ├── android     # Android 自动化工具
│   ├── context     # 上下文实现 (DingTalkToolContext, ConsoleToolContext)
│   ├── dingtalk    # 钉钉相关工具
│   ├── erp         # ERP 系统集成工具
│   ├── futu        # 富途证券集成工具
│   ├── podcast     # 播客处理工具
│   └── wechat      # 微信发布工具
├── util            # 通用工具类
│   ├── DingTalkUtil.java   # 钉钉消息处理核心
│   ├── LLMUtil.java        # 大模型调用封装
│   ├── OSSUtil.java        # 阿里云 OSS 工具
│   ├── PlayWrightUtil.java # Playwright 浏览器自动化
│   ├── PodCastUtil.java    # 播客相关辅助工具
│   └── PFileUtil.java      # 文件操作工具
├── config          # 配置管理 (AppConfig)
└── service         # 领域服务（播客/富途/钉钉/Web 自动化等）
```

```text
src/main/resources
├── agent.cfg        # Agent 配置（默认会被 AppConfig 从 classpath 加载）
├── agent_bck.cfg    # 配置模板备份（不包含敏感值）
└── com/qiyi/skills  # 按业务域拆分的 LLM prompt（base.md, com.qiyi.tools.futu.md ...）
```

## 2. 核心模块详解

### 2.1 Agent 入口 (`com.qiyi.agent`)
负责系统的启动入口与运行形态，并复用同一套工具执行链路。
- **AbstractAgent / IAgent**: 统一 Agent 生命周期与并发控制（单进程/跨进程单实例）。
- **DingTalkAgent**: 钉钉入口进程，启动钉钉回调消费（消息接入在 `RobotMsgCallbackConsumer`）。
- **ConsoleAgent**: 本地调试入口，直接输入自然语言指令调用工具链路。
- **PodwiseAgent**: 播客批处理入口，负责下载与后处理的流程编排（可独立运行）。

### 2.2 组件系统 (`com.qiyi.component`)
用于管理可复用的外部依赖连接/客户端（例如钉钉、富途），并在工具执行前做依赖校验。

- **ComponentId**: 组件枚举 ID，替代散落字符串，便于统一管理与类型安全。
- **AgentComponent**: 组件接口（配置校验 / start/stop / status）。
- **ComponentManager**: 组件注册与生命周期管理（默认组件初始化、start/stop/status）。

工具通过 `requiredComponents()` 声明依赖；执行前由 `TaskProcessor` 检查组件是否 RUNNING，并提示用户 `start_component`。

### 2.3 工具集 (`com.qiyi.tools`)
遵循 `Tool` 接口定义，是 Agent 可调用的具体原子能力。新架构引入了上下文感知（Context-Aware）机制，实现了工具逻辑与通信渠道的彻底解耦。

- **Tool.java**: 核心接口，定义工具名/描述/执行方法，并支持声明依赖组件（`requiredComponents()`）。
- **Tool.Info**: 工具元信息注释模式（name/description/domain/type/requiredComponents），推荐作为唯一元数据来源（通常实现类只需 `@Tool.Info + execute` 即可接入系统；`getName/getDescription/requiredComponents` 可不再实现）。
- **Tool.enrichPlannedTask(...)**: 任务计划补参扩展点，用于从原始 userText 里补齐/修正 LLM 规划的入参（逻辑下沉到各工具自包含实现）。
- **TaskProcessor.java**: 任务规划与执行主链路：工具筛选、任务生成（LLM）、组件依赖校验、顺序执行、错误兜底与日志。
- **TaskPlanEnricher.java**: 规划补参分发器：按 task.tool 找到 Tool 并调用 `enrichPlannedTask`。
- **ToolManager.java**: 工具注册与 Schema 导出（供 LLM 做工具选择与参数抽取）。
  - **工具发现（默认）**：类路径扫描（默认扫描 `com.qiyi.tools`，支持多包配置）
    - 配置优先级：`-Dworkagents.tools.scanPackages=...` → `agent.cfg` 的 `tools.scan.packages` → 默认 `com.qiyi.tools`
    - 单工具开关：`@Tool.Info(register=false)` 或 `@Tool.AutoRegister(false)`
  - **工具发现（可选）**：兼容 `ServiceLoader<Tool>`（当存在 `META-INF/services/com.qiyi.tools.Tool` 时可作为补充来源，但不再强制要求）
- **ToolContext / ToolMessenger**: 工具执行上下文与消息输出通道。
  - **DingTalkToolContext**: 钉钉环境下实现（同时是 ToolMessenger）。
  - **ConsoleToolContext**: 控制台环境下实现（同时是 ToolMessenger）。

#### 2.3.1 Android 自动化 (`com.qiyi.tools.android`)
- **AndroidBaseTool**: Android 工具的基类，提供设备连接、截图等通用方法。
- **TaobaoAppiumTool**: 基于 Appium 的淘宝自动化工具。
  - **核心功能**: 处理弹窗、关键词搜索、进店、商品加购、下单流程。
  - **特点**: 支持重试机制、页面滚动查找、钉钉消息实时反馈。

#### 2.3.2 示例工具 (`com.qiyi.tools.example`)
- **HelloWorldTool**: 新手入门工具。
  - **功能**: 演示基础的参数接收、消息发送，并提供进阶开发指引（如 LLM 调用代码示例）。
  - **用途**: 供开发者调试环境和学习如何编写自定义 Tool。

#### 2.3.3 钉钉工具 (`com.qiyi.tools.dingtalk`)
- **SendMessageTool**: 发送钉钉消息。
- **CreateEventTool**: 创建钉钉日程/事件。
- **SearchDingTalkUserTool**: 通过用户名的模糊搜索来查询钉钉用户的 Uid。


#### 2.3.4 证券金融 (`com.qiyi.tools.futu`)
- **GetStockQuoteTool**: 获取股票实时报价。
- **GetMarketSnapshotTool**: 获取市场快照。
- **GetCurKlineTool**: 获取 K 线数据。
- 包含其他多个针对富途 API 的封装工具。

#### 2.3.5 ERP 系统 (`com.qiyi.tools.erp`)
- **ErpBaseTool**: ERP 工具基类，处理登录会话和基础请求。
- **QueryErpOrderTool**: 查询 ERP 订单状态。
- **ErpAfterSaleTool**: 处理售后单据。

### 2.4 基础设施与工具 (`com.qiyi.util`)
- **LLMUtil**: 大模型统一入口，封装多供应商调用与路由。
- **DingTalkUtil / DingTalkService**: 钉钉集成与机器人回调消费。
- **RobotMsgCallbackConsumer**: 钉钉 Stream 回调入口，接入消息并转交 `TaskProcessor`。
- **PlayWrightUtil**: 浏览器自动化工具，包含高亮调试、截图等辅助功能。
- **OSSUtil**: 阿里云 OSS 文件上传下载。
- **PodCastUtil**: 包含 Chrome 窗口最小化等辅助功能。

### 2.5 Android RPA 框架 (`com.qiyi.service.android`)
- **IMobileRPAProcessor**: 定义移动端自动化操作的标准接口（点击、滑动、拖拽、查找元素等）。
- **BaseMobileRPAProcessor**: 实现接口的抽象基类。
  - 封装了 `AndroidDriver` 的初始化与销毁。
  - 提供了 `drag` (拖拽), `scroll` (滚动), `findElementsAndWait` (智能等待查找) 等通用方法。
- **AndroidDeviceManager**: ADB 设备管理工具。

### 2.6 播客系统 (`com.qiyi.service.podcast`)
核心自动化流程：
- **PodcastCrawler**: 基于 Playwright 实现的网页爬虫，负责扫描 Podwise 上的新内容并下载 PDF 稿件。
- **PodcastProcessor**: 负责文件的后续处理，如通过 LLM 进行批量重命名、摘要生成等。
- **PodcastManager**: 协调爬虫与处理器的业务管理器。
  - **更新**: 支持接收 `ToolContext`，在任务完成时不仅通知管理员，也能向当前调用上下文反馈结果。
- **PodCastPostToWechat**: 自动化发布流程，将处理好的播客内容同步到微信草稿箱。

### 2.7 富途证券集成 (`com.qiyi.service.futu`)
- **FutuOpenD**: 对富途官方 OpenD API 的单例封装，提供订阅、行情查询、快照获取等异步接口（基于 CompletableFuture）。
- **domain**: 完整的证券数据模型映射 (BasicQot, KLine, Ticker 等)。

## 3. 关键交互流程

本章节从“系统启动 → 消息接入 → 工具规划/执行 → 结果回传”按链路拆解，便于排障与二次开发。

### 3.1 启动流程（Agent 进程）

#### 3.1.1 通用：单实例与生命周期

- `AbstractAgent.start()`：先做进程内重复启动保护，再做跨进程文件锁单实例控制；随后调用子类 `doStart()`
- `AbstractAgent.stop()`：调用子类 `doStop()`，释放文件锁并结束运行

#### 3.1.2 DingTalkAgent（钉钉入口）

1. `DingTalkAgent.main()` → `start()`
2. `ToolManager.init()`：
   - `ComponentManager.initDefaults()` 注册默认组件（DINGTALK/FUTU）
   - 注册系统内置工具（Tool）
3. `DingTalkService.fromAppConfig().startRobotMsgCallbackConsumer()` 启动钉钉 Stream 回调消费
4. 钉钉平台回调到 `RobotMsgCallbackConsumer.execute()`，进入“消息处理流程”

#### 3.1.3 ConsoleAgent（本地调试入口）

1. `ConsoleAgent.main()` → `start()`
2. `ToolManager.init()` 完成组件默认注册 + 工具注册
3. 读取控制台输入，每行调用 `chat()` → `TaskProcessor.process()`

### 3.2 消息接入流程（钉钉）

入口类：`com.qiyi.service.dingtalk.stream.RobotMsgCallbackConsumer`

1. 解析回调 JSON，提取 `senderStaffId` 与文本内容 `text.content`
2. 从内容中解析 `@` 人员（用于决定消息回传对象）
3. 构造 `DingTalkToolContext(senderStaffId, atUserIds)`（同时实现 ToolContext + ToolMessenger）
4. 异步调用 `TaskProcessor.process(msg, ctx, ctx)`

### 3.3 任务规划与工具执行（TaskProcessor 主链路）

入口类：`com.qiyi.tools.TaskProcessor`

#### 3.3.1 两种运行模式

- **LLM 模式（默认）**：当配置了任意远程模型 Key（`LLMUtil.hasAnyRemoteChatKeyConfigured()==true`）
  1. 先做一次“工具筛选”（减少 Prompt 体积）
  2. 再让 LLM 输出任务 JSON（包含 reply + tasks）
  3. 顺序执行 tasks
- **直连工具模式（降级）**：当未配置远程模型 Key 且输入符合 `toolName key=value` 格式
  - 直接执行指定 tool（跳过 LLM），适合排障/脚本化调用

#### 3.3.2 LLM 规划输出结构（核心约束）

- `reply`：面向用户的中文提示
- `tasks[]`：任务序列，每个任务包含：
  - `tool`：工具名（必须能在 ToolManager 中找到）
  - `parameters`：参数对象（必须使用工具描述中的参数名）
  - `missing_info`：仅列“必填缺失项”；为空才会进入执行
- 支持链式参数：下游任务可用 `{{PREV_RESULT}}` 引用上一步执行结果

#### 3.3.3 组件依赖校验（requiredComponents）

在执行每个 tool 之前：

1. 读取 tool.requiredComponents()
2. 对每个 ComponentId：
   - `ComponentManager.get(id)` 必须存在

### 3.4 Tool 自检（Doctor）

- 运行态入口：`tool_doctor`（`com.qiyi.tools.agent.ToolDoctorTool`），直接输出 JSON 报告，便于线上排障
- 测试入口：`src/test/java/com/qiyi/tools/ToolDoctorTest.java`，用于在 CI/本地测试阶段提前发现配置或约束问题
- 覆盖检查：
  - 当前 ToolManager 已注册的工具列表（name/class/domain/type）
  - （可选）ServiceLoader 清单信息：是否存在与内容（用于某些打包形态排查）
  - 是否存在重复 tool name
  - description 声明了参数但 schema 解析为空的问题
  - requiredComponents 是否都在 `ComponentManager.initDefaults()` 注册范围内
  - domain 对应 skills md 缺失（仅告警，不阻断）

#### 3.3.4 工具执行与结果回传

1. `Tool.execute(params, context, messenger)`
2. 工具内部通过 `ToolMessenger.sendText/sendMarkdown/sendImage` 回传进度/结果
3. `TaskProcessor` 记录每个工具 begin/done（含耗时）；异常会：
   - 记录错误日志（含堆栈）
   - 给用户回传 `[任务失败] toolName: errorMessage`

#### 3.3.5 规划补参与 skills 注入

1. 工具筛选阶段：注入 `SkillPrompts.base()` + 全量工具 Schema，让 LLM 返回 `selected_tools`
2. 任务规划阶段：注入 `SkillPrompts.base()` + “选中工具所属业务域”的 md prompt（`SkillPrompts.forDomains(...)`）+ 选中工具 Schema
3. 补参阶段：`TaskPlanEnricher.enrich(userText, tasks)` 分发到各工具的 `Tool.enrichPlannedTask`，补齐遗漏必填参数后再进入执行

### 3.4 组件生命周期（start_component / stop_component）

组件统一由 `ComponentManager` 管理，核心接口为：

- `register(AgentComponent)`：注册组件
- `start(ComponentId/String)`：启动组件
- `stop(ComponentId/String)`：停止组件
- `status()`：获取状态快照（RUNNING/STOPPED/ERROR）

常见触发点：

- 用户主动调用工具：`start_component id=dingtalk` / `start_component id=futu`
- 工具执行前置检查提示：当 requiredComponents 未运行时提示启动

### 3.5 典型链路示例（端到端）

#### 3.5.1 发送钉钉消息（LLM 模式）

1. 钉钉用户发消息 → `RobotMsgCallbackConsumer` 接入
2. `TaskProcessor` 调用 LLM 选择 `send_message`
3. 执行前检查组件：DINGTALK 必须 RUNNING
4. `SendMessageTool.execute()` → `DingTalkService.sendTextMessageToEmployees(...)`
5. 结果通过 DingTalkToolContext 回传到钉钉

#### 3.5.2 查询富途行情（LLM 模式）

1. 用户输入“查一下 XX 股价”
2. `TaskProcessor` 规划到 futu 工具（如 `get_stock_quote`）
3. 执行前检查组件：FUTU 必须 RUNNING（OpenD 已连接）
4. 调用 `FutuOpenD` 获取行情，结果通过 messenger 回传

## 4. 依赖关系（模块、组件、工具）

### 4.1 模块依赖方向（高层 → 底层）

- `agent` → `tools` → `component` / `service` / `util`
- `tools` 通过 `ToolContext/ToolMessenger` 解耦入口渠道（钉钉/控制台）
- `component` 封装外部 runtime（钉钉回调消费 / 富途 OpenD 连接），供工具按需依赖

### 4.2 工具对组件的依赖（requiredComponents）

- 钉钉类工具（`com.qiyi.tools.dingtalk.*`）→ `ComponentId.DINGTALK`
  - 例：`send_message` / `create_event` / `search_dingtalk_user`
- 富途类工具（`com.qiyi.tools.futu.*`）→ `ComponentId.FUTU`
  - 例：`get_stock_quote` / `get_market_snapshot` / `get_cur_kline` 等
- 其他工具（erp/podcast/wechat/android 等）当前不强制依赖组件（后续如有长连接/外部 runtime，也建议抽为组件并声明 requiredComponents）

### 4.3 组件实现的外部依赖

- `DingTalkComponent`：
  - 依赖 `DingTalkConfig`（robot client id/secret 等配置）
  - 启动/停止：`DingTalkService.startRobotMsgCallbackConsumer()` / `stopRobotMsgCallbackConsumer()`
  - 状态：`DingTalkUtil.isRobotMsgCallbackConsumerRunning()`
- `FutuComponent`：
  - 依赖 `futu.opend.host/port`（可选，默认 127.0.0.1:11111）
  - 启动/停止：`FutuOpenD.connect()` / `disconnect()`
  - 状态：`FutuOpenD.isConnected()`

### 4.4 配置依赖（agent.cfg）

统一入口：`com.qiyi.config.AppConfig` 从 classpath 读取 `agent.cfg`。

- LLM Keys（远程模型）：`deepseek.api-key` / `aliyun.api-key` / `moonshot.api-key` / `minimax.api-key` / `glm.api-key` / `GEMINI_API_KEY`
- 钉钉机器人：`dingtalk.robot.client.id` / `dingtalk.robot.client.secret` / `dingtalk.robot.code` / `dingtalk.agent.id`（部分能力需要）
- 富途 OpenD：`futu.opend.host` / `futu.opend.port`
- 播客目录与管理员：`podcast.download.dir` / `podcast.publish.dir` / `podcast.published.dir` / `podcast.admin.users`
- 工具扫描包（可选）：`tools.scan.packages`（支持多个包，逗号/分号/空白分隔）

## 5. 技术栈
- **核心语言**: Java 17
- **自动化驱动**: Playwright (Web), Appium (Mobile)
- **AI 能力**: DeepSeek / 阿里云 DashScope / Gemini / Ollama（按配置与可用性路由）
- **消息推送**: 钉钉开放平台 SDK
- **构建工具**: Maven
- **数据处理**: FastJSON2, Protobuf
