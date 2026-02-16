package com.qiyi.tools.autoweb;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.microsoft.playwright.Page;
import com.qiyi.config.AppConfig;
import com.qiyi.service.autoweb.AutoWebAgent;
import com.qiyi.tools.Tool;
import com.qiyi.tools.ToolContext;
import com.qiyi.tools.ToolMessenger;
import com.qiyi.util.LLMUtil;
import com.qiyi.util.PlayWrightUtil;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Tool.Info(
        name = "autoweb_generate_cases",
        description = "Generate AutoWeb CaseInput JSON objects from URLs (or the latest opened page if urls omitted). Parameters: urls (string/List, optional, comma-separated ok; if omitted will use latest opened page), prompt (string, optional). Output: JSON array of {id, entryUrl, userTask}.",
        businessDomain = "com.qiyi.tools.autoweb",
        type = Tool.ToolType.AUTO_WEB_SCRIPT
)
public class AutoWebGenerateCasesTool implements Tool {
    private static final Object CONNECTION_LOCK = new Object();
    private static volatile PlayWrightUtil.Connection SHARED_CONNECTION;

    @Override
    public void enrichPlannedTask(String userText, JSONObject plannedTask) {
        if (plannedTask == null) return;
        JSONObject params = plannedTask.getJSONObject("parameters");
        if (params == null) {
            params = new JSONObject();
            plannedTask.put("parameters", params);
        }
        List<String> urls = extractUrls(params);
        if (!urls.isEmpty()) {
            plannedTask.put("missing_info", "");
            return;
        }
        try {
            PlayWrightUtil.Connection connection = acquireConnection();
            String url = tryGetLatestOpenedUrl(connection);
            if (url != null && !url.trim().isEmpty()) {
                params.put("urls", url.trim());
                plannedTask.put("missing_info", "");
                return;
            }
        } catch (Exception ignored) {
        }
        plannedTask.put("missing_info", "");
    }

    @Override
    public String execute(JSONObject params, ToolContext context, ToolMessenger messenger) {
        List<String> urls = extractUrls(params);
        String userPrompt = params == null ? "" : safeTrim(params.getString("prompt"));
        if (userPrompt.isEmpty()) {
            userPrompt = "请根据页面的元素，生成对应的测试用例；如果有列表，优先生成列表获取内容的测试案例，注意不要用现在页面有的统计数据作为测试用例的数据说明，不要随意生成不存在的条件选项。";
        }

        PlayWrightUtil.Connection connection = acquireConnection();
        if (urls.isEmpty()) {
            String opened = tryGetLatestOpenedUrl(connection);
            if (opened != null && !opened.trim().isEmpty()) {
                urls = new ArrayList<>();
                urls.add(opened.trim());
                sendProgress(messenger, "CASEGEN: 未提供 urls，使用已打开页面 " + opened.trim());
            } else {
                throw new IllegalArgumentException("未提供 urls，且未检测到已打开页面 URL");
            }
        }

        List<String> unique = urls.stream()
                .filter(u -> u != null && !u.trim().isEmpty())
                .map(String::trim)
                .collect(Collectors.toCollection(LinkedHashSet::new))
                .stream()
                .collect(Collectors.toList());
        if (unique.isEmpty()) {
            throw new IllegalArgumentException("urls is empty");
        }

        AtomicInteger seq = new AtomicInteger(1);
        JSONArray out = new JSONArray();
        try {
            for (String url : unique) {
                sendProgress(messenger, "CASEGEN: 打开页面 " + url);
                PageHandle pageHandle = null;
                Path screenshot = null;
                try {
                    pageHandle = openPage(connection, url);
                    screenshot = Files.createTempFile("autoweb_casegen_", ".png");
                    synchronized (AutoWebAgent.PLAYWRIGHT_LOCK) {
                        pageHandle.page.screenshot(new Page.ScreenshotOptions().setPath(screenshot));
                    }

                    String aTree = "";
                    String cleanedHtml = "";
                    try {
                        String capturedA11y = AutoWebAgent.getPageContent(pageHandle.page, AutoWebAgent.HtmlCaptureMode.ARIA_SNAPSHOT, false);
                        String cleanedA11y = AutoWebAgent.cleanCapturedContent(capturedA11y, AutoWebAgent.HtmlCaptureMode.ARIA_SNAPSHOT);
                        aTree = clipForPrompt(redactSecretsForPrompt(cleanedA11y), 120_000);
                    } catch (Exception ignored) {
                    }
                    try {
                        String capturedHtml = AutoWebAgent.getPageContent(pageHandle.page, AutoWebAgent.HtmlCaptureMode.RAW_HTML);
                        String cleaned = AutoWebAgent.cleanCapturedContent(capturedHtml, AutoWebAgent.HtmlCaptureMode.RAW_HTML);
                        cleanedHtml = clipForPrompt(redactSecretsForPrompt(cleaned), 120_000);
                    } catch (Exception ignored) {
                    }

                    String prompt = buildCaseGenPrompt(url, userPrompt, aTree, cleanedHtml);
                    String resp = analyzeScreenshotForCase(screenshot.toFile(), prompt);
                    if (resp == null || resp.trim().isEmpty()) {
                        resp = LLMUtil.chat(prompt);
                    }
                    JSONObject c = parseCaseFromModelText(resp);
                    if (c == null) {
                        throw new RuntimeException("Failed to parse CaseInput from model response.");
                    }
                    if (safeTrim(c.getString("entryUrl")).isEmpty()) c.put("entryUrl", url);
                    if (safeTrim(c.getString("id")).isEmpty()) c.put("id", String.valueOf(seq.get()));
                    out.add(c);
                    seq.incrementAndGet();
                    sendProgress(messenger, "CASEGEN: 已生成用例 id=" + c.getString("id"));
                    sendCaseToClient(messenger, c);
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
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        String result = out.toJSONString();
        sendAllCasesToClient(messenger, result, out.size());
        return result;
    }

    private static void sendProgress(ToolMessenger messenger, String msg) {
        if (messenger == null) return;
        try {
            messenger.sendText(msg);
        } catch (Exception ignored) {
        }
    }

    private static void sendCaseToClient(ToolMessenger messenger, JSONObject c) {
        if (messenger == null || c == null) return;
        String id = safeTrim(c.getString("id"));
        String entryUrl = safeTrim(c.getString("entryUrl"));
        String userTask = safeTrim(c.getString("userTask"));
        String text = "CASEGEN_RESULT:\n"
                + "id: " + id + "\n"
                + "entryUrl: " + entryUrl + "\n"
                + "userTask: " + userTask;
        sendLongText(messenger, text);
    }

    private static void sendAllCasesToClient(ToolMessenger messenger, String jsonArray, int count) {
        if (messenger == null) return;
        String text = "CASEGEN_DONE: count=" + count + "\n" + (jsonArray == null ? "" : jsonArray);
        sendLongText(messenger, text);
    }

    private static void sendLongText(ToolMessenger messenger, String text) {
        if (messenger == null) return;
        String v = text == null ? "" : text.trim();
        if (v.isEmpty()) return;
        int maxLen = 1500;
        if (v.length() <= maxLen) {
            try {
                messenger.sendText(v);
            } catch (Exception ignored) {
            }
            return;
        }
        int total = (v.length() + maxLen - 1) / maxLen;
        for (int i = 0; i < total; i++) {
            int start = i * maxLen;
            int end = Math.min(v.length(), (i + 1) * maxLen);
            String part = v.substring(start, end);
            String payload = "PART " + (i + 1) + "/" + total + "\n" + part;
            try {
                messenger.sendText(payload);
            } catch (Exception ignored) {
            }
        }
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

    private static List<String> extractUrls(JSONObject params) {
        if (params == null) return new ArrayList<>();
        Object v = params.get("urls");
        List<String> out = new ArrayList<>();
        if (v instanceof JSONArray) {
            for (Object o : (JSONArray) v) {
                if (o != null) out.add(String.valueOf(o));
            }
            return out;
        }
        if (v instanceof List) {
            for (Object o : (List<?>) v) {
                if (o != null) out.add(String.valueOf(o));
            }
            return out;
        }
        if (v instanceof String) {
            String s = (String) v;
            if (s != null && !s.trim().isEmpty()) {
                String[] parts = s.split("[,，\\s]+");
                for (String p : parts) {
                    String t = safeTrim(p);
                    if (!t.isEmpty()) out.add(t);
                }
            }
        }
        return out;
    }

    private static String safeTrim(String s) {
        return s == null ? "" : s.trim();
    }

    private static class PageHandle {
        Page page;
    }

    private static PageHandle openPage(PlayWrightUtil.Connection connection, String entryUrl) {
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
                            new Page.WaitForLoadStateOptions().setTimeout(120_000)
                    );
                } catch (Exception ignored) {
                }
                h.page.waitForTimeout(1000);
            }
        }
        return h;
    }

    private static String tryGetLatestOpenedUrl(PlayWrightUtil.Connection connection) {
        if (connection == null || connection.browser == null) return "";
        try {
            List<com.microsoft.playwright.BrowserContext> contexts = connection.browser.contexts();
            if (contexts == null || contexts.isEmpty()) return "";
            for (int ci = contexts.size() - 1; ci >= 0; ci--) {
                com.microsoft.playwright.BrowserContext ctx = contexts.get(ci);
                if (ctx == null) continue;
                List<Page> pages = ctx.pages();
                if (pages == null || pages.isEmpty()) continue;
                for (int pi = pages.size() - 1; pi >= 0; pi--) {
                    Page p = pages.get(pi);
                    if (p == null) continue;
                    try {
                        if (p.isClosed()) continue;
                    } catch (Exception ignored) {
                    }
                    String u = safeTrim(p.url());
                    if (u.isEmpty()) continue;
                    if (u.startsWith("about:")) continue;
                    return u;
                }
            }
        } catch (Exception ignored) {
        }
        return "";
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
            sb.append("\n\n").append("A_TREE:\n").append(at);
        }
        String h = cleanedHtml == null ? "" : cleanedHtml.trim();
        if (!h.isEmpty()) {
            sb.append("\n\n").append("HTML:\n").append(h);
        }
        return sb.toString();
    }

    private static String analyzeScreenshotForCase(File screenshot, String prompt) {
        AppConfig cfg = AppConfig.getInstance();
        String aliyunKey = cfg == null ? "" : (cfg.getAliyunApiKey() == null ? "" : cfg.getAliyunApiKey().trim());
        if (aliyunKey.isEmpty()) return "";
        try {
            return LLMUtil.analyzeImageWithAliyun(screenshot, prompt);
        } catch (Exception ignored) {
            return "";
        }
    }

    private static JSONObject parseCaseFromModelText(String text) {
        String raw = text == null ? "" : text.trim();
        if (raw.isEmpty()) return null;
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start < 0 || end <= start) return null;
        String json = raw.substring(start, end + 1);
        try {
            JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
            JSONObject c = new JSONObject();
            if (obj.has("id") && !obj.get("id").isJsonNull()) c.put("id", obj.get("id").getAsString());
            if (obj.has("entryUrl") && !obj.get("entryUrl").isJsonNull()) c.put("entryUrl", obj.get("entryUrl").getAsString());
            if (obj.has("userTask") && !obj.get("userTask").isJsonNull()) c.put("userTask", obj.get("userTask").getAsString());
            String task = c.getString("userTask");
            if (task == null || task.trim().isEmpty()) return null;
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
}
