package com.qiyi.skills;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 按业务域加载可选的 LLM skills prompt（markdown），用于给规划阶段提供额外上下文。
 *
 * <p>资源位置：src/main/resources/com/qiyi/skills/*.md</p>
 */
public final class SkillPrompts {
    private static final String BASE_DIR = "com/qiyi/skills/";

    private SkillPrompts() {
    }

    /**
     * 通用基础 prompt（始终注入）。
     */
    public static String base() {
        return loadOrEmpty("base.md");
    }

    /**
     * 按业务域加载 prompt（例如 futu/erp/dingtalk），用于仅在需要时注入上下文。
     */
    public static String forDomains(Collection<String> domains) {
        if (domains == null || domains.isEmpty()) return "";
        Set<String> normalized = new LinkedHashSet<>();
        for (String d : domains) {
            if (d == null) continue;
            String v = d.trim();
            if (!v.isEmpty()) normalized.add(v);
        }
        if (normalized.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        for (String domain : normalized) {
            sb.append(loadOrEmpty(domain + ".md"));
        }
        return sb.toString();
    }

    private static String loadOrEmpty(String filename) {
        if (filename == null || filename.trim().isEmpty()) return "";
        String path = BASE_DIR + filename;
        try (InputStream in = SkillPrompts.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) return "";
            byte[] bytes = in.readAllBytes();
            if (bytes.length == 0) return "";
            String s = new String(bytes, StandardCharsets.UTF_8);
            if (!s.endsWith("\n")) s = s + "\n";
            return s;
        } catch (Exception ignored) {
            return "";
        }
    }
}
