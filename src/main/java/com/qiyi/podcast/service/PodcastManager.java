package com.qiyi.podcast.service;

import com.microsoft.playwright.Browser;
import com.qiyi.util.LLMUtil.ModelType;
import com.qiyi.podcast.PodCastItem;
import com.qiyi.util.PFileUtil;
import com.qiyi.util.DingTalkUtil;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.qiyi.tools.ToolContext;

public class PodcastManager {

    private final PodcastCrawler crawler;
    private final PodcastProcessor processor;
    private final FileService fileService;

    public PodcastManager(Browser browser) {
        this.fileService = new FileService();
        this.crawler = new PodcastCrawler(browser);
        this.processor = new PodcastProcessor(fileService);
    }

    public int runDownloadTask(int maxProcessCount, int maxTryTimes, int maxDuplicatePages, boolean onlyReadReady, ModelType modelType, int maxRenameBatchSize) {
        System.out.println("Starting Download Task...");
        int downloadedCount = 0;
        
        List<String> processedNames = fileService.getProcessedItemNames();
        List<PodCastItem> itemsToDownload = new ArrayList<>();

        if (!fileService.fileListExists()) {
             itemsToDownload = crawler.scanForNewEpisodes(maxProcessCount, maxTryTimes, maxDuplicatePages, processedNames);
             fileService.writeItemListToFile(itemsToDownload);
        } else {
            System.out.println("File list exists, skipping scan.");
            itemsToDownload = fileService.readItemListFromFile();
            // 如果读取的文件列表数量超过了本次要求的最大数量，进行截断
            if (itemsToDownload.size() > maxProcessCount) {
                System.out.println("Pending list size (" + itemsToDownload.size() + ") exceeds maxProcessCount (" + maxProcessCount + "), truncating...");
                itemsToDownload = new ArrayList<>(itemsToDownload.subList(0, maxProcessCount));
            }
        }

        // Filter already downloaded
        // Note: scanForNewEpisodes already checks against processedNames, but fileList might contain old items.
        // We should check file existence.
        
        for (PodCastItem item : itemsToDownload) {
             String downloadPath = fileService.getDownloadDirOriginal() + item.channelName + "_" + item.title + ".pdf";
             if (new File(downloadPath).exists()) {
                 System.out.println("File exists, skipping: " + downloadPath);
                 continue;
             }
             
             if (item.isProcessed) {
                 String cnPath = null;
                 // Assuming we want CN version if modelType is set or always? 
                 // Original logic passed `true` for needTranslateCN to `downloadPodcasts`
                 // and it called `downloadChineseVersion`.
                 // We will define cnPath.
                 String cnFilename = "CN_" + item.channelName + "_" + item.title + ".pdf";
                 cnPath = fileService.getDownloadDirCn() + cnFilename;
                 
                 crawler.downloadEpisode(item, downloadPath, cnPath);

                 downloadedCount++;
             }
        }
        
        fileService.deleteFileList();
        
        if (modelType != null && downloadedCount > 0) {
            runRenameTask(modelType, maxRenameBatchSize);
        }

        return downloadedCount;
    }

    public void runRenameTask(ModelType modelType, int maxBatchSize) {
        File[] files = fileService.getCnFiles();
        if (files == null || files.length == 0) {
            System.out.println("No CN files to rename.");
            return;
        }
        
        System.out.println("Starting Rename Task for " + files.length + " files.");
        List<File> batch = new ArrayList<>();
        for (int i = 0; i < files.length; i++) {
            batch.add(files[i]);
            if ((i + 1) % maxBatchSize == 0 || i == files.length - 1) {
                processor.batchRenameFiles(batch, modelType);
                batch.clear();
            }
        }
    }

    public void runProcessingTask(int maxProcessCount, ModelType modelType, boolean needGenerateImage, boolean isStreaming, int threadPoolSize) {
        runProcessingTask(maxProcessCount, modelType, needGenerateImage, isStreaming, threadPoolSize, null);
    }

    public void runProcessingTask(int maxProcessCount, ModelType modelType, boolean needGenerateImage, boolean isStreaming, int threadPoolSize, ToolContext context) {
        System.out.println("Starting Processing (Summary/Image) Task...");
        
        //File[] files = fileService.getOriginalFiles(); // Or get CN files?
        // Original logic: `downLoadPodCastTask.processDownloadedFiles(downLoadPodCastTask.DOWNLOAD_DIR_CN, ...)`
        // So it processes CN files or Original? 
        // Wait, `PodwiseAgent` called it with `DOWNLOAD_DIR_CN`.
        // So we should process CN files if available, or Original if not?
        // Let's assume we process whatever is in the input dir.
        // But `FileService` has specific getters. 
        // Let's use `DOWNLOAD_DIR_CN` as per original agent call.
        
        // However, `getOriginalFiles` returns from `original/`. `getCnFiles` returns from `cn/` and filters by `CN_` prefix.
        // If we want to process files in `cn/`, we should use that.
        // But wait, `processDownloadedFiles` in `DownLoadPodCast` listed files ending with `.pdf`.
        // And `PodwiseAgent` passed `DOWNLOAD_DIR_CN`.
        
        File dir = new File(fileService.getDownloadDirCn());
        File[] targetFiles = dir.listFiles((d, name) -> name.toLowerCase().endsWith(".pdf"));
        
        if (targetFiles == null || targetFiles.length == 0) {
            System.out.println("No PDF files found in " + dir.getPath());
            return;
        }

        try {
            DingTalkUtil.sendTextMessageToEmployees(DingTalkUtil.PODCAST_ADMIN_USERS, "开始分析生成摘要，待处理文件数: " + targetFiles.length);
        } catch (Exception e) {
            e.printStackTrace();
        }
        
        if (maxProcessCount <= 0) maxProcessCount = targetFiles.length;
        
        ExecutorService executor = Executors.newFixedThreadPool(threadPoolSize > 0 ? threadPoolSize : 5);
        List<Future<?>> futures = new ArrayList<>();
        
        int processedCount = 0;
        
        for (File file : targetFiles) {
            if (processedCount >= maxProcessCount) break;
            
            String summaryFileName = file.getName().replace(".pdf", "_summary.txt");
            File summaryFile = new File(fileService.getDownloadDirSummary(), summaryFileName);
            
            if (summaryFile.exists()) {
                System.out.println("Summary exists, skipping: " + summaryFileName);
                continue;
            }
            
            processedCount++;
            futures.add(executor.submit(() -> {
                processor.generateSummary(file, summaryFile, modelType, isStreaming);
                if (summaryFile.exists()) {
                    if (needGenerateImage) {
                        processor.generateImage(summaryFile, fileService.getDownloadDirImage());
                    }
                    // Move source file to processed directory
                    fileService.moveFileToProcessed(file);
                }
            }));
        }
        
        for (Future<?> f : futures) {
            try { f.get(); } catch (Exception e) { e.printStackTrace(); }
        }

        PFileUtil.batchRenameChineseFiles(fileService.getDownloadDirSummary(),modelType, 50);
        
        executor.shutdown();
        System.out.println("Processing Task Completed.");
        try {
            DingTalkUtil.sendTextMessageToEmployees(DingTalkUtil.PODCAST_ADMIN_USERS, "播客摘要分析生成完成，处理文件数: " + processedCount);
        } catch (Exception e) {
            e.printStackTrace();
        }
        
        if (context != null) {
            try {
                context.sendText("播客摘要分析生成完成，处理文件数: " + processedCount);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
}
