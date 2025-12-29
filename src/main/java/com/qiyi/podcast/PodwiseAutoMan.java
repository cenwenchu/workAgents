/**
 * 
 */
package com.qiyi.podcast;


import java.io.IOException;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Playwright;

//先要运行这个启动可信任浏览器
//nohup /Applications/Google\ Chrome.app/Contents/MacOS/Google\ Chrome --remote-debugging-port=9222 --user-data-dir="/tmp/chrome-debug" > /tmp/chrome-debug.log 2>&1 &

//lsof -ti:9222 | xargs kill -9  杀死进程

/**
 * 
 */
public class PodwiseAutoMan {

    Playwright playwright = null;
    Browser browser = null;

    //两个模型枚举
    public enum ModelType {
        ALL,
        DEEPSEEK,
        GEMINI
    }


	public static void main(String[] args) throws IOException {

        
        PodwiseAutoMan autoMan = new PodwiseAutoMan();

        int maxProcessCount = 5;
        int maxTryTimes = 5;
        int downloadMaxProcessCount = 0;
        int threadPoolSize = 5;
        int maxDuplicatePages = 10;

        // 从main入参读取上面的几个参数，支持提示后输入
        
        java.util.Scanner scanner = new java.util.Scanner(System.in);
        
        System.out.println("请输入参数 (直接回车使用默认值):");
        
        System.out.print("请输入 播客最大新下载条数 (默认 " + maxProcessCount + "): ");
        String input = scanner.nextLine();
        if (!input.trim().isEmpty()) {
            maxProcessCount = Integer.parseInt(input.trim());
        }

        System.out.print("请输入 播客下载翻页，最大尝试次数 (默认 " + maxTryTimes + "): ");
        input = scanner.nextLine();
        if (!input.trim().isEmpty()) {
            maxTryTimes = Integer.parseInt(input.trim());
        }

        System.out.print("请输入 多少页面全量数据已经处理，自动结束播客下载 (默认 " + maxDuplicatePages + "): ");
        input = scanner.nextLine();
        if (!input.trim().isEmpty()) {
            maxDuplicatePages = Integer.parseInt(input.trim());
        }

        System.out.print("请输入 处理多少下载后的文件 (默认 " + downloadMaxProcessCount + "): ");
        input = scanner.nextLine();
        if (!input.trim().isEmpty()) {
            downloadMaxProcessCount = Integer.parseInt(input.trim());
        }
        
        System.out.print("请输入 处理下载后的文件，最大线程数 (默认 " + threadPoolSize + "): ");
        input = scanner.nextLine();
        if (!input.trim().isEmpty()) {
            threadPoolSize = Integer.parseInt(input.trim());
        }

        

        // 执行自动化操作
        autoMan.connectAndAutomate();

        //下载关注的播客节目的文本文件
         DownLoadPodCastTask downLoadPodCastTask = new DownLoadPodCastTask(autoMan.browser,"/Users/cenwenchu/Desktop/podCastItems/");
         
         //downLoadPodCastTask.batchRenameChineseFiles(ModelType.DEEPSEEK, 30);

         //下载关注的播客节目的文本文件
         downLoadPodCastTask.performAutomationDownloadTasks(maxProcessCount,maxTryTimes,true, ModelType.DEEPSEEK,20,maxDuplicatePages);

         //对于下载的文件，通过调用gemini的api来做翻译和中文摘要
         downLoadPodCastTask.processDownloadedFiles(downLoadPodCastTask.DOWNLOAD_DIR_CN,
            downLoadPodCastTask.DOWNLOAD_DIR_SUMMARY,downLoadPodCastTask.DOWNLOAD_DIR_IMAGE,
            downloadMaxProcessCount,ModelType.DEEPSEEK,false,true,threadPoolSize);



        // 从本地文件中读取播客列表,并且搜索和关注这些播客
        //AddPodCastTask addPodCastTask = new AddPodCastTask(autoMan.browser);
        //String[] podCastNames = PodCastUtil.readPodCastNamesFromFile("/Users/cenwenchu/Desktop/podcasts.txt");
        //addPodCastTask.addPodCast(podCastNames);

         
         autoMan.disconnectBrowser();

	}

    /**
     * 连接到现有 Chrome 实例，如果不存在则启动新实例
     */
    public void connectAndAutomate() {
        
        try {
            playwright = Playwright.create();
            
            // 1. 获取 Chrome 的 WebSocket 调试 URL
            String wsEndpoint = PodCastUtil.getChromeWsEndpoint(9222);
            
            if (wsEndpoint == null) {
                
                PodCastUtil.startChromeBrowser();

                wsEndpoint = PodCastUtil.getChromeWsEndpoint(9222);

                if (wsEndpoint == null) 
                {
                    System.out.println("未找到运行的 Chrome 实例，请先以调试模式启动 Chrome");
                    System.out.println("启动命令：chrome --remote-debugging-port=9222");

                    return;
                }
                
            }
            
            System.out.println("连接到: " + wsEndpoint);
            
            // 2. 连接到现有浏览器
            browser = playwright.chromium().connectOverCDP(wsEndpoint);
                 
        } catch (Exception e) {
            e.printStackTrace();
        }
    }


    /**
     * 断开与浏览器的连接并清理相关进程
     */
    public void disconnectBrowser() {
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

        PodCastUtil.killChromeProcess(9222);
    }

}