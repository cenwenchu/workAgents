package com.qiyi.tools.erp;

import com.alibaba.fastjson2.JSONObject;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.RequestOptions;
import com.qiyi.config.AppConfig;
import com.qiyi.tools.Tool;
import com.qiyi.tools.ToolContext;
import com.qiyi.tools.ToolMessenger;
import com.qiyi.util.PlayWrightUtil;
import com.qiyi.util.AppLog;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

public abstract class ErpBaseTool implements Tool {
    protected static final ReentrantLock TOOL_LOCK = new ReentrantLock();
    protected final Map<String, String> capturedApiHeaders = new HashMap<>();

    protected PlayWrightUtil.Connection connectToBrowser() {
        return PlayWrightUtil.connectAndAutomate();
    }

    protected void disconnectBrowser(PlayWrightUtil.Connection connection) {
        if (connection != null) {
            PlayWrightUtil.disconnectBrowser(connection.playwright, connection.browser);
        }
    }

    protected boolean ensureLogin(Page page, String targetUrl, ToolMessenger messenger) {
        try {
            // Setup network interception to capture headers from real requests
            setupHeaderCapture(page);

            page.navigate(targetUrl);
            try {
                page.waitForLoadState(
                        com.microsoft.playwright.options.LoadState.NETWORKIDLE,
                        new Page.WaitForLoadStateOptions().setTimeout(AppConfig.getInstance().getAutowebWaitForLoadStateTimeoutMs())
                );
            } catch (Exception e) {
                // Ignore timeout, page might still be usable or we check login next
            }
            
            boolean isLoggedIn = isLoginSuccess(page);
            
            if (!isLoggedIn) {
                if (messenger != null) messenger.sendText("检测到ERP系统未登录，请在打开的浏览器中完成登录，任务将等待您的操作。");
                
                // Wait loop
                long maxWaitTime = 5 * 60 * 1000; // 5 minutes
                long startTime = System.currentTimeMillis();
                
                while (!isLoggedIn) {
                    if (System.currentTimeMillis() - startTime > maxWaitTime) {
                        if (messenger != null) messenger.sendText("登录等待超时，任务终止。");
                        return false;
                    }
                    
                    try {
                        Thread.sleep(1000); 
                        isLoggedIn = isLoginSuccess(page);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return false;
                    } catch (Exception e) {
                        // Ignore navigation errors during wait (e.g. user interacting)
                    }
                }
                
                if (messenger != null) messenger.sendText("ERP登录成功，继续执行任务。");

                Thread.sleep(2000); 
            }
            return true;
        } catch (Exception e) {
            AppLog.error(e);
            try {
                if (messenger != null) messenger.sendText("检查登录状态时出错: " + e.getMessage());
            } catch (Exception ex) { AppLog.error(ex); }
            return false;
        }
    }

    protected void setupHeaderCapture(Page page) {
        capturedApiHeaders.clear();
        page.onRequest(request -> {
            String url = request.url();
            // Capture headers from innerapi calls or the new api.erp321.com
            if ((url.contains("innerapi.scm121.com") || url.contains("api.erp321.com")) && !url.contains(".js") && !url.contains(".css")) {
                Map<String, String> headers = request.headers();
                // Store headers if we haven't already, or overwrite
                // We prefer headers from POST requests as they are likely API calls
                if (request.method().equalsIgnoreCase("POST") || capturedApiHeaders.isEmpty()) {
                    capturedApiHeaders.putAll(headers);
                    // Remove content-specific headers that we will regenerate
                    capturedApiHeaders.remove("content-length");
                    capturedApiHeaders.remove("content-type");
                }
            }
        });
    }

    protected boolean isLoginSuccess(Page page) {
        // 检查是否有登录状态的元素
        try {
            // User instruction: "首页" is at the top level, inside a span.
            Locator homeElement = page.locator("//span[contains(text(),'首页')]").first();
            try {
                homeElement.waitFor(new Locator.WaitForOptions().setTimeout(3000));
                return true;
            } catch (Exception e) {
                // Not found
            }
            
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    protected RequestOptions createApiRequestOptions(Page page, JSONObject payload, String referer) {
        RequestOptions options = RequestOptions.create()
                .setData(payload)
                .setHeader("Content-Type", "application/json")
                .setHeader("Origin", "https://sc.scm121.com")
                .setHeader("Referer", referer);

        if (!capturedApiHeaders.isEmpty()) {
            AppLog.info("Using Captured Headers: " + capturedApiHeaders.keySet());
            for (Map.Entry<String, String> entry : capturedApiHeaders.entrySet()) {
                String key = entry.getKey();
                String lowerKey = key.toLowerCase();
                
                if (lowerKey.equals("content-length") || 
                    lowerKey.equals("host") || 
                    lowerKey.equals("connection") || 
                    lowerKey.equals("date") ||
                    lowerKey.equals("upgrade-insecure-requests") ||
                    lowerKey.equals("accept-encoding") ||
                    lowerKey.startsWith("sec-ch-ua") || 
                    lowerKey.startsWith(":")) { 
                    continue;
                }
                
                options.setHeader(key, entry.getValue());
            }
        } else {
            // Fallback: Manually construct headers
            List<com.microsoft.playwright.options.Cookie> cookies = page.context().cookies();
            StringBuilder cookieHeader = new StringBuilder();
            for (com.microsoft.playwright.options.Cookie cookie : cookies) {
                if (cookieHeader.length() > 0) cookieHeader.append("; ");
                cookieHeader.append(cookie.name).append("=").append(cookie.value);
            }
            if (cookieHeader.length() > 0) {
                options.setHeader("Cookie", cookieHeader.toString());
            }

            // Get Tokens from LocalStorage via script
            String storageScript = "() => {" +
                    "  let headers = {};" +
                    "  try {" +
                    "    for (let i = 0; i < localStorage.length; i++) {" +
                    "      let k = localStorage.key(i);" +
                    "      if (k.toLowerCase().includes('token') || k.toLowerCase().includes('auth') || k === 'uid') {" +
                    "         headers[k] = localStorage.getItem(k);" +
                    "      }" +
                    "    }" +
                    "    for (let i = 0; i < sessionStorage.length; i++) {" +
                    "      let k = sessionStorage.key(i);" +
                    "      if (k.toLowerCase().includes('token') || k.toLowerCase().includes('auth')) {" +
                    "         headers[k] = sessionStorage.getItem(k);" +
                    "      }" +
                    "    }" +
                    "  } catch (e) {}" +
                    "  return JSON.stringify(headers);" +
                    "}";
            try {
                String storageJson = (String) page.evaluate(storageScript);
                JSONObject storageHeaders = JSONObject.parseObject(storageJson);
                
                for (String key : storageHeaders.keySet()) {
                    String val = storageHeaders.getString(key);
                    options.setHeader(key, val);
                    if (key.contains("token") || key.contains("Token")) {
                         options.setHeader("token", val);
                    }
                }
            } catch (Exception e) {
                AppLog.error("Failed to get storage headers: " + e.getMessage());
            }
        }
        return options;
    }
}
