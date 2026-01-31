package com.qiyi.autoweb;

/**
 * HTML 快照缓存读写组件。
 * 负责按 URL/入口动作/采集模式生成 key 并落盘读取。
 */
class HtmlSnapshotDao {
    /**
     * 计算字符串的 SHA-256 指纹。
     * 核心逻辑：用于生成稳定文件 key，异常时降级为 hashCode。
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
     * 生成缓存 key。
     * 核心逻辑：将 url/entryAction/采集模式拼接后做哈希。
     */
    private static String makeKey(String url, String entryAction, AutoWebAgent.HtmlCaptureMode captureMode, boolean a11yInterestingOnly) {
        AutoWebAgent.HtmlCaptureMode mode = captureMode == null ? AutoWebAgent.HtmlCaptureMode.RAW_HTML : captureMode;
        StringBuilder sb = new StringBuilder();
        sb.append(url == null ? "" : url).append("\n");
        sb.append(entryAction == null ? "" : entryAction).append("\n");
        sb.append("CAPTURE_MODE=").append(mode.name()).append("\n");
        if (mode == AutoWebAgent.HtmlCaptureMode.ARIA_SNAPSHOT) {
            sb.append("A11Y_INTERESTING_ONLY=").append(a11yInterestingOnly).append("\n");
        }
        return sha256Hex(sb.toString());
    }

    /**
     * 确保缓存目录存在并返回路径。
     * 核心逻辑：按 autoweb/cache 约定创建目录。
     */
    private static java.nio.file.Path ensureCacheDir() throws java.io.IOException {
        java.nio.file.Path dir = java.nio.file.Paths.get(System.getProperty("user.dir"), "autoweb", "cache");
        if (!java.nio.file.Files.exists(dir)) {
            java.nio.file.Files.createDirectories(dir);
        }
        return dir;
    }

    /**
     * 读取缓存的 HTML 快照。
     * 核心逻辑：优先新 key，RAW_HTML 兼容 legacy key。
     */
    static AutoWebAgent.HtmlSnapshot readCachedHtml(int stepIndex, String url, String entryAction, AutoWebAgent.HtmlCaptureMode captureMode, boolean a11yInterestingOnly) {
        try {
            java.nio.file.Path dir = ensureCacheDir();
            AutoWebAgent.HtmlCaptureMode mode = captureMode == null ? AutoWebAgent.HtmlCaptureMode.RAW_HTML : captureMode;
            String key = makeKey(url, entryAction, mode, a11yInterestingOnly);
            java.nio.file.Path cleanedPath = dir.resolve(key + ".cleaned.html");
            if (!java.nio.file.Files.exists(cleanedPath)) {
                // 核心逻辑：兼容历史缓存 key
                if (mode == AutoWebAgent.HtmlCaptureMode.RAW_HTML) {
                    String legacyKey = sha256Hex((url == null ? "" : url) + "\n" + (entryAction == null ? "" : entryAction));
                    java.nio.file.Path legacyCleaned = dir.resolve(legacyKey + ".cleaned.html");
                    if (!java.nio.file.Files.exists(legacyCleaned)) return null;
                    key = legacyKey;
                    cleanedPath = legacyCleaned;
                } else {
                    return null;
                }
            }
            AutoWebAgent.HtmlSnapshot snap = new AutoWebAgent.HtmlSnapshot();
            snap.stepIndex = stepIndex;
            snap.url = url;
            snap.entryAction = entryAction;
            snap.cacheKey = key;
            snap.cleanedHtml = new String(java.nio.file.Files.readAllBytes(cleanedPath), java.nio.charset.StandardCharsets.UTF_8);
            return snap;
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * 写入缓存的 HTML 快照。
     * 核心逻辑：同时保存 raw 与 cleaned 文件，并返回快照对象。
     */
    static AutoWebAgent.HtmlSnapshot writeCachedHtml(int stepIndex, String url, String entryAction, AutoWebAgent.HtmlCaptureMode captureMode, boolean a11yInterestingOnly, String rawHtml, String cleanedHtml) {
        try {
            java.nio.file.Path dir = ensureCacheDir();
            String key = makeKey(url, entryAction, captureMode, a11yInterestingOnly);
            java.nio.file.Path rawPath = dir.resolve(key + ".raw.html");
            java.nio.file.Path cleanedPath = dir.resolve(key + ".cleaned.html");
            // 核心逻辑：落盘 raw 与 cleaned
            java.nio.file.Files.write(rawPath, (rawHtml == null ? "" : rawHtml).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            java.nio.file.Files.write(cleanedPath, (cleanedHtml == null ? "" : cleanedHtml).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            AutoWebAgent.HtmlSnapshot snap = new AutoWebAgent.HtmlSnapshot();
            snap.stepIndex = stepIndex;
            snap.url = url;
            snap.entryAction = entryAction;
            snap.cacheKey = key;
            snap.cleanedHtml = cleanedHtml;
            return snap;
        } catch (Exception ignored) {
            return null;
        }
    }
}
