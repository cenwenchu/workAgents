package com.qiyi.util;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import com.qiyi.podcast.ModelType;

public class PFileUtil {

    private static final String RENAME_PROMPT = "你是一个专业的文件名翻译助手。我有一组播客文件名，格式可能为 '{ChannelName}_{Title}.pdf' 或其他格式。请识别每个文件名中的 '{Title}' 部分（即去掉开头的频道名后剩下的部分，或者如果是简单文件名则直接翻译），如果是英文，将其翻译成中文；如果是中文，保持不变。请按以下格式返回翻译结果：\n1. 识别文件名核心含义并翻译。\n2. 新文件名**只保留翻译后的 Title**，去掉前面的 '{ChannelName}' 部分（如果有）。\n3. 确保新文件名保持原扩展名（.pdf 或 .txt）。\n\n返回格式（每行一个）：\n原始文件名=新的文件名\n\n文件名列表如下：\n";

    public static void batchRenameChineseFiles(String renameFileDir,ModelType modelType, int maxBatchSize) {
        File dir = new File(renameFileDir);
        if (!dir.exists() || !dir.isDirectory()) {
            log("文件目录不存在: " + renameFileDir);
            return;
        }

        // 修改过滤器：处理所有 pdf/txt 文件，不再限制 CN_ 前缀
        File[] files = dir.listFiles((d, name) -> (name.toLowerCase().endsWith(".pdf") || name.toLowerCase().endsWith(".txt")));

        if (files == null || files.length == 0) {
            log("目录中没有符合格式的文件");
            return;
        }

        log("开始批量翻译重命名文件，共 " + files.length + " 个文件");
        
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

    private static void processBatchRename(List<File> files, String fileListStr, ModelType modelType) {
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
                    
                    if (!originalName.equals(newName) && (newName.endsWith(".pdf") || newName.endsWith(".txt"))) {
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

                        //直接在原目录重命名
                        if (fileToRename != null && fileToRename.exists()) {
                            File newFile = new File(fileToRename.getParent(), newName);
                            try {
                                java.nio.file.Files.move(fileToRename.toPath(), newFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                                log("文件重命名成功: " + originalName + " -> " + newName);
                            } catch (Exception e) {
                                log("文件重命名失败: " + originalName + " -> " + newName + " Error:" + e.getMessage());
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log("批量重命名出错: " + e.getMessage());
        }
    }
    
    private static void log(String msg) {
        System.out.println(msg);
    }
}
