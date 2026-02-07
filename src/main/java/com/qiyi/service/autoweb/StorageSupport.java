package com.qiyi.service.autoweb;

import com.microsoft.playwright.Page;
import com.qiyi.util.AppLog;

/**
 * 调试与存储辅助工具。
 *
 * 约定目录：
 * - autoweb/debug：保存 payload/prompt/response/异常等调试产物（便于复盘与对齐模型差异）
 * - autoweb/cache：保存按 URL/入口动作/captureMode 归一化后的页面快照（减少重复采集）
 *
 * 核心能力：
 * - 保存调试文件：统一文件命名与最大长度截断；
 * - 脱敏：对 token/apiKey/Authorization 等字段做正则脱敏；
 * - 统计与摘要：输出 payload/prompt 字节数、payload 结构摘要；
 * - URL 读取：通过锁保护 Playwright 的跨线程访问。
 */
class StorageSupport {
    /**
     * 计算字符串的 SHA-256 指纹
     */
    private static String sha256Hex(String s) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] b = md.digest((s == null ? "" : s).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte x : b) sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf((s == null ? "" : s).hashCode());
        }
    }

    /**
     * 统计 UTF-8 字节长度
     */
    static long utf8Bytes(String s) {
        if (s == null) return 0L;
        try {
            return s.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        } catch (Exception e) {
            return s.length();
        }
    }

    static String stackTraceToString(Throwable t) {
        if (t == null) return "";
        try {
            java.io.StringWriter sw = new java.io.StringWriter(2048);
            java.io.PrintWriter pw = new java.io.PrintWriter(sw);
            t.printStackTrace(pw);
            pw.flush();
            return sw.toString();
        } catch (Exception e) {
            return String.valueOf(t);
        }
    }

    static String formatLog(String scene, String content, Throwable err) {
        String s = scene == null ? "" : scene.trim();
        if (s.isEmpty()) s = "AUTO_WEB";
        String c = content == null ? "" : content.trim();
        StringBuilder sb = new StringBuilder();
        sb.append("[").append(s).append("] ");
        sb.append(c);
        if (err != null) {
            String m = err.getMessage();
            sb.append(" | err=").append(err.getClass().getSimpleName());
            if (m != null && !m.trim().isEmpty()) sb.append(": ").append(m.trim());
        }
        return sb.toString();
    }

    static void log(java.util.function.Consumer<String> uiLogger, String scene, String content, Throwable err) {
        String msg = formatLog(scene, content, err);
        if (uiLogger != null) uiLogger.accept(msg);
        else AppLog.info(msg);
    }

    /**
     * 记录请求 payload/prompt 字节统计
     */
    static void logRequestBytes(
            String stageName,
            String modelName,
            String mode,
            String payload,
            String prompt,
            java.util.function.Consumer<String> uiLogger
    ) {
        if (uiLogger == null) return;
        long payloadBytes = utf8Bytes(payload);
        long promptBytes = utf8Bytes(prompt);
        uiLogger.accept("请求发起: stage=" + (stageName == null ? "" : stageName)
                + ", model=" + (modelName == null ? "" : modelName)
                + ", mode=" + (mode == null ? "" : mode)
                + ", send=prompt"
                + ", bytes(prompt)=" + promptBytes
                + ", bytes(payload)=" + payloadBytes);
    }

    private static java.nio.file.Path ensureCacheDir() throws java.io.IOException {
        java.nio.file.Path dir = java.nio.file.Paths.get(System.getProperty("user.dir"), "autoweb", "cache");
        if (!java.nio.file.Files.exists(dir)) {
            java.nio.file.Files.createDirectories(dir);
        }
        return dir;
    }

    private static java.nio.file.Path ensureDebugDir() throws java.io.IOException {
        java.nio.file.Path dir = java.nio.file.Paths.get(System.getProperty("user.dir"), "autoweb", "debug");
        if (!java.nio.file.Files.exists(dir)) {
            java.nio.file.Files.createDirectories(dir);
        }
        return dir;
    }

    /**
     * 生成调试文件时间戳
     */
    static String newDebugTimestamp() {
        try {
            return java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS"));
        } catch (Exception e) {
            return String.valueOf(System.currentTimeMillis());
        }
    }

    private static String sanitizeFileToken(String s) {
        if (s == null) return "UNKNOWN";
        String v = s.trim();
        if (v.isEmpty()) return "UNKNOWN";
        v = v.replaceAll("\\s+", "_");
        v = v.replaceAll("[^A-Za-z0-9._-]", "_");
        while (v.contains("__")) v = v.replace("__", "_");
        if (v.length() > 64) v = v.substring(0, 64);
        return v.isEmpty() ? "UNKNOWN" : v;
    }

    private static String truncateForDebug(String s, int maxChars) {
        if (s == null) return "";
        if (maxChars <= 0) return "";
        if (s.length() <= maxChars) return s;
        return s.substring(0, maxChars) + "...(truncated)";
    }

    private static String redactForDebug(String s) {
        if (s == null || s.isEmpty()) return s;
        String out = s;
        out = out.replaceAll("(?i)(api[_-]?key\\s*[:=]\\s*)([^\\s\"']+)", "$1***REDACTED***");
        out = out.replaceAll("(?i)(api[_-]?key\\s*[:=]\\s*['\"])([^'\"]+)(['\"])", "$1***REDACTED***$3");
        out = out.replaceAll("(?i)(token\\s*[:=]\\s*)([^\\s\"']+)", "$1***REDACTED***");
        out = out.replaceAll("(?i)(token\\s*[:=]\\s*['\"])([^'\"]+)(['\"])", "$1***REDACTED***$3");
        out = out.replaceAll("(?i)(secret\\s*[:=]\\s*)([^\\s\"']+)", "$1***REDACTED***");
        out = out.replaceAll("(?i)(secret\\s*[:=]\\s*['\"])([^'\"]+)(['\"])", "$1***REDACTED***$3");
        out = out.replaceAll("(?i)(Authorization\\s*[:=]\\s*Bearer\\s+)([A-Za-z0-9._-]+)", "$1***REDACTED***");
        return out;
    }

    /**
     * 保存调试材料到 autoweb/debug
     */
    static String saveDebugArtifact(
            String ts,
            String modelName,
            String mode,
            String kind,
            String content,
            java.util.function.Consumer<String> uiLogger
    ) {
        return saveDebugArtifact(ts, modelName, mode, kind, content, uiLogger, 2_000_000);
    }

    static String saveDebugArtifact(
            String ts,
            String modelName,
            String mode,
            String kind,
            String content,
            java.util.function.Consumer<String> uiLogger,
            int maxChars
    ) {
        try {
            java.nio.file.Path dir = ensureDebugDir();
            String fileName = sanitizeFileToken(ts) + "_" +
                    sanitizeFileToken(modelName) + "_" +
                    sanitizeFileToken(mode) + "_" +
                    sanitizeFileToken(kind) + ".txt";
            java.nio.file.Path path = dir.resolve(fileName);

            String redacted = redactForDebug(content);
            String processed = (maxChars <= 0) ? redacted : truncateForDebug(redacted, maxChars);
            String sha = sha256Hex(processed);

            StringBuilder sb = new StringBuilder();
            sb.append("TS: ").append(ts == null ? "" : ts).append("\n");
            sb.append("MODEL: ").append(modelName == null ? "" : modelName).append("\n");
            sb.append("MODE: ").append(mode == null ? "" : mode).append("\n");
            sb.append("KIND: ").append(kind == null ? "" : kind).append("\n");
            sb.append("LEN: ").append(processed.length()).append("\n");
            sb.append("SHA256: ").append(sha).append("\n");
            sb.append("----\n");
            sb.append(processed);

            java.nio.file.Files.write(path, sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return path.toAbsolutePath().toString();
        } catch (Exception e) {
            if (uiLogger != null) uiLogger.accept("Debug artifact save failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * 输出 payload 的摘要信息
     */
    static void logPayloadSummary(String payload, java.util.function.Consumer<String> uiLogger) {
        if (uiLogger == null) return;
        if (payload == null) {
            uiLogger.accept("Payload Summary: (null)");
            return;
        }
        String mode = AutoWebAgent.extractModeFromPayload(payload);
        String currentUrl = PlanRoutingSupport.matchFirst(payload, "(?m)^\\s*CURRENT_PAGE_URL\\s*:\\s*(.*)$");
        String userUrl = PlanRoutingSupport.matchFirst(payload, "(?m)^\\s*USER_PROVIDED_URL\\s*:\\s*(.*)$");
        boolean samePage = payload.contains("SAME_PAGE_OPERATION: true");
        int urlListCount = 0;
        try {
            java.util.regex.Pattern p = java.util.regex.Pattern.compile("(?m)^\\s*-\\s+.+$");
            java.util.regex.Pattern listHeader = java.util.regex.Pattern.compile("(?m)^\\s*USER_PROVIDED_URLS\\s*:\\s*$");
            java.util.regex.Matcher h = listHeader.matcher(payload);
            int listStart = -1;
            if (h.find()) listStart = h.end();
            if (listStart >= 0) {
                String tail = payload.substring(listStart);
                java.util.regex.Matcher mm = p.matcher(tail);
                while (mm.find()) {
                    urlListCount++;
                }
            }
        } catch (Exception ignored) {}

        StringBuilder sb = new StringBuilder();
        sb.append("Payload Summary: ");
        sb.append("MODE=").append(mode == null ? "" : mode);
        if (currentUrl != null && !currentUrl.isEmpty()) sb.append(", CURRENT_PAGE_URL=").append(currentUrl);
        if (userUrl != null && !userUrl.isEmpty()) sb.append(", USER_PROVIDED_URL=").append(userUrl);
        if (urlListCount > 0) sb.append(", USER_PROVIDED_URLS=").append(urlListCount);
        if (samePage) sb.append(", SAME_PAGE_OPERATION=true");
        uiLogger.accept(sb.toString());
    }

    /**
     * 安全读取当前页面 URL。
     * 核心逻辑：用 {@link AutoWebAgent#PLAYWRIGHT_LOCK} 串行化访问，避免跨线程直接调用 Playwright 导致不稳定。
     */
    static String safePageUrl(Page page) {
        if (page == null) return "";
        try {
            synchronized (AutoWebAgent.PLAYWRIGHT_LOCK) {
                String u = page.url();
                if (u == null) return "";
                return u.trim();
            }
        } catch (Exception ignored) {
            return "";
        }
    }
}
