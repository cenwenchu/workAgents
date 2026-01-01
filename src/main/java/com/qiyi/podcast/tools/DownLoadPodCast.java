package com.qiyi.podcast.tools;

import java.io.File;
import java.io.FileWriter;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Download;
import com.microsoft.playwright.ElementHandle;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.LoadState;
import com.qiyi.agent.PodwiseAgent;
import com.qiyi.agent.PodwiseAgent.ModelType;
import com.qiyi.podcast.PodCastItem;
import com.qiyi.util.PodCastUtil;

public class DownLoadPodCast {

    // Constants
    private static final String DEFAULT_DOWNLOAD_DIR = "/Users/cenwenchu/Desktop/podCastItems/";
    private static final int DEFAULT_TIMEOUT_MS = 5*60*1000;
    private static final int SHORT_TIMEOUT_MS = 5000;

    // Prompts
    private static final String SUMMARY_PROMPT = "你是一位顶级的播客内容策略师，擅长同时进行**精准的传播提炼**与**深度的结构分析**。\n" + //
                        "\n" + //
                        "请基于我提供的播客文本，**同时、独立地**生成以下两部分内容。两部分应直接、并行地从原始对话中提取信息，**无需相互依赖或参考**。\n" + //
                        "\n" + //
                        "---\n" + //
                        "\n" + //
                        "### **第一部分：传播导读卡片 (Part A) | 目标：快速吸引与传播**\n" + //
                        "**角色**：你是社交媒体上的资深内容编辑，善于制造话题和提炼亮点。\n" + //
                        "**核心任务**：制作一份能让读者在60秒内被吸引并理解核心价值的内容。\n" + //
                        "**请按此框架创作**：\n" + //
                        "1.  **【标题】**：设计一个引人好奇、包含矛盾或惊喜点的主标题（例如：“AI耗电怪兽如何变身电网‘充电宝’？”）。\n" + //
                        "2.  **【一句话介绍】**：用一句话点明本期播客解决的**核心矛盾**或带来的**最大反转认知**。\n" + //
                        "3.  **【核心摘要卡片（3-4张）】**：\n" + //
                        "    *   **卡片结构**：\n" + //
                        "        *   **🔥 洞察**：一个尖锐的观点或发现（例如：“电网的‘最坏情况’规划，正在浪费一个三峡电站的容量”）。\n" + //
                        "        *   **💡 解读**：用最通俗的语言解释它意味着什么。\n" + //
                        "        *   **🎙️ 原声**：截取一句最能佐证该洞察的嘉宾原话（注明发言人）。\n" + //
                        "        *   **🚀 启发**：这对行业、政策或普通人有什么启示？\n" + //
                        "4.  **【行动呼唤】**：在结尾提出一个供读者思考的问题，或建议一个简单的后续行动（如：“想想你的业务能否借鉴这种‘灵活性’思维？”）。\n" + //
                        "\n" + //
                        "**语言风格**：精炼、有网感、带节奏，可直接用于社交媒体。\n" + //
                        "\n" + //
                        "---\n" + //
                        "\n" + //
                        "### **第二部分：深度分析报告 (Part B) | 目标：深度理解与存档**\n" + //
                        "**角色**：你是专注该领域的行业分析师或研究员。\n" + //
                        "**核心任务**：生成一份结构清晰、信息完整、便于引用和存档的分析文档。\n" + //
                        "**请按此结构撰写**：\n" + //
                        "1.  **【报告摘要】**：用一段话（200-300字）概括核心问题、技术/商业模式解决方案、潜在影响及主要挑战。\n" + //
                        "2.  **【逻辑图谱】**：以大纲形式，展示内容重构后的**核心逻辑链条**（例如：1. 问题本质 → 2. 可行性原理 → 3. 关键工具 → 4. 实施挑战 → 5. 未来愿景）。\n" + //
                        "3.  **【主题深度剖析】**：\n" + //
                        "    *   围绕逻辑图谱中的每个关键节点展开。\n" + //
                        "    *   每个节点下，采用 **“观点 + 支撑（数据/案例）+ 原文引述”** 的三段式进行阐述。\n" + //
                        "    *   在复杂或关键处，可插入【分析点】进行简短评注。\n" + //
                        "4.  **【信息附录】**：\n" + //
                        "    *   **术语表**：集中解释关键技术或商业术语。\n" + //
                        "    *   **关键对话实录**：按主题归类，摘录5-8段完整、高质量的对话片段（含发言人）。\n" + //
                        "\n" + //
                        "**语言风格**：严谨、系统、客观，适合专业读者。\n" + //
                        "\n" + //
                        "---\n" + //
                        "\n" + //
                        "### **【最终输出格式与要求】**\n" + //
                        "\n" + //
                        "# 文章标题:《[根据内容自拟主题]》\n" + //
                        "\n" + //
                        "## Part A：传播导读卡片（快速传播版）\n" + //
                        "（在此完整输出第一部分内容）\n" + //
                        "\n" + //
                        "---\n" + //
                        "\n" + //
                        "## Part B：深度分析报告（深度研究版）\n" + //
                        "（在此完整输出第二部分内容）\n" + //
                        "\n" + //
                        "**通用处理原则（对A、B部分均适用）**：\n" + //
                        "1.  **独立处理**：A、B两部分均需直接、独立地从原始文本中提取信息。\n" + //
                        "2.  **严格过滤**：剔除所有寒暄、重复、跑题及琐碎的个人叙述。\n" + //
                        "3.  **忠实原文**：所有观点、数据和引用必须源于文本，不可虚构。\n" + //
                        "4.  **优化重组**：按逻辑而非时间顺序重新组织信息。\n" + //
                        "\n" + //
                        "现在，请处理以下播客文本：\n";
    private static final String IMAGE_PROMPT = "针对这份播客摘要，生成一张图片，图片中包含摘要中的核心知识点";
    private static final String RENAME_PROMPT = "你是一个专业的文件名翻译助手。我有一组播客文件名，格式为 'CN_{ChannelName}_{Title}.pdf'。请识别每个文件名中的 '{Title}' 部分，如果是英文，将其翻译成中文；如果是中文，保持不变。请按以下格式返回翻译结果：\n1. 识别 '{Title}' 并翻译。\n2. 新文件名**只保留翻译后的 Title**，去掉 'CN_' 前缀和 '{ChannelName}' 部分。\n3. 确保新文件名以 .pdf 结尾。\n\n返回格式（每行一个）：\n原始文件名=新的文件名\n\n文件名列表如下：\n";

    // Selectors
    private static final String XPATH_LIBRARY = "//a[contains(@href,'dashboard/episodes')]";
    private static final String XPATH_FOLLOWING = "//div/button[contains(text(),'Following')]";
    private static final String XPATH_PODCAST_ITEM = "//div[./img[contains(@alt, 'Podcast Cover')] and .//a[contains(@href, 'dashboard')]]";
    private static final String XPATH_READY_STATUS = "//div/span[contains(text(),'Ready')]";
    private static final String SELECTOR_LOAD_MORE = "button:has-text('Load More')";

    // Member variables
    private Browser browser;
    public String DOWNLOAD_DIR_TOP;
    public String DOWNLOAD_DIR_ORIGINAL;
    public String DOWNLOAD_DIR_CN;
    public String DOWNLOAD_DIR_SUMMARY;
    public String DOWNLOAD_DIR_IMAGE;
    public String FILELIST_FILE;

    public DownLoadPodCast(Browser browser, String downloadSaveDir) {
        this.browser = browser;
        this.DOWNLOAD_DIR_TOP = (downloadSaveDir != null) ? downloadSaveDir : DEFAULT_DOWNLOAD_DIR;
        this.DOWNLOAD_DIR_ORIGINAL = this.DOWNLOAD_DIR_TOP + "original/";
        this.DOWNLOAD_DIR_CN = this.DOWNLOAD_DIR_TOP + "cn/";
        this.DOWNLOAD_DIR_SUMMARY = this.DOWNLOAD_DIR_TOP + "summary/";
        this.DOWNLOAD_DIR_IMAGE = this.DOWNLOAD_DIR_TOP + "Image/";
        this.FILELIST_FILE = this.DOWNLOAD_DIR_TOP + "filelist.txt";
    }

    /**
     * 执行自动化下载任务
     * 
     * @param maxProcessCount 最大处理（下载）的播客数量
     * @param maxTryTimes 列表加载最大重试次数
     * @param maxPageCount 最大加载的页面数量
     * @param onlyReadReadyPodCast 是否只处理状态为 Ready 的播客
     * @param modelType 使用的模型类型（用于后续的文件名翻译等）
     * @param maxBatchSize 批量重命名时的每批文件数量
     */
    public void performAutomationDownloadTasks(int maxProcessCount, int maxTryTimes,
        boolean onlyReadReadyPodCast, ModelType modelType, int maxBatchSize,int maxDuplicatePages) {
        if (browser == null) {
            log("浏览器未连接，请先连接浏览器");
            return;
        }

        BrowserContext context = browser.contexts().isEmpty() ? browser.newContext() : browser.contexts().get(0);
        Page page = context.newPage();
        page.setDefaultTimeout(DEFAULT_TIMEOUT_MS);

        log("创建新页面");

        try {
            page.navigate("https://podwise.ai/dashboard/episodes");

            if (!PodCastUtil.isLoggedIn(page)) {
                log("用户未登录，请手动登录后继续");
                PodCastUtil.waitForManualLogin(page);
            }

            List<PodCastItem> itemList = new ArrayList<>();
            List<String> itemNameList = new ArrayList<>();

            File folder = new File(DOWNLOAD_DIR_TOP);
            if (!folder.exists()) {
                folder.mkdirs();
            }

            loadProcessedItems(itemNameList);

            if (navigateToFollowing(page)) {
                if (onlyReadReadyPodCast) {
                    filterReadyPodcasts(page);
                }

                // Wait for initial list
                try {
                    page.waitForSelector(XPATH_PODCAST_ITEM, new Page.WaitForSelectorOptions().setTimeout(DEFAULT_TIMEOUT_MS));
                } catch (Exception e) {
                    log("未找到任何播客条目");
                }

                if (!new File(FILELIST_FILE).exists()) {
                    log("执行处理节点列表");
                    processNodeList(itemList, itemNameList, page, XPATH_PODCAST_ITEM, maxProcessCount, maxTryTimes,maxDuplicatePages);
                } else {
                    log(FILELIST_FILE + " 文件列表文件已存在，跳过处理节点列表，直接进入文件下载流程");
                }

                downloadPodcasts(context, true, modelType);
                
                // Batch rename chinese files after all downloads
                if (modelType != null) {
                    batchRenameChineseFiles(modelType, maxBatchSize);
                }
            }

        } catch (Exception e) {
            log("自动化任务出错: " + e.getMessage());
            e.printStackTrace();
        } finally {
            if (!page.isClosed()) page.close();
        }
    }

    private boolean navigateToFollowing(Page page) {
        try {
            ElementHandle libraryButton = page.waitForSelector(XPATH_LIBRARY, new Page.WaitForSelectorOptions().setTimeout(DEFAULT_TIMEOUT_MS));
            if (libraryButton != null) {
                log("找到Library按钮");
                // libraryButton.click(); 
                // 使用 evaluate 执行 click 以避免 Playwright 严格的可交互性检查导致阻塞
                libraryButton.evaluate("node => node.click()");
                ElementHandle followingBtn = page.waitForSelector(XPATH_FOLLOWING, new Page.WaitForSelectorOptions().setTimeout(DEFAULT_TIMEOUT_MS));
                if (followingBtn != null) {
                    followingBtn.evaluate("node => node.click()");
                    return true;
                }
            }
        } catch (Exception e) {
            log("导航到 Following 失败: " + e.getMessage());
        }
        return false;
    }

    private void filterReadyPodcasts(Page page) {
        try {
            page.locator("button:has-text('All')").click();
            page.waitForSelector("div[role='option']:has-text('ready')");
            page.locator("div[role='option']:has-text('ready')").click();
        } catch (Exception e) {
            log("筛选 Ready 状态失败: " + e.getMessage());
        }
    }

    private void loadProcessedItems(List<String> itemNameList) {
        File folder = new File(DOWNLOAD_DIR_ORIGINAL);
        if (!folder.exists()) {
            folder.mkdirs();
            return;
        }
        File[] files = folder.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isFile() && file.getName().endsWith(".pdf") && file.getName().contains("_")) {
                    // Original logic: file.getName().replace(".pdf", "").split("_")[1]
                    // Caution: split("_") might return array with length < 2 if filename is malformed
                    String[] parts = file.getName().replace(".pdf", "").split("_");
                    if (parts.length >= 2) {
                        itemNameList.add(parts[1]);
                    }
                }
            }
        }
    }

    private void processNodeList(List<PodCastItem> itemList, List<String> itemNameList,
                                 Page page, String preciseXpath, int maxProcessCount, int maxTryTimes,int maxDuplicatePages) {
        int validItemCount = 0;
        int tryTimes = 0;
        int lastProcessedIndex = 0;
        int consecutiveDuplicatePages = 0;

        if (maxDuplicatePages <=0)
            maxDuplicatePages = 10;

        do {
            List<ElementHandle> elements = page.querySelectorAll(preciseXpath);
            log("当前元素总数: " + elements.size() + ", 已处理索引: " + lastProcessedIndex + ", 重试次数: " + tryTimes);

            if (elements.size() > lastProcessedIndex) {
                tryTimes = 0; // Reset retry count as we found new items
                boolean hasNewValidItemInThisBatch = false;

                for (int i = lastProcessedIndex; i < elements.size(); i++) {
                    if (validItemCount >= maxProcessCount) break;

                    ElementHandle element = elements.get(i);
                    PodCastItem item = parsePodcastItem(element);

                    if (item != null && !itemNameList.contains(item.title)) {
                        if (item.isProcessed) {
                            validItemCount++;
                            itemList.add(item);
                            itemNameList.add(item.title);
                            hasNewValidItemInThisBatch = true;
                            log("找到有效Item: " + item.channelName + " - " + item.title + ",totalValid:" + validItemCount);
                        } else {
                            log("未处理Item: " + item.channelName + " - " + item.title);
                        }
                    } else if (item != null) {
                        log("重复Item: " + item.channelName + " - " + item.title);
                    }
                }
                
                if (!hasNewValidItemInThisBatch) {
                    consecutiveDuplicatePages++;
                    log("当前批次未发现新有效Item，连续空转次数: " + consecutiveDuplicatePages);
                    if (consecutiveDuplicatePages >= maxDuplicatePages) {
                        log("连续 " + maxDuplicatePages + " 次下拉未发现新数据，提前结束");
                        break;
                    }
                } else {
                    consecutiveDuplicatePages = 0;
                }

                lastProcessedIndex = elements.size();
            } else {
                tryTimes++;
                if (tryClickLoadMore(page)) {
                    page.waitForTimeout(2000); // Wait for content to start loading
                    continue; 
                }
            }

            if (validItemCount >= maxProcessCount) {
                log("已达到最大处理数量: " + maxProcessCount);
                break;
            }

            if (!scrollToLoadMore(page)) {
                if (tryTimes > maxTryTimes) {
                    log("达到最大重试次数，停止加载");
                    break;
                }
            }

        } while (tryTimes <= maxTryTimes && validItemCount < maxProcessCount);

        PodCastUtil.writeItemListToFile(itemList, FILELIST_FILE);
    }

    private PodCastItem parsePodcastItem(ElementHandle element) {
        PodCastItem item = new PodCastItem();
        try {
            // Parse Link
            // Original: :scope > a, :scope a:first-child
            ElementHandle link = element.querySelector(":scope a");
            if (link == null) link = element.querySelector("a");
            
            if (link != null) {
                item.linkString = link.getAttribute("href");
                String text = (String) link.evaluate("el => el.textContent.trim()");
                item.title = text.replaceAll("[\\\\/:*?\"<>|]", "");
            }

            // Parse Channel
            ElementHandle channel = element.querySelector("//img[contains(@alt,'Podcast cover')]/../span");
            if (channel != null) {
                item.channelName = (String) channel.evaluate("el => el.textContent.trim()");
            }

            // Check Ready Status
            ElementHandle readySpan = element.querySelector(XPATH_READY_STATUS);
            item.isProcessed = (readySpan != null);

            if (item.title != null && !item.title.isEmpty()) {
                return item;
            }
        } catch (Exception e) {
            log("解析Item失败: " + e.getMessage());
        }
        return null;
    }

    private boolean tryClickLoadMore(Page page) {
        ElementHandle loadMore = page.querySelector(SELECTOR_LOAD_MORE);
        if (loadMore != null) {
            loadMore.click();
            return true;
        }
        return false;
    }

    private boolean scrollToLoadMore(Page page) {
        try {
            // Ensure last element is visible to trigger infinite scroll if applicable
            // Original code scrolled the last element into view.
            // page.evaluate("window.scrollTo(0, document.body.scrollHeight)"); 
            
            // Replicating original behavior more closely + optimization
            page.keyboard().press("End");
            log("已滚动到底部，等待加载...");
            
            PodCastUtil.waitForHeightStabilized(page, 10);
            return true;
        } catch (Exception e) {
            log("滚动失败: " + e.getMessage());
            return false;
        }
    }

    private void downloadPodcasts(BrowserContext context, boolean needTranslateCN, ModelType modelType) {
        List<PodCastItem> itemList = PodCastUtil.readItemListFromFile(FILELIST_FILE);

        for (PodCastItem item : itemList) {
            String downloadPath = DOWNLOAD_DIR_ORIGINAL + item.channelName + "_" + item.title + ".pdf";
            if (new File(downloadPath).exists()) {
                log("文件已存在，跳过下载: " + downloadPath);
                continue;
            }

            if (item.isProcessed) {
                downloadSinglePodcast(context, item, downloadPath, needTranslateCN, modelType);
            }
        }

        new File(FILELIST_FILE).delete();
    }

    private void downloadSinglePodcast(BrowserContext context, PodCastItem item, String downloadPath, boolean needTranslateCN, ModelType modelType) {
        Page page = context.newPage();
        try {
            String url = "https://podwise.ai" + item.linkString;
            page.navigate(url);
            page.waitForLoadState(LoadState.NETWORKIDLE);
            page.waitForTimeout(1000); // Wait a bit for UI stability

            ElementHandle exportDiv = page.querySelector("//button/span[contains(text(),'Export')]");

            if (exportDiv != null) {
                exportDiv.scrollIntoViewIfNeeded();
                page.waitForTimeout(500);
                exportDiv.click(new ElementHandle.ClickOptions().setForce(true));

                ElementHandle pdfButton = page.waitForSelector("//button/span[contains(text(),'PDF')]", 
                    new Page.WaitForSelectorOptions().setTimeout(SHORT_TIMEOUT_MS));

                if (pdfButton != null) {
                    pdfButton.click();

                    ElementHandle downloadBtn = page.waitForSelector("//button[contains(text(),'Download')]", 
                        new Page.WaitForSelectorOptions().setTimeout(SHORT_TIMEOUT_MS));

                    if (downloadBtn != null) {
                        Download download = page.waitForDownload(() -> {
                            downloadBtn.click();
                        });
                        download.saveAs(Paths.get(downloadPath));
                        log("下载URL: " + download.url());
                        log("保存路径: " + downloadPath);

                        if (needTranslateCN) {
                            downloadChineseVersion(page, item);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log("下载处理出错 [" + item.title + "]: " + e.getMessage());
            e.printStackTrace();
        } finally {
            page.close();
            log(item.channelName + "," + item.title + " is processed");
        }
    }

    private String downloadChineseVersion(Page page, PodCastItem item) {
        String cnPath = null;
        try {
            ElementHandle langBtn = page.waitForSelector("//button[contains(text(),'Original')]", 
                new Page.WaitForSelectorOptions().setTimeout(SHORT_TIMEOUT_MS));
            
            if (langBtn != null) {
                langBtn.click();

                // Logic to find and click Chinese button
                // Try precise selector first
                ElementHandle cnBtn = page.querySelector("//button[span[contains(text(),'简体中文')] and span[contains(text(),'Select')]]");
                
                if (cnBtn == null) {
                    // Try looser selector
                    ElementHandle cnOption = page.querySelector("//button/span[contains(text(),'简体中文')]");
                    if (cnOption != null) {
                        cnOption.click();
                        // Wait for it to become 'Select' or active
                        try {
                            cnBtn = page.waitForSelector("//button[span[contains(text(),'简体中文')] and span[contains(text(),'Select')]]",
                                new Page.WaitForSelectorOptions().setTimeout(DEFAULT_TIMEOUT_MS)); // Wait longer for translation
                        } catch(Exception e) {
                            log("等待简体中文转换超时");
                        }
                    }
                }

                if (cnBtn != null) {
                    // If it wasn't clicked yet (first case)
                    // Or if we need to click 'Select' now
                    // The logic in original code was: if found direct Select -> click. 
                    // If found Option -> click Option -> wait for Select -> click Select.
                    // Let's assume cnBtn is now the 'Select' button.
                    cnBtn.click(); 

                    ElementHandle newDownloadBtn = page.waitForSelector("//button[contains(text(),'Download')]", 
                        new Page.WaitForSelectorOptions().setTimeout(SHORT_TIMEOUT_MS));
                    
                    if (newDownloadBtn != null) {
                        Download download = page.waitForDownload(() -> {
                            newDownloadBtn.click();
                        });
                        
                        cnPath = DOWNLOAD_DIR_CN + "CN_" + item.channelName + "_" + item.title + ".pdf";
                        download.saveAs(Paths.get(cnPath));
                        log("中文保存路径: " + cnPath);
                    }
                }
            }
        } catch (Exception e) {
            log("下载中文版失败: " + e.getMessage());
        }
        return cnPath;
    }

    /**
     * 处理已下载的文件（生成摘要、图片等）
     * 
     * @param maxProcessCount 最大处理文件数量，0表示处理所有文件
     * @param modelType 使用的大模型类型 (DEEPSEEK / GEMINI)
     * @param needGenerateImage 是否需要生成配图 (使用 Gemini)
     * @param isStreamingProcess 是否使用流式输出 (针对 DeepSeek)
     * @param downloadDir 下载目录
     * @param downloadDirSummary 摘要输出目录
     */
    public void processDownloadedFiles(String downloadDir, String downloadDirSummary, String downloadDirImage,
            int maxProcessCount, ModelType modelType, boolean needGenerateImage, boolean isStreamingProcess,int threadPoolSize) 
    {
        int processedCount = 0;
        int skipCount = 0;

        if (threadPoolSize <= 0) {
            threadPoolSize = 5;
        }
        
        try {
            File dir = new File(downloadDir);
            File outputDir = new File(downloadDirSummary);
            
            if (!dir.exists() || !dir.isDirectory()) {
                log("下载目录不存在: " + downloadDir);
                return;
            }
            if (!outputDir.exists()) outputDir.mkdirs();


            File[] files = dir.listFiles((d, name) -> name.toLowerCase().endsWith(".pdf"));
            if (files == null || files.length == 0) {
                log("下载目录中没有 PDF 文件");
                return;
            }

            log("找到 " + files.length + " 个 PDF 文件，开始生成中文摘要...");

            if (maxProcessCount == 0) {
                maxProcessCount = files.length;
            }
            final int finalMaxProcessCount = maxProcessCount;

            // 使用线程池并行处理
            
            ExecutorService executor = Executors.newFixedThreadPool(threadPoolSize);
            List<Future<?>> futures = new ArrayList<>();

            for (File pdfFile : files) {
                if (processedCount >= finalMaxProcessCount) break;
           
                String pdfFileName = pdfFile.getName();

                String outputFileName = pdfFileName.replace(".pdf", "_summary.txt");
                String outputFilePath = outputDir.getPath() + "/" + outputFileName;
                File outputFile = new File(outputFilePath);

                if (outputFile.exists()) {
                    skipCount++;
                    log("摘要文件已存在，跳过: " + outputFileName);
                } else {
                    processedCount++;
                    final int currentProcessedCount = processedCount;
                    final int currentSkipCount = skipCount;
                    
                    futures.add(executor.submit(() -> {
                        log("正在处理文件: " + pdfFileName);
                        processSingleSummary(pdfFile, outputFile, modelType, isStreamingProcess);

                        log("最大处理文件数: " + finalMaxProcessCount + 
                        " ，已经处理完成第 " + currentProcessedCount + " 个任务，已跳过 " 
                        + currentSkipCount + " 个文件，剩余待处理 " + (finalMaxProcessCount - currentProcessedCount - currentSkipCount)
                        + "，文件目录中文件数量为: " + files.length);

                        if (needGenerateImage && outputFile.exists()) {
                            PodCastUtil.generateImageWithGemini(outputFile.getAbsolutePath(), downloadDirImage, IMAGE_PROMPT);
                        }
                    }));
                }
            }
            
            // 等待所有任务完成
            for (Future<?> future : futures) {
                try {
                    future.get();
                } catch (Exception e) {
                    log("任务执行异常: " + e.getMessage());
                }
            }
            
            executor.shutdown();
            log("所有文件处理完成");

        } catch (Exception e) {
            log("处理下载文件时出错: " + e.getMessage());
        }
    }

    public void batchRenameChineseFiles(ModelType modelType, int maxBatchSize) {
        File dir = new File(DOWNLOAD_DIR_CN);
        if (!dir.exists() || !dir.isDirectory()) {
            log("中文下载目录不存在: " + DOWNLOAD_DIR_CN);
            return;
        }

        File[] files = dir.listFiles((d, name) -> name.toLowerCase().endsWith(".pdf") && name.startsWith("CN_"));
        if (files == null || files.length == 0) {
            log("中文下载目录中没有符合格式的文件");
            return;
        }

        log("开始批量翻译重命名中文版文件，共 " + files.length + " 个文件");
        
        StringBuilder fileListBuilder = new StringBuilder();
        List<File> fileBatch = new ArrayList<>();
        int batchSize = maxBatchSize; // Process 50 files at a time

        for (int i = 0; i < files.length; i++) {
            fileListBuilder.append(files[i].getName()).append("\n");
            fileBatch.add(files[i]);

            if ((i + 1) % batchSize == 0 || i == files.length - 1) {
                processBatchRename(fileBatch, fileListBuilder.toString(), modelType);
                fileListBuilder.setLength(0);
                fileBatch.clear();
            }
        }
    }

    private void processBatchRename(List<File> files, String fileListStr, ModelType modelType) {
        try {
            String prompt = RENAME_PROMPT + fileListStr;
            String response = "";

            log("正在请求批量翻译文件名...");

            if (modelType == ModelType.GEMINI || modelType == ModelType.ALL) {
                response = PodCastUtil.chatWithGemini(prompt).trim();
            } else if (modelType == ModelType.DEEPSEEK) {
                response = PodCastUtil.chatWithDeepSeek(prompt).trim();
            }

            // Clean up response code blocks if any
            response = response.replace("```", "");
            
            String[] lines = response.split("\n");
            for (String line : lines) {
                line = line.trim();
                if (line.isEmpty() || !line.contains("=")) continue;

                String[] parts = line.split("=", 2);
                if (parts.length == 2) {
                    String originalName = parts[0].trim();
                    String newName = parts[1].trim();
                    
                    if (!originalName.equals(newName) && newName.endsWith(".pdf")) {
                         // Check if valid filename
                        if (newName.matches(".*[\\\\/:*?\"<>|].*")) {
                            log("跳过非法文件名: " + newName);
                            continue;
                        }

                        // Find the file object matching originalName
                        File fileToRename = null;
                        for(File f : files) {
                            if(f.getName().equals(originalName)) {
                                fileToRename = f;
                                break;
                            }
                        }

                        //保存的新文件的目录是老文件目录的下级目录，目录名为中文版
                        if (fileToRename != null && fileToRename.exists()) {
                            File cnDir = new File(fileToRename.getParent(), "中文版");
                            if (!cnDir.exists()) {
                                cnDir.mkdirs();
                            }
                            File newFile = new File(cnDir, newName);
                            try {
                                java.nio.file.Files.copy(fileToRename.toPath(), newFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                                log("复制并重命名成功: " + originalName + " -> " + newFile.getPath());
                            } catch (Exception e) {
                                log("复制并重命名失败: " + originalName + " -> " + newName + " Error:" + e.getMessage());
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log("批量重命名出错: " + e.getMessage());
        }
    }

    private void processSingleSummary(File pdfFile, File outputFile, ModelType modelType, boolean isStreamingProcess) {
        try {
            String summary = null;

            switch (modelType) {
                case GEMINI:
                    summary = PodCastUtil.generateSummaryWithGemini(pdfFile, SUMMARY_PROMPT);
                    break;
                case DEEPSEEK:
                    summary = PodCastUtil.generateContentWithDeepSeekByFile(pdfFile,SUMMARY_PROMPT,isStreamingProcess);
                    break;
                case ALL:
                    summary = "-- DeepSeek摘要 --\n" + 
                              PodCastUtil.generateContentWithDeepSeekByFile(pdfFile,SUMMARY_PROMPT,isStreamingProcess) +
                              "\n\n\n\n-- Gemini 摘要 --\n" +
                              PodCastUtil.generateSummaryWithGemini(pdfFile, SUMMARY_PROMPT);
                    break;
            }

            if (summary != null && !summary.isEmpty()) {
                try (FileWriter writer = new FileWriter(outputFile)) {
                    writer.write(summary);
                }
                log("成功生成摘要文件: " + outputFile.getName());
                Thread.sleep(1000); // Rate limit
            } else {
                log("生成摘要失败，跳过: " + pdfFile.getName());
            }
        } catch (Exception e) {
            log("生成摘要出错 " + pdfFile.getName() + ": " + e.getMessage());
        }
    }

    private void log(String msg) {
        System.out.println(msg);
    }
}
