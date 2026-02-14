# MultiModelAutoRun 工作流程说明（适合生成流程图）

本文基于 [MultiModelAutoRun.java](file:///Users/cenwenchu/Desktop/Demo/workAgents/src/main/java/com/qiyi/service/autoweb/MultiModelAutoRun.java) 总结其 E2E Runner 的执行链路、关键分支与主要数据结构，便于后续把流程转换成可视化流程图（或导入到自动生成工具）。

## 1. 目标与产物

- 目标：对同一批 Case（入口 URL + 用户任务）在多个 LLM 模型上执行“计划 → 代码 → 运行（可选修复）”，用于对比成功率/失败步骤/日志与 lint 结果。
- 产物：一份 JSON 报告（Report），落盘到 `autoweb/debug`。
  - 报告落盘：`StorageSupport.saveDebugArtifact(ts, "MULTI", "E2E", "multimodel_report", ...)`
  - 可选落盘：包含本地离线分析结果的 `multimodel_report_with_local_analysis`

## 2. 主流程概览（从 main 到报告）

入口：[MultiModelAutoRun.main](file:///Users/cenwenchu/Desktop/Demo/workAgents/src/main/java/com/qiyi/service/autoweb/MultiModelAutoRun.java#L127-L162)

高层步骤：

1) 构建 RunnerConfig（默认值）并应用运行时覆盖  
2) 填充 models 与 cases（main 里是硬编码示例）  
3) 执行一次 runOnce(cfg) 得到 Report  
4) 打印控制台摘要（每个 case×model 的成功/失败与是否修复）  
5) 报告序列化为 JSON 并落盘  
6) 可选：把压缩后的报告喂给本地模型做离线分析，再落一份带分析的报告

## 3. runOnce(cfg) 详细流程

入口：[runOnce](file:///Users/cenwenchu/Desktop/Demo/workAgents/src/main/java/com/qiyi/service/autoweb/MultiModelAutoRun.java#L203-L295)

### 3.1 初始化阶段

- 参数校验：cfg/models/cases 不能为空
- 清理运行产物：
  - [cleanupAutowebArtifacts](file:///Users/cenwenchu/Desktop/Demo/workAgents/src/main/java/com/qiyi/service/autoweb/MultiModelAutoRun.java#L789-L814) 会清空 `autoweb/cache` 与 `autoweb/debug` 的目录内容
- 生成 ts：`AutoWebAgent.newDebugTimestamp()`
- 构建 Report 并填充：
  - ts / models / captureMode / analysisPrompt / localAnalysisModel 等
- 加载 skills prompt：`GroovySupport.loadPrompts()`
- 连接浏览器：`PlayWrightUtil.connectAndAutomate()`

### 3.2 Case 循环

对每个 CaseInput：

- 初始化 CaseRunResult（id/entryUrl/userTask/runs）
- 打开页面 [openPage](file:///Users/cenwenchu/Desktop/Demo/workAgents/src/main/java/com/qiyi/service/autoweb/MultiModelAutoRun.java#L301-L334)
  - 取已有 BrowserContext（若不存在则新建）
  - `ctx.newPage()` 后导航到 entryUrl 并等待 `NETWORKIDLE`（超时 120s）
  - 额外等待 1000ms
- Model 循环：对 cfg.models 中每个 model 调用 `runSingleModelCase(...)`
  - 任一 model 的异常会被捕获并写入一个兜底的 ModelRunResult（错误标记为步骤 `(runner)`）
- 如果 openPage 失败：为每个 model 生成一个兜底的 ModelRunResult（错误标记为步骤 `(openPage)`）
- finally：关闭本次 case 的 Page

### 3.3 资源释放

- finally：`PlayWrightUtil.disconnectBrowser(...)`

## 4. runSingleModelCase(model, case, page, ...) 详细流程

入口：[runSingleModelCase](file:///Users/cenwenchu/Desktop/Demo/workAgents/src/main/java/com/qiyi/service/autoweb/MultiModelAutoRun.java#L336-L456)

一次 model×case 的运行，可能产生 1~2 个 ModelRunResult（首次 + 可选修复）。

### 4.1 PLAN 阶段

- 确保回到入口：`AutoWebAgent.ensureRootPageAtUrl(page, entryUrl, logger)`
- 构建 prompt：`buildPrompt(case)`（把 userTask 与 entryUrl 拼在一起）
- 生成计划：
  - `currentUrl = StorageSupport.safePageUrl(page)`
  - `planPayload = AutoWebAgent.buildPlanOnlyPayload(currentUrl, prompt, entryUrl)`（入口 URL 会写入 USER_PROVIDED_URL）
  - `planText = AutoWebAgent.generateGroovyScript(prompt, planPayload, logger, model)`
- 解析计划：
  - `parsed = AutoWebAgent.parsePlanFromText(planText)`
  - 记录 `planConfirmed / planHasQuestion / planSteps`
- 如果 planSteps 为空：
  - 记录错误 `"(plan) 未解析到任何步骤"`
  - 写入 runLogHead/runLogTail
  - 返回（不会进入 codegen/执行）

### 4.2 页面采集（为 CODEGEN 提供上下文）

- `snapshots = AutoWebAgent.prepareStepHtmls(page, planSteps, logger, captureMode)`
- 可选视觉补充（useVisualSupplement=true 时）：
  - `AutoWebAgentUI.buildPageVisualDescription(page, logger)`
  - 异常时写日志并置为空字符串

### 4.3 CODEGEN 阶段

- 构建 code payload：
  - `codePayload = AutoWebAgent.buildCodegenPayload(page, parsed.planText, snapshots, visualDescription?)`
- 生成 Groovy 脚本：
  - `code = AutoWebAgent.generateGroovyScript(prompt, codePayload, logger, model)`
  - `code = maybeNormalizeCode(code)`（normalize 后若 lint 通过则使用 normalize 版）
  - `lintErrors = GroovyLinter.check(code)`

### 4.4 执行阶段（逐步执行 PlanStep）

- 进入 [executePlanSteps](file:///Users/cenwenchu/Desktop/Demo/workAgents/src/main/java/com/qiyi/service/autoweb/MultiModelAutoRun.java#L468-L550)
- 记录本次执行的 stepResults / formattedErrors / runLogHead / runLogTail

### 4.5 修复阶段（可选）

触发条件：[shouldRepair](file:///Users/cenwenchu/Desktop/Demo/workAgents/src/main/java/com/qiyi/service/autoweb/MultiModelAutoRun.java#L458-L466)

- 条件：planSteps 非空，stepResults 非空，且存在任一步 `ok=false`

修复流程：

- 确保回到入口：`ensureRootPageAtUrl(...)`
- 采集“失败现场”的 freshHtml，并清洗为 freshCleanedHtml
- 构造修复提示：
  - `refineHint = buildRepairHint(out)`（包含失败步骤列表）
  - `execOutput = buildExecOutputForRepair(out)`（formattedErrors + 失败步骤的 error/logTail）
- 视觉补充（可选）：
  - 先读缓存 `AutoWebAgentUI.readCachedPageVisualDescription(...)`，无则重新 `buildPageVisualDescription(...)`
- 构造 refine payload：
  - `AutoWebAgent.buildRefinePayload(page, parsed.planText, snapshots, freshCleanedHtml, prompt, refineHint, visualDescription?)`
- 生成修复后脚本：
  - `refined = AutoWebAgent.generateRefinedGroovyScript(prompt, refinePayload, out.code, execOutput, refineHint, repairLogger, model)`
  - `refined = maybeNormalizeCode(refined)`
  - `lintErrors = GroovyLinter.check(refined)`
- 再次执行 executePlanSteps（repairAttempt=true，attemptIndex=2）

## 5. executePlanSteps：逐步执行与超时重试策略

入口：[executePlanSteps](file:///Users/cenwenchu/Desktop/Demo/workAgents/src/main/java/com/qiyi/service/autoweb/MultiModelAutoRun.java#L468-L550)

每次执行都会：

- 确保回到入口 URL（避免在“跑偏页面”执行）
- 为所有步骤共享一个 `groovy.lang.Binding sharedBinding`
  - 同一步/跨步共享变量依赖此 Binding

对每个 PlanStep：

1) 从整段 code 中提取该 step 的代码块  
   - [extractStepCode](file:///Users/cenwenchu/Desktop/Demo/workAgents/src/main/java/com/qiyi/service/autoweb/MultiModelAutoRun.java#L934-L983)
   - 支持用 “Step/步骤 n” 的标题切块；若完全找不到标题则把整段 code 当作 step1
2) 若没找到该 step 的代码：标记失败并继续下一步
3) 将 top-level `def x = ...` 改写为 `x = ...`，用于变量提升与共享  
   - [promoteTopLevelDefs](file:///Users/cenwenchu/Desktop/Demo/workAgents/src/main/java/com/qiyi/service/autoweb/MultiModelAutoRun.java#L564-L578)
4) 选择最佳执行上下文（Page/Frame）：`AutoWebAgent.waitAndFindContext(page, stepLogger)`
5) 执行 Groovy：`AutoWebAgent.executeWithGroovy(...)`
6) 若捕获到“超时类异常”：
   - 提升默认超时（boostedTimeoutMs = max(60s, baseTimeout*3)）
   - 等待 1000ms，再次选择上下文并重试一次执行
7) 异常落入 sr.error，并把 “步骤: i, 出错信息: reason” 写入 formattedErrors  
   - 若错误包含 “No such property:” 会额外打印 sharedBinding 当前可用变量名

## 6. 关键数据结构（结构定义）

### 6.1 MultiModelAutoRun 内部结构

来源：[MultiModelAutoRun.java](file:///Users/cenwenchu/Desktop/Demo/workAgents/src/main/java/com/qiyi/service/autoweb/MultiModelAutoRun.java#L41-L121)

```java
static class RunnerConfig {
  List<String> models;
  List<CaseInput> cases;
  AutoWebAgent.HtmlCaptureMode captureMode;
  boolean useVisualSupplement;
  boolean alsoStdout;
  boolean localAnalyze;
  String localAnalysisModel;
  int reportMaxChars;
}

static class CaseInput {
  String id;
  String entryUrl;
  String userTask;
}

static class StepExecutionResult {
  int stepIndex;
  boolean ok;
  long durationMs;
  String error;
  String logTail;
}

static class ModelRunResult {
  String model;
  String prompt;
  int attemptIndex;        // 1=首次, 2=修复后
  boolean repairAttempt;   // false=首次, true=修复后
  String planText;
  boolean planConfirmed;
  boolean planHasQuestion;
  List<AutoWebAgent.PlanStep> planSteps;
  String code;
  List<String> lintErrors;
  String entryUrlUsed;
  List<StepExecutionResult> stepResults;
  List<String> formattedErrors; // runner 统一格式化后的错误行
  String runLogHead;
  String runLogTail;
}

static class CaseRunResult {
  String id;
  String entryUrl;
  String userTask;
  List<ModelRunResult> runs;
}

static class Report {
  String ts;
  List<String> models;
  String captureMode;
  List<CaseRunResult> cases;
  String analysisPrompt;
  String localAnalysisModel;
  String localAnalysis;
}
```

### 6.2 AutoWebAgent 相关结构（被 MultiModelAutoRun 直接使用）

来源：[AutoWebAgent.java](file:///Users/cenwenchu/Desktop/Demo/workAgents/src/main/java/com/qiyi/service/autoweb/AutoWebAgent.java#L312-L377)

```java
static class ContextWrapper {
  Object context; // Page 或 Frame
  String name;
}

static class PlanStep {
  int index;
  String description;
  String targetUrl;
  String entryAction;
  String status;
}

static class PlanParseResult {
  String planText;
  List<PlanStep> steps;
  boolean confirmed;
  boolean hasQuestion;
}

static class HtmlSnapshot {
  int stepIndex;
  String url;
  String entryAction;
  String cacheKey;
  String cleanedHtml;
}
```

## 7. 用于生成流程图的“节点/边”定义（可直接机器解析）

下面是一个面向流程图生成工具的最小可用描述（节点 id 唯一；edge 的 when 代表分支条件）。

```yaml
nodes:
  - id: start
    label: main()
  - id: cfg_default
    label: defaultConfig + applyRuntimeOverrides
  - id: run_once
    label: runOnce(cfg)
  - id: cleanup
    label: cleanupAutowebArtifacts
  - id: load_prompts
    label: GroovySupport.loadPrompts
  - id: connect_browser
    label: PlayWrightUtil.connectAndAutomate
  - id: loop_cases
    label: for each CaseInput
  - id: open_page
    label: openPage(entryUrl)
  - id: loop_models
    label: for each model
  - id: plan
    label: PLAN (buildPlanOnlyPayload + generateGroovyScript)
  - id: parse_plan
    label: parsePlanFromText
  - id: plan_empty
    label: planSteps empty -> record error
  - id: prepare_html
    label: prepareStepHtmls
  - id: codegen
    label: CODEGEN (buildCodegenPayload + generateGroovyScript)
  - id: lint
    label: maybeNormalizeCode + GroovyLinter.check
  - id: exec
    label: executePlanSteps
  - id: repair_gate
    label: shouldRepair?
  - id: refine
    label: REFINE (freshHtml + buildRefinePayload + generateRefinedGroovyScript)
  - id: exec_repair
    label: executePlanSteps (repairAttempt=true)
  - id: save_report
    label: saveDebugArtifact(multimodel_report)
  - id: local_analyze_gate
    label: cfg.localAnalyze?
  - id: local_analyze
    label: buildCompactAnalysisInput + chatWithOllama
  - id: save_report2
    label: saveDebugArtifact(multimodel_report_with_local_analysis)
  - id: end
    label: End

edges:
  - from: start
    to: cfg_default
    when: always
  - from: cfg_default
    to: run_once
    when: always
  - from: run_once
    to: cleanup
    when: always
  - from: cleanup
    to: load_prompts
    when: always
  - from: load_prompts
    to: connect_browser
    when: always
  - from: connect_browser
    to: loop_cases
    when: success
  - from: loop_cases
    to: open_page
    when: per-case
  - from: open_page
    to: loop_models
    when: success
  - from: loop_models
    to: plan
    when: per-model
  - from: plan
    to: parse_plan
    when: always
  - from: parse_plan
    to: plan_empty
    when: planSteps is empty
  - from: parse_plan
    to: prepare_html
    when: planSteps not empty
  - from: prepare_html
    to: codegen
    when: always
  - from: codegen
    to: lint
    when: always
  - from: lint
    to: exec
    when: always
  - from: exec
    to: repair_gate
    when: always
  - from: repair_gate
    to: refine
    when: has failed step
  - from: repair_gate
    to: loop_models
    when: no failed step (next model)
  - from: refine
    to: exec_repair
    when: always
  - from: exec_repair
    to: loop_models
    when: next model
  - from: loop_cases
    to: save_report
    when: all cases done
  - from: save_report
    to: local_analyze_gate
    when: always
  - from: local_analyze_gate
    to: local_analyze
    when: cfg.localAnalyze=true
  - from: local_analyze
    to: save_report2
    when: always
  - from: local_analyze_gate
    to: end
    when: cfg.localAnalyze=false
  - from: save_report2
    to: end
    when: always
```

## 8. Mermaid 流程图（可直接渲染）

```mermaid
flowchart TD
  A[main()] --> B[defaultConfig + applyRuntimeOverrides]
  B --> C[runOnce(cfg)]
  C --> D[cleanupAutowebArtifacts]
  D --> E[GroovySupport.loadPrompts]
  E --> F[connectAndAutomate]
  F --> G{for each Case}
  G --> H[openPage(entryUrl)]
  H --> I{for each model}
  I --> J[PLAN: buildPlanOnlyPayload + generateGroovyScript]
  J --> K[parsePlanFromText]
  K -->|no steps| L[record plan error + return]
  K -->|has steps| M[prepareStepHtmls]
  M --> N[CODEGEN: buildCodegenPayload + generateGroovyScript]
  N --> O[normalize + lint]
  O --> P[executePlanSteps]
  P --> Q{shouldRepair?}
  Q -->|no| I
  Q -->|yes| R[REFINE: freshHtml + buildRefinePayload + generateRefinedGroovyScript]
  R --> S[executePlanSteps (repair)]
  S --> I
  G -->|all cases done| T[saveDebugArtifact multimodel_report]
  T --> U{localAnalyze?}
  U -->|no| Z[End]
  U -->|yes| V[chatWithOllama]
  V --> W[saveDebugArtifact multimodel_report_with_local_analysis]
  W --> Z
```

## 9. 运行时参数（System Properties）

这些参数在类内已有解析逻辑（见 [resolveCaptureModeFromSystemProperties](file:///Users/cenwenchu/Desktop/Demo/workAgents/src/main/java/com/qiyi/service/autoweb/MultiModelAutoRun.java#L816-L822) 与相关 helper 方法），可用于在 CI/命令行覆盖部分行为：

- `autoweb.e2e.captureMode`: `ARIA` / `ARIA_SNAPSHOT` / `A11Y` / `RAW_HTML`
- `autoweb.e2e.simplifiedHtml`: `true/false`（当 captureMode 未显式指定时决定使用 ARIA_SNAPSHOT 还是 RAW_HTML）
- `autoweb.e2e.visualSupplement`: `true/false`
- `autoweb.e2e.stdout`: `true/false`
- `autoweb.e2e.localAnalyze`: `true/false`
- `autoweb.e2e.localModel`: 例如 `qwen3:8b`
- `autoweb.e2e.reportMaxChars`: 报告落盘时的最大字符数（截断保护）
- `autoweb.e2e.casesFile`: 读取 case JSON 文件路径
- `autoweb.e2e.cases`: inline case 字符串（`;;` 分隔 case，`|||` 分隔 url/task）
