package com.qiyi.service.autoweb;

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

    private static String normalizeUrlForCache(String url) {
        String v = url == null ? "" : url.trim();
        if (v.isEmpty()) return v;
        if (!PlanRoutingSupport.looksLikeUrl(v)) return v;
        return PlanRoutingSupport.stripUrlQuery(v);
    }

    /**
     * 生成缓存 key。
     * 核心逻辑：将 url/entryAction/采集模式拼接后做哈希。
     */
    private static String makeKey(String url, String entryAction, AutoWebAgent.HtmlCaptureMode captureMode, boolean a11yInterestingOnly) {
        AutoWebAgent.HtmlCaptureMode mode = captureMode == null ? AutoWebAgent.HtmlCaptureMode.RAW_HTML : captureMode;
        String normalizedUrl = normalizeUrlForCache(url);
        StringBuilder sb = new StringBuilder();
        sb.append(normalizedUrl).append("\n");
        sb.append(entryAction == null ? "" : entryAction).append("\n");
        sb.append("CAPTURE_MODE=").append(mode.name()).append("\n");
        if (mode == AutoWebAgent.HtmlCaptureMode.ARIA_SNAPSHOT) {
            sb.append("A11Y_INTERESTING_ONLY=").append(a11yInterestingOnly).append("\n");
        }
        return sha256Hex(sb.toString());
    }

    private static String makeSharedKey(String url, AutoWebAgent.HtmlCaptureMode captureMode, boolean a11yInterestingOnly) {
        return makeKey(url, "", captureMode, a11yInterestingOnly);
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
     * 核心逻辑：优先精确 key，其次共享 key。
     */
    static AutoWebAgent.HtmlSnapshot readCachedHtml(int stepIndex, String url, String entryAction, AutoWebAgent.HtmlCaptureMode captureMode, boolean a11yInterestingOnly) {
        try {
            java.nio.file.Path dir = ensureCacheDir();
            AutoWebAgent.HtmlCaptureMode mode = captureMode == null ? AutoWebAgent.HtmlCaptureMode.RAW_HTML : captureMode;
            String normalizedUrl = normalizeUrlForCache(url);
            String key = makeKey(url, entryAction, mode, a11yInterestingOnly);
            java.nio.file.Path cleanedPath = dir.resolve(key + ".cleaned.html");
            if (!java.nio.file.Files.exists(cleanedPath)) {
                String sharedKey = makeSharedKey(url, mode, a11yInterestingOnly);
                java.nio.file.Path sharedCleanedPath = dir.resolve(sharedKey + ".cleaned.html");
                if (java.nio.file.Files.exists(sharedCleanedPath)) {
                    key = sharedKey;
                    cleanedPath = sharedCleanedPath;
                } else {
                    return null;
                }
            }
            AutoWebAgent.HtmlSnapshot snap = new AutoWebAgent.HtmlSnapshot();
            snap.stepIndex = stepIndex;
            snap.url = normalizedUrl;
            snap.entryAction = entryAction;
            snap.cacheKey = key;
            String cleanedText = new String(java.nio.file.Files.readAllBytes(cleanedPath), java.nio.charset.StandardCharsets.UTF_8);
            if (cleanedText == null || cleanedText.trim().isEmpty()) return null;
            if (mode == AutoWebAgent.HtmlCaptureMode.ARIA_SNAPSHOT) {
                try {
                    String t = cleanedText.trim();
                    if (t.startsWith("{") && t.contains("ariaSnapshotText")) {
                        com.google.gson.JsonElement je = com.google.gson.JsonParser.parseString(t);
                        if (je != null && je.isJsonObject()) {
                            com.google.gson.JsonObject o = je.getAsJsonObject();
                            com.google.gson.JsonElement v = o.get("ariaSnapshotText");
                            String aria = (v == null || v.isJsonNull()) ? "" : v.getAsString();
                            if (aria != null && "- document".equals(aria.trim())) aria = "";
                            boolean hasAxTree = o.has("axTree") || o.has("axTreeText");
                            if ((aria == null || aria.trim().isEmpty()) && !hasAxTree) {
                                return null;
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }
            snap.cleanedHtml = cleanedText;
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
            AutoWebAgent.HtmlCaptureMode mode = captureMode == null ? AutoWebAgent.HtmlCaptureMode.RAW_HTML : captureMode;
            String key = makeKey(url, entryAction, mode, a11yInterestingOnly);
            java.nio.file.Path rawPath = dir.resolve(key + ".raw.html");
            java.nio.file.Path cleanedPath = dir.resolve(key + ".cleaned.html");
            // 核心逻辑：落盘 raw 与 cleaned
            java.nio.file.Files.write(rawPath, (rawHtml == null ? "" : rawHtml).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            java.nio.file.Files.write(cleanedPath, (cleanedHtml == null ? "" : cleanedHtml).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            String sharedKey = makeSharedKey(url, mode, a11yInterestingOnly);
            if (sharedKey != null && !sharedKey.equals(key)) {
                java.nio.file.Path sharedRaw = dir.resolve(sharedKey + ".raw.html");
                java.nio.file.Path sharedCleaned = dir.resolve(sharedKey + ".cleaned.html");
                java.nio.file.Files.write(sharedRaw, (rawHtml == null ? "" : rawHtml).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                java.nio.file.Files.write(sharedCleaned, (cleanedHtml == null ? "" : cleanedHtml).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            AutoWebAgent.HtmlSnapshot snap = new AutoWebAgent.HtmlSnapshot();
            snap.stepIndex = stepIndex;
            snap.url = normalizeUrlForCache(url);
            snap.entryAction = entryAction;
            snap.cacheKey = key;
            snap.cleanedHtml = cleanedHtml;
            return snap;
        } catch (Exception ignored) {
            return null;
        }
    }
}
