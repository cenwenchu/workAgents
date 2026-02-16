package com.qiyi.service.autoweb;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.microsoft.playwright.CDPSession;
import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Page;
import com.qiyi.config.AppConfig;
import com.qiyi.util.LLMUtil;
import com.qiyi.util.PlayWrightUtil;
import javax.swing.SwingUtilities;

/**
 * AutoWeb 的主入口与编排器。
 *
 * 角色定位：
 * - “门面（Facade）+ 编排（Orchestration）”：把浏览器连接、页面采集、payload 组装、LLM 调用、
 *   Groovy 静态检查、脚本执行串成一条可复用链路；
 * - 供 {@link AutoWebAgentUI} 使用：UI 只负责交互与状态展示，核心业务逻辑集中在此类的静态方法中；
 * - 供回归框架使用：例如 {@link MultiModelAutoRun} 复用本类完成多模型对比执行。
 *
 * 典型链路：
 * 1) 生成计划（PLAN_*）：{@link PayloadSupport} → {@link GroovySupport#generateGroovyScript} → {@link #parsePlanFromText}
 * 2) 生成代码（CODEGEN）：{@link #prepareStepHtmls} → {@link PayloadSupport} → {@link GroovySupport#generateGroovyScript} → {@link GroovyLinter}
 * 3) 修正代码（REFINE_CODE）：freshHtml/clean → {@link PayloadSupport} → {@link GroovySupport#generateRefinedGroovyScript}
 * 4) 执行：确保入口 URL → 选择最佳上下文（Frame/Page）→ GroovyShell 绑定 {@link WebDSL} 执行
 */
public class AutoWebAgent {
    static String ACTIVE_MODEL = "DEEPSEEK";
    public static final Object PLAYWRIGHT_LOCK = new Object();
    private static final Gson PRETTY_GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    static java.util.List<String> supportedModelKeys() {
        return java.util.Arrays.asList(
                "DEEPSEEK",
                "QWEN_MAX",
                "MOONSHOT",
                "GLM",
                "MINIMAX",
                "GEMINI",
                "OLLAMA_QWEN3_8B"
        );
    }

    static String[] supportedModelDisplayNames() {
        java.util.List<String> keys = supportedModelKeys();
        String[] out = new String[keys.size()];
        for (int i = 0; i < keys.size(); i++) {
            out[i] = modelKeyToDisplayName(keys.get(i));
        }
        return out;
    }

    static String modelKeyToDisplayName(String modelKey) {
        String k = modelKey == null ? "" : modelKey.trim().toUpperCase();
        if ("QWEN_MAX".equals(k)) return "Qwen-Max";
        if ("GEMINI".equals(k)) return "Gemini";
        if ("MOONSHOT".equals(k)) return "Moonshot";
        if ("GLM".equals(k)) return "GLM";
        if ("MINIMAX".equals(k)) return "Minimax";
        if ("OLLAMA_QWEN3_8B".equals(k)) return "Ollama Qwen3:8B";
        return "DeepSeek";
    }

    public static String normalizeModelKey(String displayOrKey) {
        if (displayOrKey == null) return "DEEPSEEK";
        String raw = displayOrKey.trim();
        if (raw.isEmpty()) return "DEEPSEEK";
        String upper = raw.toUpperCase();

        if (upper.contains("DEEPSEEK")) return "DEEPSEEK";
        if (upper.contains("QWEN_MAX") || upper.contains("QWEN-MAX") || upper.contains("ALIYUN_QWEN_MAX")) return "QWEN_MAX";
        if (upper.contains("GEMINI")) return "GEMINI";
        if (upper.contains("MOONSHOT")) return "MOONSHOT";
        if (upper.equals("GLM") || upper.contains("ZHIPU")) return "GLM";
        if (upper.contains("MINIMAX")) return "MINIMAX";
        if (upper.contains("OLLAMA") || upper.contains("QWEN3:8B") || upper.contains("QWEN3_8B") || upper.contains("QWEN3")) return "OLLAMA_QWEN3_8B";

        String maybeKey = upper.replace('-', '_').replace(' ', '_');
        for (String k : supportedModelKeys()) {
            if (k.equalsIgnoreCase(maybeKey)) return k;
        }
        return "DEEPSEEK";
    }

    /**
     * 页面采集模式。
     *
     * - RAW_HTML：直接取 DOM HTML，适合结构化节点与属性较完整的页面；
     * - ARIA_SNAPSHOT：走可访问性语义快照，适合 iframe/虚拟列表/结构复杂但语义稳定的页面。
     */
    public enum HtmlCaptureMode {
        RAW_HTML,
        ARIA_SNAPSHOT
    }

    /**
     * CLI 入口，解析参数并启动 UI。
     *
     * 约定：
     * - 会在启动时清理 autoweb/debug 与 autoweb/cache，避免调试文件与缓存污染当前运行；
     * - 会从 autoweb/skills 加载 prompt 模板（可热更新）。
     */
    public static void main(String[] args) {
        AutoWebAgentUtils.cleanDebugDirectory();
        AutoWebAgentUtils.cleanCacheDirectory();
        GroovySupport.loadPrompts();
        if (args.length < 2) {
            // Default example if no args provided
            String url = ""; // No default URL, rely on Plan to navigate
            // String userPrompt = "请帮我查询“待发货”的订单。等表格加载出来后，" +
            //         "把第一页的每条记录整理成，用中文逗号分隔，内容中有回车换行的就去掉，然后逐行输出；" +
            //         "再输出页面底部显示的总记录数（比如“共xx条”）。" +
            //         "最后选中第一页第一条记录，并点击“审核推单”。";

            //String userPrompt = "请帮我查询待发货所有的订单（包括多页的数据），并且输出订单的所有信息，输出格式为：\\\"列名:列内容（去掉回车换行）\\\"，然后用\\\"｜\\\"分隔，列的顺序保持表格的顺序，一条记录一行。输出以后，回到第一条订单，选中订单，然后点击审核推单，读取弹出页面的成功和失败的笔数，失败笔数大于0，页面上获取失败原因，也一起输出";
            String userPrompt = "请查询资料已完善平台为淘宝的商品，输出这些商品的商品信息、类目名称、库存、基本售价";


            StorageSupport.log(null, "CLI", "No arguments provided. Running default example", null);
            StorageSupport.log(null, "CLI", "URL=" + url, null);
            StorageSupport.log(null, "CLI", "Prompt=" + userPrompt, null);
            run(url, userPrompt);
        } else {
            if (args.length >= 3 && args[2] != null) {
                String modelKey = normalizeModelKey(args[2]);
                ACTIVE_MODEL = modelKey;
                String display = modelKeyToDisplayName(modelKey);
                if ("OLLAMA_QWEN3_8B".equals(modelKey)) {
                    StorageSupport.log(null, "CLI", "Using model: " + display + " (" + LLMUtil.OLLAMA_MODEL_QWEN3_8B + " @ " + LLMUtil.OLLAMA_HOST + ")", null);
                } else {
                    StorageSupport.log(null, "CLI", "Using model: " + display + " (" + modelKey + ")", null);
                }
            } else {
                ACTIVE_MODEL = "DEEPSEEK";
            }
            run(args[0], args[1]);
        }
    }

    /**
     * 连接浏览器并启动控制台 UI。
     *
     * @param url 初始入口 URL（可为空；为空时会尽量附着到现有激活页面）
     * @param userPrompt 用户任务描述（自然语言）
     */
    public static void run(String url, String userPrompt) {
        GroovySupport.loadPrompts();
        PlayWrightUtil.Connection connection = PlayWrightUtil.connectAndAutomate();
        if (connection == null) {
            StorageSupport.log(null, "BROWSER", "Failed to connect to browser", null);
            return;
        }

        try {
            Page page = null;
            
            // If URL is provided, try to find or navigate
            if (url != null && !url.isEmpty()) {
                String urlCheck = PlanRoutingSupport.stripUrlQuery(url);
                // Try to find if the page is already open
                for (com.microsoft.playwright.BrowserContext context : connection.browser.contexts()) {
                    for (Page p : context.pages()) {
                        if (PlanRoutingSupport.stripUrlQuery(safePageUrl(p)).startsWith(urlCheck)) {
                            page = p;
                            break;
                        }
                    }
                    if (page != null) break;
                }

                if (page == null) {
                    StorageSupport.log(null, "BROWSER_ATTACH", "Page not found, creating new page and navigating", null);
                    // 优先使用现有的上下文（即用户配置目录的上下文），以保留登录态
                    if (!connection.browser.contexts().isEmpty()) {
                        page = connection.browser.contexts().get(0).newPage();
                    } else {
                        page = connection.browser.newPage();
                    }
                    page.navigate(url);
                } else {
                    StorageSupport.log(null, "BROWSER_ATTACH", "Found existing page | title=" + page.title(), null);
                    page.bringToFront();
                }

                // Check if we are on the target page, if not wait (e.g. for login)
                long maxWaitTime = 120000; // 120 seconds
                long interval = 2000; // 2 seconds
                long startTime = System.currentTimeMillis();

                while (!PlanRoutingSupport.stripUrlQuery(safePageUrl(page)).startsWith(urlCheck)) {
                    if (System.currentTimeMillis() - startTime > maxWaitTime) {
                        throw new RuntimeException("Timeout waiting for target URL. Current URL: " + safePageUrl(page));
                    }
                    StorageSupport.log(null, "BROWSER_ATTACH", "Waiting for target URL | current=" + safePageUrl(page) + " | target=" + urlCheck + " | ignoreQuery=true", null);
                    synchronized (PLAYWRIGHT_LOCK) {
                        page.waitForTimeout(interval);
                    }
                }
            } else {
                // No URL provided - Just pick the first active page or create a blank one
                StorageSupport.log(null, "BROWSER_ATTACH", "No target URL provided. Attaching to active page", null);
                if (!connection.browser.contexts().isEmpty() && !connection.browser.contexts().get(0).pages().isEmpty()) {
                    // Pick the last used page (usually the most relevant)
                    com.microsoft.playwright.BrowserContext context = connection.browser.contexts().get(0);
                    // Use the last page as it's likely the most recently opened
                    page = context.pages().get(context.pages().size() - 1);
                    page.bringToFront();
                    StorageSupport.log(null, "BROWSER_ATTACH", "Attached to existing page | title=" + page.title() + " | url=" + safePageUrl(page), null);
                } else {
                    StorageSupport.log(null, "BROWSER_ATTACH", "No active pages found. Creating a blank new page", null);
                    if (!connection.browser.contexts().isEmpty()) {
                        page = connection.browser.contexts().get(0).newPage();
                    } else {
                        page = connection.browser.newPage();
                    }
                }
            }

            boolean fastStart = (url == null || url.isEmpty());
            if (!fastStart) {
                try {
                    page.waitForLoadState(
                            com.microsoft.playwright.options.LoadState.NETWORKIDLE,
                            new com.microsoft.playwright.Page.WaitForLoadStateOptions().setTimeout(AppConfig.getInstance().getAutowebWaitForLoadStateTimeoutMs())
                    );
                } catch (Exception e) {
                    StorageSupport.log(null, "PAGE_WAIT", "Wait for NETWORKIDLE timed out or failed, continuing", e);
                }

                StorageSupport.log(null, "PAGE_WAIT", "Waiting 1 second for dynamic content to render", null);
                page.waitForTimeout(1000);
            } else {
                StorageSupport.log(null, "PAGE_WAIT", "Fast start mode: Skipping network idle wait and dynamic content wait", null);
            }

            com.microsoft.playwright.Frame contentFrame = null;
            String frameName = "";
            double maxArea = 0;

            if (page.frames().size() > 1) {
                int maxScanRetries = fastStart ? 1 : 3;
                int waitBetweenScansMs = fastStart ? 0 : 1000;
                StorageSupport.log(null, "FRAME_SCAN", "Checking frames | maxScanRetries=" + maxScanRetries + " | waitBetweenScansMs=" + waitBetweenScansMs, null);
                for (int i = 0; i < maxScanRetries; i++) {
                    maxArea = 0;
                    contentFrame = null;
                    for (com.microsoft.playwright.Frame f : page.frames()) {
                        if (f == page.mainFrame()) continue;

                        try {
                            com.microsoft.playwright.ElementHandle element = f.frameElement();
                            if (element != null) {
                                com.microsoft.playwright.options.BoundingBox box = element.boundingBox();
                                if (box != null) {
                                    double area = box.width * box.height;
                                    String frameUrl = "";
                                    try {
                                        synchronized (PLAYWRIGHT_LOCK) {
                                            frameUrl = f.url();
                                        }
                                    } catch (Exception ignored) {}
                                    StorageSupport.log(null, "FRAME_SCAN", "Found frame | round=" + i + " | name=" + f.name() + " | url=" + frameUrl + " | area=" + (long) area, null);

                                    if (box.width > 0 && box.height > 0) {
                                        if (area > maxArea) {
                                            maxArea = area;
                                            contentFrame = f;
                                            frameName = f.name();
                                        }
                                    }
                                }
                            }
                        } catch (Exception e) {
                            StorageSupport.log(null, "FRAME_SCAN", "Error checking frame=" + f.name(), e);
                        }
                    }

                    if (contentFrame != null) {
                        StorageSupport.log(null, "FRAME_SCAN", "Identified largest frame as content frame | name=" + frameName + " | area=" + (long) maxArea, null);
                        break;
                    }

                    if (waitBetweenScansMs > 0) {
                        StorageSupport.log(null, "FRAME_SCAN", "No significant child frame found yet. Waiting " + (waitBetweenScansMs / 1000) + "s", null);
                        page.waitForTimeout(waitBetweenScansMs);
                    }
                }
            } else {
                StorageSupport.log(null, "FRAME_SCAN", "No child frames detected, skipping frame scan", null);
            }

            // Launch UI
            StorageSupport.log(null, "UI", "Launching Control UI", null);
            // Use contentFrame if available, otherwise use page
            Object executionContext = (contentFrame != null) ? contentFrame : page;
            SwingUtilities.invokeLater(() -> AutoWebAgentUI.createGUI(executionContext, "", userPrompt, connection));

        } catch (Exception e) {
            StorageSupport.log(null, "INIT", "Error during initialization", e);
            if (connection != null && connection.playwright != null) {
                connection.playwright.close();
            }
            System.exit(1);
        }
    }
    
    /**
     * 执行上下文包装：承载当前用于采集/执行的 Playwright 上下文。
     *
     * 约定：
     * - context 可能是 {@link Page} 或 {@link Frame}；
     * - name 用于 UI 展示与日志标识（例如 “Main Page” 或 “Frame: xxx”）。
     */
    public static class ContextWrapper {
        public Object context;
        public String name;
        @Override
        public String toString() {
            return name;
        }
    }

    /**
     * 上下文扫描结果。
     * wrappers 为候选上下文列表；best 为当前最推荐的上下文（通常是内容区 iframe）。
     */
    public static class ScanResult {
        java.util.List<ContextWrapper> wrappers = new java.util.ArrayList<>();
        ContextWrapper best;
    }

    /**
     * 计划步骤结构（Plan Step）。
     *
     * 字段含义：
     * - index：从 1 开始的步骤序号；
     * - description：该步的自然语言描述；
     * - targetUrl：目标页面（可为 CURRENT_PAGE 或空，表示复用当前页面）；
     * - entryAction：当需要进入目标页面/区域时的入口动作提示（例如点击某菜单/按钮）；
     * - status：CONFIRMED/UNKNOWN 等，用于判断计划是否可执行。
     */
    public static class PlanStep {
        public int index;
        public String description;
        public String targetUrl;
        public String entryAction;
        public String status;
    }

    /**
     * 计划解析结果。
     * planText 为原始计划文本（可能已截取 PLAN_START~PLAN_END）；steps 为解析出的步骤列表；
     * confirmed/hasQuestion 用于驱动 UI 是否需要补充入口地址或重新规划。
     */
    public static class PlanParseResult {
        public String planText;
        public java.util.List<PlanStep> steps = new java.util.ArrayList<>();
        public boolean confirmed;
        public boolean hasQuestion;
    }

    /**
     * 步骤 HTML 快照结构。
     * 采集与清洗后的页面内容会写入 autoweb/cache，并在生成 CODEGEN/REFINE_CODE payload 时复用。
     */
    public static class HtmlSnapshot {
        public int stepIndex;
        public String url;
        public String entryAction;
        public String cacheKey;
        public String cleanedHtml;
    }

    /**
     * 单模型会话状态（UI 侧每个模型一个会话）。
     * 该对象在 {@link AutoWebAgentUI} 中承载计划/步骤、采集结果与执行阶段的状态切换。
     */
    public static class ModelSession {
        public String userPrompt;
        public String planText;
        public java.util.List<PlanStep> steps = new java.util.ArrayList<>();
        public java.util.Map<Integer, HtmlSnapshot> stepSnapshots = new java.util.HashMap<>();
        public boolean planConfirmed;
        public boolean htmlPrepared;
        public HtmlCaptureMode htmlCaptureMode;
        public boolean htmlA11yInterestingOnly;
        public String lastArtifactType;
    }

    static String safePageUrl(Page page) {
        return StorageSupport.safePageUrl(page);
    }

    static boolean looksLikeUrl(String s) {
        return PlanRoutingSupport.looksLikeUrl(s);
    }

    private static String normalizeUrlToken(String s) {
        return PlanRoutingSupport.normalizeUrlToken(s);
    }

    static String extractFirstUrlFromText(String text) {
        return PlanRoutingSupport.extractFirstUrlFromText(text);
    }

    static java.util.LinkedHashMap<String, String> extractUrlMappingsFromText(String text) {
        return PlanRoutingSupport.extractUrlMappingsFromText(text);
    }

    private static boolean waitForUrlPrefix(Page page, String expectedPrefix, long maxWaitMs, long intervalMs, java.util.function.Consumer<String> uiLogger, String stage) {
        return PlanRoutingSupport.waitForUrlPrefix(page, expectedPrefix, maxWaitMs, intervalMs, uiLogger, stage);
    }

    private static String firstQuotedToken(String s) {
        return PlanRoutingSupport.firstQuotedToken(s);
    }

    public static PlanParseResult parsePlanFromText(String text) {
        return PlanRoutingSupport.parsePlanFromText(text);
    }

    static String buildEntryInputHint(java.util.List<String> needModels, java.util.Map<String, ModelSession> sessionsByModel) {
        return PlanRoutingSupport.buildEntryInputHint(needModels, sessionsByModel);
    }

    private static HtmlSnapshot readCachedHtml(int stepIndex, String url, String entryAction, HtmlCaptureMode captureMode, boolean a11yInterestingOnly) {
        return HtmlSnapshotDao.readCachedHtml(stepIndex, url, entryAction, captureMode, a11yInterestingOnly);
    }

    private static HtmlSnapshot writeCachedHtml(int stepIndex, String url, String entryAction, HtmlCaptureMode captureMode, boolean a11yInterestingOnly, String rawHtml, String cleanedHtml) {
        return HtmlSnapshotDao.writeCachedHtml(stepIndex, url, entryAction, captureMode, a11yInterestingOnly, rawHtml, cleanedHtml);
    }

    private static HtmlSnapshot captureAndCacheSnapshot(int stepIndex, String url, String entryAction, Object pageOrFrame, HtmlCaptureMode captureMode, boolean a11yInterestingOnly) {
        String rawHtml = "";
        try { rawHtml = getPageContent(pageOrFrame, captureMode, a11yInterestingOnly); } catch (Exception ignored) {}
        String cleaned = cleanCapturedContent(rawHtml, captureMode);
        return writeCachedHtml(stepIndex, url, entryAction, captureMode, a11yInterestingOnly, rawHtml, cleaned);
    }

    static String newDebugTimestamp() {
        return StorageSupport.newDebugTimestamp();
    }

    static String stackTraceToString(Throwable t) {
        return StorageSupport.stackTraceToString(t);
    }

    static void saveDebugArtifacts(String rawHtml, String cleanedHtml, String code, java.util.function.Consumer<String> uiLogger) {
        AutoWebAgentUtils.saveDebugArtifacts(rawHtml, cleanedHtml, code, uiLogger);
    }

    static void saveDebugCodeVariant(String code, String modelName, String tag, java.util.function.Consumer<String> uiLogger) {
        AutoWebAgentUtils.saveDebugCodeVariant(code, modelName, tag, uiLogger);
    }

    static int clearDirFiles(java.nio.file.Path dir, java.util.function.Consumer<String> uiLogger) {
        return AutoWebAgentUtils.clearDirFiles(dir, uiLogger);
    }

    public static String buildPlanOnlyPayload(Page currentPage, String userPrompt) {
        return PayloadSupport.buildPlanOnlyPayload(currentPage, userPrompt);
    }

    public static String buildPlanOnlyPayload(String currentUrl, String userPrompt) {
        return PayloadSupport.buildPlanOnlyPayload(currentUrl, userPrompt);
    }

    public static String buildPlanOnlyPayload(String currentUrl, String userPrompt, String entryUrl) {
        return PayloadSupport.buildPlanOnlyPayload(currentUrl, userPrompt, entryUrl);
    }

    static String buildPlanEntryPayload(Page currentPage, String userPrompt) {
        return PayloadSupport.buildPlanEntryPayload(currentPage, userPrompt);
    }

    static String buildPlanEntryPayload(String currentUrl, String userPrompt) {
        return PayloadSupport.buildPlanEntryPayload(currentUrl, userPrompt);
    }

    static String buildPlanEntryPayload(String currentUrl, String userPrompt, String entryUrl) {
        return PayloadSupport.buildPlanEntryPayload(currentUrl, userPrompt, entryUrl);
    }

    static String buildPlanRefinePayload(Page currentPage, String userPrompt, String refineHint) {
        return PayloadSupport.buildPlanRefinePayload(currentPage, userPrompt, refineHint);
    }

    static String buildPlanRefinePayload(Page currentPage, String userPrompt, String refineHint, String visualDescription) {
        return PayloadSupport.buildPlanRefinePayload(currentPage, userPrompt, refineHint, visualDescription);
    }

    static String buildPlanRefinePayload(String currentUrl, String userPrompt, String refineHint) {
        return PayloadSupport.buildPlanRefinePayload(currentUrl, userPrompt, refineHint);
    }

    static String buildPlanRefinePayload(String currentUrl, String userPrompt, String refineHint, String visualDescription) {
        return PayloadSupport.buildPlanRefinePayload(currentUrl, userPrompt, refineHint, visualDescription);
    }

    public static String buildCodegenPayload(Page currentPage, String planText, java.util.List<HtmlSnapshot> snapshots) {
        return PayloadSupport.buildCodegenPayload(currentPage, planText, snapshots);
    }

    public static String buildCodegenPayload(Page currentPage, String planText, java.util.List<HtmlSnapshot> snapshots, String visualDescription) {
        return PayloadSupport.buildCodegenPayload(currentPage, planText, snapshots, visualDescription);
    }

    public static String buildCodegenPayload(String currentUrl, String planText, java.util.List<HtmlSnapshot> snapshots) {
        return PayloadSupport.buildCodegenPayload(currentUrl, planText, snapshots);
    }

    public static String buildCodegenPayload(String currentUrl, String planText, java.util.List<HtmlSnapshot> snapshots, String visualDescription) {
        return PayloadSupport.buildCodegenPayload(currentUrl, planText, snapshots, visualDescription);
    }

    static String buildRefinePayload(Page currentPage, String planText, java.util.List<HtmlSnapshot> snapshots, String currentCleanedHtml, String userPrompt, String refineHint) {
        return PayloadSupport.buildRefinePayload(currentPage, planText, snapshots, currentCleanedHtml, userPrompt, refineHint);
    }

    static String buildRefinePayload(Page currentPage, String planText, java.util.List<HtmlSnapshot> snapshots, String currentCleanedHtml, String userPrompt, String refineHint, String visualDescription) {
        return PayloadSupport.buildRefinePayload(currentPage, planText, snapshots, currentCleanedHtml, userPrompt, refineHint, visualDescription);
    }

    static String buildRefinePayload(String currentUrl, String planText, java.util.List<HtmlSnapshot> snapshots, String currentCleanedHtml, String userPrompt, String refineHint) {
        return PayloadSupport.buildRefinePayload(currentUrl, planText, snapshots, currentCleanedHtml, userPrompt, refineHint);
    }

    static String buildRefinePayload(String currentUrl, String planText, java.util.List<HtmlSnapshot> snapshots, String currentCleanedHtml, String userPrompt, String refineHint, String visualDescription) {
        return PayloadSupport.buildRefinePayload(currentUrl, planText, snapshots, currentCleanedHtml, userPrompt, refineHint, visualDescription);
    }

    static long utf8Bytes(String s) {
        return StorageSupport.utf8Bytes(s);
    }

    static String saveDebugArtifact(String ts, String modelName, String mode, String kind, String content, java.util.function.Consumer<String> uiLogger) {
        return StorageSupport.saveDebugArtifact(ts, modelName, mode, kind, content, uiLogger);
    }

    private static JsonObject contextToJson(ContextWrapper ctx) {
        JsonObject o = new JsonObject();
        if (ctx == null) return o;
        o.addProperty("name", ctx.name == null ? "" : ctx.name);
        Object c = ctx.context;
        if (c == null) return o;
        if (c instanceof Frame) {
            o.addProperty("type", "Frame");
            String u = "";
            try { u = ((Frame) c).url(); } catch (Exception ignored) {}
            o.addProperty("url", u == null ? "" : u);
            String n = "";
            try { n = ((Frame) c).name(); } catch (Exception ignored) {}
            o.addProperty("frameName", n == null ? "" : n);
        } else if (c instanceof Page) {
            o.addProperty("type", "Page");
            String u = "";
            try { u = ((Page) c).url(); } catch (Exception ignored) {}
            o.addProperty("url", u == null ? "" : u);
        } else {
            o.addProperty("type", c.getClass().getName());
        }
        return o;
    }

    private static JsonObject frameToJson(Page page, Frame f) {
        JsonObject o = new JsonObject();
        if (f == null) return o;
        String n = "";
        String u = "";
        boolean isMain = false;
        try { n = f.name(); } catch (Exception ignored) {}
        try { u = f.url(); } catch (Exception ignored) {}
        try { isMain = (page != null && page.mainFrame() == f); } catch (Exception ignored) {}
        o.addProperty("name", n == null ? "" : n);
        o.addProperty("url", u == null ? "" : u);
        o.addProperty("isMainFrame", isMain);

        double area = 0;
        boolean isVisible = false;
        boolean hasFrameElement = false;
        JsonObject boxJson = new JsonObject();
        try {
            com.microsoft.playwright.ElementHandle el = f.frameElement();
            if (el != null) {
                hasFrameElement = true;
                com.microsoft.playwright.options.BoundingBox box = el.boundingBox();
                if (box != null) {
                    boxJson.addProperty("x", box.x);
                    boxJson.addProperty("y", box.y);
                    boxJson.addProperty("width", box.width);
                    boxJson.addProperty("height", box.height);
                    area = box.width * box.height;
                    isVisible = box.width > 0 && box.height > 0;
                }
            }
        } catch (Exception ignored) {}
        o.addProperty("hasFrameElement", hasFrameElement);
        o.addProperty("area", area);
        o.addProperty("isVisible", isVisible);
        o.add("boundingBox", boxJson);
        return o;
    }

    private static JsonObject scanAttemptToJson(Page page, ScanResult sr, int attempt) {
        JsonObject o = new JsonObject();
        o.addProperty("attempt", attempt);
        String pageUrl = "";
        try { pageUrl = page == null ? "" : page.url(); } catch (Exception ignored) {}
        o.addProperty("pageUrl", pageUrl == null ? "" : pageUrl);
        o.add("best", sr == null ? new JsonObject() : contextToJson(sr.best));

        JsonArray frames = new JsonArray();
        try {
            if (page != null) {
                for (Frame f : page.frames()) {
                    frames.add(frameToJson(page, f));
                }
            }
        } catch (Exception ignored) {}
        o.add("frames", frames);

        JsonArray wrappers = new JsonArray();
        try {
            if (sr != null && sr.wrappers != null) {
                for (ContextWrapper w : sr.wrappers) {
                    wrappers.add(contextToJson(w));
                }
            }
        } catch (Exception ignored) {}
        o.add("wrappers", wrappers);
        return o;
    }

    private static void saveFrameDiagnostics(
            int stepIndex,
            String targetUrl,
            String urlKey,
            HtmlCaptureMode captureMode,
            boolean a11yInterestingOnly,
            Page page,
            JsonArray attempts,
            ContextWrapper finalBest,
            java.util.function.Consumer<String> uiLogger
    ) {
        try {
            JsonObject root = new JsonObject();
            root.addProperty("stepIndex", stepIndex);
            root.addProperty("targetUrl", targetUrl == null ? "" : targetUrl);
            root.addProperty("urlKey", urlKey == null ? "" : urlKey);
            root.addProperty("captureMode", captureMode == null ? "" : captureMode.name());
            root.addProperty("a11yInterestingOnly", a11yInterestingOnly);
            String pageUrl = "";
            try { pageUrl = page == null ? "" : page.url(); } catch (Exception ignored) {}
            root.addProperty("pageUrl", pageUrl == null ? "" : pageUrl);
            root.add("finalBest", contextToJson(finalBest));
            root.add("attempts", attempts == null ? new JsonArray() : attempts);

            String ts = newDebugTimestamp();
            String path = saveDebugArtifact(
                    ts,
                    "AUTOWEB",
                    "HTML_CAPTURE",
                    "frame_diagnostics_step_" + stepIndex,
                    PRETTY_GSON.toJson(root),
                    uiLogger
            );
            if (uiLogger != null && path != null && !path.trim().isEmpty()) {
                uiLogger.accept("Frame diagnostics saved: " + path);
            }
        } catch (Exception ignored) {}
    }

    /**
     * 基于计划步骤与用户任务，推断执行入口 URL
     */
    static String chooseExecutionEntryUrl(ModelSession session, String currentPrompt) {
        return PlanRoutingSupport.chooseExecutionEntryUrl(session, currentPrompt);
    }

    /**
     * 确保 rootPage 定位到目标 URL（必要时导航或等待）
     */
    public static boolean ensureRootPageAtUrl(Page rootPage, String targetUrl, java.util.function.Consumer<String> uiLogger) {
        return PlanRoutingSupport.ensureRootPageAtUrl(rootPage, targetUrl, uiLogger);
    }

    /**
     * 等待并选取最佳执行上下文（主页面或 iframe）
     */
    public static ContextWrapper waitAndFindContext(Page rootPage, java.util.function.Consumer<String> uiLogger) {
        return PlanRoutingSupport.waitAndFindContext(rootPage, uiLogger);
    }

    private static ScanResult scanContexts(Page page) {
        return PlanRoutingSupport.scanContexts(page);
    }

    /**
     * 重新加载页面后选择最佳上下文
     */
    static ContextWrapper reloadAndFindContext(Page rootPage, java.util.function.Consumer<String> uiLogger) {
        return PlanRoutingSupport.reloadAndFindContext(rootPage, uiLogger);
    }

    /**
     * 按计划步骤采集页面 HTML 并返回快照
     *
     * @param rootPage 根页面
     * @param steps 计划步骤
     * @param uiLogger 可选日志输出
     * @return 采集结果列表
     */
    public static java.util.List<HtmlSnapshot> prepareStepHtmls(
            Page rootPage,
            java.util.List<PlanStep> steps,
            java.util.function.Consumer<String> uiLogger
    ) {
        return prepareStepHtmls(rootPage, steps, uiLogger, HtmlCaptureMode.RAW_HTML);
    }

    /**
     * 按采集模式采集 HTML 快照（RAW_HTML/ARIA_SNAPSHOT）
     *
     * @param rootPage 根页面
     * @param steps 计划步骤
     * @param uiLogger 可选日志输出
     * @param captureMode 采集模式
     * @return 采集结果列表
     */
    public static java.util.List<HtmlSnapshot> prepareStepHtmls(
            Page rootPage,
            java.util.List<PlanStep> steps,
            java.util.function.Consumer<String> uiLogger,
            HtmlCaptureMode captureMode
    ) {
        return prepareStepHtmls(rootPage, steps, uiLogger, captureMode, false);
    }

    /**
     * 按采集模式与 interestingOnly 采集 HTML 快照
     *
     * @param rootPage 根页面
     * @param steps 计划步骤
     * @param uiLogger 可选日志输出
     * @param captureMode 采集模式
     * @param a11yInterestingOnly ARIA 快照是否仅保留语义节点
     * @return 采集结果列表
     */
    public static java.util.List<HtmlSnapshot> prepareStepHtmls(
            Page rootPage,
            java.util.List<PlanStep> steps,
            java.util.function.Consumer<String> uiLogger,
            HtmlCaptureMode captureMode,
            boolean a11yInterestingOnly
    ) {
        if (steps == null || steps.isEmpty()) return new java.util.ArrayList<>();
        synchronized (PLAYWRIGHT_LOCK) {
            HtmlCaptureMode primaryMode = captureMode == null ? HtmlCaptureMode.RAW_HTML : captureMode;
            HtmlCaptureMode secondaryMode = (primaryMode == HtmlCaptureMode.ARIA_SNAPSHOT) ? HtmlCaptureMode.RAW_HTML : HtmlCaptureMode.ARIA_SNAPSHOT;
            java.util.List<HtmlSnapshot> out = new java.util.ArrayList<>();
            AutoWebAgentUI.FrameState frameState = AutoWebAgentUI.captureFrameState();

            // 同一 URL 的多个 step 只采集一次：避免重复打开页面与重复抓取，节省时间与 token
            java.util.HashMap<String, HtmlSnapshot> snapshotByUrl = new java.util.HashMap<>();
            com.microsoft.playwright.BrowserContext ctx = rootPage.context();
            Page tmp = null;
            java.util.List<Page> openedPages = new java.util.ArrayList<>();
            String rootUrl = safePageUrl(rootPage);
            try {
                // 统一使用一个临时 Page 进行跨 URL 导航采集，避免污染用户正在操作的 rootPage
                tmp = ctx.newPage();
                openedPages.add(tmp);
                for (PlanStep step : steps) {
                    if (step == null) continue;

                    String url = normalizeUrlToken(step.targetUrl);
                    boolean isCurrentPage = (url == null || url.trim().isEmpty() || "CURRENT_PAGE".equalsIgnoreCase(url.trim()));
                    if (isCurrentPage) {
                        // 计划里写 CURRENT_PAGE/空地址时，视为“采集当前 rootPage”
                        url = rootUrl;
                    }
                    url = normalizeUrlToken(url);
                    if (!looksLikeUrl(url)) {
                        // 不是标准 URL（可能是占位符/标签/UNKNOWN），只能尝试按原样读缓存
                        HtmlSnapshot cached = readCachedHtml(step.index, url, step.entryAction, primaryMode, a11yInterestingOnly);
                        if (cached != null) out.add(cached);
                        continue;
                    }

                    String urlKey = PlanRoutingSupport.stripUrlQuery(url == null ? "" : url.trim());
                    HtmlSnapshot existing = urlKey.isEmpty() ? null : snapshotByUrl.get(urlKey);
                    if (existing != null) {
                        // 同 URL 的 step 直接复用第一次采集的 cleanedHtml，只更新 stepIndex/entryAction
                        if (uiLogger != null) uiLogger.accept("复用已采集页面: Step " + step.index + " | " + urlKey + " | sameAsStep=" + existing.stepIndex);
                        HtmlSnapshot clone = new HtmlSnapshot();
                        clone.stepIndex = step.index;
                        clone.url = existing.url;
                        clone.entryAction = step.entryAction;
                        clone.cacheKey = existing.cacheKey;
                        clone.cleanedHtml = existing.cleanedHtml;
                        out.add(clone);
                        continue;
                    }

                    if (isCurrentPage) {
                        // 当前页面：优先走缓存；未命中时从 rootPage 采集，并自动选择最佳 iframe 上下文
                        HtmlSnapshot cachedPrimary = readCachedHtml(step.index, url, step.entryAction, primaryMode, a11yInterestingOnly);
                        HtmlSnapshot cachedSecondary = readCachedHtml(step.index, url, step.entryAction, secondaryMode, a11yInterestingOnly);
                        if (cachedPrimary != null && cachedSecondary != null) {
                            if (uiLogger != null) uiLogger.accept("命中缓存: Step " + step.index + " | " + urlKey);
                            if (!urlKey.isEmpty()) snapshotByUrl.putIfAbsent(urlKey, cachedPrimary);
                            out.add(cachedPrimary);
                            continue;
                        }

                        // 采集时尽量减少控制台窗口遮挡对页面可见性/布局的影响
                        AutoWebAgentUI.minimizeFrameIfNeeded(frameState);
                        if (uiLogger != null) uiLogger.accept("采集当前页面: Step " + step.index + " | " + urlKey);
                        Object captureContext = rootPage;
                        try {
                            ContextWrapper best = null;
                            boolean debugFrames = AppConfig.getInstance().isAutowebDebugFrameCaptureEnabled();
                            JsonArray attempts = new JsonArray();
                            for (int attempt = 0; attempt < 16; attempt++) {
                                // 页面可能动态加载 iframe：短轮询等待最佳内容上下文出现
                                ScanResult sr = scanContexts(rootPage);
                                if (sr != null) best = sr.best;
                                if (debugFrames) {
                                    attempts.add(scanAttemptToJson(rootPage, sr, attempt));
                                }
                                if (best != null && best.name != null && !"Main Page".equals(best.name)) break;
                                try { rootPage.waitForTimeout(500); } catch (Exception ignored) {}
                            }
                            if (debugFrames) {
                                saveFrameDiagnostics(step.index, url, urlKey, primaryMode, a11yInterestingOnly, rootPage, attempts, best, uiLogger);
                            }
                            if (best != null) {
                                String ctxUrl = "";
                                try {
                                    if (best.context instanceof com.microsoft.playwright.Frame) {
                                        ctxUrl = ((com.microsoft.playwright.Frame) best.context).url();
                                    } else if (best.context instanceof Page) {
                                        ctxUrl = ((Page) best.context).url();
                                    }
                                } catch (Exception ignored) {}
                                if (uiLogger != null) {
                                    uiLogger.accept("采集 HTML 上下文: Step " + step.index + " | " + best.name + (ctxUrl == null || ctxUrl.trim().isEmpty() ? "" : " | " + ctxUrl.trim()));
                                }
                                captureContext = best.context;
                            } else {
                                captureContext = rootPage;
                            }
                        } catch (Exception ignored) {
                            captureContext = rootPage;
                        }
                        HtmlSnapshot primarySnap = cachedPrimary;
                        if (primarySnap == null) primarySnap = captureAndCacheSnapshot(step.index, url, step.entryAction, captureContext, primaryMode, a11yInterestingOnly);
                        if (cachedSecondary == null) captureAndCacheSnapshot(step.index, url, step.entryAction, captureContext, secondaryMode, a11yInterestingOnly);
                        if (primarySnap != null) {
                            if (!urlKey.isEmpty()) snapshotByUrl.putIfAbsent(urlKey, primarySnap);
                            out.add(primarySnap);
                        }
                        continue;
                    }

                    // 非当前页面：先查缓存，未命中则导航到目标 URL 再采集
                    HtmlSnapshot cachedPrimary = readCachedHtml(step.index, url, step.entryAction, primaryMode, a11yInterestingOnly);
                    HtmlSnapshot cachedSecondary = readCachedHtml(step.index, url, step.entryAction, secondaryMode, a11yInterestingOnly);
                    if (cachedPrimary != null && cachedSecondary != null) {
                        if (uiLogger != null) uiLogger.accept("命中缓存: Step " + step.index + " | " + urlKey);
                        if (!urlKey.isEmpty()) snapshotByUrl.putIfAbsent(urlKey, cachedPrimary);
                        out.add(cachedPrimary);
                        continue;
                    }
                    if (cachedPrimary != null && cachedSecondary == null && uiLogger != null) {
                        uiLogger.accept("补齐缓存(另一采集模式): Step " + step.index + " | " + urlKey);
                    }

                    AutoWebAgentUI.minimizeFrameIfNeeded(frameState);
                    if (uiLogger != null) uiLogger.accept("采集页面: Step " + step.index + " | " + urlKey);
                    try {
                        tmp.navigate(url);
                    } catch (Exception navEx) {
                        if (uiLogger != null) uiLogger.accept("打开 URL 失败: " + navEx.getMessage());
                    }
                    try {
                        tmp.waitForLoadState(
                                com.microsoft.playwright.options.LoadState.NETWORKIDLE,
                                new Page.WaitForLoadStateOptions().setTimeout(AppConfig.getInstance().getAutowebWaitForLoadStateTimeoutMs())
                        );
                    } catch (Exception ignored) {
                        try {
                            tmp.waitForLoadState(
                                    com.microsoft.playwright.options.LoadState.LOAD,
                                    new Page.WaitForLoadStateOptions().setTimeout(AppConfig.getInstance().getAutowebWaitForLoadStateTimeoutMs())
                            );
                        } catch (Exception ignored2) {}
                    }
                    // 登录/跳转场景可能带 query 或中间页：用“URL 前缀”方式等待落到目标页面（忽略参数）
                    boolean stepOk = waitForUrlPrefix(tmp, url, 120000, 2000, uiLogger, "采集页面 Step " + step.index);
                    if (!stepOk) {
                        if (cachedPrimary != null) {
                            if (!urlKey.isEmpty()) snapshotByUrl.putIfAbsent(urlKey, cachedPrimary);
                            out.add(cachedPrimary);
                        }
                        continue;
                    }

                    String entry = step.entryAction == null ? "" : step.entryAction;
                    String token = firstQuotedToken(entry);
                    if (token != null) {
                        // Entry Action 里常包含引号包裹的“入口按钮/链接”文本：尝试点击进入业务区域
                        try {
                            com.microsoft.playwright.Locator loc = tmp.locator("text=" + token).first();
                            if (loc != null) {
                                boolean mayOpenNew = entry.contains("新开") || entry.toLowerCase().contains("new tab") || entry.toLowerCase().contains("new window");
                                if (mayOpenNew) {
                                    // 若入口动作描述为“新开/新窗口”，则等待新 Page 并切换采集上下文
                                    try {
                                        Page newPage = tmp.context().waitForPage(
                                                new com.microsoft.playwright.BrowserContext.WaitForPageOptions().setTimeout(5000),
                                                () -> loc.click(new com.microsoft.playwright.Locator.ClickOptions().setTimeout(5000))
                                        );
                                        if (newPage != null) {
                                            Page old = tmp;
                                            tmp = newPage;
                                            openedPages.add(tmp);
                                            try { old.close(); } catch (Exception ignored) {}
                                            try {
                                                tmp.waitForLoadState(
                                                        com.microsoft.playwright.options.LoadState.NETWORKIDLE,
                                                        new Page.WaitForLoadStateOptions().setTimeout(AppConfig.getInstance().getAutowebWaitForLoadStateTimeoutMs())
                                                );
                                            } catch (Exception ignored) {
                                                try {
                                                    tmp.waitForLoadState(
                                                            com.microsoft.playwright.options.LoadState.LOAD,
                                                            new Page.WaitForLoadStateOptions().setTimeout(AppConfig.getInstance().getAutowebWaitForLoadStateTimeoutMs())
                                                    );
                                                } catch (Exception ignored2) {}
                                            }
                                        }
                                    } catch (Exception e) {
                                        try { loc.click(new com.microsoft.playwright.Locator.ClickOptions().setTimeout(5000)); } catch (Exception ignored) {}
                                    }
                                } else {
                                    // 普通点击：尽力等待网络空闲，提升采集到稳定 DOM 的概率
                                    try { loc.click(new com.microsoft.playwright.Locator.ClickOptions().setTimeout(5000)); } catch (Exception ignored) {}
                                    try {
                                        tmp.waitForLoadState(
                                                com.microsoft.playwright.options.LoadState.NETWORKIDLE,
                                                new Page.WaitForLoadStateOptions().setTimeout(AppConfig.getInstance().getAutowebWaitForLoadStateTimeoutMs())
                                        );
                                    } catch (Exception ignored2) {}
                                }
                            }
                        } catch (Exception ignored) {}
                    }

                    Object captureContext = tmp;
                    try {
                        ContextWrapper best = null;
                        boolean debugFrames = AppConfig.getInstance().isAutowebDebugFrameCaptureEnabled();
                        JsonArray attempts = new JsonArray();
                        for (int attempt = 0; attempt < 16; attempt++) {
                            // 采集前同样扫描 iframe：优先选择可见面积最大的内容 frame
                            ScanResult sr = scanContexts(tmp);
                            if (sr != null) best = sr.best;
                            if (debugFrames) {
                                attempts.add(scanAttemptToJson(tmp, sr, attempt));
                            }
                            if (best != null && best.name != null && !"Main Page".equals(best.name)) break;
                            try { tmp.waitForTimeout(500); } catch (Exception ignored) {}
                        }
                        if (debugFrames) {
                            saveFrameDiagnostics(step.index, url, urlKey, primaryMode, a11yInterestingOnly, tmp, attempts, best, uiLogger);
                        }
                        if (best != null) {
                            String ctxUrl = "";
                            try {
                                if (best.context instanceof com.microsoft.playwright.Frame) {
                                    ctxUrl = ((com.microsoft.playwright.Frame) best.context).url();
                                } else if (best.context instanceof Page) {
                                    ctxUrl = ((Page) best.context).url();
                                }
                            } catch (Exception ignored) {}
                            if (uiLogger != null) {
                                uiLogger.accept("采集 HTML 上下文: Step " + step.index + " | " + best.name + (ctxUrl == null || ctxUrl.trim().isEmpty() ? "" : " | " + ctxUrl.trim()));
                            }
                            captureContext = best.context;
                        } else {
                            captureContext = tmp;
                        }
                    } catch (Exception ignored) {
                        captureContext = tmp;
                    }
                    HtmlSnapshot primarySnap = cachedPrimary;
                    if (primarySnap == null) primarySnap = captureAndCacheSnapshot(step.index, url, step.entryAction, captureContext, primaryMode, a11yInterestingOnly);
                    if (cachedSecondary == null) captureAndCacheSnapshot(step.index, url, step.entryAction, captureContext, secondaryMode, a11yInterestingOnly);
                    if (primarySnap != null) {
                        if (!urlKey.isEmpty()) snapshotByUrl.putIfAbsent(urlKey, primarySnap);
                        out.add(primarySnap);
                    }
                }
            } finally {
                for (int i = openedPages.size() - 1; i >= 0; i--) {
                    Page p = openedPages.get(i);
                    if (p == null) continue;
                    try { p.close(); } catch (Exception ignored) {}
                }
                // 采集结束后恢复控制台窗口状态，避免影响用户后续操作
                AutoWebAgentUI.restoreFrameIfNeeded(frameState);
            }
            return out;
        }
    }

    /**
     * 读取页面内容（默认 RAW_HTML）
     *
     * @param pageOrFrame Page 或 Frame
     * @return 页面内容
     */
    public static String getPageContent(Object pageOrFrame) {
        return getPageContent(pageOrFrame, HtmlCaptureMode.RAW_HTML);
    }

    /**
    /**
     * 读取页面内容（按采集模式）
     *
     * @param pageOrFrame Page 或 Frame
     * @param captureMode 采集模式
     * @return 页面内容
     */
    public static String getPageContent(Object pageOrFrame, HtmlCaptureMode captureMode) {
        return getPageContent(pageOrFrame, captureMode, true);
    }

    /**
    /**
     * 读取页面内容（支持 A11y interestingOnly）
     *
     * @param pageOrFrame Page 或 Frame
     * @param captureMode 采集模式
     * @param a11yInterestingOnly ARIA 快照是否仅保留语义节点
     * @return 页面内容
     */
    public static String getPageContent(Object pageOrFrame, HtmlCaptureMode captureMode, boolean a11yInterestingOnly) {
        HtmlCaptureMode mode = captureMode == null ? HtmlCaptureMode.RAW_HTML : captureMode;
        if (mode == HtmlCaptureMode.ARIA_SNAPSHOT) {
            String snap = getAriaSnapshot(pageOrFrame, a11yInterestingOnly);
            if (snap != null && !snap.isEmpty()) return snap;
        }
        return getRawHtmlContent(pageOrFrame);
    }

    private static String getRawHtmlContent(Object pageOrFrame) {
        try {
            if (pageOrFrame instanceof Page) {
                Page p = (Page) pageOrFrame;
                try {
                    p.waitForLoadState(
                            com.microsoft.playwright.options.LoadState.DOMCONTENTLOADED,
                            new Page.WaitForLoadStateOptions().setTimeout(AppConfig.getInstance().getAutowebWaitForLoadStateTimeoutMs())
                    );
                } catch (Exception ignored) {}
                try {
                    p.waitForLoadState(
                            com.microsoft.playwright.options.LoadState.NETWORKIDLE,
                            new Page.WaitForLoadStateOptions().setTimeout(AppConfig.getInstance().getAutowebWaitForLoadStateTimeoutMs())
                    );
                } catch (Exception ignored) {}
                String html = "";
                try { html = p.content(); } catch (Exception ignored) {}
                if (html != null && !html.trim().isEmpty()) {
                    if (!looksLikeEmptySpaShell(html)) return html;
                    String latest = html;
                    for (int i = 0; i < 12; i++) {
                        try { p.waitForTimeout(500); } catch (Exception ignored) {}
                        try { latest = p.content(); } catch (Exception ignored) {}
                        if (latest != null && !latest.trim().isEmpty() && !looksLikeEmptySpaShell(latest)) return latest;
                    }
                    return latest == null ? "" : latest;
                }
                try {
                    Object v = p.locator("html").evaluate("el => el ? el.outerHTML : ''");
                    String s = v == null ? "" : String.valueOf(v);
                    return s == null ? "" : s;
                } catch (Exception ignored) {}
                try {
                    Object v = p.locator("body").evaluate("el => el ? el.outerHTML : ''");
                    String s = v == null ? "" : String.valueOf(v);
                    return s == null ? "" : s;
                } catch (Exception ignored) {}
                return "";
            } else if (pageOrFrame instanceof com.microsoft.playwright.Frame) {
                com.microsoft.playwright.Frame f = (com.microsoft.playwright.Frame) pageOrFrame;
                try {
                    f.waitForLoadState(
                            com.microsoft.playwright.options.LoadState.DOMCONTENTLOADED,
                            new Frame.WaitForLoadStateOptions().setTimeout(AppConfig.getInstance().getAutowebWaitForLoadStateTimeoutMs())
                    );
                } catch (Exception ignored) {}
                try {
                    f.waitForLoadState(
                            com.microsoft.playwright.options.LoadState.NETWORKIDLE,
                            new Frame.WaitForLoadStateOptions().setTimeout(AppConfig.getInstance().getAutowebWaitForLoadStateTimeoutMs())
                    );
                } catch (Exception ignored) {}
                String html = "";
                try { html = f.content(); } catch (Exception ignored) {}
                if (html != null && !html.trim().isEmpty()) {
                    if (!looksLikeEmptySpaShell(html)) return html;
                    String latest = html;
                    for (int i = 0; i < 12; i++) {
                        try {
                            Page fp = f.page();
                            if (fp != null) fp.waitForTimeout(500);
                        } catch (Exception ignored) {}
                        try { latest = f.content(); } catch (Exception ignored) {}
                        if (latest != null && !latest.trim().isEmpty() && !looksLikeEmptySpaShell(latest)) return latest;
                    }
                    return latest == null ? "" : latest;
                }
                try {
                    Object v = f.locator("html").evaluate("el => el ? el.outerHTML : ''");
                    String s = v == null ? "" : String.valueOf(v);
                    return s == null ? "" : s;
                } catch (Exception ignored) {}
                try {
                    Object v = f.locator("body").evaluate("el => el ? el.outerHTML : ''");
                    String s = v == null ? "" : String.valueOf(v);
                    return s == null ? "" : s;
                } catch (Exception ignored) {}
                return "";
            }
            return "";
        } catch (Exception ignored) {
            return "";
        }
    }

    private static boolean looksLikeEmptySpaShell(String html) {
        if (html == null) return true;
        String s = html.trim();
        if (s.isEmpty()) return true;
        String t = s.toLowerCase();
        if (t.contains("<div id=\"root\"></div>")) return true;
        if (t.contains("<div id='root'></div>")) return true;
        if (t.contains("<div id=\"app\"></div>")) return true;
        if (t.contains("<div id='app'></div>")) return true;
        if (t.contains("<div id=\"root\">") && t.contains("</div>")) {
            int idx = t.indexOf("<div id=\"root\">");
            int end = t.indexOf("</div>", idx);
            if (idx >= 0 && end > idx) {
                String mid = t.substring(idx, Math.min(end, idx + 200));
                if (!mid.contains("<div") && !mid.contains("<span") && !mid.contains("<table") && !mid.contains("<form") && !mid.contains("<button") && !mid.contains("<input")) {
                    return true;
                }
            }
        }
        if (t.contains("<div id='root'>") && t.contains("</div>")) {
            int idx = t.indexOf("<div id='root'>");
            int end = t.indexOf("</div>", idx);
            if (idx >= 0 && end > idx) {
                String mid = t.substring(idx, Math.min(end, idx + 200));
                if (!mid.contains("<div") && !mid.contains("<span") && !mid.contains("<table") && !mid.contains("<form") && !mid.contains("<button") && !mid.contains("<input")) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isMeaningfulAriaSnapshotText(String snapText) {
        if (snapText == null) return false;
        String t = snapText.trim();
        if (t.isEmpty()) return false;
        if ("- document".equals(t) || "document".equalsIgnoreCase(t)) return false;
        return true;
    }

    private static String readAriaSnapshotFromPage(Page p) {
        if (p == null) return "";
        try {
            String t = p.locator("body").ariaSnapshot();
            if (!isMeaningfulAriaSnapshotText(t)) {
                t = p.locator("html").ariaSnapshot();
            }
            return isMeaningfulAriaSnapshotText(t) ? t : "";
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String readAriaSnapshotFromFrame(com.microsoft.playwright.Frame f) {
        if (f == null) return "";
        try {
            String t = f.locator("body").ariaSnapshot();
            if (!isMeaningfulAriaSnapshotText(t)) {
                t = f.locator("html").ariaSnapshot();
            }
            return isMeaningfulAriaSnapshotText(t) ? t : "";
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String getAriaSnapshot(Object pageOrFrame, boolean a11yInterestingOnly) {
        try {
            if (pageOrFrame instanceof Page) {
                Page p = (Page) pageOrFrame;
                Object scoped = p;
                try {
                    // ARIA_SNAPSHOT 默认取 Page，但若存在更合适的内容 Frame，则优先在 Frame 上采集
                    ScanResult sr = scanContexts(p);
                    ContextWrapper best = sr == null ? null : sr.best;
                    if (best != null && best.context instanceof com.microsoft.playwright.Frame) {
                        String name = best.name == null ? "" : best.name.trim();
                        if (!name.isEmpty() && !"Main Page".equals(name)) {
                            scoped = best.context;
                        }
                    }
                } catch (Exception ignored) {}
                
                String snapText = "";
                if (scoped instanceof com.microsoft.playwright.Frame) {
                    snapText = readAriaSnapshotFromFrame((com.microsoft.playwright.Frame) scoped);
                    if (!isMeaningfulAriaSnapshotText(snapText)) {
                        snapText = readAriaSnapshotFromPage(p);
                    }
                } else {
                    snapText = readAriaSnapshotFromPage(p);
                }
                if (isMeaningfulAriaSnapshotText(snapText)) return wrapAsJsonText(snapText);
                String axTree = "";
                try {
                    axTree = getA11yFullAxTreeJson(p, a11yInterestingOnly, safePageUrl(p));
                } catch (Exception ignored) {
                    axTree = "";
                }
                if (axTree != null && !axTree.trim().isEmpty()) {
                    return wrapAsA11yFallbackJson("", axTree);
                }
                String raw = getRawHtmlContent(scoped);
                return raw == null ? "" : raw;
            } else if (pageOrFrame instanceof com.microsoft.playwright.Frame) {
                com.microsoft.playwright.Frame f = (com.microsoft.playwright.Frame) pageOrFrame;
                try {
                    String snapText = readAriaSnapshotFromFrame(f);
                    if (!isMeaningfulAriaSnapshotText(snapText)) {
                        try {
                            Page fp = f.page();
                            snapText = readAriaSnapshotFromPage(fp);
                        } catch (Exception ignored) {}
                    }
                    if (isMeaningfulAriaSnapshotText(snapText)) {
                        String snap = wrapAsJsonText(snapText);
                        return snap == null ? "" : snap;
                    }
                    String raw = getRawHtmlContent(f);
                    return raw == null ? "" : raw;
                } catch (Exception ignored) {
                    return "";
                }
            }
        } catch (Exception ignored) {}
        return "";
    }

    private static String wrapAsJsonText(String text) {
        JsonObject o = new JsonObject();
        o.addProperty("ariaSnapshotText", text == null ? "" : text);
        return PRETTY_GSON.toJson(o);
    }

    private static String wrapAsA11yFallbackJson(String ariaSnapshotText, String axTreeJsonText) {
        JsonObject o = new JsonObject();
        o.addProperty("ariaSnapshotText", ariaSnapshotText == null ? "" : ariaSnapshotText);
        if (axTreeJsonText != null && !axTreeJsonText.trim().isEmpty()) {
            try {
                o.add("axTree", JsonParser.parseString(axTreeJsonText));
            } catch (Exception ignored) {
                o.addProperty("axTreeText", axTreeJsonText);
            }
        }
        return PRETTY_GSON.toJson(o);
    }

    private static String getAxRoleValue(JsonObject node) {
        if (node == null) return "";
        try {
            JsonObject role = node.getAsJsonObject("role");
            if (role == null) return "";
            JsonElement v = role.get("value");
            if (v == null) return "";
            return v.getAsString();
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String getAxNodeUrl(JsonObject node) {
        if (node == null) return "";
        try {
            JsonArray props = node.getAsJsonArray("properties");
            if (props == null) return "";
            for (JsonElement pe : props) {
                if (!(pe instanceof JsonObject)) continue;
                JsonObject p = (JsonObject) pe;
                JsonElement name = p.get("name");
                if (name == null) continue;
                if (!"url".equalsIgnoreCase(name.getAsString())) continue;
                JsonObject val = p.getAsJsonObject("value");
                if (val == null) return "";
                JsonElement vv = val.get("value");
                return vv == null ? "" : vv.getAsString();
            }
        } catch (Exception ignored) {}
        return "";
    }

    private static java.util.Set<String> collectAxSubtreeIds(java.util.Map<String, JsonObject> byId, String rootId) {
        java.util.HashSet<String> out = new java.util.HashSet<>();
        if (rootId == null || rootId.isEmpty()) return out;
        java.util.ArrayDeque<String> q = new java.util.ArrayDeque<>();
        q.add(rootId);
        while (!q.isEmpty()) {
            String id = q.poll();
            if (id == null || id.isEmpty()) continue;
            if (!out.add(id)) continue;
            JsonObject n = byId.get(id);
            if (n == null) continue;
            try {
                JsonArray children = n.getAsJsonArray("childIds");
                if (children == null) continue;
                for (JsonElement ce : children) {
                    if (ce == null) continue;
                    try {
                        q.add(ce.getAsString());
                    } catch (Exception ignored) {}
                }
            } catch (Exception ignored) {}
        }
        return out;
    }

    private static JsonObject filterAxTreeToLargestRootWebArea(JsonObject result, String expectedUrl) {
        if (result == null) return null;
        JsonArray nodes = null;
        try { nodes = result.getAsJsonArray("nodes"); } catch (Exception ignored) {}
        if (nodes == null || nodes.isEmpty()) return result;

        java.util.HashMap<String, JsonObject> byId = new java.util.HashMap<>();
        java.util.ArrayList<String> rootIds = new java.util.ArrayList<>();

        for (JsonElement e : nodes) {
            if (!(e instanceof JsonObject)) continue;
            JsonObject n = (JsonObject) e;
            String id = "";
            try {
                JsonElement ide = n.get("nodeId");
                if (ide != null) id = ide.getAsString();
            } catch (Exception ignored) {}
            if (!id.isEmpty()) byId.put(id, n);

            String roleValue = getAxRoleValue(n);
            boolean isRoot = "RootWebArea".equalsIgnoreCase(roleValue);
            if (!isRoot) {
                try {
                    JsonObject chromeRole = n.getAsJsonObject("chromeRole");
                    if (chromeRole != null) {
                        JsonElement v = chromeRole.get("value");
                        if (v != null && v.getAsInt() == 144) isRoot = true;
                    }
                } catch (Exception ignored) {}
            }
            if (isRoot && !id.isEmpty()) rootIds.add(id);
        }

        if (rootIds.isEmpty()) return result;

        String expected = "";
        try {
            expected = PlanRoutingSupport.stripUrlQuery(expectedUrl);
            if (expected == null) expected = "";
            expected = expected.trim();
        } catch (Exception ignored) {}

        String bestRootId = null;
        java.util.Set<String> bestIds = null;
        boolean bestBad = true;
        boolean bestMatch = false;

        for (String rid : rootIds) {
            java.util.Set<String> ids = collectAxSubtreeIds(byId, rid);
            int size = ids == null ? 0 : ids.size();
            JsonObject rootNode = byId.get(rid);
            String url = getAxNodeUrl(rootNode);
            String urlTrim = url == null ? "" : url.trim();
            boolean bad = urlTrim.isEmpty() || "about:blank".equalsIgnoreCase(urlTrim);
            String urlNoQuery = "";
            try {
                urlNoQuery = PlanRoutingSupport.stripUrlQuery(urlTrim);
                if (urlNoQuery == null) urlNoQuery = "";
                urlNoQuery = urlNoQuery.trim();
            } catch (Exception ignored) {}
            boolean match = !expected.isEmpty()
                    && !urlNoQuery.isEmpty()
                    && (urlNoQuery.startsWith(expected) || expected.startsWith(urlNoQuery));

            if (bestRootId == null) {
                bestRootId = rid;
                bestIds = ids;
                bestBad = bad;
                bestMatch = match;
                continue;
            }
            int bestSize = bestIds == null ? 0 : bestIds.size();
            if (!bestMatch && match) {
                bestRootId = rid;
                bestIds = ids;
                bestBad = bad;
                bestMatch = true;
                continue;
            }
            if (bestMatch == match && bestBad && !bad) {
                bestRootId = rid;
                bestIds = ids;
                bestBad = bad;
                bestMatch = match;
                continue;
            }
            if (bestMatch == match && bestBad == bad && size > bestSize) {
                bestRootId = rid;
                bestIds = ids;
                bestBad = bad;
                bestMatch = match;
            }
        }

        if (bestIds == null || bestIds.isEmpty()) return result;

        JsonArray filtered = new JsonArray();
        for (JsonElement e : nodes) {
            if (!(e instanceof JsonObject)) continue;
            JsonObject n = (JsonObject) e;
            String id = "";
            try {
                JsonElement ide = n.get("nodeId");
                if (ide != null) id = ide.getAsString();
            } catch (Exception ignored) {}
            if (!id.isEmpty() && bestIds.contains(id)) filtered.add(n);
        }

        JsonObject out = result.deepCopy();
        out.add("nodes", filtered);
        return out;
    }

    private static String getA11yFullAxTreeJson(Page page, boolean interestingOnly, String expectedUrl) {
        if (page == null) return "";
        CDPSession cdp = null;
        try {
            cdp = page.context().newCDPSession(page);
            try { cdp.send("Accessibility.enable"); } catch (Exception ignored) {}
            JsonObject params = new JsonObject();
            params.addProperty("interestingOnly", interestingOnly);
            JsonObject result = cdp.send("Accessibility.getFullAXTree", params);
            JsonObject filtered = filterAxTreeToLargestRootWebArea(result, expectedUrl);
            return filtered == null ? "" : PRETTY_GSON.toJson(filtered);
        } catch (Exception ignored) {
            return "";
        } finally {
            if (cdp != null) {
                try { cdp.send("Accessibility.disable"); } catch (Exception ignored) {}
                try { cdp.detach(); } catch (Exception ignored) {}
            }
        }
    }

    /**
     * 清洗并截断采集内容，控制 payload 大小
     *
     * @param captured 采集内容
     * @param captureMode 采集模式
     * @return 清洗后的内容
     */
    public static String cleanCapturedContent(String captured, HtmlCaptureMode captureMode) {
        HtmlCaptureMode mode = captureMode == null ? HtmlCaptureMode.RAW_HTML : captureMode;
        String out;
        if (mode == HtmlCaptureMode.ARIA_SNAPSHOT) {
            StorageSupport.log(null, "CAPTURE", "ARIA_SNAPSHOT len=" + (captured == null ? 0 : captured.length()), null);
            out = captured == null ? "" : captured.replace("\r\n", "\n");
        } else {
            out = HTMLCleaner.clean(captured);
        }
        int maxLen = (mode == HtmlCaptureMode.ARIA_SNAPSHOT) ? 1500000 : 500000;
        if (out.length() > maxLen) out = out.substring(0, maxLen) + "...(truncated)";
        return out;
    }

    /**
     * LLM 调用封装函数 (generateGroovyScript)
     * 1. 加载 Prompt 模板 (groovy_script_prompt.txt)
     * 2. 根据 Mode (PLAN/CODEGEN) 动态裁剪模板内容 (stripTaggedBlocks)
     * 3. 组装最终 Prompt (模板 + 用户任务 + Payload)
     * 4. 调用 LLM (callModel) 并记录 Debug Artifacts
     */
    public static String generateGroovyScript(String userPrompt, String cleanedHtml, java.util.function.Consumer<String> uiLogger, String modelName) {
        return GroovySupport.generateGroovyScript(userPrompt, cleanedHtml, uiLogger, modelName);
    }

    static String extractModeFromPayload(String payload) {
        if (payload == null) return "";
        try {
            java.util.regex.Pattern p = java.util.regex.Pattern.compile("(?m)^\\s*MODE\\s*:\\s*([A-Z_]+)\\s*$");
            java.util.regex.Matcher m = p.matcher(payload);
            if (m.find()) {
                String v = m.group(1);
                return v == null ? "" : v.trim();
            }
        } catch (Exception ignored) {}
        return "";
    }

    public static String generateRefinedGroovyScript(
        String originalUserPrompt,
        String cleanedHtml,
        String previousCode,
        String execOutput,
        String refineHint,
        java.util.function.Consumer<String> uiLogger,
        String modelName
    ) {
        return GroovySupport.generateRefinedGroovyScript(
                originalUserPrompt,
                cleanedHtml,
                previousCode,
                execOutput,
                refineHint,
                uiLogger,
                modelName
        );
    }
    
    static String normalizeGeneratedGroovy(String code) {
        return GroovySupport.normalizeGeneratedGroovy(code);
    }

    private static String normalizePlanBlockCommentFormat(String code) {
        if (code == null) return null;
        int ps = code.indexOf("PLAN_START");
        int pe = code.indexOf("PLAN_END");
        if (ps < 0 || pe < 0 || pe <= ps) return code;
        int blockStart = code.lastIndexOf("/*", ps);
        if (blockStart < 0) return code;
        int blockEnd = code.indexOf("*/", pe);
        if (blockEnd < 0) return code;

        String before = code.substring(0, blockStart + 2);
        String inside = code.substring(blockStart + 2, blockEnd);
        String after = code.substring(blockEnd);

        String[] lines = inside.split("\\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            int p = 0;
            while (p < line.length() && Character.isWhitespace(line.charAt(p))) p++;
            String indent = line.substring(0, p);
            String rest = line.substring(p);
            if (rest.startsWith("*")) {
                rest = rest.substring(1);
                if (!rest.isEmpty() && rest.charAt(0) == ' ') rest = rest.substring(1);
            }
            if (rest.startsWith("//")) {
                rest = rest.substring(2);
                if (!rest.isEmpty() && rest.charAt(0) == ' ') rest = rest.substring(1);
            }
            lines[i] = indent + rest;
        }
        String rebuiltInside = String.join("\n", lines);
        return before + rebuiltInside + after;
    }

    private static String commentPlanMarkersOutsideBlockComment(String code) {
        if (code == null) return null;
        String[] lines = code.split("\\n", -1);
        boolean inBlock = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (!inBlock) {
                String trimmed = line == null ? "" : line.trim();
                if (trimmed.startsWith("PLAN_START") || trimmed.startsWith("PLAN_END")) {
                    lines[i] = "// " + line;
                }
            }
            inBlock = updateBlockCommentState(inBlock, line);
        }
        return String.join("\n", lines);
    }

    private static boolean updateBlockCommentState(boolean inBlock, String line) {
        if (line == null || line.isEmpty()) return inBlock;
        int i = 0;
        int n = line.length();
        boolean state = inBlock;
        while (i < n - 1) {
            char c = line.charAt(i);
            char d = line.charAt(i + 1);
            if (!state && c == '/' && d == '*') {
                state = true;
                i += 2;
                continue;
            }
            if (state && c == '*' && d == '/') {
                state = false;
                i += 2;
                continue;
            }
            i++;
        }
        return state;
    }

    private static String escapeNonInterpolatedDollarInDoubleQuotedStrings(String code) {
        if (code == null || code.indexOf('$') < 0 || code.indexOf('"') < 0) return code;
        StringBuilder out = new StringBuilder(code.length() + 16);
        int n = code.length();
        int i = 0;
        boolean inDouble = false;
        int doubleQuoteLen = 0;
        while (i < n) {
            char c = code.charAt(i);
            if (!inDouble) {
                if (c == '"') {
                    if (i + 2 < n && code.charAt(i + 1) == '"' && code.charAt(i + 2) == '"') {
                        inDouble = true;
                        doubleQuoteLen = 3;
                        out.append("\"\"\"");
                        i += 3;
                        continue;
                    }
                    inDouble = true;
                    doubleQuoteLen = 1;
                    out.append('"');
                    i++;
                    continue;
                }
                out.append(c);
                i++;
                continue;
            }

            if (c == '\\') {
                out.append(c);
                if (i + 1 < n) {
                    out.append(code.charAt(i + 1));
                    i += 2;
                } else {
                    i++;
                }
                continue;
            }

            if (doubleQuoteLen == 3) {
                if (c == '"' && i + 2 < n && code.charAt(i + 1) == '"' && code.charAt(i + 2) == '"') {
                    inDouble = false;
                    doubleQuoteLen = 0;
                    out.append("\"\"\"");
                    i += 3;
                    continue;
                }
            } else {
                if (c == '"') {
                    inDouble = false;
                    doubleQuoteLen = 0;
                    out.append('"');
                    i++;
                    continue;
                }
            }

            if (c == '$') {
                char next = (i + 1) < n ? code.charAt(i + 1) : '\0';
                boolean interpolation = next == '{' || next == '_' || Character.isLetter(next);
                if (!interpolation) out.append('\\');
                out.append('$');
                i++;
                continue;
            }

            out.append(c);
            i++;
        }
        return out.toString();
    }

    public static void executeWithGroovy(String scriptCode, Object pageOrFrame, java.util.function.Consumer<String> logger) throws Exception {
        executeWithGroovy(scriptCode, pageOrFrame, logger, null, null, null);
    }

    public static void executeWithGroovy(
            String scriptCode,
            Object pageOrFrame,
            java.util.function.Consumer<String> logger,
            groovy.lang.Binding sharedBinding,
            Integer dslDefaultTimeoutMs,
            Integer dslMaxRetries
    ) throws Exception {
        if (scriptCode == null) {
            if (logger != null) logger.accept("Groovy execution failed: scriptCode is null");
            throw new IllegalArgumentException("scriptCode is null");
        }
        if (scriptCode.trim().isEmpty()) {
            if (logger != null) logger.accept("Groovy execution failed: scriptCode is empty");
            throw new IllegalArgumentException("empty scriptCode");
        }

        String normalizedCode = normalizeGroovyScriptForExecution(scriptCode);
        if (!normalizedCode.equals(scriptCode)) {
            String ts = newDebugTimestamp();
            saveDebugArtifact(ts, "EXEC", "GROOVY", "script_raw", scriptCode, logger);
            saveDebugArtifact(ts, "EXEC", "GROOVY", "script_normalized", normalizedCode, logger);
        }

        // 1. Static Linting
        java.util.List<String> lintErrors = GroovyLinter.check(normalizedCode);
        if (!lintErrors.isEmpty()) {
            StringBuilder sb = new StringBuilder("Static Analysis Found Issues:\n");
            for (String err : lintErrors) {
                sb.append("- ").append(err).append("\n");
            }
            if (logger != null) logger.accept(sb.toString());
            
            // Abort on security violations
            boolean hasSecurityError = lintErrors.stream().anyMatch(e -> e.startsWith("Security Error"));
            boolean hasSyntaxError = lintErrors.stream().anyMatch(e -> e.startsWith("Syntax Error") || e.startsWith("Parse Error"));
            if (hasSecurityError || hasSyntaxError) {
                 throw new RuntimeException("Execution aborted due to static analysis violations.");
            }
        }

        try {
            groovy.lang.Binding binding = sharedBinding == null ? new groovy.lang.Binding() : sharedBinding;
            binding.setVariable("page", pageOrFrame);
            
            // Inject WebDSL
            WebDSL dsl = new WebDSL(pageOrFrame, logger);
            if (dslDefaultTimeoutMs != null && dslDefaultTimeoutMs > 0) {
                dsl.withDefaultTimeout(dslDefaultTimeoutMs);
            }
            if (dslMaxRetries != null && dslMaxRetries > 0) {
                dsl.withMaxRetries(dslMaxRetries);
            }
            binding.setVariable("web", dsl);
            
            // Redirect print output to our UI logger
            binding.setVariable("out", new java.io.PrintWriter(new java.io.Writer() {
                private StringBuilder buffer = new StringBuilder();
                @Override
                public void write(char[] cbuf, int off, int len) {
                    buffer.append(cbuf, off, len);
                    checkBuffer();
                }
                @Override
                public void flush() { checkBuffer(); }
                @Override
                public void close() { flush(); }
                
                private void checkBuffer() {
                    int newline = buffer.indexOf("\n");
                    while (newline != -1) {
                        String line = buffer.substring(0, newline);
                        if (logger != null) logger.accept(line);
                        buffer.delete(0, newline + 1);
                        newline = buffer.indexOf("\n");
                    }
                }
            }, true)); // Auto-flush

            groovy.lang.GroovyShell shell = new groovy.lang.GroovyShell(binding, secureGroovyCompilerConfig());
            shell.evaluate(normalizedCode);
            if (logger != null) logger.accept("Groovy script executed successfully.");
        } catch (Exception e) {
            String msg = e.getMessage();
            if (msg == null) msg = "";
            if (logger != null) logger.accept("Groovy execution failed: " + e.getClass().getName() + (msg.isEmpty() ? "" : (": " + msg)));
            try {
                Throwable c = e.getCause();
                if (c != null && c != e) {
                    String cm = c.getMessage();
                    if (cm == null) cm = "";
                    if (logger != null) logger.accept("Groovy execution cause: " + c.getClass().getName() + (cm.isEmpty() ? "" : (": " + cm)));
                }
            } catch (Exception ignored) {}
            try {
                String st = StorageSupport.stackTraceToString(e);
                if (st != null && !st.trim().isEmpty()) {
                    String[] lines = st.split("\\r?\\n", -1);
                    int limit = Math.min(lines.length, 25);
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < limit; i++) {
                        sb.append(lines[i]).append("\n");
                    }
                    if (logger != null) logger.accept(sb.toString().trim());
                }
            } catch (Exception ignored) {}
            // 抛出异常以便主程序捕获并退出
            throw e;
        }
    }

    static String normalizeGroovyScriptForExecution(String code) {
        if (code == null) return "";
        String out = code;
        out = out.replaceAll("```groovy", "").replaceAll("```java", "").replaceAll("```", "");
        out = out.replaceFirst("(?s)^\\s*(groovy|java)\\s*\\r?\\n", "");

        out = out.replaceAll("(?m)^\\s*import\\s+static\\s+[^\\r\\n]+\\s*$", "");

        out = out.replaceAll("(?m)^\\s*import\\s+java\\.util\\.[A-Za-z0-9_.$]+\\s*$", "import java.util.*");
        out = out.replaceAll("(?m)^\\s*import\\s+java\\.math\\.[A-Za-z0-9_.$]+\\s*$", "import java.math.*");
        out = out.replaceAll("(?m)^\\s*import\\s+java\\.time\\.[A-Za-z0-9_.$]+\\s*$", "import java.time.*");
        out = out.replaceAll("(?m)^\\s*import\\s+java\\.time\\.format\\.[A-Za-z0-9_.$]+\\s*$", "");
        out = out.replaceAll("(?m)^\\s*import\\s+java\\.text\\.[A-Za-z0-9_.$]+\\s*$", "import java.text.*");
        out = out.replaceAll("(?m)^\\s*import\\s+java\\.util\\.regex\\.[A-Za-z0-9_.$]+\\s*$", "import java.util.regex.*");
        out = out.replaceAll("(?m)^\\s*import\\s+com\\.google\\.gson\\.[A-Za-z0-9_.$]+\\s*$", "import com.google.gson.*");

        out = out.replaceAll("(?m)^\\s*import\\s+groovy\\.json\\.(JsonOutput|JsonBuilder)\\s*$", "");

        out = out.replaceAll("(?m)^\\s*println\\s+groovy\\.json\\.JsonBuilder\\.newInstance\\s*\\(", "println new groovy.json.JsonBuilder(");
        out = out.replaceAll("groovy\\.json\\.JsonBuilder\\.newInstance\\s*\\(", "new groovy.json.JsonBuilder(");

        out = out.replaceAll("new\\s+groovy\\.json\\.JsonBuilder\\(([^\\)]*)\\)\\.toPrettyString\\(\\)", "new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson($1)");
        out = out.replaceAll("groovy\\.json\\.JsonBuilder\\s*\\(([^\\)]*)\\)\\.toPrettyString\\(\\)", "new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson($1)");
        out = out.replaceAll("println\\s+new\\s+groovy\\.json\\.JsonBuilder\\(([^\\)]*)\\)\\.toPrettyString\\(\\)", "println new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson($1)");

        out = out.replaceAll("JsonOutput\\.prettyPrint\\(\\s*JsonOutput\\.toJson\\(([^\\)]*)\\)\\s*\\)", "new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson($1)");
        out = out.replaceAll("println\\s+JsonOutput\\.prettyPrint\\(\\s*JsonOutput\\.toJson\\(([^\\)]*)\\)\\s*\\)", "println new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson($1)");
        out = out.replaceAll("JsonOutput\\.toJson\\(([^\\)]*)\\)", "new com.google.gson.Gson().toJson($1)");

        out = out.replaceAll("new\\s+Date\\(\\s*\\)\\s*\\.\\s*format\\(\\s*\"([^\"]+)\"\\s*\\)", "new java.text.SimpleDateFormat(\"$1\").format(new Date())");
        out = out.replaceAll("new\\s+Date\\(\\s*\\)\\s*\\.\\s*format\\(\\s*'([^']+)'\\s*\\)", "new java.text.SimpleDateFormat('$1').format(new Date())");
        out = out.replaceAll("([A-Za-z_][A-Za-z0-9_]*)\\s*\\.\\s*format\\(\\s*\"([^\"]+)\"\\s*\\)", "new java.text.SimpleDateFormat(\"$2\").format($1)");
        out = out.replaceAll("([A-Za-z_][A-Za-z0-9_]*)\\s*\\.\\s*format\\(\\s*'([^']+)'\\s*\\)", "new java.text.SimpleDateFormat('$2').format($1)");

        out = out.replaceAll("new\\s+Date\\(\\s*\\)\\s*\\.\\s*minus\\(\\s*(\\d+)\\s*\\)", "new Date(java.time.Instant.ofEpochMilli(System.currentTimeMillis()).minus(java.time.Duration.ofDays($1L)).toEpochMilli())");
        out = out.replaceAll("new\\s+Date\\(\\s*\\)\\s*\\.\\s*plus\\(\\s*(\\d+)\\s*\\)", "new Date(java.time.Instant.ofEpochMilli(System.currentTimeMillis()).plus(java.time.Duration.ofDays($1L)).toEpochMilli())");
        out = out.replaceAll("([A-Za-z_][A-Za-z0-9_]*)\\s*\\.\\s*minus\\(\\s*(\\d+)\\s*\\)", "new Date(java.time.Instant.ofEpochMilli($1.getTime()).minus(java.time.Duration.ofDays($2L)).toEpochMilli())");
        out = out.replaceAll("([A-Za-z_][A-Za-z0-9_]*)\\s*\\.\\s*plus\\(\\s*(\\d+)\\s*\\)", "new Date(java.time.Instant.ofEpochMilli($1.getTime()).plus(java.time.Duration.ofDays($2L)).toEpochMilli())");

        out = out.replaceAll("(?<![A-Za-z0-9_\\.])LocalDate\\b", "java.time.LocalDate");
        out = out.replaceAll("(?<![A-Za-z0-9_\\.])LocalDateTime\\b", "java.time.LocalDateTime");
        out = out.replaceAll("(?<![A-Za-z0-9_\\.])LocalTime\\b", "java.time.LocalTime");
        out = out.replaceAll("(?<![A-Za-z0-9_\\.])DateTimeFormatter\\b", "java.time.format.DateTimeFormatter");
        out = out.replaceAll("(?<![A-Za-z0-9_\\.])DateTimeParseException\\b", "java.time.format.DateTimeParseException");

        return out.trim();
    }

    static org.codehaus.groovy.control.CompilerConfiguration secureGroovyCompilerConfig() {
        org.codehaus.groovy.control.CompilerConfiguration cfg = new org.codehaus.groovy.control.CompilerConfiguration();
        org.codehaus.groovy.control.customizers.SecureASTCustomizer sc = new org.codehaus.groovy.control.customizers.SecureASTCustomizer();

        sc.setIndirectImportCheckEnabled(false);
        sc.setClosuresAllowed(true);
        sc.setMethodDefinitionAllowed(true);

        sc.setAllowedImports(java.util.Collections.emptyList());
        sc.setAllowedStaticImports(java.util.Collections.emptyList());
        sc.setAllowedStaticStarImports(java.util.Collections.emptyList());
        sc.setAllowedStarImports(java.util.Arrays.asList(
                "java.lang",
                "java.util",
                "java.math",
                "java.time",
                "java.text",
                "java.util.regex",
                "com.google.gson"
        ));

        sc.setDisallowedReceivers(java.util.Arrays.asList(
                "java.lang.System",
                "java.lang.Runtime",
                "java.lang.ProcessBuilder",
                "java.lang.Class",
                "java.lang.ClassLoader",
                "java.lang.Thread",
                "java.io.File",
                "java.nio.file.Files",
                "java.nio.file.Path",
                "java.nio.file.Paths",
                "java.net.URL",
                "java.net.URI",
                "java.net.Socket",
                "java.net.ServerSocket",
                "groovy.lang.GroovyShell",
                "groovy.lang.GroovyClassLoader",
                "groovy.util.Eval"
        ));

        cfg.addCompilationCustomizers(sc);
        return cfg;
    }

}
