# WorkAgents 项目结构与架构说明

本项目是一个基于大模型（LLM）驱动的自动化智能体（Agent）框架，主要通过钉钉机器人（DingTalk Robot）作为交互入口，集成了播客下载与处理、微信公众号发布、富途证券行情分析、ERP 订单查询以及 Android 自动化操作等多种能力。

## 1. 整体目录结构

```text
src/main/java/com/qiyi
├── agent           # 智能体启动入口
│   ├── DingTalkAgent.java      # 主程序入口，启动钉钉监听
│   ├── ConsoleAgent.java       # 命令行交互入口，支持自然语言测试
│   └── PodwiseAgent.java       # Podwise 专用 Agent，支持独立运行或被工具调用
├── tools           # 工具集实现（Agent 的“手”）
│   ├── ToolManager.java    # 工具管理与执行核心
│   ├── ToolRegistry.java   # 工具注册表
│   ├── Tool.java           # 工具接口定义
│   ├── ToolContext.java    # 工具执行上下文接口
│   ├── agent       # Agent 自身管理工具 (ListCapabilities, Shutdown)
│   ├── android     # Android 自动化工具 (TaobaoAppiumTool, AndroidBaseTool)
│   ├── context     # 上下文实现 (DingTalkToolContext, ConsoleToolContext)
│   ├── dingtalk    # 钉钉相关工具 (CreateEvent, SendMessage)
│   ├── erp         # ERP 系统集成工具 (QueryErpOrder, ErpAfterSale, ErpBaseTool)
│   ├── futu        # 富途证券集成工具 (GetStockQuote, GetMarketSnapshot...)
│   ├── podcast     # 播客处理工具 (DownloadPodcast)
│   └── wechat      # 微信发布工具 (PublishWechat)
├── podcast         # 播客核心业务逻辑
│   ├── service     # 爬虫(PodcastCrawler)、处理器(PodcastProcessor)、管理器(PodcastManager)
│   └── PodCastItem.java
├── futu            # 富途 OpenD 客户端封装与模型
│   ├── FutuOpenD.java
│   ├── domain      # 数据模型 (BasicQot, KLine, Ticker...)
│   └── constants   # 常量定义
├── android         # Android RPA 基础框架
│   ├── BaseMobileRPAProcessor.java # Appium 基础封装
│   ├── IMobileRPAProcessor.java    # RPA 接口定义
│   └── AndroidDeviceManager.java   # ADB 设备管理
├── util            # 通用工具类
│   ├── DingTalkUtil.java   # 钉钉消息处理核心
│   ├── LLMUtil.java        # 大模型调用封装
│   ├── OSSUtil.java        # 阿里云 OSS 工具
│   ├── PlayWrightUtil.java # Playwright 浏览器自动化
│   ├── PodCastUtil.java    # 播客相关辅助工具
│   └── PFileUtil.java      # 文件操作工具
├── config          # 配置管理 (AppConfig)
├── dingtalk        # 钉钉实体模型
├── wechat          # 微信实体模型
└── demo            # 演示与测试代码
```

## 2. 核心模块详解

### 2.1 Agent 入口 (`com.qiyi.agent`)
负责系统的启动、事件监听以及 `ToolContext` 的初始化。
- **DingTalkAgent**: 项目的主要启动类。监听钉钉消息，为每个请求创建 `DingTalkToolContext`。
- **ConsoleAgent**: 命令行测试工具。
  - **功能**: 允许开发者在控制台直接测试各个 Tool 的功能，无需启动钉钉机器人。
  - **交互模式**: 支持自然语言交互（如“帮我查一下茅台股价”），行为逻辑与 `DingTalkAgent` 一致；支持 `exit`/`quit` 命令退出。
  - **输出**: 使用 `ConsoleToolContext` 将工具的反馈结果直接打印到标准输出。
- **PodwiseAgent**: 针对 Podwise 平台的特定 Agent 实现。
  - **双重角色**: 既可以作为独立进程运行（`main` 方法），也可以被 `DownloadPodcastTool` 作为库调用以执行下载任务。

### 2.2 工具集 (`com.qiyi.tools`)
遵循 `Tool` 接口定义，是 Agent 可调用的具体原子能力。新架构引入了上下文感知（Context-Aware）机制，实现了工具逻辑与通信渠道的彻底解耦。

- **Tool.java**: 核心接口，定义了 `getName` (工具名), `getDescription` (工具描述) 和 `execute(JSONObject params, ToolContext context)` 方法。
- **ToolContext.java**: 工具执行上下文接口。封装了发送者信息 (`senderId`) 和消息发送能力 (`sendText`, `sendImage` 等)。
  - **DingTalkToolContext**: 钉钉环境下的实现，将消息路由到钉钉机器人。
  - **ConsoleToolContext**: 控制台环境下的实现，将消息输出到标准输出 (System.out)。
- **ToolRegistry.java**: 工具注册表，负责管理所有可用的工具实例，提供给 LLM 进行工具检索。
- **ToolManager.java**: 工具管理核心类。负责工具的自动注册、LLM 意图识别（Analyze）以及工具执行流程的编排。

#### 2.2.1 Android 自动化 (`com.qiyi.tools.android`)
- **AndroidBaseTool**: Android 工具的基类，提供设备连接、截图等通用方法。
- **TaobaoAppiumTool**: 基于 Appium 的淘宝自动化工具。
  - **核心功能**: 处理弹窗、关键词搜索、进店、商品加购、下单流程。
  - **特点**: 支持重试机制、页面滚动查找、钉钉消息实时反馈。

#### 2.2.2 钉钉工具 (`com.qiyi.tools.dingtalk`)
- **SendMessageTool**: 发送钉钉消息。
- **CreateEventTool**: 创建钉钉日程/事件。
- **SearchDingTalkUserTool**: 通过用户名的模糊搜索来查询钉钉用户的 Uid。


#### 2.2.3 证券金融 (`com.qiyi.tools.futu`)
- **GetStockQuoteTool**: 获取股票实时报价。
- **GetMarketSnapshotTool**: 获取市场快照。
- **GetCurKlineTool**: 获取 K 线数据。
- 包含其他多个针对富途 API 的封装工具。

#### 2.2.4 ERP 系统 (`com.qiyi.tools.erp`)
- **ErpBaseTool**: ERP 工具基类，处理登录会话和基础请求。
- **QueryErpOrderTool**: 查询 ERP 订单状态。
- **ErpAfterSaleTool**: 处理售后单据。

### 2.3 基础设施与工具 (`com.qiyi.util`)
- **LLMUtil**: 大模型调用工具类，封装了 DeepSeek 和 Gemini 的调用逻辑，支持多模态（图片分析）。
- **DingTalkUtil**: 钉钉集成核心工具。
  - **注意**: 随着 `ToolContext` 的引入，建议在 Tool 实现中使用 `ToolContext` 替代直接调用本类发送消息。
  - **消息监听**: `RobotMsgCallbackConsumer` 接收群/单聊消息。
- **PlayWrightUtil**: 浏览器自动化工具，包含高亮调试、截图等辅助功能。
- **OSSUtil**: 阿里云 OSS 文件上传下载。
- **PodCastUtil**: 包含 Chrome 窗口最小化等辅助功能。

### 2.4 Android RPA 框架 (`com.qiyi.android`)
- **IMobileRPAProcessor**: 定义移动端自动化操作的标准接口（点击、滑动、拖拽、查找元素等）。
- **BaseMobileRPAProcessor**: 实现接口的抽象基类。
  - 封装了 `AndroidDriver` 的初始化与销毁。
  - 提供了 `drag` (拖拽), `scroll` (滚动), `findElementsAndWait` (智能等待查找) 等通用方法。
- **AndroidDeviceManager**: ADB 设备管理工具。

### 2.5 播客系统 (`com.qiyi.podcast`)
核心自动化流程：
- **PodcastCrawler**: 基于 Playwright 实现的网页爬虫，负责扫描 Podwise 上的新内容并下载 PDF 稿件。
- **PodcastProcessor**: 负责文件的后续处理，如通过 LLM 进行批量重命名、摘要生成等。
- **PodcastManager**: 协调爬虫与处理器的业务管理器。
  - **更新**: 支持接收 `ToolContext`，在任务完成时不仅通知管理员，也能向当前调用上下文反馈结果。
- **PodCastPostToWechat**: 自动化发布流程，将处理好的播客内容同步到微信草稿箱。

### 2.6 富途证券集成 (`com.qiyi.futu`)
- **FutuOpenD**: 对富途官方 OpenD API 的单例封装，提供订阅、行情查询、快照获取等异步接口（基于 CompletableFuture）。
- **domain**: 完整的证券数据模型映射 (BasicQot, KLine, Ticker 等)。

## 3. 关键交互流程

1. **消息接收**: Agent 入口（`DingTalkAgent` 或 `ConsoleAgent`）接收用户指令。
2. **上下文构建**: Agent 根据当前环境创建对应的 `ToolContext` (如 `DingTalkToolContext`)。
3. **意图识别**: `ToolManager` 调用 `LLMUtil`，结合 `ToolRegistry` 中的工具描述进行意图分析。
4. **任务规划**: 大模型生成执行计划（JSON 格式的任务序列）。
5. **工具执行**: `ToolManager` 或 Agent 遍历任务列表，注入 `ToolContext` 并调用工具的 `execute` 方法。
   - 例如：用户输入 "帮我在淘宝买个iPhone"，识别出 `TaobaoAppiumTool`，传入 `ToolContext` 执行 `executeBuyFlow`。
6. **反馈结果**: 工具内部通过 `context.sendText()` 等方法反馈进度和结果，消息会自动路由到正确的渠道（钉钉或控制台）。

## 4. 技术栈
- **核心语言**: Java 8+
- **自动化驱动**: Playwright (Web), Appium (Mobile)
- **AI 能力**: DeepSeek, Google Gemini
- **消息推送**: 钉钉开放平台 SDK
- **构建工具**: Maven
- **数据处理**: FastJSON2, Protobuf
