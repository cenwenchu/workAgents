package com.qiyi.agent;


import java.io.IOException;
import com.qiyi.util.AppLog;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Playwright;
import com.qiyi.service.podcast.service.PodcastManager;
import com.qiyi.util.LLMUtil.ModelType;
import com.qiyi.util.PlayWrightUtil;
import com.qiyi.util.PodCastUtil;
import com.qiyi.tools.ToolContext;
import com.qiyi.tools.ToolMessenger;
import com.qiyi.tools.TaskProcessor;
import com.qiyi.tools.context.ConsoleToolContext;

//先要运行这个启动可信任浏览器
//nohup /Applications/Google\ Chrome.app/Contents/MacOS/Google\ Chrome --remote-debugging-port=9222 --user-data-dir="${HOME}/chrome-debug-profile" > /tmp/chrome-debug.log 2>&1 &

//lsof -ti:9222 | xargs kill -9  杀死进程

/**
 * Podwise 专用 Agent。
 *
 * <p>主要用于播客下载与后处理的独立运行入口，同时也提供 chat() 能力用于复用 Tool 调用链路。</p>
 */
public class PodwiseAgent extends AbstractAgent {

    Playwright playwright = null;
    Browser browser = null;
    private final ToolContext chatContext = new ConsoleToolContext();


    public int run(int maxProcessCount, int maxTryTimes, int maxDuplicatePages, int downloadMaxProcessCount, int threadPoolSize) {
        return run(maxProcessCount, maxTryTimes, maxDuplicatePages, downloadMaxProcessCount, threadPoolSize, null);
    }

    public int run(int maxProcessCount, int maxTryTimes, int maxDuplicatePages, int downloadMaxProcessCount, int threadPoolSize, ToolContext context) {
        return run(maxProcessCount, maxTryTimes, maxDuplicatePages, downloadMaxProcessCount, threadPoolSize, context, null);
    }

    public int run(int maxProcessCount, int maxTryTimes, int maxDuplicatePages, int downloadMaxProcessCount, int threadPoolSize, ToolContext context, ToolMessenger messenger) {
        // 执行自动化操作
        PlayWrightUtil.Connection connection = PlayWrightUtil.connectAndAutomate();
        if (connection != null) {
            this.playwright = connection.playwright;
            this.browser = connection.browser;
        } else {
            AppLog.info("无法连接到浏览器，程序退出");
            throw new RuntimeException("无法连接到浏览器");
        }

        int downloadedCount = 0;
        try {
            // 使用新的 PodcastManager
            PodcastManager podcastManager = new PodcastManager(this.browser);

            // 1. 下载任务
            // maxBatchSize 默认为 20
            downloadedCount = podcastManager.runDownloadTask(maxProcessCount, maxTryTimes, maxDuplicatePages, true, ModelType.DEEPSEEK, 20);

            // 2. 处理任务 (摘要、翻译、图片)
            if (downloadedCount > 0) {
                // 最小化浏览器，避免占用屏幕
                PodCastUtil.minimizeChromeWindow();
                podcastManager.runProcessingTask(downloadMaxProcessCount, ModelType.DEEPSEEK, false, true, threadPoolSize, context, messenger);
            }
            else {
                AppLog.info("没有新下载的文件，无需处理");
            }
        } finally {
             PlayWrightUtil.disconnectBrowser(this.playwright, this.browser);
        }
        return downloadedCount;
    }

	public static void main(String[] args) throws IOException {
        new PodwiseAgent().start();
    }

    @Override
    protected void doStart() {
        int maxProcessCount = 50;
        int maxTryTimes = 15;
        int downloadMaxProcessCount = 0;
        int threadPoolSize = 15;
        int maxDuplicatePages = 10;

        java.util.Scanner scanner = new java.util.Scanner(System.in);

        AppLog.info("请输入参数 (直接回车使用默认值):");

        AppLog.info("请输入 播客最大新下载条数 (默认 " + maxProcessCount + "): ");
        String input = scanner.nextLine();
        if (!input.trim().isEmpty()) {
            maxProcessCount = Integer.parseInt(input.trim());
        }

        AppLog.info("请输入 播客下载翻页，最大尝试次数 (默认 " + maxTryTimes + "): ");
        input = scanner.nextLine();
        if (!input.trim().isEmpty()) {
            maxTryTimes = Integer.parseInt(input.trim());
        }

        AppLog.info("请输入 多少页面全量数据已经处理，自动结束播客下载 (默认 " + maxDuplicatePages + "): ");
        input = scanner.nextLine();
        if (!input.trim().isEmpty()) {
            maxDuplicatePages = Integer.parseInt(input.trim());
        }

        AppLog.info("请输入 处理多少下载后的文件 (默认 " + downloadMaxProcessCount + "，0为目录下所有未处理的文件): ");
        input = scanner.nextLine();
        if (!input.trim().isEmpty()) {
            downloadMaxProcessCount = Integer.parseInt(input.trim());
        }

        AppLog.info("请输入 处理下载后的文件，最大线程数 (默认 " + threadPoolSize + "): ");
        input = scanner.nextLine();
        if (!input.trim().isEmpty()) {
            threadPoolSize = Integer.parseInt(input.trim());
        }

        try {
            run(maxProcessCount, maxTryTimes, maxDuplicatePages, downloadMaxProcessCount, threadPoolSize);
        } catch (Exception e) {
            AppLog.error(e);
        } finally {
            try {
                scanner.close();
            } catch (Exception ignored) {
            }
            stop();
        }
    }

    @Override
    protected void doStop() {
        if (this.playwright != null || this.browser != null) {
            try {
                PlayWrightUtil.disconnectBrowser(this.playwright, this.browser);
            } catch (Exception e) {
                AppLog.error(e);
            } finally {
                this.playwright = null;
                this.browser = null;
            }
        }
    }

    @Override
    public String chat(String userInput) {
        if (userInput == null) return "";
        String input = userInput.trim();
        if (input.isEmpty()) return "";
        CapturingMessenger messenger = new CapturingMessenger();
        TaskProcessor.process(input, chatContext, messenger);
        return messenger.getCaptured();
    }

    private static final class CapturingMessenger implements ToolMessenger {
        private final StringBuilder captured = new StringBuilder();
        private java.util.List<String> mentionedUserIds = java.util.Collections.emptyList();

        @Override
        public java.util.List<String> getMentionedUserIds() {
            return mentionedUserIds;
        }

        @Override
        public ToolMessenger withMentionedUserIds(java.util.List<String> mentionedUserIds) {
            this.mentionedUserIds = mentionedUserIds == null ? java.util.Collections.emptyList() : mentionedUserIds;
            return this;
        }

        @Override
        public void sendText(String content) {
            if (content != null) {
                captured.append(content).append("\n");
            }
            AppLog.info("[PodwiseAgent] Text: " + content);
        }

        @Override
        public void sendMarkdown(String title, String content) {
            if (title != null) {
                captured.append(title).append("\n");
            }
            if (content != null) {
                captured.append(content).append("\n");
            }
            AppLog.info("[PodwiseAgent] Markdown Title: " + title);
            AppLog.info("[PodwiseAgent] Content: \n" + content);
        }

        @Override
        public void sendImage(String imageUrl) {
            if (imageUrl != null) {
                captured.append(imageUrl).append("\n");
            }
            AppLog.info("[PodwiseAgent] Image: " + imageUrl);
        }

        @Override
        public void sendImage(java.io.File imageFile) {
            String path = imageFile == null ? null : imageFile.getAbsolutePath();
            if (path != null) {
                captured.append(path).append("\n");
            }
            AppLog.info("[PodwiseAgent] Local Image: " + path);
        }

        public String getCaptured() {
            return captured.toString().trim();
        }
    }

}
