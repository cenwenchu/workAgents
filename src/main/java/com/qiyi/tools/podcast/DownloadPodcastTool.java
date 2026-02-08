package com.qiyi.tools.podcast;

import com.alibaba.fastjson2.JSONObject;
import com.qiyi.tools.Tool;
import com.qiyi.tools.ToolContext;
import com.qiyi.tools.ToolMessenger;
import java.util.concurrent.locks.ReentrantLock;
import com.qiyi.util.AppLog;

/**
 * 播客抓取与后处理入口工具（Podwise）。
 *
 * <p>执行流程：连接浏览器 → 扫描可下载条目 → 下载 →（可选）调用大模型做重命名/摘要/图片等后处理。</p>
 */
@Tool.Info(
        name = "download_podcast",
        description = "Download podcasts from Podwise. Parameters: maxProcessCount (int, default 50) - Maximum number of new episodes to download (e.g., 'download 5 items' sets this to 5), maxTryTimes (int, default 15) - Maximum scroll attempts, maxDuplicatePages (int, default 10) - Stop after N pages of duplicates, downloadMaxProcessCount (int, default 0) - Max files to process after download (0=all), threadPoolSize (int, default 15) - Thread pool size for processing."
)
public class DownloadPodcastTool implements Tool {
    private static final ReentrantLock DOWNLOAD_LOCK = new ReentrantLock();
    private com.qiyi.agent.PodwiseAgent podwiseAgent = new com.qiyi.agent.PodwiseAgent();

    // For testing
    protected void setPodwiseAgent(com.qiyi.agent.PodwiseAgent podwiseAgent) {
        this.podwiseAgent = podwiseAgent;
    }

    public static final int DOWNLOAD_MAX_PROCESS_COUNT = 50;
    public static final int DOWNLOAD_MAX_TRY_TIMES = 15;
    public static final int DOWNLOAD_MAX_DUPLICATE_PAGES = 10;
    public static final int DOWNLOAD_DOWNLOAD_MAX_PROCESS_COUNT = 0;
    public static final int DOWNLOAD_THREAD_POOL_SIZE = 15;
    public static final boolean PUBLISH_IS_DRAFT = false;

    @Override
    public String execute(JSONObject params, ToolContext context, ToolMessenger messenger) {
        int maxProcessCount = params != null && params.containsKey("maxProcessCount") ? params.getIntValue("maxProcessCount") : DOWNLOAD_MAX_PROCESS_COUNT;
        int maxTryTimes = params != null && params.containsKey("maxTryTimes") ? params.getIntValue("maxTryTimes") : DOWNLOAD_MAX_TRY_TIMES;
        int maxDuplicatePages = params != null && params.containsKey("maxDuplicatePages") ? params.getIntValue("maxDuplicatePages") : DOWNLOAD_MAX_DUPLICATE_PAGES;
        int downloadMaxProcessCount = params != null && params.containsKey("downloadMaxProcessCount") ? params.getIntValue("downloadMaxProcessCount") : DOWNLOAD_DOWNLOAD_MAX_PROCESS_COUNT;
        int threadPoolSize = params != null && params.containsKey("threadPoolSize") ? params.getIntValue("threadPoolSize") : DOWNLOAD_THREAD_POOL_SIZE;

        if (!DOWNLOAD_LOCK.tryLock()) {
            try {
                if (messenger != null) messenger.sendText("当前已有下载任务正在执行，请稍后再试。");
            } catch (Exception e) {
                AppLog.error(e);
            }
            return "Task locked";
        }

        try {
            if (messenger != null) messenger.sendText("开始执行下载任务...");
            int count = podwiseAgent.run(maxProcessCount, maxTryTimes, maxDuplicatePages, downloadMaxProcessCount, threadPoolSize, context, messenger);
            String result = "下载任务执行完毕，共下载更新了 " + count + " 条播客。";
            if (messenger != null) messenger.sendText(result);
            return result;
        } catch (Exception e) {
            AppLog.error(e);
            try {
                if (messenger != null) messenger.sendText("下载任务执行异常: " + e.getMessage());
            } catch (Exception ex) {
                AppLog.error(ex);
            }
            return "Error: " + e.getMessage();
        } finally {
            DOWNLOAD_LOCK.unlock();
        }
    }
}
