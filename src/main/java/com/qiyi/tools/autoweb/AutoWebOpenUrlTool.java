package com.qiyi.tools.autoweb;

import com.alibaba.fastjson2.JSONObject;
import com.microsoft.playwright.Page;
import com.qiyi.service.autoweb.AutoWebAgent;
import com.qiyi.tools.Tool;
import com.qiyi.tools.ToolContext;
import com.qiyi.tools.ToolMessenger;
import com.qiyi.util.PlayWrightUtil;

@Tool.Info(
        name = "autoweb_open_url",
        description = "Open a webpage in the shared Chrome CDP session. Parameters: entryUrl (string, mandatory). Output: JSON {ok, openedUrl}.",
        businessDomain = "com.qiyi.tools.autoweb",
        type = Tool.ToolType.AUTO_WEB_SCRIPT
)
public class AutoWebOpenUrlTool implements Tool {
    private static final Object CONNECTION_LOCK = new Object();
    private static volatile PlayWrightUtil.Connection SHARED_CONNECTION;

    @Override
    public String execute(JSONObject params, ToolContext context, ToolMessenger messenger) {
        String entryUrl = params == null ? "" : safeTrim(params.getString("entryUrl"));
        if (entryUrl.isEmpty()) {
            throw new IllegalArgumentException("entryUrl is required");
        }
        String normalized = normalizeUrl(entryUrl);
        if (normalized == null || normalized.isEmpty()) {
            throw new IllegalArgumentException("invalid entryUrl: " + entryUrl);
        }

        PlayWrightUtil.Connection connection = acquireConnection();
        Page page = openPage(connection, normalized);
        String openedUrl = "";
        if (page != null) {
            try {
                synchronized (AutoWebAgent.PLAYWRIGHT_LOCK) {
                    openedUrl = safeTrim(page.url());
                }
            } catch (Exception ignored) {
                openedUrl = "";
            }
            if (openedUrl.isEmpty() || "about:blank".equalsIgnoreCase(openedUrl)) {
                long deadline = System.currentTimeMillis() + 2000;
                while (System.currentTimeMillis() < deadline) {
                    try {
                        synchronized (AutoWebAgent.PLAYWRIGHT_LOCK) {
                            openedUrl = safeTrim(page.url());
                            if (!openedUrl.isEmpty() && !"about:blank".equalsIgnoreCase(openedUrl)) break;
                            try { page.waitForTimeout(120); } catch (Exception ignored) {}
                        }
                    } catch (Exception ignored) {}
                }
            }
        }
        sendProgress(messenger, "AUTOWEB: 已打开页面 " + openedUrl);

        JSONObject out = new JSONObject();
        out.put("ok", true);
        out.put("openedUrl", openedUrl);
        return out.toJSONString();
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

    private static Page openPage(PlayWrightUtil.Connection connection, String entryUrl) {
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
            page.navigate(entryUrl.trim());
            try {
                page.waitForLoadState(
                        com.microsoft.playwright.options.LoadState.NETWORKIDLE,
                        new Page.WaitForLoadStateOptions().setTimeout(120_000)
                );
            } catch (Exception ignored) {
            }
            try {
                page.waitForTimeout(500);
            } catch (Exception ignored) {
            }
        }
        return page;
    }

    private static String normalizeUrl(String raw) {
        String v = safeTrim(raw);
        if (v.isEmpty()) return null;
        if (v.regionMatches(true, 0, "www.", 0, 4)) {
            v = "https://" + v;
        } else if (!v.contains("://") && looksLikeHostOrLocal(v)) {
            v = "https://" + v;
        }
        try {
            java.net.URI u = java.net.URI.create(v);
            String scheme = u.getScheme();
            if (scheme == null) return null;
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) return null;
            if (u.getHost() == null || u.getHost().trim().isEmpty()) return null;
            return u.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean looksLikeHostOrLocal(String v) {
        String s = safeTrim(v).toLowerCase();
        if (s.isEmpty()) return false;
        if (s.startsWith("localhost")) return true;
        if (s.startsWith("127.0.0.1")) return true;
        if (s.startsWith("0.0.0.0")) return true;
        if (s.contains(" ")) return false;
        return s.contains(".");
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
