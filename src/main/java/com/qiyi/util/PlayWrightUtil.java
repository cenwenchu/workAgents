package com.qiyi.util;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Playwright;
import com.qiyi.config.AppConfig;

/**
 * Playwright 浏览器连接与调试辅助。
 *
 * <p>该工具类主要用于“复用本机 Chrome（CDP）”的自动化场景，例如播客抓取、微信公众号发布。</p>
 */
public class PlayWrightUtil {

    public static class Connection {
        public Playwright playwright;
        public Browser browser;
    }

    /**
     * 连接到现有 Chrome 实例，如果不存在则启动新实例
     */
    public static Connection connectAndAutomate() {
        Connection connection = new Connection();
        int port = AppConfig.getInstance().getChromeDebugPort();
        try {
            connection.playwright = Playwright.create();
            
            // 1. 获取 Chrome 的 WebSocket 调试 URL
            String wsEndpoint = PodCastUtil.getChromeWsEndpoint(port);
            
            if (wsEndpoint == null) {
                
                PodCastUtil.startChromeBrowser(port);

                // Retry connecting to Chrome (up to 10 times, 2s interval)
                for (int i = 0; i < 10; i++) {
                    wsEndpoint = PodCastUtil.getChromeWsEndpoint(port);
                    if (wsEndpoint != null) {
                        break;
                    }
                    try {
                        AppLog.info("等待 Chrome 调试接口准备就绪... (" + (i + 1) + "/10)");
                        Thread.sleep(2000);
                    } catch (InterruptedException e) {
                        AppLog.error(e);
                    }
                }

                if (wsEndpoint == null) 
                {
                    AppLog.info("未找到运行的 Chrome 实例，请先以调试模式启动 Chrome");
                    String userDataDir = System.getProperty("user.home") + "/chrome-debug-profile";
                    AppLog.info("启动命令：chrome --remote-debugging-port=" + port + " --user-data-dir=\"" + userDataDir + "\"");

                    return null;
                }
                
            }
            
            AppLog.info("连接到: " + wsEndpoint);
            
            // 2. 连接到现有浏览器
            connection.browser = connection.playwright.chromium().connectOverCDP(wsEndpoint);
            return connection;
                 
        } catch (Exception e) {
            AppLog.error(e);
            return null;
        }
    }


    /**
     * 断开与浏览器的连接并清理相关进程
     */
    public static void disconnectBrowser(Playwright playwright,Browser browser) {
        if (browser != null) {
            try {
                browser.close();
            } catch (Exception e) {
                AppLog.info("关闭浏览器连接失败: " + e.getMessage());
            }
        }

        if (playwright != null) {
                playwright.close();
            }

        PodCastUtil.killChromeProcess(AppConfig.getInstance().getChromeDebugPort());
    }

    /**
     * 调试辅助工具：高亮指定元素并截图保存
     * @param page 当前页面对象
     * @param locator 需要高亮的元素定位器
     * @param filename 截图保存的文件名
     */
    public static void highlightAndScreenshot(com.microsoft.playwright.Page page, com.microsoft.playwright.Locator locator, String filename) {
        AppLog.info("DEBUG: 准备高亮元素并截图验证...");
        try {
            // 高亮按钮 (红色边框)
            locator.evaluate("element => element.style.border = '5px solid red'");
            // 截图保存
            java.nio.file.Path path = java.nio.file.Paths.get(filename);
            page.screenshot(new com.microsoft.playwright.Page.ScreenshotOptions().setPath(path));
            AppLog.info("DEBUG: 截图已保存至: " + path.toAbsolutePath());
        } catch (Exception debugEx) {
            AppLog.info("DEBUG: 调试代码执行异常: " + debugEx.getMessage());
        }
    }
    
}
