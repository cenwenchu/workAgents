package com.qiyi.util;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Playwright;
import com.qiyi.config.AppConfig;

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

                wsEndpoint = PodCastUtil.getChromeWsEndpoint(port);

                if (wsEndpoint == null) 
                {
                    System.out.println("未找到运行的 Chrome 实例，请先以调试模式启动 Chrome");
                    System.out.println("启动命令：chrome --remote-debugging-port=" + port);

                    return null;
                }
                
            }
            
            System.out.println("连接到: " + wsEndpoint);
            
            // 2. 连接到现有浏览器
            connection.browser = connection.playwright.chromium().connectOverCDP(wsEndpoint);
            return connection;
                 
        } catch (Exception e) {
            e.printStackTrace();
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
                System.out.println("关闭浏览器连接失败: " + e.getMessage());
            }
        }

        if (playwright != null) {
                playwright.close();
            }

        PodCastUtil.killChromeProcess(AppConfig.getInstance().getChromeDebugPort());
    }
    
}
