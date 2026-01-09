# WorkAgents 项目结构与架构说明

本项目是一个基于大模型（LLM）驱动的自动化智能体（Agent）框架，主要通过钉钉机器人（DingTalk Robot）作为交互入口，集成了播客下载与处理、微信公众号发布、富途证券行情分析、ERP 订单查询以及 Android 自动化操作等多种能力。

## 1. 整体目录结构

```text
src/main/java/com/qiyi
├── agent           # 智能体启动入口
├── tools           # 工具集实现（Agent 的“手”）
│   ├── agent       # Agent 自身管理工具
│   ├── android     # Android 自动化工具 (Appium/ADB)
│   ├── dingtalk    # 钉钉相关工具
│   ├── erp         # ERP 系统集成工具
│   ├── futu        # 富途证券集成工具
│   ├── podcast     # 播客处理工具
│   └── wechat      # 微信发布工具
├── podcast         # 播客核心业务逻辑
│   └── service     # 爬虫、处理器、管理器等
├── futu            # 富途 OpenD 客户端封装与模型
├── android         # Android RPA 基础框架
├── util            # 通用工具类（LLM、OSS、通知、Playwright等）
├── config          # 配置管理
├── dingtalk        # 钉钉实体模型
└── wechat          # 微信实体模型
```

## 2. 核心模块介绍

### 2.1 Agent 入口 (`com.qiyi.agent`)
- **DingTalkAgent**: 项目的主要启动类。负责初始化钉钉机器人的消息监听回调，并维持程序运行。
- **PodwiseAgent**: 针对 Podwise 平台的特定 Agent 实现。

### 2.2 工具集 (`com.qiyi.tools`)
遵循 `Tool` 接口定义，是 Agent 可调用的具体原子能力：
- **Tool.java**: 接口定义，包含 `getName`, `getDescription` 和 `execute` 方法。
- **ToolRegistry.java**: 工具注册表，负责管理所有可用的工具实例。
- **具体工具实现**:
    - `DownloadPodcastTool`: 触发播客扫描与下载任务。
    - `PublishWechatTool`: 自动发布文章到微信公众号。
    - `GetStockQuoteTool`: 获取股票实时行情（富途）。
    - `QueryErpOrderTool`: 查询 ERP 订单状态。
    - `TaobaoAppiumTool`: 淘宝 App 自动化操作示例。

### 2.3 播客系统 (`com.qiyi.podcast`)
核心自动化流程：
- **PodcastCrawler**: 基于 Playwright 实现的网页爬虫，负责扫描 Podwise 上的新内容并下载 PDF 稿件。
- **PodcastProcessor**: 负责文件的后续处理，如通过 LLM 进行批量重命名、摘要生成等。
- **PodcastManager**: 协调爬虫与处理器的业务管理器。
- **PodCastPostToWechat**: 自动化发布流程，将处理好的播客内容同步到微信草稿箱。

### 2.4 富途证券集成 (`com.qiyi.futu`)
- **FutuOpenD**: 对富途官方 OpenD API 的单例封装，提供订阅、行情查询、快照获取等异步接口（基于 CompletableFuture）。
- **domain/constants**: 完整的证券数据模型映射。

### 2.5 基础设施与工具 (`com.qiyi.util`)
- **LLMUtil**: 大模型调用工具类，封装了 DeepSeek 和 Gemini 的调用逻辑，支持多模态（图片分析）。
- **DingTalkUtil**: 钉钉集成核心工具。负责：
    - 机器人消息的回调解析。
    - 工具的选择（Intent Selection）与任务规划（Execution Plan）。
    - 消息的发送（文本、Markdown、卡片、工作通知等）。
- **PlayWrightUtil**: 浏览器自动化工具，包含高亮调试、截图等辅助功能。
- **OSSUtil**: 阿里云 OSS 文件上传下载。

### 2.6 Android RPA (`com.qiyi.android`)
- **BaseMobileRPAProcessor**: 移动端 RPA 的基类，封装了 Appium 常用操作（查找元素、点击、滑动、按键等）。
- **AndroidDeviceManager**: 管理连接的 Android 设备。

## 3. 关键交互流程

1. **消息接收**: `DingTalkUtil` 监听到钉钉用户的文字输入。
2. **意图识别**: 经过 `LLMUtil` 调用大模型，根据 `ToolRegistry` 中的工具描述选择最匹配的工具。
3. **任务规划**: 大模型生成执行计划（JSON 格式的任务序列）。
4. **工具执行**: `DingTalkUtil` 遍历任务列表，调用对应工具的 `execute` 方法。
5. **反馈结果**: 执行结果通过钉钉机器人异步或同步返回给用户。

## 4. 技术栈
- **核心语言**: Java 8+
- **自动化驱动**: Playwright (Web), Appium (Mobile)
- **AI 能力**: DeepSeek, Google Gemini
- **消息推送**: 钉钉开放平台 SDK
- **构建工具**: Maven
- **数据处理**: FastJSON2, Protobuf
