package com.qiyi.podcast.service;

import com.qiyi.util.LLMUtil.ModelType;
import com.qiyi.util.LLMUtil;

import java.io.File;
import java.io.FileWriter;
import java.util.List;

public class PodcastProcessor {

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

    private final FileService fileService;

    public PodcastProcessor(FileService fileService) {
        this.fileService = fileService;
    }

    public void generateSummary(File pdfFile, File outputFile, ModelType modelType, boolean isStreamingProcess) {
        try {
            String summary = null;
            switch (modelType) {
                    case GEMINI:
                        summary = LLMUtil.generateSummaryWithGemini(pdfFile, SUMMARY_PROMPT);
                        break;
                    case DEEPSEEK:
                        summary = LLMUtil.generateContentWithDeepSeekByFile(pdfFile, SUMMARY_PROMPT, isStreamingProcess);
                        break;
                    case ALIYUN:
                        summary = LLMUtil.generateContentWithAliyunByFile(pdfFile, SUMMARY_PROMPT);
                        break;
                    case ALIYUN_VL:
                        //summary = LLMUtil.generateContentWithAliyunByFile(pdfFile, SUMMARY_PROMPT);
                        break;
                    case ALL:
                        summary = "-- DeepSeek摘要 --\n" + 
                                  LLMUtil.generateContentWithDeepSeekByFile(pdfFile, SUMMARY_PROMPT, isStreamingProcess) +
                                  "\n\n\n\n-- Gemini 摘要 --\n" +
                                  LLMUtil.generateSummaryWithGemini(pdfFile, SUMMARY_PROMPT);
                        break;
                }

            if (summary != null && !summary.isEmpty()) {
                try (FileWriter writer = new FileWriter(outputFile)) {
                    writer.write(summary);
                }
                System.out.println("成功生成摘要文件: " + outputFile.getName());
                // Rate limit
                try { Thread.sleep(1000); } catch (InterruptedException e) {} 
            } else {
                System.out.println("生成摘要失败，跳过: " + pdfFile.getName());
            }
        } catch (Exception e) {
            System.err.println("生成摘要出错 " + pdfFile.getName() + ": " + e.getMessage());
        }
    }

    public void generateImage(File summaryFile, String outputDir) {
        LLMUtil.generateImageWithGemini(summaryFile.getAbsolutePath(), outputDir, IMAGE_PROMPT);
    }

    public void batchRenameFiles(List<File> files, ModelType modelType) {
        if (files == null || files.isEmpty()) return;

        StringBuilder fileListBuilder = new StringBuilder();
        for (File f : files) {
            fileListBuilder.append(f.getName()).append("\n");
        }

        try {
            String prompt = RENAME_PROMPT + fileListBuilder.toString();
            String response = "";

            System.out.println("正在请求批量翻译文件名...");

            if (modelType == ModelType.GEMINI || modelType == ModelType.ALL) {
                response = LLMUtil.chatWithGemini(prompt).trim();
            } else if (modelType == ModelType.DEEPSEEK) {
                response = LLMUtil.chatWithDeepSeek(prompt).trim();
            } else if (modelType == ModelType.ALIYUN) {
                response = LLMUtil.chatWithAliyun(prompt).trim();
            }

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
                         if (newName.matches(".*[\\\\/:*?\"<>|].*")) {
                            System.out.println("跳过非法文件名: " + newName);
                            continue;
                        }
                        
                        // Find matching file
                        File fileToRename = null;
                        for(File f : files) {
                            if(f.getName().equals(originalName)) {
                                fileToRename = f;
                                break;
                            }
                        }
                        
                        if (fileToRename != null) {
                            fileService.renameFile(fileToRename, newName);
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("批量重命名出错: " + e.getMessage());
        }
    }
}
