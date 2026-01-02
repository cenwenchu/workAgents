/**
 * 
 */
package com.qiyi.agent;


import java.io.IOException;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Playwright;
import com.qiyi.podcast.ModelType;
import com.qiyi.podcast.service.PodcastManager;
import com.qiyi.util.PlayWrightUtil;

//先要运行这个启动可信任浏览器
//nohup /Applications/Google\ Chrome.app/Contents/MacOS/Google\ Chrome --remote-debugging-port=9222 --user-data-dir="/tmp/chrome-debug" > /tmp/chrome-debug.log 2>&1 &

//lsof -ti:9222 | xargs kill -9  杀死进程

/**
 * 
 */
public class PodwiseAgent {

    Playwright playwright = null;
    Browser browser = null;


	public static void main(String[] args) throws IOException {

        
        PodwiseAgent autoMan = new PodwiseAgent();

        int maxProcessCount = 50;
        int maxTryTimes = 7;
        int downloadMaxProcessCount = 0;
        int threadPoolSize = 15;
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

        System.out.print("请输入 处理多少下载后的文件 (默认 " + downloadMaxProcessCount + "，0为目录下所有未处理的文件): ");
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
        PlayWrightUtil.Connection connection = PlayWrightUtil.connectAndAutomate();
        if (connection != null) {
            autoMan.playwright = connection.playwright;
            autoMan.browser = connection.browser;
        } else {
            System.out.println("无法连接到浏览器，程序退出");
            return;
        }

        // 使用新的 PodcastManager
        PodcastManager podcastManager = new PodcastManager(autoMan.browser);
        
        // 1. 下载任务
        // maxBatchSize 默认为 20
        podcastManager.runDownloadTask(maxProcessCount, maxTryTimes, maxDuplicatePages, true, ModelType.DEEPSEEK, 20);

        // 2. 处理任务 (摘要、翻译、图片)
        // isStreamingProcess=true, needGenerateImage=false (as per original code call: false, true -> needGenerateImage=false?? Wait)
        // Original: processDownloadedFiles(..., needGenerateImage=false, isStreamingProcess=true, ...)
        podcastManager.runProcessingTask(downloadMaxProcessCount, ModelType.DEEPSEEK, false, true, threadPoolSize);

        
         PlayWrightUtil.disconnectBrowser(autoMan.playwright, autoMan.browser);

	}

}
