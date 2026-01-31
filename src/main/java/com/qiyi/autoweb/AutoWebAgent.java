package com.qiyi.autoweb;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.microsoft.playwright.CDPSession;
import com.microsoft.playwright.Page;
import com.qiyi.util.LLMUtil;
import com.qiyi.util.PlayWrightUtil;
import javax.swing.SwingUtilities;

/**
 * AutoWeb 主入口与编排器
 * 负责连接浏览器、采集页面、调用模型并执行 Groovy 脚本
 */
public class AutoWebAgent {
    static String ACTIVE_MODEL = "DEEPSEEK";
    static final Object PLAYWRIGHT_LOCK = new Object();
    private static final Gson PRETTY_GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /**
     * 页面采集模式：原始 HTML 或 ARIA 快照
     */
    enum HtmlCaptureMode {
        RAW_HTML,
        ARIA_SNAPSHOT
    }

    /**
     * CLI 入口，解析参数并启动 UI
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

            String userPrompt = "请帮我查询待发货所有的订单（包括多页的数据），并且输出订单的所有信息，输出格式为：\\\"列名:列内容（去掉回车换行）\\\"，然后用\\\"｜\\\"分隔，列的顺序保持表格的顺序，一条记录一行。输出以后，回到第一条订单，选中订单，然后点击审核推单，读取弹出页面的成功和失败的笔数，失败笔数大于0，页面上获取失败原因，也一起输出";

            System.out.println("No arguments provided. Running default example:");
            System.out.println("URL: " + url);
            System.out.println("Prompt: " + userPrompt);
            run(url, userPrompt);
        } else {
            if (args.length >= 3 && args[2] != null) {
                String modelArg = args[2].trim();
                String upper = modelArg.toUpperCase();
                if ("DEEPSEEK".equals(upper)) {
                    ACTIVE_MODEL = "DEEPSEEK";
                    System.out.println("Using model: DeepSeek (remote)");
                } else if ("QWEN-MAX".equals(upper) || "QWEN_MAX".equals(upper) || "ALIYUN_QWEN_MAX".equals(upper)) {
                    ACTIVE_MODEL = "QWEN_MAX";
                    System.out.println("Using model: Aliyun Qwen-Max (remote)");
                } else if ("GEMINI".equals(upper) || "GEMINI_FLASH".equals(upper)) {
                    ACTIVE_MODEL = "GEMINI";
                    System.out.println("Using model: Gemini (remote)");
                } else if ("MOONSHOT".equals(upper) || "MOONSHOT_MOONSHOT".equals(upper) || "MOONSHOT_V1".equals(upper)) {
                    ACTIVE_MODEL = "MOONSHOT";
                    System.out.println("Using model: Moonshot (remote)");
                } else if ("GLM".equals(upper) || "ZHIPU".equals(upper)) {
                    ACTIVE_MODEL = "GLM";
                    System.out.println("Using model: Zhipu GLM (remote)");
                } else if ("OLLAMA_MODEL_QWEN3_8B".equals(upper)
                        || "OLLAMA_QWEN3_8B".equals(upper)
                        || "OLLAMA".equals(upper)
                        || "QWEN3_8B".equals(upper)
                        || "QWEN3:8B".equals(upper)) {
                    ACTIVE_MODEL = "OLLAMA_QWEN3_8B";
                    System.out.println("Using local Ollama model: " + LLMUtil.OLLAMA_MODEL_QWEN3_8B + " @ " + LLMUtil.OLLAMA_HOST);
                } else {
                    ACTIVE_MODEL = "DEEPSEEK";
                    System.out.println("Unknown model arg '" + modelArg + "', defaulting to DeepSeek.");
                }
            } else {
                ACTIVE_MODEL = "DEEPSEEK";
            }
            run(args[0], args[1]);
        }
    }

    /**
     * 连接浏览器并启动控制台 UI
     */
    public static void run(String url, String userPrompt) {
        GroovySupport.loadPrompts();
        PlayWrightUtil.Connection connection = PlayWrightUtil.connectAndAutomate();
        if (connection == null) {
            System.err.println("Failed to connect to browser.");
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
                    System.out.println("Page not found, creating new page and navigating...");
                    // 优先使用现有的上下文（即用户配置目录的上下文），以保留登录态
                    if (!connection.browser.contexts().isEmpty()) {
                        page = connection.browser.contexts().get(0).newPage();
                    } else {
                        page = connection.browser.newPage();
                    }
                    page.navigate(url);
                } else {
                    System.out.println("Found existing page: " + page.title());
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
                    System.out.println("Current URL: " + safePageUrl(page) + ". Waiting for target URL: " + urlCheck + " (ignoring query, login might be required)...");
                    synchronized (PLAYWRIGHT_LOCK) {
                        page.waitForTimeout(interval);
                    }
                }
            } else {
                // No URL provided - Just pick the first active page or create a blank one
                System.out.println("No target URL provided. Attaching to active page...");
                if (!connection.browser.contexts().isEmpty() && !connection.browser.contexts().get(0).pages().isEmpty()) {
                    // Pick the last used page (usually the most relevant)
                    com.microsoft.playwright.BrowserContext context = connection.browser.contexts().get(0);
                    // Use the last page as it's likely the most recently opened
                    page = context.pages().get(context.pages().size() - 1);
                    page.bringToFront();
                    System.out.println("Attached to existing page: " + page.title() + " (" + safePageUrl(page) + ")");
                } else {
                    System.out.println("No active pages found. Creating a blank new page.");
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
                    page.waitForLoadState(com.microsoft.playwright.options.LoadState.NETWORKIDLE, new com.microsoft.playwright.Page.WaitForLoadStateOptions().setTimeout(10000));
                } catch (Exception e) {
                    System.out.println("Wait for NETWORKIDLE timed out or failed, continuing...");
                }

                System.out.println("Waiting 1 second for dynamic content to render...");
                page.waitForTimeout(1000);
            } else {
                System.out.println("Fast start mode: Skipping network idle wait and dynamic content wait.");
            }

            com.microsoft.playwright.Frame contentFrame = null;
            String frameName = "";
            double maxArea = 0;

            if (page.frames().size() > 1) {
                int maxScanRetries = fastStart ? 1 : 3;
                int waitBetweenScansMs = fastStart ? 0 : 1000;
                System.out.println("Checking frames (scanning up to " + maxScanRetries + " times)...");
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
                                    System.out.println(" - [" + i + "] Frame: " + f.name() + " | URL: " + frameUrl + " | Area: " + area);

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
                            System.out.println(" - Error checking frame " + f.name() + ": " + e.getMessage());
                        }
                    }

                    if (contentFrame != null) {
                        System.out.println("   -> Identified largest frame as content frame: " + frameName + " (Area: " + maxArea + ")");
                        break;
                    }

                    if (waitBetweenScansMs > 0) {
                        System.out.println("   -> No significant child frame found yet. Waiting " + (waitBetweenScansMs / 1000) + "s...");
                        page.waitForTimeout(waitBetweenScansMs);
                    }
                }
            } else {
                System.out.println("No child frames detected, skipping frame scan.");
            }

            // Launch UI
            System.out.println("Launching Control UI...");
            // Use contentFrame if available, otherwise use page
            Object executionContext = (contentFrame != null) ? contentFrame : page;
            SwingUtilities.invokeLater(() -> AutoWebAgentUI.createGUI(executionContext, "", userPrompt, connection));

        } catch (Exception e) {
            e.printStackTrace();
            System.err.println("Error during initialization: " + e.getMessage());
            if (connection != null && connection.playwright != null) {
                connection.playwright.close();
            }
            System.exit(1);
        }
    }
    
    // A simple wrapper to hold the current execution context (Page or Frame)
    /**
     * 执行上下文包装：Page 或 Frame
     */
    static class ContextWrapper {
        Object context;
        String name;
        @Override
        public String toString() {
            return name;
        }
    }

    /**
     * 上下文扫描结果
     */
    static class ScanResult {
        java.util.List<ContextWrapper> wrappers = new java.util.ArrayList<>();
        ContextWrapper best;
    }

    /**
     * 计划步骤结构
     */
    static class PlanStep {
        int index;
        String description;
        String targetUrl;
        String entryAction;
        String status;
    }

    /**
     * 计划解析结果
     */
    static class PlanParseResult {
        String planText;
        java.util.List<PlanStep> steps = new java.util.ArrayList<>();
        boolean confirmed;
        boolean hasQuestion;
    }

    /**
     * 步骤 HTML 快照结构
     */
    static class HtmlSnapshot {
        int stepIndex;
        String url;
        String entryAction;
        String cacheKey;
        String cleanedHtml;
    }

    /**
     * 单模型会话状态
     */
    static class ModelSession {
        String userPrompt;
        String planText;
        java.util.List<PlanStep> steps = new java.util.ArrayList<>();
        java.util.Map<Integer, HtmlSnapshot> stepSnapshots = new java.util.HashMap<>();
        boolean planConfirmed;
        boolean htmlPrepared;
        String lastArtifactType;
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

    static PlanParseResult parsePlanFromText(String text) {
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

    static String buildPlanOnlyPayload(Page currentPage, String userPrompt) {
        return PayloadSupport.buildPlanOnlyPayload(currentPage, userPrompt);
    }

    static String buildPlanOnlyPayload(String currentUrl, String userPrompt) {
        return PayloadSupport.buildPlanOnlyPayload(currentUrl, userPrompt);
    }

    static String buildPlanEntryPayload(Page currentPage, String userPrompt) {
        return PayloadSupport.buildPlanEntryPayload(currentPage, userPrompt);
    }

    static String buildPlanEntryPayload(String currentUrl, String userPrompt) {
        return PayloadSupport.buildPlanEntryPayload(currentUrl, userPrompt);
    }

    static String buildPlanRefinePayload(Page currentPage, String userPrompt, String refineHint) {
        return PayloadSupport.buildPlanRefinePayload(currentPage, userPrompt, refineHint);
    }

    static String buildPlanRefinePayload(String currentUrl, String userPrompt, String refineHint) {
        return PayloadSupport.buildPlanRefinePayload(currentUrl, userPrompt, refineHint);
    }

    static String buildCodegenPayload(Page currentPage, String planText, java.util.List<HtmlSnapshot> snapshots) {
        return PayloadSupport.buildCodegenPayload(currentPage, planText, snapshots);
    }

    static String buildRefinePayload(Page currentPage, String planText, java.util.List<HtmlSnapshot> snapshots, String currentCleanedHtml, String userPrompt, String refineHint) {
        return PayloadSupport.buildRefinePayload(currentPage, planText, snapshots, currentCleanedHtml, userPrompt, refineHint);
    }

    static long utf8Bytes(String s) {
        return StorageSupport.utf8Bytes(s);
    }

    static String saveDebugArtifact(String ts, String modelName, String mode, String kind, String content, java.util.function.Consumer<String> uiLogger) {
        return StorageSupport.saveDebugArtifact(ts, modelName, mode, kind, content, uiLogger);
    }

    static String chooseExecutionEntryUrl(ModelSession session, String currentPrompt) {
        return PlanRoutingSupport.chooseExecutionEntryUrl(session, currentPrompt);
    }

    static boolean ensureRootPageAtUrl(Page rootPage, String targetUrl, java.util.function.Consumer<String> uiLogger) {
        return PlanRoutingSupport.ensureRootPageAtUrl(rootPage, targetUrl, uiLogger);
    }

    static ContextWrapper waitAndFindContext(Page rootPage, java.util.function.Consumer<String> uiLogger) {
        return PlanRoutingSupport.waitAndFindContext(rootPage, uiLogger);
    }

    private static ScanResult scanContexts(Page page) {
        return PlanRoutingSupport.scanContexts(page);
    }

    static ContextWrapper reloadAndFindContext(Page rootPage, java.util.function.Consumer<String> uiLogger) {
        return PlanRoutingSupport.reloadAndFindContext(rootPage, uiLogger);
    }

    static java.util.List<HtmlSnapshot> prepareStepHtmls(
            Page rootPage,
            java.util.List<PlanStep> steps,
            java.util.function.Consumer<String> uiLogger
    ) {
        return prepareStepHtmls(rootPage, steps, uiLogger, HtmlCaptureMode.RAW_HTML);
    }

    static java.util.List<HtmlSnapshot> prepareStepHtmls(
            Page rootPage,
            java.util.List<PlanStep> steps,
            java.util.function.Consumer<String> uiLogger,
            HtmlCaptureMode captureMode
    ) {
        return prepareStepHtmls(rootPage, steps, uiLogger, captureMode, true);
    }

    static java.util.List<HtmlSnapshot> prepareStepHtmls(
            Page rootPage,
            java.util.List<PlanStep> steps,
            java.util.function.Consumer<String> uiLogger,
            HtmlCaptureMode captureMode,
            boolean a11yInterestingOnly
    ) {
        if (steps == null || steps.isEmpty()) return new java.util.ArrayList<>();
        synchronized (PLAYWRIGHT_LOCK) {
            java.util.List<HtmlSnapshot> out = new java.util.ArrayList<>();
            AutoWebAgentUI.FrameState frameState = AutoWebAgentUI.captureFrameState();

            java.util.HashMap<String, HtmlSnapshot> snapshotByUrl = new java.util.HashMap<>();
            com.microsoft.playwright.BrowserContext ctx = rootPage.context();
            Page tmp = null;
            java.util.List<Page> openedPages = new java.util.ArrayList<>();
            String rootUrl = safePageUrl(rootPage);
            try {
                tmp = ctx.newPage();
                openedPages.add(tmp);
                for (PlanStep step : steps) {
                    if (step == null) continue;

                    String url = normalizeUrlToken(step.targetUrl);
                    boolean isCurrentPage = (url == null || url.trim().isEmpty() || "CURRENT_PAGE".equalsIgnoreCase(url.trim()));
                    if (isCurrentPage) {
                        url = rootUrl;
                    }
                    url = normalizeUrlToken(url);
                    if (!looksLikeUrl(url)) {
                        HtmlSnapshot cached = readCachedHtml(step.index, url, step.entryAction, captureMode, a11yInterestingOnly);
                        if (cached != null) out.add(cached);
                        continue;
                    }

                    String urlKey = url == null ? "" : url.trim();
                    HtmlSnapshot existing = urlKey.isEmpty() ? null : snapshotByUrl.get(urlKey);
                    if (existing != null) {
                        if (uiLogger != null) uiLogger.accept("复用已采集页面: Step " + step.index + " | " + url + " | sameAsStep=" + existing.stepIndex);
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
                        HtmlSnapshot cached = readCachedHtml(step.index, url, step.entryAction, captureMode, a11yInterestingOnly);
                        if (cached != null) {
                            if (uiLogger != null) uiLogger.accept("命中缓存: Step " + step.index + " | " + url);
                            if (!urlKey.isEmpty()) snapshotByUrl.putIfAbsent(urlKey, cached);
                            out.add(cached);
                            continue;
                        }

                        AutoWebAgentUI.minimizeFrameIfNeeded(frameState);
                        if (uiLogger != null) uiLogger.accept("采集当前页面: Step " + step.index + " | " + url);
                        String rawHtml = "";
                        try {
                            ContextWrapper best = null;
                            for (int attempt = 0; attempt < 16; attempt++) {
                                ScanResult sr = scanContexts(rootPage);
                                if (sr != null) best = sr.best;
                                if (best != null && best.name != null && !"Main Page".equals(best.name)) break;
                                try { rootPage.waitForTimeout(500); } catch (Exception ignored) {}
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
                                rawHtml = getPageContent(best.context, captureMode, a11yInterestingOnly);
                            } else {
                                rawHtml = getPageContent(rootPage, captureMode, a11yInterestingOnly);
                            }
                        } catch (Exception ignored) {
                            try { rawHtml = getPageContent(rootPage, captureMode, a11yInterestingOnly); } catch (Exception ignored2) {}
                        }
                        String cleaned = cleanCapturedContent(rawHtml, captureMode);
                        HtmlSnapshot snap = writeCachedHtml(step.index, url, step.entryAction, captureMode, a11yInterestingOnly, rawHtml, cleaned);
                        if (snap != null) {
                            if (!urlKey.isEmpty()) snapshotByUrl.putIfAbsent(urlKey, snap);
                            out.add(snap);
                        }
                        continue;
                    }

                    HtmlSnapshot cached = readCachedHtml(step.index, url, step.entryAction, captureMode, a11yInterestingOnly);
                    if (cached != null) {
                        if (uiLogger != null) uiLogger.accept("命中缓存: Step " + step.index + " | " + url);
                        if (!urlKey.isEmpty()) snapshotByUrl.putIfAbsent(urlKey, cached);
                        out.add(cached);
                        continue;
                    }

                    AutoWebAgentUI.minimizeFrameIfNeeded(frameState);
                    if (uiLogger != null) uiLogger.accept("采集页面: Step " + step.index + " | " + url);
                    try {
                        tmp.navigate(url);
                    } catch (Exception navEx) {
                        if (uiLogger != null) uiLogger.accept("打开 URL 失败: " + navEx.getMessage());
                    }
                    try {
                        tmp.waitForLoadState(com.microsoft.playwright.options.LoadState.NETWORKIDLE, new Page.WaitForLoadStateOptions().setTimeout(10000));
                    } catch (Exception ignored) {
                        try { tmp.waitForLoadState(com.microsoft.playwright.options.LoadState.LOAD); } catch (Exception ignored2) {}
                    }
                    boolean stepOk = waitForUrlPrefix(tmp, url, 120000, 2000, uiLogger, "采集页面 Step " + step.index);
                    if (!stepOk) {
                        continue;
                    }

                    String entry = step.entryAction == null ? "" : step.entryAction;
                    String token = firstQuotedToken(entry);
                    if (token != null) {
                        try {
                            com.microsoft.playwright.Locator loc = tmp.locator("text=" + token).first();
                            if (loc != null) {
                                boolean mayOpenNew = entry.contains("新开") || entry.toLowerCase().contains("new tab") || entry.toLowerCase().contains("new window");
                                if (mayOpenNew) {
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
                                            tmp.waitForLoadState();
                                        }
                                    } catch (Exception e) {
                                        try { loc.click(new com.microsoft.playwright.Locator.ClickOptions().setTimeout(5000)); } catch (Exception ignored) {}
                                    }
                                } else {
                                    try { loc.click(new com.microsoft.playwright.Locator.ClickOptions().setTimeout(5000)); } catch (Exception ignored) {}
                                    try {
                                        tmp.waitForLoadState(com.microsoft.playwright.options.LoadState.NETWORKIDLE, new Page.WaitForLoadStateOptions().setTimeout(8000));
                                    } catch (Exception ignored2) {}
                                }
                            }
                        } catch (Exception ignored) {}
                    }

                    String rawHtml = "";
                    try {
                        ContextWrapper best = null;
                        for (int attempt = 0; attempt < 16; attempt++) {
                            ScanResult sr = scanContexts(tmp);
                            if (sr != null) best = sr.best;
                            if (best != null && best.name != null && !"Main Page".equals(best.name)) break;
                            try { tmp.waitForTimeout(500); } catch (Exception ignored) {}
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
                            rawHtml = getPageContent(best.context, captureMode, a11yInterestingOnly);
                        } else {
                            rawHtml = getPageContent(tmp, captureMode, a11yInterestingOnly);
                        }
                    } catch (Exception ignored) {
                        try { rawHtml = getPageContent(tmp, captureMode, a11yInterestingOnly); } catch (Exception ignored2) {}
                    }
                    String cleaned = cleanCapturedContent(rawHtml, captureMode);
                    HtmlSnapshot snap = writeCachedHtml(step.index, url, step.entryAction, captureMode, a11yInterestingOnly, rawHtml, cleaned);
                    if (snap != null) {
                        if (!urlKey.isEmpty()) snapshotByUrl.putIfAbsent(urlKey, snap);
                        out.add(snap);
                    }
                }
            } finally {
                for (int i = openedPages.size() - 1; i >= 0; i--) {
                    Page p = openedPages.get(i);
                    if (p == null) continue;
                    try { p.close(); } catch (Exception ignored) {}
                }
                AutoWebAgentUI.restoreFrameIfNeeded(frameState);
            }
            return out;
        }
    }

    static String getPageContent(Object pageOrFrame) {
        return getPageContent(pageOrFrame, HtmlCaptureMode.RAW_HTML);
    }

    static String getPageContent(Object pageOrFrame, HtmlCaptureMode captureMode) {
        return getPageContent(pageOrFrame, captureMode, true);
    }

    static String getPageContent(Object pageOrFrame, HtmlCaptureMode captureMode, boolean a11yInterestingOnly) {
        HtmlCaptureMode mode = captureMode == null ? HtmlCaptureMode.RAW_HTML : captureMode;
        if (mode == HtmlCaptureMode.ARIA_SNAPSHOT) {
            String snap = getAriaSnapshot(pageOrFrame, a11yInterestingOnly);
            if (snap != null && !snap.isEmpty()) return snap;
        }
        return getRawHtmlContent(pageOrFrame);
    }

    private static String getRawHtmlContent(Object pageOrFrame) {
        if (pageOrFrame instanceof Page) {
            return ((Page) pageOrFrame).content();
        } else if (pageOrFrame instanceof com.microsoft.playwright.Frame) {
            return ((com.microsoft.playwright.Frame) pageOrFrame).content();
        }
        return "";
    }

    private static String getAriaSnapshot(Object pageOrFrame, boolean a11yInterestingOnly) {
        try {
            if (pageOrFrame instanceof Page) {
                Page p = (Page) pageOrFrame;
                Object scoped = p;
                try {
                    ScanResult sr = scanContexts(p);
                    ContextWrapper best = sr == null ? null : sr.best;
                    if (best != null && best.context instanceof com.microsoft.playwright.Frame) {
                        String name = best.name == null ? "" : best.name.trim();
                        if (!name.isEmpty() && !"Main Page".equals(name)) {
                            scoped = best.context;
                        }
                    }
                } catch (Exception ignored) {}
                
                String snapText;
                if (scoped instanceof com.microsoft.playwright.Frame) {
                    snapText = ((com.microsoft.playwright.Frame) scoped).locator("body").ariaSnapshot();
                } else {
                    snapText = p.locator("body").ariaSnapshot();
                }
                return wrapAsJsonText(snapText);
            } else if (pageOrFrame instanceof com.microsoft.playwright.Frame) {
                com.microsoft.playwright.Frame f = (com.microsoft.playwright.Frame) pageOrFrame;
                try {
                    String snapText = f.locator("body").ariaSnapshot();
                    String snap = wrapAsJsonText(snapText);
                    return snap == null ? "" : snap;
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

    static String cleanCapturedContent(String captured, HtmlCaptureMode captureMode) {
        HtmlCaptureMode mode = captureMode == null ? HtmlCaptureMode.RAW_HTML : captureMode;
        String out;
        if (mode == HtmlCaptureMode.ARIA_SNAPSHOT) {
            System.out.println("ARIA_SNAPSHOT.length:\n" + (captured == null ? 0 : captured.length()));
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
    static String generateGroovyScript(String userPrompt, String cleanedHtml, java.util.function.Consumer<String> uiLogger, String modelName) {
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

    static String generateRefinedGroovyScript(
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
        if (code == null) return null;
        String normalized = code;
        normalized = normalized.replaceAll("(?m)^(\\s*)(PLAN:|THINK:|ANALYSIS:|REASONING:|思考过程|计划|PLAN_START|PLAN_END|QUESTION:)\\b", "$1// $2");
        normalized = normalized.replaceAll("(?m)^(\\s*)(-\\s*[Pp]lan\\b.*)", "$1// $2");
        normalized = normalized.replaceAll("(?m)^(\\s*)(\\*\\s*[Pp]lan\\b.*)", "$1// $2");
        normalized = normalized.replaceAll("(?m)^(\\s*)(\\[Plan\\].*)", "$1// $2");
        normalized = normalized.replaceAll("(?m)^(\\s*)(<plan>.*)</plan>\\s*$", "$1// $2");
        normalized = normalized.replaceAll("(?m)^(\\s*)(<think>.*)</think>\\s*$", "$1// $2");
        normalized = normalized.replaceAll("(?m)^(\\s*)(思考:.*)", "$1// $2");

        boolean applyNormalization =
                normalized.contains("web.extractList(") ||
                normalized.matches("(?s).*\\browCount\\b\\s*=\\s*web\\.count\\(.*") ||
                normalized.contains("rowTexts") ||
                normalized.contains("joinedRow");
        if (applyNormalization) {
            String replacement = "def rows = web.extractFirstPageRows(containerSelector, rowSelector, cellSelector)\n" +
                    "rows.each { row -> web.log(row) }\n";
            java.util.regex.Pattern blockPatternA = java.util.regex.Pattern.compile(
                    "(?s)def\\s+rowCount\\s*=\\s*web\\.count\\([^\\n]*\\).*?allRowsOutput\\.each\\s*\\{.*?\\}\\s*"
            );
            java.util.regex.Pattern blockPatternB = java.util.regex.Pattern.compile(
                    "(?s)def\\s+rowCount\\s*=\\s*web\\.count\\([^\\n]*\\).*?(?=def\\s+totalCountText|def\\s+totalText|web\\.getText\\()"
            );
            normalized = blockPatternA.matcher(normalized).replaceAll(replacement);
            normalized = blockPatternB.matcher(normalized).replaceAll(replacement);
            normalized = normalized.replaceAll("(?s)def\\s+rowTexts\\s*=\\s*\\[\\].*?def\\s+joinedRow\\s*=.*?web\\.log\\(joinedRow\\).*?(?=def\\s+totalCountText|def\\s+totalText|web\\.getText\\()", "");
            normalized = normalized.replaceAll("(?s)int\\s+rowCount\\s*=\\s*web\\.count\\([^\\n]*\\).*?for\\s*\\(\\s*int\\s+i\\s*=\\s*0;.*?\\)\\s*\\{.*?web\\.log\\(joinedRow\\)\\s*;?\\s*\\}.*?(?=def\\s+totalCountText|def\\s+totalText|web\\.getText\\()", replacement);
            
            java.util.regex.Pattern getTextLogAssignPattern = java.util.regex.Pattern.compile("(?s)(?:String|def|var)?\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*web\\.getText\\((\"|')(.*?)\\2\\)\\s*\\n\\s*web\\.log\\(\\1\\)");
            java.util.regex.Matcher getTextLogAssignMatcher = getTextLogAssignPattern.matcher(normalized);
            StringBuffer getTextLogAssignBuffer = new StringBuffer();
            while (getTextLogAssignMatcher.find()) {
                String varName = getTextLogAssignMatcher.group(1);
                String sel = getTextLogAssignMatcher.group(3);
                String replacementBlock = "def " + varName + " = web.getText(\"" + sel.replace("\"", "\\\"") + "\")\nweb.log(" + varName + ")";
                getTextLogAssignMatcher.appendReplacement(getTextLogAssignBuffer, java.util.regex.Matcher.quoteReplacement(replacementBlock));
            }
            getTextLogAssignMatcher.appendTail(getTextLogAssignBuffer);
            normalized = getTextLogAssignBuffer.toString();
            
            java.util.regex.Pattern getTextLogPattern = java.util.regex.Pattern.compile("(?s)web\\.getText\\((\"|')(.*?)\\1\\)\\s*\\n\\s*web\\.log\\(([^\\)]+)\\)");
            java.util.regex.Matcher getTextLogMatcher = getTextLogPattern.matcher(normalized);
            StringBuffer getTextLogBuffer = new StringBuffer();
            while (getTextLogMatcher.find()) {
                String sel = getTextLogMatcher.group(2);
                String varName = getTextLogMatcher.group(3).trim();
                String replacementBlock = "def " + varName + " = web.getText(\"" + sel.replace("\"", "\\\"") + "\")\nweb.log(" + varName + ")";
                getTextLogMatcher.appendReplacement(getTextLogBuffer, java.util.regex.Matcher.quoteReplacement(replacementBlock));
            }
            getTextLogMatcher.appendTail(getTextLogBuffer);
            normalized = getTextLogBuffer.toString();
        }
        normalized = escapeNonInterpolatedDollarInDoubleQuotedStrings(normalized);
        return normalized;
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

    static void executeWithGroovy(String scriptCode, Object pageOrFrame, java.util.function.Consumer<String> logger) throws Exception {
        // 1. Static Linting
        java.util.List<String> lintErrors = GroovyLinter.check(scriptCode);
        if (!lintErrors.isEmpty()) {
            StringBuilder sb = new StringBuilder("Static Analysis Found Issues:\n");
            for (String err : lintErrors) {
                sb.append("- ").append(err).append("\n");
            }
            logger.accept(sb.toString());
            
            // Abort on security violations
            boolean hasSecurityError = lintErrors.stream().anyMatch(e -> e.startsWith("Security Error"));
            boolean hasSyntaxError = lintErrors.stream().anyMatch(e -> e.startsWith("Syntax Error") || e.startsWith("Parse Error"));
            if (hasSecurityError || hasSyntaxError) {
                 throw new RuntimeException("Execution aborted due to static analysis violations.");
            }
        }

        try {
            groovy.lang.Binding binding = new groovy.lang.Binding();
            binding.setVariable("page", pageOrFrame);
            
            // Inject WebDSL
            WebDSL dsl = new WebDSL(pageOrFrame, logger);
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
                        logger.accept(line); // Log to UI
                        buffer.delete(0, newline + 1);
                        newline = buffer.indexOf("\n");
                    }
                }
            }, true)); // Auto-flush

            groovy.lang.GroovyShell shell = new groovy.lang.GroovyShell(binding);
            shell.evaluate(scriptCode);
            logger.accept("Groovy script executed successfully.");
        } catch (Exception e) {
            logger.accept("Groovy execution failed: " + e.getMessage());
            // 抛出异常以便主程序捕获并退出
            throw e;
        }
    }

}
