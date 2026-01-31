package com.qiyi.autoweb;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * AutoWeb 调试与工具方法集合。
 * 负责缓存/调试目录清理、调试内容落盘、LLM 调用超时保护。
 */
class AutoWebAgentUtils {
    /**
     * 清理并重建 debug 目录。
     * 核心逻辑：递归删除目录下文件，保留根目录，最后确保目录存在。
     */
    static void cleanDebugDirectory() {
        try {
            Path debugDir = Paths.get(System.getProperty("user.dir"), "autoweb", "debug");
            if (Files.exists(debugDir)) {
                // 核心逻辑：倒序删除目录树，避免先删父目录失败
                System.out.println("Cleaning debug directory: " + debugDir.toAbsolutePath());
                try (java.util.stream.Stream<Path> walk = Files.walk(debugDir)) {
                    walk.sorted(java.util.Comparator.reverseOrder())
                        .forEach(p -> {
                            if (p == null) return;
                            if (p.equals(debugDir)) return;
                            try { Files.deleteIfExists(p); } catch (Exception ignored) {}
                        });
                }
            }
            // 核心逻辑：确保目录存在供后续写入
            Files.createDirectories(debugDir);
        } catch (IOException e) {
            System.err.println("Warning: Failed to clean debug directory: " + e.getMessage());
        }
    }

    /**
     * 清理并重建 cache 目录。
     * 核心逻辑：与 debug 同样的递归清理策略。
     */
    static void cleanCacheDirectory() {
        try {
            Path cacheDir = Paths.get(System.getProperty("user.dir"), "autoweb", "cache");
            if (Files.exists(cacheDir)) {
                // 核心逻辑：倒序删除目录树，避免父目录非空
                System.out.println("Cleaning cache directory: " + cacheDir.toAbsolutePath());
                try (java.util.stream.Stream<Path> walk = Files.walk(cacheDir)) {
                    walk.sorted(java.util.Comparator.reverseOrder())
                            .forEach(p -> {
                                if (p == null) return;
                                if (p.equals(cacheDir)) return;
                                try { Files.deleteIfExists(p); } catch (Exception ignored) {}
                            });
                }
            }
            // 核心逻辑：确保目录存在供后续写入
            Files.createDirectories(cacheDir);
        } catch (IOException e) {
            System.err.println("Warning: Failed to clean cache directory: " + e.getMessage());
        }
    }

    /**
     * 保存 HTML 与代码的调试产物。
     * 核心逻辑：按固定文件名落盘 raw/cleaned/code，并输出日志。
     */
    static void saveDebugArtifacts(String rawHtml, String cleanedHtml, String code, java.util.function.Consumer<String> uiLogger) {
        try {
            java.nio.file.Path debugDir = java.nio.file.Paths.get(System.getProperty("user.dir"), "autoweb", "debug");
            if (!java.nio.file.Files.exists(debugDir)) {
                java.nio.file.Files.createDirectories(debugDir);
            }
            
            if (rawHtml != null) {
                // 核心逻辑：落盘原始 HTML
                java.nio.file.Files.write(debugDir.resolve("debug_raw.html"), rawHtml.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            if (cleanedHtml != null) {
                // 核心逻辑：落盘清洗后 HTML
                java.nio.file.Files.write(debugDir.resolve("debug_cleaned.html"), cleanedHtml.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            if (code != null) {
                 // 核心逻辑：落盘 Groovy 代码
                 java.nio.file.Path codePath = debugDir.resolve("debug_code.groovy");
                 java.nio.file.Files.write(codePath, code.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                 if (uiLogger != null) {
                     uiLogger.accept("Debug code saved to: " + codePath.toAbsolutePath());
                 }
            }
            
            if (rawHtml != null || cleanedHtml != null) {
                // 核心逻辑：统一提示调试目录位置
                if (uiLogger != null) {
                    uiLogger.accept("Debug HTMLs saved to: " + debugDir.toAbsolutePath());
                }
            }
        } catch (Exception ex) {
            if (uiLogger != null) {
                uiLogger.accept("Failed to save debug artifacts: " + ex.getMessage());
            }
        }
    }

    /**
     * 保存特定模型/标签的调试代码变体。
     * 核心逻辑：模型名与标签做安全化，再组合时间戳写入文件。
     */
    static void saveDebugCodeVariant(String code, String modelName, String tag, java.util.function.Consumer<String> uiLogger) {
        if (code == null) return;
        try {
            java.nio.file.Path debugDir = java.nio.file.Paths.get(System.getProperty("user.dir"), "autoweb", "debug");
            if (!java.nio.file.Files.exists(debugDir)) {
                java.nio.file.Files.createDirectories(debugDir);
            }
            String safeModel = modelName == null ? "UNKNOWN" : modelName.trim().replaceAll("[^A-Za-z0-9_\\-]", "_");
            if (safeModel.isEmpty()) safeModel = "UNKNOWN";
            String safeTag = tag == null ? "code" : tag.trim().replaceAll("[^A-Za-z0-9_\\-]", "_");
            if (safeTag.isEmpty()) safeTag = "code";
            // 核心逻辑：用时间戳区分多次输出
            String ts = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS"));
            java.nio.file.Path codePath = debugDir.resolve("debug_code_" + safeModel + "_" + safeTag + "_" + ts + ".groovy");
            java.nio.file.Files.write(codePath, code.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            if (uiLogger != null) {
                uiLogger.accept("Debug code saved to: " + codePath.toAbsolutePath());
            }
        } catch (Exception ex) {
            if (uiLogger != null) {
                uiLogger.accept("Failed to save debug code: " + ex.getMessage());
            }
        }
    }

    /**
     * 清理目录下所有文件（不删除目录本身）。
     * 核心逻辑：遍历文件并删除，返回删除数量并记录日志。
     */
    static int clearDirFiles(java.nio.file.Path dir, java.util.function.Consumer<String> uiLogger) {
        if (dir == null) return 0;
        try {
            if (!java.nio.file.Files.exists(dir)) return 0;
            if (!java.nio.file.Files.isDirectory(dir)) return 0;
            java.util.List<java.nio.file.Path> files;
            try (java.util.stream.Stream<java.nio.file.Path> s = java.nio.file.Files.walk(dir)) {
                files = s.filter(p -> p != null && java.nio.file.Files.isRegularFile(p)).toList();
            }
            int deleted = 0;
            for (java.nio.file.Path p : files) {
                try {
                    // 核心逻辑：尽力删除，忽略失败
                    java.nio.file.Files.deleteIfExists(p);
                    deleted++;
                } catch (Exception ignored) {}
            }
            if (uiLogger != null) uiLogger.accept("已清理目录: " + dir.toAbsolutePath() + "，deleted=" + deleted);
            return deleted;
        } catch (Exception ex) {
            if (uiLogger != null) uiLogger.accept("清理目录失败: " + dir.toAbsolutePath() + "，err=" + ex.getMessage());
            return 0;
        }
    }

    /**
     * 带超时的 LLM 调用封装。
     * 核心逻辑：单线程执行 + 超时取消 + 统一日志。
     */
    static String callLLMWithTimeout(java.util.concurrent.Callable<String> task, long timeoutMillis, java.util.function.Consumer<String> uiLogger, String modelName) {
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        java.util.concurrent.Future<String> future = executor.submit(task);
        try {
            return future.get(timeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException te) {
            // 核心逻辑：超时后取消任务，避免阻塞
            future.cancel(true);
            if (uiLogger != null) {
                uiLogger.accept("LLM 调用超时，模型: " + modelName + "，已中止本次请求。");
            }
        } catch (Exception ex) {
            // 核心逻辑：异常取消任务并输出错误
            future.cancel(true);
            if (uiLogger != null) {
                uiLogger.accept("LLM 调用失败，模型: " + modelName + "，错误: " + ex.getMessage());
            }
        } finally {
            executor.shutdownNow();
        }
        return "";
    }
}
