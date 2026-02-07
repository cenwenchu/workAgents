package com.qiyi.service.podcast.service;

import com.qiyi.config.AppConfig;
import com.qiyi.service.podcast.PodCastItem;
import com.qiyi.util.PodCastUtil;
import com.qiyi.util.AppLog;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

public class FileService {

    private String downloadDirTop;
    private final String downloadDirOriginal;
    private final String downloadDirCn;
    private final String downloadDirSummary;
    private final String downloadDirProcessed;
    private final String downloadDirImage;
    private final String filelistFile;

    public FileService() {
        this.downloadDirTop = AppConfig.getInstance().getPodcastDownloadDir();

        if (!this.downloadDirTop.endsWith("/")) {
            this.downloadDirTop += "/";
        }

        this.downloadDirOriginal = this.downloadDirTop + "original/";
        this.downloadDirCn = this.downloadDirTop + "cn/";
        this.downloadDirSummary = this.downloadDirTop + "summary/";
        this.downloadDirProcessed = this.downloadDirTop + "processed/";
        this.downloadDirImage = this.downloadDirTop + "Image/";
        this.filelistFile = this.downloadDirTop + "filelist.txt";
        
        initDirectories();
    }

    private void initDirectories() {
        createDirIfNotExist(downloadDirTop);
        createDirIfNotExist(downloadDirOriginal);
        createDirIfNotExist(downloadDirCn);
        createDirIfNotExist(downloadDirSummary);
        createDirIfNotExist(downloadDirProcessed);
        createDirIfNotExist(downloadDirImage);
    }

    private void createDirIfNotExist(String path) {
        File dir = new File(path);
        if (!dir.exists()) {
            dir.mkdirs();
        }
    }

    public String getDownloadDirOriginal() {
        return downloadDirOriginal;
    }

    public String getDownloadDirCn() {
        return downloadDirCn;
    }

    public String getDownloadDirSummary() {
        return downloadDirSummary;
    }

    public String getDownloadDirProcessed() {
        return downloadDirProcessed;
    }

    public String getDownloadDirImage() {
        return downloadDirImage;
    }

    public String getFilelistFile() {
        return filelistFile;
    }

    public boolean fileListExists() {
        return new File(filelistFile).exists();
    }

    public void deleteFileList() {
        new File(filelistFile).delete();
    }

    public void writeItemListToFile(List<PodCastItem> itemList) {
        PodCastUtil.writeItemListToFile(itemList, filelistFile);
    }

    public List<PodCastItem> readItemListFromFile() {
        return PodCastUtil.readItemListFromFile(filelistFile);
    }

    public List<String> getProcessedItemNames() {
        List<String> itemNameList = new ArrayList<>();
        File folder = new File(downloadDirOriginal);
        if (!folder.exists()) {
            return itemNameList;
        }
        File[] files = folder.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isFile() && file.getName().endsWith(".pdf") && file.getName().contains("_")) {
                    String[] parts = file.getName().replace(".pdf", "").split("_");
                    if (parts.length >= 2) {
                        itemNameList.add(parts[1]);
                    }
                }
            }
        }
        return itemNameList;
    }
    
    public File[] getOriginalFiles() {
        File dir = new File(downloadDirOriginal);
        if (!dir.exists()) return new File[0];
        return dir.listFiles((d, name) -> name.toLowerCase().endsWith(".pdf"));
    }

    public File[] getCnFiles() {
        File dir = new File(downloadDirCn);
        if (!dir.exists()) return new File[0];
        return dir.listFiles((d, name) -> name.toLowerCase().endsWith(".pdf") && name.startsWith("CN_"));
    }
    
    public void renameFile(File originalFile, String newName) {
         if (originalFile != null && originalFile.exists()) {
            File cnDir = new File(originalFile.getParent(), "中文版");
            if (!cnDir.exists()) {
                cnDir.mkdirs();
            }
            File newFile = new File(cnDir, newName);
            try {
                Files.copy(originalFile.toPath(), newFile.toPath(), StandardCopyOption.REPLACE_EXISTING);

                // 去掉老文件的CN_前缀
                if (originalFile.getName().startsWith("CN_")) {
                    String cleanName = originalFile.getName().substring(3);
                    File newSourceFile = new File(originalFile.getParent(), cleanName);
                    try {
                        java.nio.file.Files.move(originalFile.toPath(), newSourceFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        AppLog.info("原文件重命名成功: " + originalFile.getName() + " -> " + cleanName);
                    } catch (Exception ex) {
                        AppLog.info("原文件重命名失败: " + originalFile.getName() + " Error:" + ex.getMessage());
                    }
                }

                AppLog.info("复制并重命名成功: " + originalFile.getName() + " -> " + newFile.getPath());
            } catch (IOException e) {
                AppLog.error("复制并重命名失败: " + originalFile.getName() + " -> " + newName + " Error:" + e.getMessage());
            }
        }
    }

    public void moveFileToProcessed(File file) {
        if (file == null || !file.exists()) return;
        File destDir = new File(downloadDirProcessed);
        if (!destDir.exists()) destDir.mkdirs();
        File destFile = new File(destDir, file.getName());
        try {
            Files.move(file.toPath(), destFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            AppLog.info("Moved processed file to: " + destFile.getPath());
        } catch (IOException e) {
            AppLog.error("Failed to move file to processed: " + e.getMessage());
        }
    }
}
