package com.qiyi.service.autoweb;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.qiyi.config.AppConfig;
import com.qiyi.util.LLMUtil;
import com.qiyi.util.PlayWrightUtil;
import com.qiyi.util.AppLog;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 多模型端到端自动化回归执行器（E2E Runner）。
 *
 * 用途：
 * 1) 使用同一批 Case（入口 URL + 用户任务）对多个模型进行 Plan/Code/执行对比；
 * 2) 采集每一步执行的结果、耗时与日志片段，汇总生成可落盘的 JSON 报告；
 * 3) 可选：将报告压缩后喂给本地模型做“离线分析”（定位系统性失败点与改进建议）。
 *
 * 产物：
 * - 报告通过 {@link StorageSupport#saveDebugArtifact(String, String, String, String, String, java.util.function.Consumer, int)}
 *   写入 autoweb/debug（文件名含 ts/model/mode/kind）。
 */
public class MultiModelAutoRun {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    /**
     * Runner 的运行参数集合。
     * 注意：默认回归场景在 defaultConfig() 中设置为 captureMode=RAW_HTML、useVisualSupplement=true。
     */
    static class RunnerConfig {
        List<String> models = new ArrayList<>();
        List<CaseInput> cases = new ArrayList<>();
        AutoWebAgent.HtmlCaptureMode captureMode = AutoWebAgent.HtmlCaptureMode.ARIA_SNAPSHOT;
        boolean useVisualSupplement = true;
        boolean alsoStdout = true;
        boolean localAnalyze = false;
        String localAnalysisModel = LLMUtil.OLLAMA_MODEL_QWEN3_8B;
        int reportMaxChars = 8_000_000;
    }

    /**
     * 单个测试用例输入（Case）。
     *
     * - entryUrl：入口地址（用于导航/确保登录态后回到入口）
     * - userTask：自然语言任务描述（用于生成 Plan/Code）
     */
    static class CaseInput {
        String id;
        String entryUrl;
        String userTask;
    }

    /**
     * 单步（Plan Step）执行的结果。
     * 用于在报告中展示“哪一步失败/耗时多少/错误是什么/日志尾部是什么”。
     */
    static class StepExecutionResult {
        int stepIndex;
        boolean ok;
        long durationMs;
        String error;
        String logTail;
    }

    /**
     * 某个模型在某个 Case 下的一次运行结果（可包含重试/修正）。
     * plan/code/lintErrors/stepResults 会写入最终报告。
     */
    static class ModelRunResult {
        String model;
        String prompt;
        int attemptIndex;
        boolean repairAttempt;
        String planText;
        boolean planConfirmed;
        boolean planHasQuestion;
        List<AutoWebAgent.PlanStep> planSteps;
        String code;
        List<String> lintErrors;
        String entryUrlUsed;
        List<StepExecutionResult> stepResults;
        List<String> formattedErrors;
        String runLogHead;
        String runLogTail;
    }

    /**
     * Case 级别的汇总（包含多个模型的运行结果）。
     */
    static class CaseRunResult {
        String id;
        String entryUrl;
        String userTask;
        List<ModelRunResult> runs;
    }

    /**
     * 最终报告结构。
     * - analysisPrompt：给本地模型做离线分析的提示词
     * - localAnalysis：本地模型返回的分析文本（可选）
     */
    static class Report {
        String ts;
        List<String> models;
        String captureMode;
        List<CaseRunResult> cases;
        String analysisPrompt;
        String localAnalysisModel;
        String localAnalysis;
    }

    /**
     * 命令行入口：构建默认配置、装载 models/cases，并执行一次完整回归。
     * 产物写入 autoweb/debug，便于在 CI 或本地对比模型差异。
     */
    public static void main(String[] args) throws Exception {
        RunnerConfig cfg = defaultConfig();
        applyRuntimeOverrides(cfg);

        List<String> modelsOverride = parseCsv(readArgOrProp(args, "models", "autoweb.e2e.models", ""));
        if (!modelsOverride.isEmpty()) {
            for (String m : modelsOverride) cfg.models.add(AutoWebAgent.normalizeModelKey(m));
        } else {
            cfg.models.add(AutoWebAgent.normalizeModelKey("deepseek"));
            //cfg.models.add(AutoWebAgent.normalizeModelKey("QWEN_MAX"));
            //cfg.models.add(AutoWebAgent.normalizeModelKey("MOONSHOT"));
        }

        List<CaseInput> casesOverride = loadCasesFromProperties();
        if (casesOverride != null && !casesOverride.isEmpty()) {
            cfg.cases.addAll(casesOverride);
        } else {
            List<String> genUrls = parseCsv(readArgOrProp(args, "genUrls", "autoweb.e2e.genUrls", ""));
            String genPrompt = readArgOrProp(args, "genPrompt", "autoweb.e2e.genPrompt", "").trim();
            
            genUrls.add("https://sc.scm121.com/manage/goods/goodsManage/index?pageNum=1&pageSize=20");
            genUrls.add("https://sc.scm121.com/suppliersManage/tower/distribute");
            genUrls.add("https://sc.scm121.com/partnerManage/myPartner");


            genPrompt ="请根据页面的元素，生成对应的测试用例；如果有列表，优先生成列表获取内容的测试案例，注意不要用现在页面有的统计数据作为测试用例的数据说明。";

            
            if (!genUrls.isEmpty()) {
                cfg.cases.addAll(generateCasesFromUrls(genUrls, genPrompt));
            } else {
                cfg.cases.add(new CaseInput() {{
                    id = "1";
                    entryUrl = "https://sc.scm121.com/manage/goods/goodsManage/index?pageNum=1&pageSize=20";
                    userTask = "请查询资料编码包含\"自由品牌\"的商品，输出这些商品的商品信息、类目名称、库存、基本售价，最多只需要 3 页";
                }});

                cfg.cases.add(new CaseInput() {{
                    id = "2";
                    entryUrl = "https://sc.scm121.com/tradeManage/tower/distribute";
                    userTask = "请帮我查询待发货所有的订单（包括多页的数据），并且输出订单的所有信息，输出格式为：\"列名:列内容（去掉回车换行）\"，然后用\"｜\"分隔，列的顺序保持表格的顺序，一条记录一行。输出以后，回到第一条订单，选中订单，然后点击审核推单，读取弹出页面的成功和失败的笔数，失败笔数大于0，页面上获取失败原因，也一起输出";
                }});
            }
        }

        //打印出cases里面所有的信息
        for (CaseInput caseInput : cfg.cases) {
            AppLog.info("Case: " + caseInput.id + " " + caseInput.entryUrl + " \\r\\n " + caseInput.userTask);
        }

        
        Report report = runOnce(cfg);
        printConsoleSummary(report, System.out::println);
        String reportJson = GSON.toJson(toJson(report));
        String reportPath = StorageSupport.saveDebugArtifact(report.ts, "MULTI", "E2E", "multimodel_report", reportJson, new BufferingLogger("[E2E] ", true), cfg.reportMaxChars);
        AppLog.info("Multi-model E2E report saved: " + (reportPath == null ? "" : reportPath));

        if (cfg.localAnalyze) {
            String analysisInput = buildCompactAnalysisInput(report);
            String analysis = LLMUtil.chatWithOllama(analysisInput, cfg.localAnalysisModel, null, false);
            report.localAnalysis = analysis == null ? "" : analysis;
            String updated = GSON.toJson(toJson(report));
            String path2 = StorageSupport.saveDebugArtifact(report.ts, "MULTI", "E2E", "multimodel_report_with_local_analysis", updated, new BufferingLogger("[E2E] ", true), cfg.reportMaxChars);
            AppLog.info("Multi-model E2E report (with local analysis) saved: " + (path2 == null ? "" : path2));
        }
    }

    static class BufferingLogger implements java.util.function.Consumer<String> {
        private final List<String> lines = new ArrayList<>();
        private final boolean alsoStdout;
        private final String prefix;

        /**
         * @param prefix 日志行前缀（用于区分 model/阶段）
         * @param alsoStdout 是否同时输出到标准输出
         */
        BufferingLogger(String prefix, boolean alsoStdout) {
            this.prefix = prefix == null ? "" : prefix;
            this.alsoStdout = alsoStdout;
        }

        @Override
        public void accept(String s) {
            String v = s == null ? "" : s;
            String line = prefix.isEmpty() ? v : (prefix + v);
            lines.add(line);
            if (alsoStdout) {
                AppLog.info(line);
            }
        }

        List<String> snapshot() {
            return new ArrayList<>(lines);
        }

        String head(int maxLines) {
            int n = Math.min(maxLines, lines.size());
            return String.join("\n", lines.subList(0, n));
        }

        String tail(int maxLines) {
            int n = Math.min(maxLines, lines.size());
            return String.join("\n", lines.subList(lines.size() - n, lines.size()));
        }
    }

    private static Report runOnce(RunnerConfig cfg) throws Exception {
        if (cfg == null) throw new IllegalArgumentException("cfg is null");
        if (cfg.models == null || cfg.models.isEmpty()) throw new IllegalArgumentException("cfg.models is empty");
        if (cfg.cases == null || cfg.cases.isEmpty()) throw new IllegalArgumentException("cfg.cases is empty");

        cleanupAutowebArtifacts();

        String ts = AutoWebAgent.newDebugTimestamp();
        Report report = new Report();
        report.ts = ts;
        report.models = cfg.models;
        report.captureMode = cfg.captureMode == null ? "" : cfg.captureMode.name();
        report.cases = new ArrayList<>();
        report.analysisPrompt = buildAnalysisPrompt();
        report.localAnalysisModel = cfg.localAnalyze ? (cfg.localAnalysisModel == null ? "" : cfg.localAnalysisModel) : "";
        report.localAnalysis = "";

        GroovySupport.loadPrompts();

        BufferingLogger rootLogger = new BufferingLogger("[E2E] ", cfg.alsoStdout);
        rootLogger.accept("E2E config: captureMode=" + report.captureMode + ", visualSupplement=" + cfg.useVisualSupplement);
        PlayWrightUtil.Connection connection = PlayWrightUtil.connectAndAutomate();
        if (connection == null || connection.browser == null) throw new RuntimeException("Failed to connect to browser.");

        try {
            for (CaseInput c : cfg.cases) {
                CaseRunResult caseResult = new CaseRunResult();
                caseResult.id = c.id;
                caseResult.entryUrl = c.entryUrl;
                caseResult.userTask = c.userTask;
                caseResult.runs = new ArrayList<>();

                PageHandle pageHandle = null;
                try {
                    pageHandle = openPage(connection, c.entryUrl, rootLogger);
                    for (String model : cfg.models) {
                        try {
                            List<ModelRunResult> rs = runSingleModelCase(model, c, pageHandle.page, cfg.captureMode, cfg.useVisualSupplement, cfg.alsoStdout);
                            if (rs != null) caseResult.runs.addAll(rs);
                        } catch (Exception ex) {
                            ModelRunResult r = new ModelRunResult();
                            r.model = model;
                            r.prompt = buildPrompt(c);
                            r.attemptIndex = 1;
                            r.repairAttempt = false;
                            r.planSteps = new ArrayList<>();
                            r.lintErrors = new ArrayList<>();
                            r.stepResults = new ArrayList<>();
                            r.formattedErrors = new ArrayList<>();
                            r.entryUrlUsed = c == null ? "" : c.entryUrl;
                            r.formattedErrors.add("步骤: (runner), 出错信息: " + (ex.getMessage() == null ? ex.toString() : ex.getMessage()));
                            r.runLogHead = "";
                            r.runLogTail = "";
                            caseResult.runs.add(r);
                        }
                    }
                } catch (Exception ex) {
                    for (String model : cfg.models) {
                        ModelRunResult r = new ModelRunResult();
                        r.model = model;
                        r.prompt = buildPrompt(c);
                        r.attemptIndex = 1;
                        r.repairAttempt = false;
                        r.planSteps = new ArrayList<>();
                        r.lintErrors = new ArrayList<>();
                        r.stepResults = new ArrayList<>();
                        r.formattedErrors = new ArrayList<>();
                        r.entryUrlUsed = c == null ? "" : c.entryUrl;
                        r.formattedErrors.add("步骤: (openPage), 出错信息: " + (ex.getMessage() == null ? ex.toString() : ex.getMessage()));
                        r.runLogHead = "";
                        r.runLogTail = "";
                        caseResult.runs.add(r);
                    }
                } finally {
                    if (pageHandle != null) {
                        try {
                            pageHandle.page.close();
                        } catch (Exception ignored) {
                        }
                    }
                }

                report.cases.add(caseResult);
            }
        } finally {
            try {
                PlayWrightUtil.disconnectBrowser(connection.playwright, connection.browser);
            } catch (Exception ignored) {
            }
        }

        return report;
    }

    private static class PageHandle {
        com.microsoft.playwright.Page page;
    }

    private static PageHandle openPage(PlayWrightUtil.Connection connection, String entryUrl, java.util.function.Consumer<String> logger) {
        PageHandle h = new PageHandle();
        com.microsoft.playwright.BrowserContext ctx = null;
        try {
            if (connection.browser.contexts() != null && !connection.browser.contexts().isEmpty()) {
                ctx = connection.browser.contexts().get(0);
            }
        } catch (Exception ignored) {
            ctx = null;
        }

        if (ctx == null) {
            ctx = connection.browser.newContext();
        }

        h.page = ctx.newPage();
        if (entryUrl != null && !entryUrl.trim().isEmpty()) {
            synchronized (AutoWebAgent.PLAYWRIGHT_LOCK) {
                h.page.navigate(entryUrl.trim());
                try {
                    h.page.waitForLoadState(
                            com.microsoft.playwright.options.LoadState.NETWORKIDLE,
                            new com.microsoft.playwright.Page.WaitForLoadStateOptions().setTimeout(120_000)
                    );
                } catch (Exception ignored) {
                }
                h.page.waitForTimeout(1000);
            }
            if (logger != null) {
                logger.accept("Opened page: " + StorageSupport.safePageUrl(h.page));
            }
        }
        return h;
    }

    private static List<ModelRunResult> runSingleModelCase(
            String model,
            CaseInput c,
            com.microsoft.playwright.Page page,
            AutoWebAgent.HtmlCaptureMode captureMode,
            boolean useVisualSupplement,
            boolean alsoStdout
    ) {
        List<ModelRunResult> outs = new ArrayList<>();

        BufferingLogger logger = new BufferingLogger("[model=" + model + "] ", alsoStdout);
        AutoWebAgent.ensureRootPageAtUrl(page, c.entryUrl, logger);

        ModelRunResult out = new ModelRunResult();
        out.model = model;
        out.prompt = buildPrompt(c);
        out.attemptIndex = 1;
        out.repairAttempt = false;
        out.planSteps = new ArrayList<>();
        out.lintErrors = new ArrayList<>();
        out.stepResults = new ArrayList<>();
        out.formattedErrors = new ArrayList<>();
        out.entryUrlUsed = c.entryUrl;

        String currentUrl = StorageSupport.safePageUrl(page);
        String planPayload = AutoWebAgent.buildPlanOnlyPayload(currentUrl, out.prompt, c.entryUrl);
        String planText = AutoWebAgent.generateGroovyScript(out.prompt, planPayload, logger, model);
        out.planText = planText == null ? "" : planText;

        AutoWebAgent.PlanParseResult parsed = AutoWebAgent.parsePlanFromText(out.planText);
        out.planConfirmed = parsed.confirmed;
        out.planHasQuestion = parsed.hasQuestion;
        out.planSteps = parsed.steps == null ? new ArrayList<>() : parsed.steps;

        if (out.planSteps.isEmpty()) {
            out.formattedErrors.add("步骤: (plan), 出错信息: 未解析到任何步骤");
            out.runLogHead = logger.head(200);
            out.runLogTail = logger.tail(200);
            outs.add(out);
            return outs;
        }

        List<AutoWebAgent.HtmlSnapshot> snapshots = AutoWebAgent.prepareStepHtmls(page, out.planSteps, logger, captureMode);
        String visualDescriptionForCodegen = null;
        if (useVisualSupplement) {
            try {
                visualDescriptionForCodegen = AutoWebAgentUI.buildPageVisualDescription(page, logger);
            } catch (Exception ex) {
                logger.accept("视觉补充(CODEGEN)失败: " + ex.getMessage());
                visualDescriptionForCodegen = "";
            }
        }
        String codePayload = AutoWebAgent.buildCodegenPayload(page, parsed.planText, snapshots, useVisualSupplement ? visualDescriptionForCodegen : null);
        String code = AutoWebAgent.generateGroovyScript(out.prompt, codePayload, logger, model);
        code = code == null ? "" : code;

        code = maybeNormalizeCode(code);
        out.code = code;
        out.lintErrors = safeLintErrors(code);

        executePlanSteps(out, model, c, page, code, alsoStdout, logger);
        out.runLogHead = logger.head(200);
        out.runLogTail = logger.tail(200);
        outs.add(out);

        if (shouldRepair(out)) {
            BufferingLogger repairLogger = new BufferingLogger("[model=" + model + " REPAIR] ", alsoStdout);
            AutoWebAgent.ensureRootPageAtUrl(page, c.entryUrl, repairLogger);

            String freshHtml = "";
            try {
                freshHtml = AutoWebAgent.getPageContent(page, captureMode, true);
            } catch (Exception ignored) {
                freshHtml = "";
            }
            String freshCleanedHtml = AutoWebAgent.cleanCapturedContent(freshHtml, captureMode);
            String refineHint = buildRepairHint(out);
            String visualDescriptionForRefine = null;
            if (useVisualSupplement) {
                try {
                    String cached = AutoWebAgentUI.readCachedPageVisualDescription(page, repairLogger);
                    if (cached != null && !cached.isEmpty()) {
                        visualDescriptionForRefine = cached;
                    } else {
                        visualDescriptionForRefine = AutoWebAgentUI.buildPageVisualDescription(page, repairLogger);
                    }
                } catch (Exception ex) {
                    repairLogger.accept("视觉补充(REFINE_CODE)失败: " + ex.getMessage());
                    visualDescriptionForRefine = "";
                }
            }
            String refinePayload = AutoWebAgent.buildRefinePayload(page, parsed.planText, snapshots, freshCleanedHtml, out.prompt, refineHint, useVisualSupplement ? visualDescriptionForRefine : null);
            String execOutput = buildExecOutputForRepair(out);

            String refined = AutoWebAgent.generateRefinedGroovyScript(out.prompt, refinePayload, out.code, execOutput, refineHint, repairLogger, model);
            refined = refined == null ? "" : refined;
            refined = maybeNormalizeCode(refined);

            ModelRunResult repaired = new ModelRunResult();
            repaired.model = model;
            repaired.prompt = out.prompt;
            repaired.attemptIndex = 2;
            repaired.repairAttempt = true;
            repaired.planText = out.planText;
            repaired.planConfirmed = out.planConfirmed;
            repaired.planHasQuestion = out.planHasQuestion;
            repaired.planSteps = out.planSteps == null ? new ArrayList<>() : out.planSteps;
            repaired.code = refined;
            repaired.lintErrors = safeLintErrors(refined);
            repaired.entryUrlUsed = out.entryUrlUsed;
            repaired.stepResults = new ArrayList<>();
            repaired.formattedErrors = new ArrayList<>();

            executePlanSteps(repaired, model, c, page, refined, alsoStdout, repairLogger);
            repaired.runLogHead = repairLogger.head(200);
            repaired.runLogTail = repairLogger.tail(200);
            outs.add(repaired);
        }

        return outs;
    }

    private static boolean shouldRepair(ModelRunResult r) {
        if (r == null) return false;
        if (r.planSteps == null || r.planSteps.isEmpty()) return false;
        if (r.stepResults == null || r.stepResults.isEmpty()) return false;
        for (StepExecutionResult sr : r.stepResults) {
            if (sr != null && !sr.ok) return true;
        }
        return false;
    }

    private static void executePlanSteps(
            ModelRunResult out,
            String model,
            CaseInput c,
            com.microsoft.playwright.Page page,
            String code,
            boolean alsoStdout,
            java.util.function.Consumer<String> attemptLogger
    ) {
        if (out == null) return;
        if (out.stepResults == null) out.stepResults = new ArrayList<>();
        if (out.formattedErrors == null) out.formattedErrors = new ArrayList<>();

        AutoWebAgent.ensureRootPageAtUrl(page, c.entryUrl, attemptLogger);

        String stepPrefix = out.repairAttempt ? ("[model=" + model + " REPAIR step=") : ("[model=" + model + " step=");
        groovy.lang.Binding sharedBinding = new groovy.lang.Binding();
        int baseTimeoutMs = com.qiyi.config.AppConfig.getInstance().getAutowebWaitForLoadStateTimeoutMs();
        int boostedTimeoutMs = Math.max(60_000, baseTimeoutMs * 3);
        int baseMaxRetries = 3;

        for (AutoWebAgent.PlanStep step : out.planSteps) {
            if (step == null) continue;
            int stepIndex = step.index;
            BufferingLogger stepLogger = new BufferingLogger(stepPrefix + stepIndex + "] ", alsoStdout);
            long t0 = System.currentTimeMillis();
            StepExecutionResult sr = new StepExecutionResult();
            sr.stepIndex = stepIndex;
            sr.ok = false;
            sr.durationMs = 0L;
            sr.error = "";
            sr.logTail = "";

            String stepCode = extractStepCode(code, stepIndex);
            if (stepCode == null || stepCode.trim().isEmpty()) {
                sr.ok = true;
                sr.error = "";
                sr.durationMs = System.currentTimeMillis() - t0;
                sr.logTail = stepLogger.tail(200);
                out.stepResults.add(sr);
                continue;
            }

            try {
                String normalizedStepCode = promoteTopLevelDefs(stepCode, stepLogger);
                AutoWebAgent.ContextWrapper bestContext = AutoWebAgent.waitAndFindContext(page, stepLogger);
                Object executionTarget = bestContext == null ? page : bestContext.context;
                try {
                    AutoWebAgent.executeWithGroovy(normalizedStepCode, executionTarget, stepLogger, sharedBinding, baseTimeoutMs, baseMaxRetries);
                } catch (Exception ex1) {
                    if (isTimeoutException(ex1)) {
                        stepLogger.accept("检测到超时，准备重试本步骤并提升默认超时到 " + boostedTimeoutMs + "ms");
                        stepLogger.accept("等待 1000ms 后重新选择上下文并重试");
                        try { page.waitForTimeout(1000); } catch (Exception ignored) {}
                        AutoWebAgent.ContextWrapper bestContext2 = AutoWebAgent.waitAndFindContext(page, stepLogger);
                        Object executionTarget2 = bestContext2 == null ? page : bestContext2.context;
                        AutoWebAgent.executeWithGroovy(normalizedStepCode, executionTarget2, stepLogger, sharedBinding, boostedTimeoutMs, baseMaxRetries);
                    } else {
                        throw ex1;
                    }
                }
                sr.ok = true;
                sr.durationMs = System.currentTimeMillis() - t0;
                sr.logTail = stepLogger.tail(200);
            } catch (Exception ex) {
                String reason = ex.getMessage();
                if (reason == null || reason.trim().isEmpty()) reason = ex.toString();
                sr.ok = false;
                sr.error = reason;
                sr.durationMs = System.currentTimeMillis() - t0;
                sr.logTail = stepLogger.tail(200);
                if (reason.contains("No such property:")) {
                    try {
                        java.util.Set<String> keys = sharedBinding.getVariables().keySet();
                        stepLogger.accept("当前可用共享变量: " + String.join(",", keys));
                    } catch (Exception ignored) {}
                }
                out.formattedErrors.add("步骤: " + stepIndex + ", 出错信息: " + reason);
            }
            out.stepResults.add(sr);
        }
    }

    private static boolean isTimeoutException(Throwable t) {
        if (t == null) return false;
        if (t instanceof com.microsoft.playwright.TimeoutError) return true;
        String cn = t.getClass().getName();
        if (cn != null && cn.contains("Timeout")) return true;
        String msg = t.getMessage();
        if (msg != null && (msg.contains("Timeout") || msg.contains("exceeded"))) return true;
        Throwable c = t.getCause();
        if (c != null && c != t) return isTimeoutException(c);
        return false;
    }

    private static String promoteTopLevelDefs(String stepCode, java.util.function.Consumer<String> logger) {
        if (stepCode == null) return "";
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("(?m)^def\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*=");
        java.util.regex.Matcher m = p.matcher(stepCode);
        java.util.List<String> vars = new java.util.ArrayList<>();
        while (m.find()) {
            String v = m.group(1);
            if (v != null && !v.trim().isEmpty()) vars.add(v.trim());
        }
        String rewritten = p.matcher(stepCode).replaceAll("$1 =");
        if (!vars.isEmpty() && logger != null) {
            logger.accept("已将 top-level def 变量提升为共享变量: " + String.join(",", vars));
        }
        return rewritten;
    }

    private static List<String> safeLintErrors(String code) {
        String normalized = AutoWebAgent.normalizeGroovyScriptForExecution(code);
        List<String> lintErrors = GroovyLinter.check(normalized);
        if (lintErrors == null || lintErrors.isEmpty()) return new ArrayList<>();
        return lintErrors;
    }

    private static String maybeNormalizeCode(String code) {
        String src = code == null ? "" : code;
        String normalized = GroovySupport.normalizeGeneratedGroovy(src);
        if (normalized != null && !normalized.equals(src)) {
            List<String> normalizeErrors = GroovyLinter.check(normalized);
            if (normalizeErrors != null && normalizeErrors.isEmpty()) {
                return normalized;
            }
        }
        return src;
    }

    private static String buildRepairHint(ModelRunResult r) {
        List<String> failed = collectFailedStepLabels(r);
        String failedText = failed.isEmpty() ? "" : String.join(",", failed);
        StringBuilder sb = new StringBuilder();
        sb.append("运行代码阶段出现错误，请修复 Groovy 脚本，仅修改必要部分，保持原计划与输出格式不变。失败步骤=").append(failedText);
        sb.append("\n兼容性要求：禁止使用或 import groovy.json.*（JsonOutput/JsonBuilder 等），不要使用 Date.format/minus/plus；JSON 输出请使用 com.google.gson；日期请使用 java.time 或 java.text.SimpleDateFormat。");
        if (r != null && r.lintErrors != null && !r.lintErrors.isEmpty()) {
            sb.append("\n静态分析提示：\n");
            for (String e : r.lintErrors) {
                if (e == null || e.trim().isEmpty()) continue;
                sb.append("- ").append(e.trim()).append("\n");
            }
        }
        return sb.toString().trim();
    }

    private static String buildExecOutputForRepair(ModelRunResult r) {
        if (r == null) return "";
        StringBuilder sb = new StringBuilder();
        if (r.formattedErrors != null && !r.formattedErrors.isEmpty()) {
            sb.append("=== formattedErrors ===\n");
            for (String e : r.formattedErrors) sb.append(e == null ? "" : e).append("\n");
        }
        if (r.stepResults != null && !r.stepResults.isEmpty()) {
            sb.append("\n=== stepResults ===\n");
            for (StepExecutionResult sr : r.stepResults) {
                if (sr == null) continue;
                if (sr.ok) continue;
                sb.append("step=").append(sr.stepIndex).append(" ok=false\n");
                sb.append("error=").append(sr.error == null ? "" : sr.error).append("\n");
                if (sr.logTail != null && !sr.logTail.isEmpty()) {
                    sb.append("logTail:\n").append(sr.logTail).append("\n");
                }
                sb.append("\n");
            }
        }
        String out = sb.toString().trim();
        return out;
    }

    private static boolean isRunOk(ModelRunResult r) {
        if (r == null) return false;
        if (r.planSteps == null || r.planSteps.isEmpty()) return false;
        if (r.stepResults == null || r.stepResults.isEmpty()) return false;
        for (StepExecutionResult sr : r.stepResults) {
            if (sr == null || !sr.ok) return false;
        }
        return true;
    }

    private static List<String> collectFailedStepLabels(ModelRunResult r) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (r == null) return new ArrayList<>();
        if (r.planSteps == null || r.planSteps.isEmpty()) {
            out.add("plan");
            return new ArrayList<>(out);
        }
        if (r.stepResults == null || r.stepResults.isEmpty()) {
            out.add("run");
            return new ArrayList<>(out);
        }
        for (StepExecutionResult sr : r.stepResults) {
            if (sr == null) continue;
            if (!sr.ok) out.add(String.valueOf(sr.stepIndex));
        }
        if (out.isEmpty() && r.formattedErrors != null && !r.formattedErrors.isEmpty()) {
            out.add("unknown");
        }
        return new ArrayList<>(out);
    }

    private static void printConsoleSummary(Report report, java.util.function.Consumer<String> logger) {
        if (report == null) return;
        java.util.function.Consumer<String> out = logger == null ? System.out::println : logger;
        out.accept("=== 多模型E2E执行汇总 ===");
        if (report.models != null) out.accept("models=" + String.join(",", report.models));
        out.accept("ts=" + (report.ts == null ? "" : report.ts));

        if (report.cases == null) return;
        for (CaseRunResult c : report.cases) {
            if (c == null) continue;
            String caseId = c.id == null ? "" : c.id;
            if (report.models == null) continue;
            for (String model : report.models) {
                if (model == null) continue;
                ModelRunResult first = null;
                ModelRunResult repaired = null;
                if (c.runs != null) {
                    for (ModelRunResult r : c.runs) {
                        if (r == null) continue;
                        if (!model.equals(r.model)) continue;
                        if (r.repairAttempt) repaired = r;
                        else first = r;
                    }
                }
                if (first == null) continue;

                boolean ok1 = isRunOk(first);
                boolean hasRepair = repaired != null;
                boolean ok2 = hasRepair && isRunOk(repaired);
                List<String> failed = new ArrayList<>();
                if (!ok1 && !hasRepair) failed = collectFailedStepLabels(first);
                else if (hasRepair && !ok2) failed = collectFailedStepLabels(repaired);
                String failedText = failed.isEmpty() ? "" : String.join(",", failed);

                StringBuilder line = new StringBuilder();
                line.append("模型").append(model)
                        .append("，任务").append(caseId)
                        .append("，执行").append(ok1 ? "成功" : "失败")
                        .append("，是否有修复代码").append(hasRepair ? "是" : "否");
                if (hasRepair) {
                    line.append("，修复后代码执行").append(ok2 ? "成功" : "失败");
                }
                if (!failedText.isEmpty()) {
                    line.append("，执行失败的步骤为").append(failedText);
                }
                out.accept(line.toString());
            }
        }
    }

    private static String buildPrompt(CaseInput c) {
        String task = c == null ? "" : (c.userTask == null ? "" : c.userTask.trim());
        String url = c == null ? "" : (c.entryUrl == null ? "" : c.entryUrl.trim());
        if (url.isEmpty()) return task;
        if (task.isEmpty()) return "入口URL: " + url;
        return task + "\n入口URL: " + url;
    }

    private static String readArgOrProp(String[] args, String argKey, String propKey, String fallback) {
        String fromProp = System.getProperty(propKey, "");
        if (fromProp != null && !fromProp.trim().isEmpty()) return fromProp.trim();
        if (args != null && argKey != null && !argKey.trim().isEmpty()) {
            String prefix = "--" + argKey.trim() + "=";
            for (String a : args) {
                if (a == null) continue;
                String v = a.trim();
                if (v.startsWith(prefix)) return v.substring(prefix.length()).trim();
            }
        }
        return fallback == null ? "" : fallback;
    }

    private static List<CaseInput> generateCasesFromUrls(List<String> urls, String userPrompt) throws Exception {
        if (urls == null || urls.isEmpty()) return new ArrayList<>();

        List<String> unique = urls.stream()
                .filter(u -> u != null && !u.trim().isEmpty())
                .map(String::trim)
                .collect(Collectors.toCollection(LinkedHashSet::new))
                .stream()
                .collect(Collectors.toList());
        if (unique.isEmpty()) return new ArrayList<>();

        BufferingLogger logger = new BufferingLogger("[CASEGEN] ", true);
        PlayWrightUtil.Connection connection = PlayWrightUtil.connectAndAutomate();
        if (connection == null || connection.browser == null) throw new RuntimeException("Failed to connect to browser for case generation.");

        AtomicInteger seq = new AtomicInteger(1);
        List<CaseInput> out = new ArrayList<>();
        try {
            for (String url : unique) {
                PageHandle pageHandle = null;
                Path screenshot = null;
                try {
                    pageHandle = openPage(connection, url, logger);
                    screenshot = Files.createTempFile("autoweb_casegen_", ".png");
                    synchronized (AutoWebAgent.PLAYWRIGHT_LOCK) {
                        pageHandle.page.screenshot(new com.microsoft.playwright.Page.ScreenshotOptions()
                                .setPath(screenshot));
                    }
                    String aTree = "";
                    String cleanedHtml = "";
                    try {
                        String capturedA11y = AutoWebAgent.getPageContent(pageHandle.page, AutoWebAgent.HtmlCaptureMode.ARIA_SNAPSHOT, false);
                        String cleanedA11y = AutoWebAgent.cleanCapturedContent(capturedA11y, AutoWebAgent.HtmlCaptureMode.ARIA_SNAPSHOT);
                        aTree = clipForPrompt(redactSecretsForPrompt(cleanedA11y), 120_000);
                    } catch (Exception ignored) {}
                    try {
                        String capturedHtml = AutoWebAgent.getPageContent(pageHandle.page, AutoWebAgent.HtmlCaptureMode.RAW_HTML);
                        String cleaned = AutoWebAgent.cleanCapturedContent(capturedHtml, AutoWebAgent.HtmlCaptureMode.RAW_HTML);
                        cleanedHtml = clipForPrompt(redactSecretsForPrompt(cleaned), 120_000);
                    } catch (Exception ignored) {}

                    String prompt = buildCaseGenPrompt(url, userPrompt, aTree, cleanedHtml);
                    String resp = analyzeScreenshotForCase(screenshot.toFile(), prompt);
                    CaseInput c = parseCaseFromModelText(resp);
                    if (c == null) {
                        throw new RuntimeException("Failed to parse CaseInput from model response.");
                    }
                    if (c.entryUrl == null || c.entryUrl.trim().isEmpty()) c.entryUrl = url;
                    if (c.id == null || c.id.trim().isEmpty()) c.id = String.valueOf(seq.get());
                    out.add(c);
                    seq.incrementAndGet();
                } finally {
                    if (pageHandle != null && pageHandle.page != null) {
                        try {
                            pageHandle.page.close();
                        } catch (Exception ignored) {
                        }
                    }
                    if (screenshot != null) {
                        try {
                            Files.deleteIfExists(screenshot);
                        } catch (Exception ignored) {
                        }
                    }
                }
            }
        } finally {
            try {
                PlayWrightUtil.disconnectBrowser(connection.playwright, connection.browser);
            } catch (Exception ignored) {
            }
        }

        return out;
    }

    private static String buildCaseGenPrompt(String entryUrl, String userPrompt, String aTree, String cleanedHtml) {
        String base = userPrompt == null ? "" : userPrompt.trim();
        StringBuilder sb = new StringBuilder();
        if (!base.isEmpty()) {
            sb.append(base).append("\n\n");
        }
        sb.append("你会收到一张网页截图，以及页面结构内容（A tree / HTML）。请综合这些信息推断该页面的核心功能与关键交互点，然后为自动化测试生成一个 CaseInput。")
                .append("\n")
                .append("要求：只输出一个 JSON 对象（不要 markdown），字段为：")
                .append("\n")
                .append("{\"id\":\"<string>\",\"entryUrl\":\"").append(entryUrl == null ? "" : entryUrl.trim()).append("\",\"userTask\":\"<string>\"}")
                .append("\n")
                .append("其中 userTask 必须是中文、可执行、可量化的网页操作任务，尽量包含：筛选/搜索条件、分页限制、需要采集的字段、输出格式、以及任务结束后回到哪里。");
        String at = aTree == null ? "" : aTree.trim();
        if (!at.isEmpty()) {
            sb.append("\n\n")
                    .append("A_TREE:\n")
                    .append(at);
        }
        String h = cleanedHtml == null ? "" : cleanedHtml.trim();
        if (!h.isEmpty()) {
            sb.append("\n\n")
                    .append("HTML:\n")
                    .append(h);
        }
        return sb.toString();
    }

    private static String analyzeScreenshotForCase(File screenshot, String prompt) {
        AppConfig cfg = AppConfig.getInstance();
    
        String aliyunKey = cfg == null ? "" : (cfg.getAliyunApiKey() == null ? "" : cfg.getAliyunApiKey().trim());

        String resp = "";

        if (!aliyunKey.isEmpty()) {
            resp = LLMUtil.analyzeImageWithAliyun(screenshot, prompt);
        }
        if (resp == null) resp = "";
        return resp;
    }

    private static CaseInput parseCaseFromModelText(String text) {
        String raw = text == null ? "" : text.trim();
        if (raw.isEmpty()) return null;

        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        String json = raw.substring(start, end + 1);

        try {
            JsonObject obj = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
            CaseInput c = new CaseInput();
            if (obj.has("id") && !obj.get("id").isJsonNull()) c.id = obj.get("id").getAsString();
            if (obj.has("entryUrl") && !obj.get("entryUrl").isJsonNull()) c.entryUrl = obj.get("entryUrl").getAsString();
            if (obj.has("userTask") && !obj.get("userTask").isJsonNull()) c.userTask = obj.get("userTask").getAsString();
            if (c.userTask == null || c.userTask.trim().isEmpty()) return null;
            return c;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String clipForPrompt(String s, int maxChars) {
        if (s == null) return "";
        String v = s.trim();
        if (v.isEmpty()) return "";
        if (maxChars <= 0) return "";
        if (v.length() <= maxChars) return v;
        int head = Math.max(2000, Math.min(maxChars / 2, 60_000));
        int tail = Math.max(2000, Math.min(maxChars - head, 60_000));
        if (head + tail + 64 >= maxChars) {
            return v.substring(0, maxChars) + "...(truncated)";
        }
        return v.substring(0, head) + "\n...(truncated)...\n" + v.substring(v.length() - tail);
    }

    private static String redactSecretsForPrompt(String s) {
        if (s == null || s.isEmpty()) return s;
        String out = s;
        out = out.replaceAll("(?i)(api[_-]?key\\s*[:=]\\s*)([^\\s\"']+)", "$1***REDACTED***");
        out = out.replaceAll("(?i)(api[_-]?key\\s*[:=]\\s*['\"])([^'\"]+)(['\"])", "$1***REDACTED***$3");
        out = out.replaceAll("(?i)(token\\s*[:=]\\s*)([^\\s\"']+)", "$1***REDACTED***");
        out = out.replaceAll("(?i)(token\\s*[:=]\\s*['\"])([^'\"]+)(['\"])", "$1***REDACTED***$3");
        out = out.replaceAll("(?i)(secret\\s*[:=]\\s*)([^\\s\"']+)", "$1***REDACTED***");
        out = out.replaceAll("(?i)(secret\\s*[:=]\\s*['\"])([^'\"]+)(['\"])", "$1***REDACTED***$3");
        out = out.replaceAll("(?i)(Authorization\\s*[:=]\\s*Bearer\\s+)([A-Za-z0-9._-]+)", "$1***REDACTED***");
        return out;
    }

    private static int parseInt(String s, int fallback) {
        if (s == null) return fallback;
        try {
            return Integer.parseInt(s.trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static AutoWebAgent.HtmlCaptureMode parseCaptureMode(String s) {
        String v = s == null ? "" : s.trim().toUpperCase();
        if ("ARIA".equals(v) || "ARIA_SNAPSHOT".equals(v) || "A11Y".equals(v)) return AutoWebAgent.HtmlCaptureMode.ARIA_SNAPSHOT;
        return AutoWebAgent.HtmlCaptureMode.RAW_HTML;
    }

    private static List<String> parseCsv(String s) {
        if (s == null) return new ArrayList<>();
        return java.util.Arrays.stream(s.split(","))
                .map(String::trim)
                .filter(x -> !x.isEmpty())
                .collect(Collectors.toList());
    }

    private static List<CaseInput> loadCasesFromProperties() throws Exception {
        String file = System.getProperty("autoweb.e2e.casesFile", "").trim();
        if (!file.isEmpty()) {
            Path p = Path.of(file);
            if (!p.isAbsolute()) {
                p = Path.of(System.getProperty("user.dir")).resolve(file).normalize();
            }
            if (!Files.exists(p)) return new ArrayList<>();
            String text = Files.readString(p, StandardCharsets.UTF_8);
            return parseCasesJson(text);
        }

        String raw = System.getProperty("autoweb.e2e.cases", "").trim();
        if (raw.isEmpty()) return new ArrayList<>();
        return parseCasesInline(raw);
    }

    private static RunnerConfig configFromSystemProperties() throws Exception {
        RunnerConfig cfg = new RunnerConfig();
        cfg.models = parseCsv(System.getProperty("autoweb.e2e.models", "DEEPSEEK"));
        cfg.cases = loadCasesFromProperties();
        cfg.captureMode = resolveCaptureModeFromSystemProperties();
        cfg.useVisualSupplement = Boolean.parseBoolean(System.getProperty("autoweb.e2e.visualSupplement", "false"));
        cfg.alsoStdout = Boolean.parseBoolean(System.getProperty("autoweb.e2e.stdout", "true"));
        cfg.localAnalyze = Boolean.parseBoolean(System.getProperty("autoweb.e2e.localAnalyze", "false"));
        cfg.localAnalysisModel = System.getProperty("autoweb.e2e.localModel", LLMUtil.OLLAMA_MODEL_QWEN3_8B);
        cfg.reportMaxChars = parseInt(System.getProperty("autoweb.e2e.reportMaxChars", "8000000"), 8_000_000);
        return cfg;
    }

    private static RunnerConfig defaultConfig() {
        RunnerConfig cfg = new RunnerConfig();
        cfg.models = new ArrayList<>();
        cfg.captureMode = AutoWebAgent.HtmlCaptureMode.RAW_HTML;
        cfg.useVisualSupplement = true;
        cfg.alsoStdout = true;
        cfg.localAnalyze = false;
        cfg.localAnalysisModel = LLMUtil.OLLAMA_MODEL_QWEN3_8B;
        cfg.reportMaxChars = 8_000_000;

        return cfg;
    }

    private static void applyRuntimeOverrides(RunnerConfig cfg) {
        if (cfg == null) return;
        cfg.captureMode = resolveCaptureModeFromSystemProperties();
        cfg.useVisualSupplement = Boolean.parseBoolean(System.getProperty("autoweb.e2e.visualSupplement", String.valueOf(cfg.useVisualSupplement)));
    }

    private static void cleanupAutowebArtifacts() {
        Path base = Paths.get(System.getProperty("user.dir"), "autoweb");
        deleteDirectoryContents(base.resolve("cache"));
        deleteDirectoryContents(base.resolve("debug"));
    }

    private static void deleteDirectoryContents(Path dir) {
        if (dir == null) return;
        try {
            if (!Files.exists(dir)) return;
        } catch (Exception ignored) {
            return;
        }
        try {
            java.util.List<Path> paths = Files.walk(dir).sorted(Comparator.reverseOrder()).collect(Collectors.toList());
            for (Path p : paths) {
                if (p == null) continue;
                if (p.equals(dir)) continue;
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static AutoWebAgent.HtmlCaptureMode resolveCaptureModeFromSystemProperties() {
        String mode = System.getProperty("autoweb.e2e.captureMode", "");
        String v = mode == null ? "" : mode.trim();
        if (!v.isEmpty()) return parseCaptureMode(v);
        boolean simplified = Boolean.parseBoolean(System.getProperty("autoweb.e2e.simplifiedHtml", "true"));
        return simplified ? AutoWebAgent.HtmlCaptureMode.ARIA_SNAPSHOT : AutoWebAgent.HtmlCaptureMode.RAW_HTML;
    }

    private static List<CaseInput> parseCasesJson(String json) {
        if (json == null || json.trim().isEmpty()) return new ArrayList<>();
        try {
            JsonElement el = com.google.gson.JsonParser.parseString(json);
            if (el == null) return new ArrayList<>();
            List<CaseInput> out = new ArrayList<>();
            AtomicInteger seq = new AtomicInteger(1);
            if (el.isJsonArray()) {
                for (JsonElement e : el.getAsJsonArray()) {
                    if (e == null || !e.isJsonObject()) continue;
                    CaseInput c = parseCaseObject(e.getAsJsonObject(), String.valueOf(seq.getAndIncrement()));
                    if (c != null) out.add(c);
                }
            } else if (el.isJsonObject()) {
                CaseInput c = parseCaseObject(el.getAsJsonObject(), String.valueOf(seq.getAndIncrement()));
                if (c != null) out.add(c);
            }
            return out;
        } catch (Exception ignored) {
            return new ArrayList<>();
        }
    }

    private static CaseInput parseCaseObject(JsonObject o, String fallbackId) {
        if (o == null) return null;
        CaseInput c = new CaseInput();
        c.id = getFirstString(o, "id", "caseId", "name");
        if (c.id == null || c.id.trim().isEmpty()) c.id = fallbackId;
        c.entryUrl = getFirstString(o, "entryUrl", "url", "entry", "entryURL");
        c.userTask = getFirstString(o, "userTask", "task", "prompt");
        if (c.entryUrl == null) c.entryUrl = "";
        if (c.userTask == null) c.userTask = "";
        c.entryUrl = c.entryUrl.trim();
        c.userTask = c.userTask.trim();
        if (c.entryUrl.isEmpty() && c.userTask.isEmpty()) return null;
        return c;
    }

    private static String getFirstString(JsonObject o, String... keys) {
        if (o == null || keys == null) return null;
        for (String k : keys) {
            if (k == null) continue;
            try {
                JsonElement v = o.get(k);
                if (v != null && v.isJsonPrimitive()) {
                    String s = v.getAsString();
                    if (s != null && !s.trim().isEmpty()) return s;
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private static List<CaseInput> parseCasesInline(String raw) {
        List<CaseInput> out = new ArrayList<>();
        AtomicInteger seq = new AtomicInteger(1);
        String[] parts = raw.split("\\s*;;\\s*");
        for (String part : parts) {
            if (part == null) continue;
            String p = part.trim();
            if (p.isEmpty()) continue;
            CaseInput c = parseSingleInlineCase(p, String.valueOf(seq.getAndIncrement()));
            if (c != null) out.add(c);
        }
        return out;
    }

    private static CaseInput parseSingleInlineCase(String raw, String id) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;
        String[] segs = s.split("\\s*\\|\\|\\|\\s*");
        String a = segs.length > 0 ? segs[0].trim() : "";
        String b = segs.length > 1 ? segs[1].trim() : "";
        if (segs.length <= 1) {
            CaseInput c = new CaseInput();
            c.id = id;
            c.userTask = s;
            c.entryUrl = PlanRoutingSupport.extractFirstUrlFromText(s);
            if (c.entryUrl == null) c.entryUrl = "";
            return c;
        }

        String url;
        String task;
        if (PlanRoutingSupport.looksLikeUrl(a)) {
            url = a;
            task = b;
        } else if (PlanRoutingSupport.looksLikeUrl(b)) {
            url = b;
            task = a;
        } else {
            url = PlanRoutingSupport.extractFirstUrlFromText(s);
            if (url == null) url = "";
            task = s;
        }

        CaseInput c = new CaseInput();
        c.id = id;
        c.entryUrl = url;
        c.userTask = task;
        if (c.entryUrl == null) c.entryUrl = "";
        if (c.userTask == null) c.userTask = "";
        c.entryUrl = c.entryUrl.trim();
        c.userTask = c.userTask.trim();
        if (c.entryUrl.isEmpty() && c.userTask.isEmpty()) return null;
        return c;
    }

    private static String stripPlanBlock(String code) {
        if (code == null) return "";
        String src = code;
        int ps = src.indexOf("PLAN_START");
        int pe = src.indexOf("PLAN_END");
        if (ps >= 0 && pe > ps) {
            return (src.substring(0, ps) + "\n" + src.substring(pe + "PLAN_END".length())).trim();
        }
        return src;
    }

    private static String extractStepCode(String code, int stepIndex) {
        if (code == null || code.trim().isEmpty()) return "";
        String src = stripPlanBlock(code);

        java.util.regex.Pattern header = java.util.regex.Pattern.compile("(?mi)^\\s*(?:/\\*+\\s*)?(?:\\*+\\s*)?(?://\\s*)?(?:#+\\s*)?(?:[-–—*>•]+\\s*)?(?:Step|步骤)\\s*[:：#\\-]?\\s*(\\d+).*$");
        java.util.regex.Matcher m = header.matcher(src);
        List<int[]> marks = new ArrayList<>();
        while (m.find()) {
            String g = m.group(1);
            int idx;
            try {
                idx = Integer.parseInt(g);
            } catch (Exception ignored) {
                continue;
            }
            marks.add(new int[]{idx, m.start(), m.end()});
        }

        if (marks.isEmpty()) {
            return stepIndex == 1 ? src : "";
        }

        marks.sort(Comparator.comparingInt(a -> a[1]));
        int start = -1;
        int end = -1;
        for (int i = 0; i < marks.size(); i++) {
            int[] cur = marks.get(i);
            if (cur[0] == stepIndex) {
                start = cur[1];
                if (i + 1 < marks.size()) end = marks.get(i + 1)[1];
                else end = src.length();
                break;
            }
        }

        if (start < 0) return "";
        String block = src.substring(start, Math.min(end, src.length()));
        return block.trim();
    }

    private static JsonObject toJson(Report report) {
        JsonObject o = new JsonObject();
        o.addProperty("ts", report.ts == null ? "" : report.ts);
        o.addProperty("captureMode", report.captureMode == null ? "" : report.captureMode);
        JsonArray models = new JsonArray();
        if (report.models != null) {
            for (String m : report.models) models.add(m);
        }
        o.add("models", models);

        JsonArray cases = new JsonArray();
        if (report.cases != null) {
            for (CaseRunResult c : report.cases) {
                JsonObject cj = new JsonObject();
                cj.addProperty("id", c.id == null ? "" : c.id);
                cj.addProperty("entryUrl", c.entryUrl == null ? "" : c.entryUrl);
                cj.addProperty("userTask", c.userTask == null ? "" : c.userTask);

                JsonArray runs = new JsonArray();
                if (c.runs != null) {
                    for (ModelRunResult r : c.runs) {
                        JsonObject rj = new JsonObject();
                        rj.addProperty("model", r.model == null ? "" : r.model);
                        rj.addProperty("prompt", r.prompt == null ? "" : r.prompt);
                        rj.addProperty("attemptIndex", r.attemptIndex);
                        rj.addProperty("repairAttempt", r.repairAttempt);
                        rj.addProperty("entryUrlUsed", r.entryUrlUsed == null ? "" : r.entryUrlUsed);
                        rj.addProperty("planConfirmed", r.planConfirmed);
                        rj.addProperty("planHasQuestion", r.planHasQuestion);
                        rj.addProperty("planText", r.planText == null ? "" : r.planText);
                        rj.addProperty("code", r.code == null ? "" : r.code);

                        JsonArray steps = new JsonArray();
                        if (r.planSteps != null) {
                            for (AutoWebAgent.PlanStep s : r.planSteps) {
                                if (s == null) continue;
                                JsonObject sj = new JsonObject();
                                sj.addProperty("index", s.index);
                                sj.addProperty("description", s.description == null ? "" : s.description);
                                sj.addProperty("targetUrl", s.targetUrl == null ? "" : s.targetUrl);
                                sj.addProperty("entryAction", s.entryAction == null ? "" : s.entryAction);
                                sj.addProperty("status", s.status == null ? "" : s.status);
                                steps.add(sj);
                            }
                        }
                        rj.add("planSteps", steps);

                        JsonArray lint = new JsonArray();
                        if (r.lintErrors != null) {
                            for (String e : r.lintErrors) lint.add(e);
                        }
                        rj.add("lintErrors", lint);

                        JsonArray exec = new JsonArray();
                        if (r.stepResults != null) {
                            for (StepExecutionResult sr : r.stepResults) {
                                JsonObject ej = new JsonObject();
                                ej.addProperty("stepIndex", sr.stepIndex);
                                ej.addProperty("ok", sr.ok);
                                ej.addProperty("durationMs", sr.durationMs);
                                ej.addProperty("error", sr.error == null ? "" : sr.error);
                                ej.addProperty("logTail", sr.logTail == null ? "" : sr.logTail);
                                exec.add(ej);
                            }
                        }
                        rj.add("stepResults", exec);

                        JsonArray errs = new JsonArray();
                        if (r.formattedErrors != null) {
                            for (String e : r.formattedErrors) errs.add(e);
                        }
                        rj.add("formattedErrors", errs);
                        rj.addProperty("runLogHead", r.runLogHead == null ? "" : r.runLogHead);
                        rj.addProperty("runLogTail", r.runLogTail == null ? "" : r.runLogTail);

                        runs.add(rj);
                    }
                }
                cj.add("runs", runs);
                cases.add(cj);
            }
        }
        o.add("cases", cases);

        o.addProperty("analysisPrompt", report.analysisPrompt == null ? "" : report.analysisPrompt);
        o.addProperty("localAnalysisModel", report.localAnalysisModel == null ? "" : report.localAnalysisModel);
        o.addProperty("localAnalysis", report.localAnalysis == null ? "" : report.localAnalysis);
        return o;
    }

    private static String buildCompactAnalysisInput(Report report) {
        StringBuilder sb = new StringBuilder();
        sb.append("你是一个资深Java自动化/LLM Agent工程师。请阅读以下多模型E2E测试汇总，给出可落地的代码优化建议（优先级排序），要求：\n");
        sb.append("1) 优先发现结构性问题：可复用抽象、错误处理、日志落盘、并发/超时、可测性\n");
        sb.append("2) 给出建议时同时指出可能修改的类/方法名\n");
        sb.append("3) 输出格式：每条建议一行，含“原因/改法/影响范围”\n\n");
        sb.append("=== 测试汇总 ===\n");
        sb.append("ts=").append(report.ts).append("\n");
        sb.append("models=").append(report.models == null ? "" : String.join(",", report.models)).append("\n");
        sb.append("captureMode=").append(report.captureMode).append("\n\n");

        if (report.cases != null) {
            for (CaseRunResult c : report.cases) {
                sb.append("CASE ").append(c.id).append("\n");
                sb.append("entryUrl=").append(c.entryUrl).append("\n");
                sb.append("task=").append(c.userTask == null ? "" : c.userTask.replace("\n", " ")).append("\n");
                if (c.runs != null) {
                    for (ModelRunResult r : c.runs) {
                        int total = r.stepResults == null ? 0 : r.stepResults.size();
                        int ok = 0;
                        if (r.stepResults != null) {
                            for (StepExecutionResult sr : r.stepResults) if (sr != null && sr.ok) ok++;
                        }
                        sb.append("- model=").append(r.model)
                                .append(" attempt=").append(r.attemptIndex)
                                .append(" repair=").append(r.repairAttempt)
                                .append(" planConfirmed=").append(r.planConfirmed)
                                .append(" steps=").append(total)
                                .append(" ok=").append(ok)
                                .append(" lintErrors=").append(r.lintErrors == null ? 0 : r.lintErrors.size())
                                .append("\n");
                        if (r.formattedErrors != null && !r.formattedErrors.isEmpty()) {
                            for (String e : r.formattedErrors) {
                                sb.append("  ").append(e).append("\n");
                            }
                        }
                    }
                }
                sb.append("\n");
            }
        }
        return sb.toString();
    }

    private static String buildAnalysisPrompt() {
        return String.join("\n",
                "把本报告文件内容整体提供给本地代码模型，并要求它：",
                "1) 对比不同模型的Plan/Code差异，找出系统性失败点（如：frame选择、等待策略、selector鲁棒性、payload构造）",
                "2) 针对失败步骤与lintErrors，给出AutoWebAgent/PlanRoutingSupport/WebDSL/GroovySupport等类的可落地优化建议",
                "3) 输出为可执行的改动列表（文件+方法+改动点），以及风险点"
        );
    }
}
