package com.qiyi.tools.autoweb;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.microsoft.playwright.Page;
import com.qiyi.config.AppConfig;
import com.qiyi.service.autoweb.AutoWebAgent;
import com.qiyi.tools.Tool;
import com.qiyi.tools.ToolContext;
import com.qiyi.tools.ToolMessenger;
import com.qiyi.util.PlayWrightUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Tool.Info(
        name = "autoweb_two_phase_run",
        description = "Two-phase AutoWeb execution with resumable runId. Parameters: action (string, optional: open|prepare|run|prepare_and_run|resume|drop), runId (string, optional), entryUrl (string, optional; if absent, use the latest URL from an already opened page in the shared Chrome session), userTask (string, required for prepare), model (string, optional), captureMode (string, optional: RAW_HTML|ARIA_SNAPSHOT), fromStep (int, optional, default 1), maxSteps (int, optional, default 0 meaning all), autoRepair (boolean, optional, default true), maxRepairAttempts (int, optional, default 1). Output: JSON with runId, planText, code, stepResults.",
        businessDomain = "com.qiyi.tools.autoweb",
        type = Tool.ToolType.AUTO_WEB_SCRIPT
)
public class AutoWebTwoPhaseRunTool implements Tool {
    private static final Map<String, RunSession> SESSIONS = new ConcurrentHashMap<>();
    private static final Object CONNECTION_LOCK = new Object();
    private static volatile PlayWrightUtil.Connection SHARED_CONNECTION;

    @Override
    public String execute(JSONObject params, ToolContext context, ToolMessenger messenger) {
        String action = params == null ? "" : safeTrim(params.getString("action")).toLowerCase();
        if (action.isEmpty()) action = "prepare_and_run";
        try {
            if ("open".equals(action)) {
                String entryUrl = params == null ? "" : safeTrim(params.getString("entryUrl"));
                if (entryUrl.isEmpty()) throw new IllegalArgumentException("entryUrl is required");
                BufferingMessengerLogger logger = new BufferingMessengerLogger("[AUTOWEB] ", messenger, 50);
                PlayWrightUtil.Connection connection = acquireConnection();
                PageHandle h = openPage(connection, entryUrl, logger);
                sendProgress(messenger, "AUTOWEB: 已打开页面 " + safeTrim(h.page.url()));
                JSONObject out = new JSONObject();
                out.put("ok", true);
                out.put("entryUrl", entryUrl);
                out.put("openedUrl", safeTrim(h.page.url()));
                return out.toJSONString();
            }

            if ("drop".equals(action)) {
                String runId = params == null ? "" : safeTrim(params.getString("runId"));
                if (!runId.isEmpty()) {
                    SESSIONS.remove(runId);
                    sendProgress(messenger, "AUTOWEB: 已清理 runId=" + runId);
                }
                JSONObject out = new JSONObject();
                out.put("ok", true);
                out.put("runId", runId);
                return out.toJSONString();
            }

            if ("run".equals(action) || "resume".equals(action)) {
                return runExisting(params, messenger).toJSONString();
            }

            if ("prepare".equals(action) || "prepare_and_run".equals(action)) {
                RunSession session = prepare(params, messenger);
                if ("prepare".equals(action)) {
                    return buildPrepareOutput(session).toJSONString();
                }
                JSONObject runOut = runPrepared(session, params, messenger);
                return runOut.toJSONString();
            }

            throw new IllegalArgumentException("Unsupported action: " + action);
        } catch (Throwable t) {
            String msg = t.getMessage();
            if (msg == null || msg.trim().isEmpty()) msg = t.toString();
            sendProgress(messenger, "AUTOWEB: 执行异常 action=" + action + " err=" + msg);
            JSONObject out = new JSONObject();
            out.put("ok", false);
            out.put("action", action);
            out.put("error", msg);
            String runId = params == null ? "" : safeTrim(params.getString("runId"));
            if (!runId.isEmpty()) out.put("runId", runId);
            return out.toJSONString();
        }
    }

    private static JSONObject runExisting(JSONObject params, ToolMessenger messenger) {
        String runId = params == null ? "" : safeTrim(params.getString("runId"));
        if (runId.isEmpty()) {
            throw new IllegalArgumentException("runId is required for run/resume");
        }
        RunSession session = SESSIONS.get(runId);
        if (session == null) {
            throw new IllegalArgumentException("Unknown runId: " + runId);
        }
        return runPrepared(session, params, messenger);
    }

    private static RunSession prepare(JSONObject params, ToolMessenger messenger) {
        String entryUrl = params == null ? "" : safeTrim(params.getString("entryUrl"));
        String userTask = params == null ? "" : safeTrim(params.getString("userTask"));
        if (userTask.isEmpty()) throw new IllegalArgumentException("userTask is required");

        String model = params == null ? "" : safeTrim(params.getString("model"));
        String modelKey = AutoWebAgent.normalizeModelKey(model.isEmpty() ? "DEEPSEEK" : model);

        AutoWebAgent.HtmlCaptureMode captureMode = parseCaptureMode(params == null ? "" : safeTrim(params.getString("captureMode")));

        BufferingMessengerLogger logger = new BufferingMessengerLogger("[AUTOWEB] ", messenger, 200);
        sendProgress(messenger, "AUTOWEB: 连接浏览器");
        PlayWrightUtil.Connection connection = acquireConnection();
        if (entryUrl.isEmpty()) {
            entryUrl = safeTrim(tryGetLatestOpenedUrl(connection));
        }
        if (entryUrl.isEmpty()) throw new IllegalArgumentException("entryUrl is required");

        PageHandle pageHandle = null;
        try {
            pageHandle = openPage(connection, entryUrl, logger);
            String prompt = buildPrompt(entryUrl, userTask);
            String currentUrl = safeTrim(pageHandle.page.url());

            sendProgress(messenger, "AUTOWEB: 生成计划 model=" + modelKey);
            String planPayload = AutoWebAgent.buildPlanOnlyPayload(currentUrl, prompt, entryUrl);
            String planText = AutoWebAgent.generateGroovyScript(prompt, planPayload, logger, modelKey);
            AutoWebAgent.PlanParseResult parsed = AutoWebAgent.parsePlanFromText(planText);
            if (parsed == null || parsed.steps == null || parsed.steps.isEmpty()) {
                throw new RuntimeException("未解析到任何步骤");
            }

            sendProgress(messenger, "AUTOWEB: 采集页面 HTML，steps=" + parsed.steps.size());
            List<AutoWebAgent.HtmlSnapshot> snapshots = AutoWebAgent.prepareStepHtmls(pageHandle.page, parsed.steps, logger, captureMode);

            sendProgress(messenger, "AUTOWEB: 生成代码 model=" + modelKey);
            String codePayload = AutoWebAgent.buildCodegenPayload(pageHandle.page, parsed.planText, snapshots);
            String code = AutoWebAgent.generateGroovyScript(prompt, codePayload, logger, modelKey);
            code = code == null ? "" : code;

            RunSession session = new RunSession();
            session.runId = UUID.randomUUID().toString().replace("-", "");
            session.entryUrl = entryUrl;
            session.userTask = userTask;
            session.prompt = prompt;
            session.modelKey = modelKey;
            session.captureMode = captureMode;
            session.planText = planText == null ? "" : planText;
            session.planSteps = parsed.steps;
            session.code = code;
            session.createdAt = System.currentTimeMillis();
            SESSIONS.put(session.runId, session);

            sendPlanDetails(messenger, session);
            sendProgress(messenger, "AUTOWEB: 已完成准备 runId=" + session.runId);
            return session;
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            if (pageHandle != null && pageHandle.page != null) {
                try {
                    pageHandle.page.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static JSONObject buildPrepareOutput(RunSession session) {
        JSONObject out = new JSONObject();
        out.put("ok", true);
        out.put("runId", session == null ? "" : session.runId);
        out.put("model", session == null ? "" : session.modelKey);
        out.put("entryUrl", session == null ? "" : session.entryUrl);
        out.put("planText", session == null ? "" : session.planText);
        out.put("code", session == null ? "" : session.code);
        out.put("executionSteps", buildExecutionSteps(session));
        JSONArray steps = new JSONArray();
        if (session != null && session.planSteps != null) {
            for (AutoWebAgent.PlanStep s : session.planSteps) {
                if (s == null) continue;
                JSONObject o = new JSONObject();
                o.put("index", s.index);
                o.put("description", s.description == null ? "" : s.description);
                o.put("targetUrl", s.targetUrl == null ? "" : s.targetUrl);
                o.put("entryAction", s.entryAction == null ? "" : s.entryAction);
                o.put("status", s.status == null ? "" : s.status);
                steps.add(o);
            }
        }
        out.put("steps", steps);
        return out;
    }

    private static JSONObject runPrepared(RunSession session, JSONObject params, ToolMessenger messenger) {
        int fromStep = params == null ? 1 : Math.max(1, params.getIntValue("fromStep", 1));
        int maxSteps = params == null ? 0 : Math.max(0, params.getIntValue("maxSteps", 0));
        boolean autoRepair = params == null || !params.containsKey("autoRepair") || params.getBooleanValue("autoRepair");
        int maxRepairAttempts = params == null ? 1 : Math.max(0, params.getIntValue("maxRepairAttempts", 1));

        BufferingMessengerLogger logger = new BufferingMessengerLogger("[AUTOWEB] ", messenger, 200);

        sendProgress(messenger, "AUTOWEB: 连接浏览器准备执行 runId=" + session.runId);
        PlayWrightUtil.Connection connection = acquireConnection();

        PageHandle pageHandle = null;
        JSONObject out = new JSONObject();
        out.put("ok", false);
        out.put("runId", session.runId);
        if (safeTrim(session.entryUrl).isEmpty()) {
            session.entryUrl = safeTrim(tryGetLatestOpenedUrl(connection));
        }
        out.put("entryUrl", session.entryUrl);
        out.put("model", session.modelKey);
        out.put("fromStep", fromStep);
        out.put("maxSteps", maxSteps);
        out.put("planText", session.planText);
        out.put("code", session.code);
        out.put("executionSteps", buildExecutionSteps(session));
        JSONArray results = new JSONArray();
        out.put("stepResults", results);

        try {
            pageHandle = openPage(connection, session.entryUrl, logger);
            AutoWebAgent.ensureRootPageAtUrl(pageHandle.page, session.entryUrl, logger);

            sendPlanDetails(messenger, session);
            groovy.lang.Binding sharedBinding = new groovy.lang.Binding();
            int baseTimeoutMs = AppConfig.getInstance().getAutowebWaitForLoadStateTimeoutMs();
            int boostedTimeoutMs = Math.max(60_000, baseTimeoutMs * 3);
            int baseMaxRetries = 3;

            int executed = 0;
            for (AutoWebAgent.PlanStep step : session.planSteps) {
                if (step == null) continue;
                int idx = step.index;
                if (idx < fromStep) continue;
                if (maxSteps > 0 && executed >= maxSteps) break;

                sendProgress(messenger, "AUTOWEB: 执行步骤 " + idx + " / " + session.planSteps.size());
                long t0 = System.currentTimeMillis();
                JSONObject sr = new JSONObject();
                sr.put("stepIndex", idx);
                sr.put("ok", false);
                sr.put("durationMs", 0L);
                sr.put("error", "");
                sr.put("logTail", "");

                String stepCode = extractStepCode(session.code, idx);
                if (stepCode == null || stepCode.trim().isEmpty()) {
                    sr.put("ok", true);
                    sr.put("durationMs", System.currentTimeMillis() - t0);
                    sr.put("logTail", logger.tail());
                    results.add(sr);
                    sendProgress(messenger, "AUTOWEB: 步骤成功 step=" + idx);
                    executed++;
                    continue;
                }

                try {
                    String normalizedStepCode = promoteTopLevelDefs(stepCode, logger);
                    Object executionTarget = chooseExecutionTarget(pageHandle.page, logger);
                    try {
                        AutoWebAgent.executeWithGroovy(normalizedStepCode, executionTarget, logger, sharedBinding, baseTimeoutMs, baseMaxRetries);
                    } catch (Exception ex1) {
                        if (isTimeoutException(ex1)) {
                            logger.accept("检测到超时，准备重试本步骤并提升默认超时到 " + boostedTimeoutMs + "ms");
                            try {
                                pageHandle.page.waitForTimeout(1000);
                            } catch (Exception ignored) {
                            }
                            Object executionTarget2 = chooseExecutionTarget(pageHandle.page, logger);
                            AutoWebAgent.executeWithGroovy(normalizedStepCode, executionTarget2, logger, sharedBinding, boostedTimeoutMs, baseMaxRetries);
                        } else {
                            throw ex1;
                        }
                    }
                    sr.put("ok", true);
                    sr.put("durationMs", System.currentTimeMillis() - t0);
                    sr.put("logTail", logger.tail());
                    results.add(sr);
                    sendProgress(messenger, "AUTOWEB: 步骤成功 step=" + idx);
                    executed++;
                } catch (Exception ex) {
                    String reason = ex.getMessage();
                    if (reason == null || reason.trim().isEmpty()) reason = ex.toString();

                    boolean repairedOk = false;
                    if (autoRepair && maxRepairAttempts > 0) {
                        for (int attempt = 1; attempt <= maxRepairAttempts; attempt++) {
                            try {
                                sendProgress(messenger, "AUTOWEB: 步骤失败，尝试修复并重试 step=" + idx + " attempt=" + attempt + "/" + maxRepairAttempts);
                                Object repairTarget = chooseExecutionTarget(pageHandle.page, logger);
                                String captured = AutoWebAgent.getPageContent(repairTarget, session.captureMode, true);
                                String cleaned = AutoWebAgent.cleanCapturedContent(captured, session.captureMode);
                                String hint = "步骤 " + idx + " 执行失败，原因：" + reason;
                                String refined = AutoWebAgent.generateRefinedGroovyScript(session.prompt, cleaned, session.code, logger.tail(), hint, logger, session.modelKey);
                                if (refined != null && !refined.trim().isEmpty()) {
                                    session.code = refined;
                                }
                                String repairedStepCode = extractStepCode(session.code, idx);
                                if (repairedStepCode == null || repairedStepCode.trim().isEmpty()) {
                                    break;
                                }
                                String normalizedRepairedStepCode = promoteTopLevelDefs(repairedStepCode, logger);
                                Object executionTarget3 = chooseExecutionTarget(pageHandle.page, logger);
                                AutoWebAgent.executeWithGroovy(normalizedRepairedStepCode, executionTarget3, logger, sharedBinding, baseTimeoutMs, baseMaxRetries);
                                repairedOk = true;
                                sr.put("repaired", true);
                                sendProgress(messenger, "AUTOWEB: 修复成功 step=" + idx);
                                break;
                            } catch (Exception ex2) {
                                String r2 = ex2.getMessage();
                                if (r2 == null || r2.trim().isEmpty()) r2 = ex2.toString();
                                reason = r2;
                            }
                        }
                    }

                    if (repairedOk) {
                        sr.put("ok", true);
                        sr.put("durationMs", System.currentTimeMillis() - t0);
                        sr.put("logTail", logger.tail());
                        results.add(sr);
                        executed++;
                        continue;
                    }

                    sr.put("ok", false);
                    sr.put("error", reason);
                    sr.put("durationMs", System.currentTimeMillis() - t0);
                    sr.put("logTail", logger.tail());
                    results.add(sr);
                    sendProgress(messenger, "AUTOWEB: 步骤失败 step=" + idx + " err=" + reason);
                    out.put("ok", false);
                    out.put("failedStep", idx);
                    out.put("failedReason", reason);
                    out.put("code", session.code);
                    out.put("executionSteps", buildExecutionSteps(session));
                    return out;
                }
            }

            out.put("ok", true);
            Object clientResult = extractClientResult(sharedBinding);
            if (clientResult != null) {
                Object normalized = normalizeClientResult(clientResult);
                out.put("result", normalized);
                sendProgress(messenger, "AUTOWEB: 全部步骤执行成功 runId=" + session.runId);
                String preview = safeTrim(stringifyResultForMessage(normalized));
                if (!preview.isEmpty()) {
                    sendProgress(messenger, "AUTOWEB: 结果\n" + preview);
                }
            } else {
                sendProgress(messenger, "AUTOWEB: 全部步骤执行成功 runId=" + session.runId);
            }
            return out;
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            if (pageHandle != null && pageHandle.page != null) {
                try {
                    pageHandle.page.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private static String buildPrompt(String entryUrl, String task) {
        String url = entryUrl == null ? "" : entryUrl.trim();
        String t = task == null ? "" : task.trim();
        if (url.isEmpty()) return t;
        if (t.isEmpty()) return "入口URL: " + url;
        return t
                + "\n入口URL: " + url
                + "\n请在脚本最终把关键结果写入变量 autowebResult（JSON 可序列化的 List/Map/String），便于回传给客户端。";
    }

    private static AutoWebAgent.HtmlCaptureMode parseCaptureMode(String s) {
        String v = s == null ? "" : s.trim().toUpperCase();
        if ("ARIA".equals(v) || "ARIA_SNAPSHOT".equals(v) || "A11Y".equals(v)) return AutoWebAgent.HtmlCaptureMode.ARIA_SNAPSHOT;
        return AutoWebAgent.HtmlCaptureMode.RAW_HTML;
    }

    private static String safeTrim(String s) {
        return s == null ? "" : s.trim();
    }

    private static void sendProgress(ToolMessenger messenger, String msg) {
        if (messenger == null) return;
        try {
            messenger.sendText(msg);
        } catch (Exception ignored) {
        }
    }

    private static Object extractClientResult(groovy.lang.Binding binding) {
        if (binding == null) return null;
        try {
            Object v = null;
            if (binding.hasVariable("autowebResult")) {
                v = binding.getVariable("autowebResult");
            } else if (binding.hasVariable("result")) {
                v = binding.getVariable("result");
            } else if (binding.hasVariable("__autoweb_result")) {
                v = binding.getVariable("__autoweb_result");
            }
            if (v != null) return v;
        } catch (Exception ignored) {
        }
        try {
            if (binding.hasVariable("web")) {
                Object web = binding.getVariable("web");
                if (web != null) {
                    java.lang.reflect.Method m = web.getClass().getMethod("getResult");
                    return m.invoke(web);
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static Object normalizeClientResult(Object v) {
        if (v == null) return null;
        if (v instanceof groovy.lang.GString) return v.toString();
        if (v instanceof CharSequence) return v.toString();
        if (v instanceof Number) return v;
        if (v instanceof Boolean) return v;
        String csv = tryConvertToCsv(v);
        if (csv != null) return csv;
        if (v instanceof JSONObject) return v;
        if (v instanceof JSONArray) return v;
        if (v instanceof java.util.Map) return v;
        if (v instanceof java.util.Collection) return v;
        return v.toString();
    }

    private static String stringifyResultForMessage(Object v) {
        if (v == null) return "";
        try {
            String s;
            if (v instanceof String) {
                s = (String) v;
            } else {
                s = com.alibaba.fastjson2.JSON.toJSONString(v);
            }
            if (s == null) return "";
            s = s.trim();
            int limit = 1800;
            if (s.length() > limit) {
                return s.substring(0, limit) + "...(truncated)";
            }
            return s;
        } catch (Exception e) {
            String s = v.toString();
            if (s == null) return "";
            s = s.trim();
            int limit = 1800;
            if (s.length() > limit) {
                return s.substring(0, limit) + "...(truncated)";
            }
            return s;
        }
    }

    private static void sendPlanDetails(ToolMessenger messenger, RunSession session) {
        if (messenger == null || session == null) return;
        if (session.planNotified) return;
        if (session.planSteps == null || session.planSteps.isEmpty()) return;

        session.planNotified = true;

        int maxLines = 25;
        StringBuilder sb = new StringBuilder();
        sb.append("AUTOWEB: 计划已生成").append(" (runId=").append(safeTrim(session.runId)).append(")");
        String entryUrl = safeTrim(session.entryUrl);
        if (!entryUrl.isEmpty()) {
            sb.append("\n入口：").append(entryUrl);
        }
        sb.append("\n步骤：");

        int count = 0;
        for (AutoWebAgent.PlanStep s : session.planSteps) {
            if (s == null) continue;
            count++;
            if (count > maxLines) break;
            String desc = s.description == null ? "" : s.description.trim();
            if (desc.isEmpty()) desc = "(无描述)";
            sb.append("\n").append(s.index).append(". ").append(desc);
        }
        if (session.planSteps.size() > maxLines) {
            sb.append("\n... 共 ").append(session.planSteps.size()).append(" 步，已截断展示");
        }
        sb.append("\n继续执行：autoweb_two_phase_run action=run runId=").append(safeTrim(session.runId));

        try {
            messenger.sendText(sb.toString());
        } catch (Exception ignored) {
        }

        String codePreview = buildStepCodePreviewMarkdown(session, 8, 1200, 200);
        if (!codePreview.isEmpty()) {
            try {
                messenger.sendMarkdown("AUTOWEB: 步骤代码", codePreview);
            } catch (Exception ignored) {
                try {
                    messenger.sendText("AUTOWEB: 步骤代码\n" + codePreview);
                } catch (Exception ignored2) {
                }
            }
        }
    }

    private static String buildStepCodePreviewMarkdown(RunSession session, int maxSteps, int maxCharsPerStep, int maxTotalLines) {
        if (session == null || session.planSteps == null || session.planSteps.isEmpty()) return "";
        String code = session.code == null ? "" : session.code;
        if (code.trim().isEmpty()) return "";

        int steps = Math.max(1, maxSteps);
        int perStepChars = Math.max(200, maxCharsPerStep);
        int totalLines = Math.max(50, maxTotalLines);

        StringBuilder sb = new StringBuilder();
        int emittedSteps = 0;
        int emittedLines = 0;
        for (AutoWebAgent.PlanStep s : session.planSteps) {
            if (s == null) continue;
            if (emittedSteps >= steps) break;
            String block = extractStepCode(code, s.index);
            if (block == null) block = "";
            block = block.trim();
            if (block.isEmpty()) continue;

            String desc = s.description == null ? "" : s.description.trim();
            if (desc.isEmpty()) desc = "(无描述)";
            sb.append("#### Step ").append(s.index).append(": ").append(desc).append("\n\n");

            String compact = block;
            if (compact.length() > perStepChars) compact = compact.substring(0, perStepChars) + "...(truncated)";
            sb.append("```groovy\n").append(compact).append("\n```\n\n");

            emittedSteps++;
            emittedLines = sb.toString().split("\n", -1).length;
            if (emittedLines >= totalLines) {
                sb.append("...(truncated)\n");
                break;
            }
        }
        return sb.toString().trim();
    }

    private static String tryConvertToCsv(Object v) {
        List<List<String>> rows = extractRows(v, 5000);
        if (rows == null || rows.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (List<String> row : rows) {
            if (row == null) continue;
            if (count > 0) sb.append("\n");
            for (int i = 0; i < row.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append(escapeCsvField(row.get(i)));
            }
            count++;
            if (count >= 3000) break;
        }
        String out = sb.toString().trim();
        return out.isEmpty() ? null : out;
    }

    private static String escapeCsvField(String s) {
        String v = s == null ? "" : s;
        boolean needQuote = v.indexOf(',') >= 0 || v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0 || v.indexOf('"') >= 0;
        if (!needQuote) return v;
        String escaped = v.replace("\"", "\"\"");
        return "\"" + escaped + "\"";
    }

    private static List<List<String>> extractRows(Object v, int maxRows) {
        if (v == null) return null;
        int limit = Math.max(1, maxRows);

        if (v instanceof JSONArray) {
            JSONArray arr = (JSONArray) v;
            List<List<String>> rows = new ArrayList<>();
            for (int i = 0; i < arr.size() && rows.size() < limit; i++) {
                Object r = arr.get(i);
                List<String> row = extractRowValues(r);
                if (row != null) rows.add(row);
            }
            return rows.isEmpty() ? null : rows;
        }

        if (v instanceof java.util.Collection) {
            java.util.Collection<?> c = (java.util.Collection<?>) v;
            List<List<String>> rows = new ArrayList<>();
            for (Object r : c) {
                if (rows.size() >= limit) break;
                List<String> row = extractRowValues(r);
                if (row != null) rows.add(row);
            }
            return rows.isEmpty() ? null : rows;
        }

        return null;
    }

    private static List<String> extractRowValues(Object rowObj) {
        if (rowObj == null) return null;

        if (rowObj instanceof JSONArray) {
            JSONArray a = (JSONArray) rowObj;
            List<String> row = new ArrayList<>();
            for (int i = 0; i < a.size(); i++) {
                row.add(a.get(i) == null ? "" : String.valueOf(a.get(i)));
            }
            return row;
        }

        if (rowObj instanceof java.util.Collection) {
            java.util.Collection<?> c = (java.util.Collection<?>) rowObj;
            List<String> row = new ArrayList<>();
            for (Object x : c) row.add(x == null ? "" : String.valueOf(x));
            return row;
        }

        if (rowObj instanceof JSONObject) {
            Object values = ((JSONObject) rowObj).get("values");
            List<String> row = extractRowValues(values);
            if (row != null) return row;
        }

        if (rowObj instanceof java.util.Map) {
            Object values = ((java.util.Map<?, ?>) rowObj).get("values");
            List<String> row = extractRowValues(values);
            if (row != null) return row;
        }

        try {
            java.lang.reflect.Method m = rowObj.getClass().getMethod("getValues");
            Object values = m.invoke(rowObj);
            return extractRowValues(values);
        } catch (Exception ignored) {
        }

        return null;
    }

    private static Object chooseExecutionTarget(Page rootPage, java.util.function.Consumer<String> logger) {
        if (rootPage == null) return null;
        int frameCount = 0;
        try {
            List<com.microsoft.playwright.Frame> frames = rootPage.frames();
            if (frames != null) frameCount = frames.size();
        } catch (Exception ignored) {
            frameCount = 0;
        }
        if (frameCount <= 1) {
            return rootPage;
        }
        AutoWebAgent.ContextWrapper best = AutoWebAgent.waitAndFindContext(rootPage, logger);
        return best == null || best.context == null ? rootPage : best.context;
    }

    private static PlayWrightUtil.Connection acquireConnection() {
        PlayWrightUtil.Connection c = SHARED_CONNECTION;
        if (c != null && c.browser != null) return c;
        synchronized (CONNECTION_LOCK) {
            c = SHARED_CONNECTION;
            if (c != null && c.browser != null) return c;
            c = PlayWrightUtil.connectAndAutomate();
            if (c == null || c.browser == null) {
                throw new RuntimeException("Failed to connect to browser.");
            }
            SHARED_CONNECTION = c;
            return c;
        }
    }

    private static String tryGetLatestOpenedUrl(PlayWrightUtil.Connection connection) {
        if (connection == null || connection.browser == null) return "";
        try {
            List<com.microsoft.playwright.BrowserContext> contexts = connection.browser.contexts();
            if (contexts == null || contexts.isEmpty()) return "";
            List<UrlCandidate> candidates = new ArrayList<>();
            for (com.microsoft.playwright.BrowserContext ctx : contexts) {
                if (ctx == null) continue;
                List<Page> pages = ctx.pages();
                if (pages == null || pages.isEmpty()) continue;
                for (int i = pages.size() - 1; i >= 0; i--) {
                    Page p = pages.get(i);
                    if (p == null) continue;
                    UrlCandidate c = new UrlCandidate();
                    c.url = safeTrim(p.url());
                    c.closed = isPageClosed(p);
                    candidates.add(c);
                }
            }
            return chooseLatestOpenedUrl(candidates);
        } catch (Exception ignored) {
            return "";
        }
    }

    private static boolean isPageClosed(Page p) {
        if (p == null) return true;
        try {
            return p.isClosed();
        } catch (Exception ignored) {
            return true;
        }
    }

    private static final class UrlCandidate {
        String url;
        boolean closed;
    }

    private static String chooseLatestOpenedUrl(List<UrlCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) return "";
        for (UrlCandidate c : candidates) {
            if (c == null) continue;
            if (c.closed) continue;
            String u = safeTrim(c.url);
            if (u.isEmpty()) continue;
            if (u.startsWith("about:")) continue;
            return u;
        }
        return "";
    }

    private static class RunSession {
        String runId;
        String entryUrl;
        String userTask;
        String prompt;
        String modelKey;
        AutoWebAgent.HtmlCaptureMode captureMode;
        String planText;
        List<AutoWebAgent.PlanStep> planSteps;
        String code;
        long createdAt;
        boolean planNotified;
    }

    private static class PageHandle {
        Page page;
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
                    int timeoutMs = AppConfig.getInstance().getAutowebWaitForLoadStateTimeoutMs();
                    h.page.waitForLoadState(
                            com.microsoft.playwright.options.LoadState.DOMCONTENTLOADED,
                            new Page.WaitForLoadStateOptions().setTimeout(Math.max(5_000, timeoutMs))
                    );
                } catch (Exception ignored) {
                }
                try {
                    h.page.waitForLoadState(
                            com.microsoft.playwright.options.LoadState.NETWORKIDLE,
                            new Page.WaitForLoadStateOptions().setTimeout(3_000)
                    );
                } catch (Exception ignored) {
                }
                try {
                    h.page.waitForTimeout(200);
                } catch (Exception ignored) {
                }
            }
            if (logger != null) {
                logger.accept("Opened page: " + safeTrim(h.page.url()));
            }
        }
        return h;
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
        Pattern p = Pattern.compile("(?m)^def\\s+([A-Za-z_][A-Za-z0-9_]*)\\s*=");
        Matcher m = p.matcher(stepCode);
        List<String> vars = new ArrayList<>();
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
        Pattern header = Pattern.compile("(?mi)^\\s*(?:/\\*+\\s*)?(?:\\*+\\s*)?(?://\\s*)?(?:#+\\s*)?(?:[-–—*>•]+\\s*)?(?:Step|步骤)\\s*[:：#\\-]?\\s*(\\d+).*$");
        Matcher m = header.matcher(src);
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

        marks.sort(java.util.Comparator.comparingInt(a -> a[1]));
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

    private static JSONArray buildExecutionSteps(RunSession session) {
        JSONArray arr = new JSONArray();
        if (session == null || session.planSteps == null) return arr;
        for (AutoWebAgent.PlanStep s : session.planSteps) {
            if (s == null) continue;
            JSONObject o = new JSONObject();
            o.put("stepIndex", s.index);
            o.put("description", s.description == null ? "" : s.description);
            String sc = extractStepCode(session.code, s.index);
            o.put("code", sc == null ? "" : sc);
            arr.add(o);
        }
        return arr;
    }

    private static final class BufferingMessengerLogger implements java.util.function.Consumer<String> {
        private final String prefix;
        private final ToolMessenger messenger;
        private final int maxLines;
        private final java.util.ArrayDeque<String> tail = new java.util.ArrayDeque<>();

        BufferingMessengerLogger(String prefix, ToolMessenger messenger, int maxLines) {
            this.prefix = prefix == null ? "" : prefix;
            this.messenger = messenger;
            this.maxLines = Math.max(20, maxLines);
        }

        @Override
        public void accept(String s) {
            String v = s == null ? "" : s;
            String line = prefix.isEmpty() ? v : (prefix + v);
            tail.addLast(line);
            while (tail.size() > maxLines) tail.removeFirst();
        }

        String tail() {
            return String.join("\n", tail);
        }
    }
}
