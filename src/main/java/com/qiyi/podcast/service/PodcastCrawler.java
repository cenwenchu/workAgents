package com.qiyi.podcast.service;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.LoadState;
import com.qiyi.podcast.PodCastItem;
import com.qiyi.util.PodCastUtil;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public class PodcastCrawler {

    private final Browser browser;
    private static final int DEFAULT_TIMEOUT_MS = 5 * 60 * 1000;
    private static final int SHORT_TIMEOUT_MS = 5000;

    // Selectors
    private static final String XPATH_LIBRARY = "//a[contains(@href,'dashboard/episodes')]";
    private static final String XPATH_FOLLOWING = "//div/button[contains(text(),'Following')]";
    private static final String XPATH_PODCAST_ITEM = "//div[./img[contains(@alt, 'Podcast Cover')] and .//a[contains(@href, 'dashboard')]]";
    private static final String XPATH_READY_STATUS = "//div/span[contains(text(),'Ready')]";
    private static final String SELECTOR_LOAD_MORE = "button:has-text('Load More')";

    public PodcastCrawler(Browser browser) {
        this.browser = browser;
    }

    public List<PodCastItem> scanForNewEpisodes(int maxProcessCount, int maxTryTimes, int maxDuplicatePages, List<String> existingItemNames) {
        List<PodCastItem> newItems = new ArrayList<>();
        if (browser == null) return newItems;

        BrowserContext context = browser.contexts().isEmpty() ? browser.newContext() : browser.contexts().get(0);
        Page page = context.newPage();
        page.setDefaultTimeout(DEFAULT_TIMEOUT_MS);

        try {
            page.navigate("https://podwise.ai/dashboard/episodes");

            if (!PodCastUtil.isLoggedIn(page)) {
                System.out.println("用户未登录，请手动登录后继续");
                PodCastUtil.waitForManualLogin(page);
            }

            if (navigateToFollowing(page)) {
                filterReadyPodcasts(page);

                try {
                    page.waitForSelector(XPATH_PODCAST_ITEM, new Page.WaitForSelectorOptions().setTimeout(DEFAULT_TIMEOUT_MS));
                } catch (Exception e) {
                    System.out.println("未找到任何播客条目");
                    return newItems;
                }

                processNodeList(newItems, existingItemNames, page, maxProcessCount, maxTryTimes, maxDuplicatePages);
            }

        } catch (Exception e) {
            System.err.println("扫描任务出错: " + e.getMessage());
            e.printStackTrace();
        } finally {
            if (!page.isClosed()) page.close();
        }

        return newItems;
    }

    private boolean navigateToFollowing(Page page) {
        try {
            ElementHandle libraryButton = page.waitForSelector(XPATH_LIBRARY, new Page.WaitForSelectorOptions().setTimeout(DEFAULT_TIMEOUT_MS));
            if (libraryButton != null) {
                // libraryButton.click();
                libraryButton.evaluate("node => node.click()");
                ElementHandle followingBtn = page.waitForSelector(XPATH_FOLLOWING, new Page.WaitForSelectorOptions().setTimeout(DEFAULT_TIMEOUT_MS));
                if (followingBtn != null) {
                    followingBtn.evaluate("node => node.click()");
                    return true;
                }
            }
        } catch (Exception e) {
            System.err.println("导航到 Following 失败: " + e.getMessage());
        }
        return false;
    }

    private void filterReadyPodcasts(Page page) {
        try {
            page.locator("button:has-text('All')").click();
            page.waitForSelector("div[role='option']:has-text('ready')");
            page.locator("div[role='option']:has-text('ready')").click();
        } catch (Exception e) {
            System.err.println("筛选 Ready 状态失败: " + e.getMessage());
        }
    }

    private void processNodeList(List<PodCastItem> itemList, List<String> existingNames, Page page, 
                                 int maxProcessCount, int maxTryTimes, int maxDuplicatePages) {
        int validItemCount = 0;
        int tryTimes = 0;
        int lastProcessedIndex = 0;
        int consecutiveDuplicatePages = 0;

        do {
            List<ElementHandle> elements = page.querySelectorAll(XPATH_PODCAST_ITEM);
            System.out.println("当前元素总数: " + elements.size() + ", 已处理索引: " + lastProcessedIndex);
            showPageNotification(page, "正在分析播客列表... 当前已加载 " + elements.size() + " 个项目");

            // 防止页面刷新或DOM重构导致索引越界或漏处理
            if (elements.size() < lastProcessedIndex) {
                System.out.println("检测到列表元素数量减少，重置索引以防止遗漏...");
                lastProcessedIndex = 0;
            }

            if (elements.size() > lastProcessedIndex) {
                tryTimes = 0;
                boolean hasNewValidItemInThisBatch = false;

                for (int i = lastProcessedIndex; i < elements.size(); i++) {
                    if (validItemCount >= maxProcessCount) break;

                    try {
                        ElementHandle element = elements.get(i);
                        PodCastItem item = parsePodcastItem(element);

                        if (item != null && !existingNames.contains(item.title)) {
                            if (item.isProcessed) {
                                validItemCount++;
                                itemList.add(item);
                                existingNames.add(item.title);
                                hasNewValidItemInThisBatch = true;
                                System.out.println("找到有效Item: " + item.channelName + " - " + item.title);
                                showPageNotification(page, "发现新可下载播客: " + item.title);
                            } else {
                                // 调试日志：记录未就绪的项目
                                 System.out.println("Item 未就绪 (Skipped): " + item.title);
                            }
                        }
                        else
                        {
                            if (item != null && existingNames.contains(item.title)) {
                                System.out.println("Item 重复 (Skipped): " + item.title);
                            }
                            else
                            {
                                System.out.println("Item 不存在 (Skipped)");
                            }
                        }
                    } catch (Exception e) {
                        System.err.println("处理单个元素时发生异常 (Index: " + i + "), 继续处理下一个: " + e.getMessage());
                    }
                }

                if (!hasNewValidItemInThisBatch) {
                    consecutiveDuplicatePages++;

                    System.out.println("连续 " + consecutiveDuplicatePages + " 次下拉未发现新数据");

                    if (consecutiveDuplicatePages >= maxDuplicatePages) {
                        System.out.println("连续 " + maxDuplicatePages + " 次下拉未发现新数据，提前结束");
                        break;
                    }
                } else {
                    consecutiveDuplicatePages = 0;
                }

                lastProcessedIndex = elements.size();
            } else {
                tryTimes++;
                if (tryClickLoadMore(page)) {
                    page.waitForTimeout(2000);
                    continue;
                }
            }

            if (validItemCount >= maxProcessCount) break;

            if (!scrollToLoadMore(page)) {
                if (tryTimes > maxTryTimes) break;
            }

        } while (tryTimes <= maxTryTimes && validItemCount < maxProcessCount);
        
        showPageNotification(page, "扫描任务完成，共找到 " + validItemCount + " 个新播客");
    }

    private PodCastItem parsePodcastItem(ElementHandle element) {
        PodCastItem item = new PodCastItem();
        try {
            ElementHandle link = element.querySelector(":scope a");
            if (link == null) link = element.querySelector("a");
            
            if (link != null) {
                item.linkString = link.getAttribute("href");
                String text = (String) link.evaluate("el => el.textContent.trim()");
                item.title = text.replaceAll("[\\\\/:*?\"<>|]", "");
            }

            ElementHandle channel = element.querySelector("//img[contains(@alt,'Podcast cover')]/../span");
            if (channel != null) {
                item.channelName = (String) channel.evaluate("el => el.textContent.trim()");
            }

            ElementHandle readySpan = element.querySelector(XPATH_READY_STATUS);
            item.isProcessed = (readySpan != null);

            if (item.title != null && !item.title.isEmpty()) {
                return item;
            }
            else
            {
                System.out.println("Item 被跳过: " + item.toString());
                return null;
            }
        } catch (Exception e) {
            System.err.println("解析Item失败: " + e.getMessage());
            return null;
        }
    }

    private void showPageNotification(Page page, String message) {
        try {
            String js = "msg => {" +
                "  let el = document.getElementById('crawler-notification');" +
                "  if (!el) {" +
                "    el = document.createElement('div');" +
                "    el.id = 'crawler-notification';" +
                "    el.style.position = 'fixed';" +
                "    el.style.top = '80px';" + // Avoid covering header if any
                "    el.style.left = '50%';" +
                "    el.style.transform = 'translateX(-50%)';" +
                "    el.style.backgroundColor = 'rgba(33, 150, 243, 0.9)';" + // Blue with transparency
                "    el.style.color = 'white';" +
                "    el.style.padding = '12px 20px';" +
                "    el.style.borderRadius = '8px';" +
                "    el.style.zIndex = '999999';" +
                "    el.style.fontSize = '14px';" +
                "    el.style.fontWeight = 'bold';" +
                "    el.style.boxShadow = '0 4px 12px rgba(0,0,0,0.15)';" +
                "    el.style.fontFamily = '-apple-system, BlinkMacSystemFont, \"Segoe UI\", Roboto, Helvetica, Arial, sans-serif';" +
                "    el.style.transition = 'all 0.3s ease';" +
                "    document.body.appendChild(el);" +
                "  }" +
                "  el.innerText = msg;" +
                "  el.style.display = 'block';" +
                "}";
            page.evaluate(js, message);
        } catch (Exception e) {
            // Ignore UI update errors
        }
    }

    private boolean tryClickLoadMore(Page page) {
        ElementHandle loadMore = page.querySelector(SELECTOR_LOAD_MORE);
        if (loadMore != null) {
            showPageNotification(page, "正在加载更多内容 (点击 Load More)...");
            loadMore.click();
            return true;
        }
        return false;
    }

    private boolean scrollToLoadMore(Page page) {
        try {
            showPageNotification(page, "正在向下滚动加载更多内容...");
            page.keyboard().press("End");
            System.out.println("已滚动到底部，等待加载...");
            PodCastUtil.waitForHeightStabilized(page, 10);
            return true;
        } catch (Exception e) {
            System.err.println("滚动失败: " + e.getMessage());
            return false;
        }
    }
    
    public void downloadEpisode(PodCastItem item, String savePath, String cnSavePath) {

        Browser.NewContextOptions contextOptions = new Browser.NewContextOptions()
            .setAcceptDownloads(true); // 使用临时目录

        BrowserContext context = browser.contexts().isEmpty() ? browser.newContext(contextOptions) : browser.contexts().get(0);
        Page page = context.newPage();
        try {
            String url = "https://podwise.ai" + item.linkString;
            showPageNotification(page, "打开播客详情页...");
            page.navigate(url);
            page.waitForLoadState(LoadState.NETWORKIDLE);
            page.waitForTimeout(1000);

            ElementHandle exportDiv = page.querySelector("//button/span[contains(text(),'Export')]");
            if (exportDiv != null) {
                showPageNotification(page, "打开导出菜单...");
                exportDiv.scrollIntoViewIfNeeded();
                page.waitForTimeout(500);
                exportDiv.click(new ElementHandle.ClickOptions().setForce(true));

                ElementHandle pdfButton = page.waitForSelector("//button/span[contains(text(),'PDF')]", 
                    new Page.WaitForSelectorOptions().setTimeout(SHORT_TIMEOUT_MS));

                if (pdfButton != null) {
                    showPageNotification(page, "选择 PDF...");
                    pdfButton.click();
                    ElementHandle downloadBtn = page.waitForSelector("//button[contains(text(),'Download')]", 
                        new Page.WaitForSelectorOptions().setTimeout(SHORT_TIMEOUT_MS));

                    if (downloadBtn != null) {

                        page.onDownload(download -> {
                            System.out.println("下载开始: " + download.url());
                            System.out.println("建议的文件名: " + download.suggestedFilename());
                            try {
                                showPageNotification(page, "下载开始: " + download.suggestedFilename());
                            } catch (Exception ignore) {}
                        });

                        // Ensure directory exists
                        java.nio.file.Path targetPath = Paths.get(savePath);
                        Files.createDirectories(targetPath.getParent());

                        showPageNotification(page, "点击下载...");
                        Download download = page.waitForDownload(() -> downloadBtn.click());
                        
                        // Check for download failure
                        if (download.failure() != null) {
                            throw new RuntimeException("Download reported failure: " + download.failure());
                        }

                        // Validate temporary file path
                        java.nio.file.Path tempPath = download.path();
                        if (tempPath == null || !tempPath.toFile().exists()) {
                            int maxTempRetries = 10;
                            for(int i=0; i<maxTempRetries; i++) {
                                if (tempPath != null && tempPath.toFile().exists()) break;
                                try { Thread.sleep(500); } catch(Exception e){}
                                tempPath = download.path();
                            }
                        }

                        // Manual copy with saveAs() fallback
                        if (tempPath != null && tempPath.toFile().exists()) {
                            System.out.println("临时文件路径: " + tempPath);
                            java.nio.file.Files.copy(tempPath, targetPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                            download.delete(); // Cleanup temporary file
                        } else {
                            // 尝试从默认下载目录查找
                            String userHome = System.getProperty("user.home");
                            java.nio.file.Path defaultDownloadPath = Paths.get(userHome, "Downloads", download.suggestedFilename());
                            
                            if (Files.exists(defaultDownloadPath)) {
                                System.out.println("从默认下载目录找到文件: " + defaultDownloadPath);
                                java.nio.file.Files.copy(defaultDownloadPath, targetPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                                // 可选：删除默认下载目录中的文件
                                Files.delete(defaultDownloadPath); 
                            } else {
                                System.out.println("默认下载目录未找到文件，尝试 saveAs");
                                download.saveAs(targetPath);
                            }
                        }
                        
                        // Wait for file system to flush and file to be available
                        java.io.File file = targetPath.toFile();
                        int maxRetries = 20;
                        int retryCount = 0;
                        while ((!file.exists() || file.length() == 0) && retryCount < maxRetries) {
                            try {
                                Thread.sleep(500); 
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                            retryCount++;
                        }
                        
                        if (!file.exists()) {
                             // Double check if it exists now
                             if (!java.nio.file.Files.exists(targetPath)) {
                                 throw new RuntimeException("File save failed, file not found after wait: " + savePath);
                             }
                        }

                        // Close page now as we have the file
                        if (!page.isClosed() && cnSavePath == null) {
                            page.close();
                        }

                        System.out.println("English download: " + savePath);
                        try {
                            showPageNotification(page, "下载完成: " + savePath);
                        } catch (Exception ignore) {}

                        if (cnSavePath != null) {
                             try { showPageNotification(page, "准备下载中文版本..."); } catch (Exception ignore) {}
                             downloadChineseVersionInternal(page, cnSavePath);
                             if (!page.isClosed()) {
                                 page.close();
                             }
                        }
                    }
                }
            }
            
        } catch (Exception e) {
            System.err.println("Download failed [" + item.title + "]: " + e.getMessage());
            try { showPageNotification(page, "下载失败: " + e.getMessage()); } catch (Exception ignore) {}
        } finally {
            if (!page.isClosed()) {
                page.close();
            }
        }
    }

    private void downloadChineseVersionInternal(Page page, String savePath) {
        try {
            ElementHandle langBtn = page.waitForSelector("//button[contains(text(),'Original')]", 
                new Page.WaitForSelectorOptions().setTimeout(SHORT_TIMEOUT_MS));
            
            if (langBtn != null) {
                try { showPageNotification(page, "切换语言..."); } catch (Exception ignore) {}
                langBtn.click();
                ElementHandle cnBtn = page.querySelector("//button[span[contains(text(),'简体中文')] and span[contains(text(),'Select')]]");
                
                if (cnBtn == null) {
                    ElementHandle cnOption = page.querySelector("//button/span[contains(text(),'简体中文')]");
                    if (cnOption != null) {
                        cnOption.click();
                        try {
                            cnBtn = page.waitForSelector("//button[span[contains(text(),'简体中文')] and span[contains(text(),'Select')]]",
                                new Page.WaitForSelectorOptions().setTimeout(DEFAULT_TIMEOUT_MS));
                        } catch(Exception e) {}
                    }
                }

                if (cnBtn != null) {
                    try { showPageNotification(page, "选择简体中文..."); } catch (Exception ignore) {}
                    cnBtn.click();
                    ElementHandle newDownloadBtn = page.waitForSelector("//button[contains(text(),'Download')]", 
                        new Page.WaitForSelectorOptions().setTimeout(SHORT_TIMEOUT_MS));
                    
                    if (newDownloadBtn != null) {
                        java.nio.file.Path targetPath = Paths.get(savePath);
                        if (targetPath.getParent() != null) {
                            java.nio.file.Files.createDirectories(targetPath.getParent());
                        }

                        try { showPageNotification(page, "点击下载（中文版本）..."); } catch (Exception ignore) {}
                        Download download = page.waitForDownload(() -> newDownloadBtn.click());
                        
                        if (download.failure() != null) {
                             throw new RuntimeException("CN Download reported failure: " + download.failure());
                        }

                        // Explicit wait
                        try {
                             Thread.sleep(1000); 
                        } catch (InterruptedException e) {
                             Thread.currentThread().interrupt();
                        }

                         // Try to get the path first
                        java.nio.file.Path tempPath = download.path();
                        if (tempPath == null || !tempPath.toFile().exists()) {
                             int maxTempRetries = 20;
                             for(int i=0; i<maxTempRetries; i++) {
                                 if (tempPath != null && tempPath.toFile().exists()) break;
                                 try { Thread.sleep(500); } catch(Exception e){}
                                 tempPath = download.path();
                             }
                        }

                        if (tempPath != null && tempPath.toFile().exists()) {
                             java.nio.file.Files.copy(tempPath, targetPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                             download.delete();
                        } else {
                             // 尝试从默认下载目录查找
                             String userHome = System.getProperty("user.home");
                             java.nio.file.Path defaultDownloadPath = Paths.get(userHome, "Downloads", download.suggestedFilename());
                             
                             // 增加等待重试
                             int waitDefaultFile = 0;
                             while (!Files.exists(defaultDownloadPath) && waitDefaultFile < 20) {
                                 try { Thread.sleep(500); } catch(Exception e){}
                                 waitDefaultFile++;
                             }

                             if (Files.exists(defaultDownloadPath)) {
                                 System.out.println("从默认下载目录找到文件(CN): " + defaultDownloadPath);
                                 java.nio.file.Files.copy(defaultDownloadPath, targetPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                                 // 可选：删除默认下载目录中的文件
                                 Files.delete(defaultDownloadPath); 
                             } else {
                                 download.saveAs(targetPath);
                             }
                        }
                        
                        // Wait for file system to flush and file to be available
                        java.io.File file = targetPath.toFile();
                        int maxRetries = 10;
                        int retryCount = 0;
                        while ((!file.exists() || file.length() == 0) && retryCount < maxRetries) {
                            try {
                                Thread.sleep(500); 
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                            retryCount++;
                        }
                        
                        if (!file.exists()) {
                             // Double check
                             if (!java.nio.file.Files.exists(targetPath)) {
                                 throw new RuntimeException("CN File save failed, file not found after wait: " + savePath);
                             }
                        }

                        System.out.println("Chinese download: " + savePath);
                        try { showPageNotification(page, "中文版本下载完成: " + savePath); } catch (Exception ignore) {}
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("CN download failed: " + e.getMessage());
            try { showPageNotification(page, "中文下载失败: " + e.getMessage()); } catch (Exception ignore) {}
        }
    }
}
