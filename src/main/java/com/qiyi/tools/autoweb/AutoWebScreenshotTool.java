package com.qiyi.tools.autoweb;

import com.alibaba.fastjson2.JSONObject;
import com.microsoft.playwright.Page;
import com.qiyi.config.AppConfig;
import com.qiyi.service.autoweb.AutoWebAgent;
import com.qiyi.tools.Tool;
import com.qiyi.tools.ToolContext;
import com.qiyi.tools.ToolMessenger;
import com.qiyi.util.PlayWrightUtil;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Tool.Info(
        name = "autoweb_screenshot",
        description = "Capture a screenshot of the current opened page (or a given URL). Parameters: entryUrl (string, optional; if omitted uses latest opened page), fullPage (string, optional; true/false, default true). Output: JSON {ok, url, screenshotPath}.",
        businessDomain = "com.qiyi.tools.autoweb",
        type = Tool.ToolType.AUTO_WEB_SCRIPT
)
public class AutoWebScreenshotTool implements Tool {
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
        String entryUrl = safeTrim(params.getString("entryUrl"));
        if (!entryUrl.isEmpty()) {
            plannedTask.put("missing_info", "");
            return;
        }
        try {
            PlayWrightUtil.Connection c = acquireConnection();
            String latest = tryGetLatestOpenedUrl(c);
            if (latest != null && !latest.trim().isEmpty()) {
                params.put("entryUrl", latest.trim());
            }
        } catch (Exception ignored) {
        }
        plannedTask.put("missing_info", "");
    }

    @Override
    public String execute(JSONObject params, ToolContext context, ToolMessenger messenger) {
        String entryUrl = params == null ? "" : safeTrim(params.getString("entryUrl"));
        boolean fullPage = true;
        if (params != null) {
            String v = safeTrim(params.getString("fullPage"));
            if (!v.isEmpty()) {
                fullPage = "true".equalsIgnoreCase(v) || "1".equals(v) || "yes".equalsIgnoreCase(v);
            }
        }

        PlayWrightUtil.Connection connection = acquireConnection();
        String target = entryUrl;
        if (target.isEmpty()) {
            target = safeTrim(tryGetLatestOpenedUrl(connection));
        }
        if (target.isEmpty()) {
            throw new IllegalArgumentException("未提供 entryUrl，且未检测到已打开页面 URL");
        }

        Page page = attachOrOpen(connection, target);
        String url = safeTrim(page == null ? "" : page.url());
        sendProgress(messenger, "AUTOWEB: 正在截图 " + url);

        Path screenshot = null;
        try {
            screenshot = Files.createTempFile("autoweb_screenshot_", ".png");
            Path out = screenshot;
            synchronized (AutoWebAgent.PLAYWRIGHT_LOCK) {
                page.screenshot(new Page.ScreenshotOptions().setPath(out).setFullPage(fullPage));
            }
            try {
                messenger.sendImage(out.toFile());
            } catch (Exception ignored) {
            }

            JSONObject resp = new JSONObject();
            resp.put("ok", true);
            resp.put("url", url);
            resp.put("screenshotPath", out.toAbsolutePath().toString());
            return resp.toJSONString();
        } catch (Exception e) {
            throw (e instanceof RuntimeException) ? (RuntimeException) e : new RuntimeException(e);
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

    private static Page attachOrOpen(PlayWrightUtil.Connection connection, String entryUrl) {
        if (connection == null || connection.browser == null) {
            throw new IllegalArgumentException("browser connection is null");
        }
        String desiredRaw = safeTrim(entryUrl);
        if (desiredRaw.isEmpty()) {
            throw new IllegalArgumentException("entryUrl is empty");
        }
        String desired = stripUrlQuery(desiredRaw);
        try {
            for (com.microsoft.playwright.BrowserContext ctx : connection.browser.contexts()) {
                if (ctx == null) continue;
                for (Page p : ctx.pages()) {
                    if (p == null) continue;
                    try {
                        if (p.isClosed()) continue;
                    } catch (Exception ignored) {
                    }
                    String u = stripUrlQuery(safeTrim(p.url()));
                    if (!u.isEmpty() && u.startsWith(desired)) {
                        try {
                            p.bringToFront();
                        } catch (Exception ignored) {
                        }
                        return p;
                    }
                }
            }
        } catch (Exception ignored) {
        }

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
        Page page = ctx.newPage();
        synchronized (AutoWebAgent.PLAYWRIGHT_LOCK) {
            page.navigate(desiredRaw);
            try {
                int timeoutMs = AppConfig.getInstance().getAutowebWaitForLoadStateTimeoutMs();
                page.waitForLoadState(
                        com.microsoft.playwright.options.LoadState.DOMCONTENTLOADED,
                        new Page.WaitForLoadStateOptions().setTimeout(Math.max(5_000, timeoutMs))
                );
            } catch (Exception ignored) {
            }
            try {
                page.waitForLoadState(
                        com.microsoft.playwright.options.LoadState.NETWORKIDLE,
                        new Page.WaitForLoadStateOptions().setTimeout(3_000)
                );
            } catch (Exception ignored) {
            }
            try {
                page.waitForTimeout(200);
            } catch (Exception ignored) {
            }
        }
        return page;
    }

    private static String stripUrlQuery(String url) {
        String u = safeTrim(url);
        if (u.isEmpty()) return "";
        int q = u.indexOf('?');
        int h = u.indexOf('#');
        int cut = -1;
        if (q >= 0 && h >= 0) cut = Math.min(q, h);
        else if (q >= 0) cut = q;
        else if (h >= 0) cut = h;
        if (cut >= 0) u = u.substring(0, cut);
        return safeTrim(u);
    }

    private static void sendProgress(ToolMessenger messenger, String msg) {
        if (messenger == null) return;
        try {
            messenger.sendText(msg);
        } catch (Exception ignored) {
        }
    }

    private static String safeTrim(String s) {
        return s == null ? "" : s.trim();
    }
}
