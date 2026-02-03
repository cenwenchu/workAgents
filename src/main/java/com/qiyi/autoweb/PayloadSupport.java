package com.qiyi.autoweb;

import com.microsoft.playwright.Page;

/**
 * LLM payload 组装器
 * 负责把用户意图、页面信息、计划步骤拼成模型可读格式
 */
class PayloadSupport {
    /**
     * 在 currentUrl 与 step snapshot URL 中选择更可靠的当前页
     *
     * @param currentUrl 当前页面 URL
     * @param snapshots 已采集的 step 快照
     * @return 可靠的当前页 URL
     */
    private static String chooseCurrentUrl(String currentUrl, java.util.List<AutoWebAgent.HtmlSnapshot> snapshots) {
        String cur = currentUrl == null ? "" : currentUrl.trim();
        if (cur.isEmpty() || "about:blank".equalsIgnoreCase(cur)) {
            if (snapshots != null) {
                for (AutoWebAgent.HtmlSnapshot s : snapshots) {
                    if (s == null || s.url == null) continue;
                    String u = s.url.trim();
                    if (PlanRoutingSupport.looksLikeUrl(u)) return u;
                }
            }
            return "";
        }

        boolean matchesAny = false;
        String curBase = PlanRoutingSupport.stripUrlQuery(cur);
        if (snapshots != null) {
            for (AutoWebAgent.HtmlSnapshot s : snapshots) {
                if (s == null || s.url == null) continue;
                String u = s.url.trim();
                if (!PlanRoutingSupport.looksLikeUrl(u)) continue;
                String uBase = PlanRoutingSupport.stripUrlQuery(u);
                if (!curBase.isEmpty() && (uBase.startsWith(curBase) || curBase.startsWith(uBase))) {
                    matchesAny = true;
                    break;
                }
            }
        }
        if (matchesAny) return curBase;

        if (snapshots != null) {
            for (AutoWebAgent.HtmlSnapshot s : snapshots) {
                if (s == null || s.url == null) continue;
                String u = s.url.trim();
                if (PlanRoutingSupport.looksLikeUrl(u)) return u;
            }
        }
        return curBase;
    }

    /**
     * 生成 PLAN_ONLY 模式 payload
     *
     * @param currentPage 当前页面
     * @param userPrompt 用户任务描述
     * @return payload 文本
     */
    static String buildPlanOnlyPayload(Page currentPage, String userPrompt) {
        return buildPlanOnlyPayload(StorageSupport.safePageUrl(currentPage), userPrompt);
    }

    /**
     * 生成 PLAN_ONLY 模式 payload（使用当前 URL）
     *
     * @param currentUrl 当前 URL
     * @param userPrompt 用户任务描述
     * @return payload 文本
     */
    static String buildPlanOnlyPayload(String currentUrl, String userPrompt) {
        String userProvidedUrl = PlanRoutingSupport.extractFirstUrlFromText(userPrompt);
        java.util.LinkedHashMap<String, String> urlMappings = PlanRoutingSupport.extractUrlMappingsFromText(userPrompt);
        boolean samePageOperation = false;
        if (userPrompt != null) {
            String t = userPrompt;
            samePageOperation =
                    t.contains("所有的任务都是这个页面") ||
                    t.contains("不是独立的入口") ||
                    t.contains("不需要独立的入口") ||
                    t.contains("不独立的入口");
        }

        StringBuilder sb = new StringBuilder();
        sb.append("MODE: PLAN_ONLY\n");
        if (currentUrl != null && !currentUrl.isEmpty()) {
            sb.append("CURRENT_PAGE_URL: ").append(PlanRoutingSupport.stripUrlQuery(currentUrl)).append("\n");
        }
        if (userProvidedUrl != null && !userProvidedUrl.isEmpty()) {
            sb.append("USER_PROVIDED_URL: ").append(userProvidedUrl).append("\n");
        }
        if (!urlMappings.isEmpty()) {
            sb.append("USER_PROVIDED_URLS:\n");
            for (java.util.Map.Entry<String, String> e : urlMappings.entrySet()) {
                String k = e.getKey() == null ? "" : e.getKey().trim();
                String v = e.getValue() == null ? "" : e.getValue().trim();
                if (v.isEmpty()) continue;
                if (k.isEmpty()) sb.append("- ").append(v).append("\n");
                else sb.append("- ").append(k).append(": ").append(v).append("\n");
            }
        }
        if (samePageOperation) {
            sb.append("SAME_PAGE_OPERATION: true\n");
        }
        return sb.toString();
    }

    /**
     * 生成 PLAN_ENTRY 模式 payload
     *
     * @param currentPage 当前页面
     * @param userPrompt 用户任务描述
     * @return payload 文本
     */
    static String buildPlanEntryPayload(Page currentPage, String userPrompt) {
        return buildPlanEntryPayload(StorageSupport.safePageUrl(currentPage), userPrompt);
    }

    /**
     * 生成 PLAN_ENTRY 模式 payload（包含用户 URL 映射）
     *
     * @param currentUrl 当前 URL
     * @param userPrompt 用户任务描述
     * @return payload 文本
     */
    static String buildPlanEntryPayload(String currentUrl, String userPrompt) {
        String userProvidedUrl = PlanRoutingSupport.extractFirstUrlFromText(userPrompt);
        java.util.LinkedHashMap<String, String> urlMappings = PlanRoutingSupport.extractUrlMappingsFromText(userPrompt);
        boolean samePageOperation = false;

        if (userPrompt != null) {
            String t = userPrompt;
            samePageOperation =
                    t.contains("所有的任务都是这个页面") ||
                    t.contains("不是独立的入口") ||
                    t.contains("不需要独立的入口") ||
                    t.contains("不独立的入口");
        }

        StringBuilder sb = new StringBuilder();
        sb.append("MODE: PLAN_ENTRY\n");
        if (currentUrl != null && !currentUrl.isEmpty()) {
            sb.append("CURRENT_PAGE_URL: ").append(PlanRoutingSupport.stripUrlQuery(currentUrl)).append("\n");
        }
        if (userProvidedUrl != null && !userProvidedUrl.isEmpty()) {
            sb.append("USER_PROVIDED_URL: ").append(userProvidedUrl).append("\n");
        }

        if (!urlMappings.isEmpty()) {
            sb.append("USER_PROVIDED_URLS:\n");
            for (java.util.Map.Entry<String, String> e : urlMappings.entrySet()) {
                String k = e.getKey() == null ? "" : e.getKey().trim();
                String v = e.getValue() == null ? "" : e.getValue().trim();
                if (v.isEmpty()) continue;
                if (k.isEmpty()) sb.append("- ").append(v).append("\n");
                else sb.append("- ").append(k).append(": ").append(v).append("\n");
            }
        }
        if (samePageOperation) {
            sb.append("SAME_PAGE_OPERATION: true\n");
        }
        return sb.toString();
    }

    /**
     * 生成 PLAN_REFINE 模式 payload
     *
     * @param currentPage 当前页面
     * @param userPrompt 用户任务描述
     * @param refineHint 补充入口说明
     * @return payload 文本
     */
    static String buildPlanRefinePayload(Page currentPage, String userPrompt, String refineHint) {
        return buildPlanRefinePayload(StorageSupport.safePageUrl(currentPage), userPrompt, refineHint);
    }

    static String buildPlanRefinePayload(Page currentPage, String userPrompt, String refineHint, String visualDescription) {
        return buildPlanRefinePayload(StorageSupport.safePageUrl(currentPage), userPrompt, refineHint, visualDescription);
    }

    /**
     * 生成 PLAN_REFINE 模式 payload（包含补充提示与 URL 映射）
     *
     * @param currentUrl 当前 URL
     * @param userPrompt 用户任务描述
     * @param refineHint 补充入口说明
     * @return payload 文本
     */
    static String buildPlanRefinePayload(String currentUrl, String userPrompt, String refineHint) {
        java.util.LinkedHashMap<String, String> urlMappings = new java.util.LinkedHashMap<>();
        urlMappings.putAll(PlanRoutingSupport.extractUrlMappingsFromText(userPrompt));
        java.util.LinkedHashMap<String, String> hintMappings = PlanRoutingSupport.extractUrlMappingsFromText(refineHint);
        for (java.util.Map.Entry<String, String> e : hintMappings.entrySet()) {
            String k = e.getKey();
            String v = e.getValue();
            if (v == null || v.trim().isEmpty()) continue;
            boolean exists = urlMappings.values().stream().anyMatch(x -> x.equals(v.trim()));
            if (!exists) {
                urlMappings.put(k, v.trim());
            }
        }

        boolean samePageOperation = false;
        String t = (refineHint == null ? "" : refineHint) + "\n" + (userPrompt == null ? "" : userPrompt);
        samePageOperation =
                t.contains("所有的任务都是这个页面") ||
                t.contains("不是独立的入口") ||
                t.contains("不需要独立的入口") ||
                t.contains("不独立的入口");

        StringBuilder sb = new StringBuilder();
        sb.append("MODE: PLAN_REFINE\n");
        if (!currentUrl.isEmpty()) sb.append("CURRENT_PAGE_URL: ").append(PlanRoutingSupport.stripUrlQuery(currentUrl)).append("\n");

        String userProvidedUrl = PlanRoutingSupport.extractFirstUrlFromText(refineHint);
        if (userProvidedUrl != null && !userProvidedUrl.isEmpty()) {
            sb.append("USER_PROVIDED_URL: ").append(userProvidedUrl).append("\n");
        }

        if (refineHint != null && !refineHint.trim().isEmpty()) {
            sb.append("USER_INPUT_RAW: ").append(refineHint.trim()).append("\n");
        }

        if (!urlMappings.isEmpty()) {
            sb.append("USER_PROVIDED_URLS:\n");
            for (java.util.Map.Entry<String, String> e : urlMappings.entrySet()) {
                String k = e.getKey() == null ? "" : e.getKey().trim();
                String v = e.getValue() == null ? "" : e.getValue().trim();
                if (v.isEmpty()) continue;
                if (k.isEmpty()) sb.append("- ").append(v).append("\n");
                else sb.append("- ").append(k).append(": ").append(v).append("\n");
            }
        }
        if (samePageOperation) sb.append("SAME_PAGE_OPERATION: true\n");
        return sb.toString();
    }

    static String buildPlanRefinePayload(String currentUrl, String userPrompt, String refineHint, String visualDescription) {
        java.util.LinkedHashMap<String, String> urlMappings = new java.util.LinkedHashMap<>();
        urlMappings.putAll(PlanRoutingSupport.extractUrlMappingsFromText(userPrompt));
        java.util.LinkedHashMap<String, String> hintMappings = PlanRoutingSupport.extractUrlMappingsFromText(refineHint);
        for (java.util.Map.Entry<String, String> e : hintMappings.entrySet()) {
            String k = e.getKey();
            String v = e.getValue();
            if (v == null || v.trim().isEmpty()) continue;
            boolean exists = urlMappings.values().stream().anyMatch(x -> x.equals(v.trim()));
            if (!exists) {
                urlMappings.put(k, v.trim());
            }
        }

        boolean samePageOperation = false;
        String t = (refineHint == null ? "" : refineHint) + "\n" + (userPrompt == null ? "" : userPrompt);
        samePageOperation =
                t.contains("所有的任务都是这个页面") ||
                t.contains("不是独立的入口") ||
                t.contains("不需要独立的入口") ||
                t.contains("不独立的入口");

        StringBuilder sb = new StringBuilder();
        sb.append("MODE: PLAN_REFINE\n");
        if (currentUrl != null && !currentUrl.isEmpty()) sb.append("CURRENT_PAGE_URL: ").append(PlanRoutingSupport.stripUrlQuery(currentUrl)).append("\n");
        if (visualDescription != null && !visualDescription.trim().isEmpty()) {
            sb.append("VISUAL_DESCRIPTION:\n").append(visualDescription.trim()).append("\n");
        }

        String userProvidedUrl = PlanRoutingSupport.extractFirstUrlFromText(refineHint);
        if (userProvidedUrl != null && !userProvidedUrl.isEmpty()) {
            sb.append("USER_PROVIDED_URL: ").append(userProvidedUrl).append("\n");
        }

        if (refineHint != null && !refineHint.trim().isEmpty()) {
            sb.append("USER_INPUT_RAW: ").append(refineHint.trim()).append("\n");
        }

        if (!urlMappings.isEmpty()) {
            sb.append("USER_PROVIDED_URLS:\n");
            for (java.util.Map.Entry<String, String> e : urlMappings.entrySet()) {
                String k = e.getKey() == null ? "" : e.getKey().trim();
                String v = e.getValue() == null ? "" : e.getValue().trim();
                if (v.isEmpty()) continue;
                if (k.isEmpty()) sb.append("- ").append(v).append("\n");
                else sb.append("- ").append(k).append(": ").append(v).append("\n");
            }
        }
        if (samePageOperation) sb.append("SAME_PAGE_OPERATION: true\n");
        return sb.toString();
    }

    /**
     * 生成 CODEGEN 模式 payload
     *
     * @param currentPage 当前页面
     * @param planText 计划文本
     * @param snapshots 步骤快照
     * @return payload 文本
     */
    static String buildCodegenPayload(Page currentPage, String planText, java.util.List<AutoWebAgent.HtmlSnapshot> snapshots) {
        String currentUrl = chooseCurrentUrl(StorageSupport.safePageUrl(currentPage), snapshots);

        StringBuilder sb = new StringBuilder();
        sb.append("MODE: CODEGEN\n");
        if (!currentUrl.isEmpty()) {
            sb.append("CURRENT_PAGE_URL: ").append(PlanRoutingSupport.stripUrlQuery(currentUrl)).append("\n");
        }
        sb.append("PLAN:\n").append(planText == null ? "" : planText).append("\n");
        sb.append("STEP_HTMLS_CLEANED:\n");
        appendStepHtmlsCleaned(sb, snapshots, 500000);
        return sb.toString();
    }

    static String buildCodegenPayload(Page currentPage, String planText, java.util.List<AutoWebAgent.HtmlSnapshot> snapshots, String visualDescription) {
        String currentUrl = chooseCurrentUrl(StorageSupport.safePageUrl(currentPage), snapshots);

        StringBuilder sb = new StringBuilder();
        sb.append("MODE: CODEGEN\n");
        if (!currentUrl.isEmpty()) {
            sb.append("CURRENT_PAGE_URL: ").append(PlanRoutingSupport.stripUrlQuery(currentUrl)).append("\n");
        }
        if (visualDescription != null && !visualDescription.trim().isEmpty()) {
            sb.append("VISUAL_DESCRIPTION:\n").append(visualDescription.trim()).append("\n");
        }
        sb.append("PLAN:\n").append(planText == null ? "" : planText).append("\n");
        sb.append("STEP_HTMLS_CLEANED:\n");
        appendStepHtmlsCleaned(sb, snapshots, 500000);
        return sb.toString();
    }

    /**
     * 将步骤 HTML 片段追加到 payload，控制总长度
     *
     * @param sb 目标 builder
     * @param snapshots 步骤快照
     * @param maxChars 最大字符数
     */
    private static void appendStepHtmlsCleaned(StringBuilder sb, java.util.List<AutoWebAgent.HtmlSnapshot> snapshots, int maxChars) {
        if (sb == null) return;
        if (snapshots == null || snapshots.isEmpty()) return;
        int used = 0;
        java.util.HashMap<String, Integer> firstStepByUrl = new java.util.HashMap<>();
        for (AutoWebAgent.HtmlSnapshot snap : snapshots) {
            if (snap == null || snap.cleanedHtml == null) continue;
            String urlKey = "";
            try { urlKey = (snap.url == null ? "" : snap.url.trim()); } catch (Exception ignored) {}
            String header = "[Step " + snap.stepIndex + "] URL: " + (snap.url == null ? "" : snap.url) + " | Entry: " + (snap.entryAction == null ? "" : snap.entryAction) + "\n";
            Integer first = urlKey.isEmpty() ? null : firstStepByUrl.get(urlKey);
            if (first != null && first.intValue() != snap.stepIndex) {
                String body = "DUPLICATE_URL: SAME_AS_STEP " + first + "\n";
                int remaining = maxChars - used - header.length();
                if (remaining <= 0) break;
                if (body.length() > remaining) {
                    body = body.substring(0, remaining) + "...(truncated)";
                }
                sb.append(header).append(body).append("\n");
                used += header.length() + body.length() + 1;
                if (used >= maxChars) break;
                continue;
            }
            if (!urlKey.isEmpty() && !firstStepByUrl.containsKey(urlKey)) {
                firstStepByUrl.put(urlKey, snap.stepIndex);
            }
            String body = snap.cleanedHtml;
            int remaining = maxChars - used - header.length();
            if (remaining <= 0) break;
            if (body.length() > remaining) {
                body = body.substring(0, remaining) + "...(truncated)";
            }
            sb.append(header).append(body).append("\n");
            used += header.length() + body.length() + 1;
            if (used >= maxChars) break;
        }
    }

    /**
     * 生成 REFINE_CODE 模式 payload
     *
     * @param currentPage 当前页面
     * @param planText 计划文本
     * @param snapshots 步骤快照
     * @param currentCleanedHtml 当前页清洗后的 HTML
     * @param userPrompt 用户任务描述
     * @param refineHint 修正提示
     * @return payload 文本
     */
    static String buildRefinePayload(Page currentPage, String planText, java.util.List<AutoWebAgent.HtmlSnapshot> snapshots, String currentCleanedHtml, String userPrompt, String refineHint) {
        return buildRefinePayload(currentPage, planText, snapshots, currentCleanedHtml, userPrompt, refineHint, null);
    }

    static String buildRefinePayload(Page currentPage, String planText, java.util.List<AutoWebAgent.HtmlSnapshot> snapshots, String currentCleanedHtml, String userPrompt, String refineHint, String visualDescription) {
        String currentUrl = chooseCurrentUrl(StorageSupport.safePageUrl(currentPage), snapshots);
        StringBuilder sb = new StringBuilder();
        sb.append("MODE: REFINE_CODE\n");
        if (!currentUrl.isEmpty()) {
            sb.append("CURRENT_PAGE_URL: ").append(PlanRoutingSupport.stripUrlQuery(currentUrl)).append("\n");
        }
        if (visualDescription != null && !visualDescription.trim().isEmpty()) {
            sb.append("VISUAL_DESCRIPTION:\n").append(visualDescription.trim()).append("\n");
        }

        java.util.LinkedHashMap<String, String> urlMappings = new java.util.LinkedHashMap<>();
        urlMappings.putAll(PlanRoutingSupport.extractUrlMappingsFromText(userPrompt));
        java.util.LinkedHashMap<String, String> hintMappings = PlanRoutingSupport.extractUrlMappingsFromText(refineHint);
        for (java.util.Map.Entry<String, String> e : hintMappings.entrySet()) {
            String k = e.getKey();
            String v = e.getValue();
            if (v == null || v.trim().isEmpty()) continue;
            boolean exists = urlMappings.values().stream().anyMatch(x -> x.equals(v.trim()));
            if (!exists) {
                urlMappings.put(k, v.trim());
            }
        }
        if (!urlMappings.isEmpty()) {
            sb.append("USER_PROVIDED_URLS:\n");
            for (java.util.Map.Entry<String, String> e : urlMappings.entrySet()) {
                String k = e.getKey() == null ? "" : e.getKey().trim();
                String v = e.getValue() == null ? "" : e.getValue().trim();
                if (v.isEmpty()) continue;
                if (k.isEmpty()) sb.append("- ").append(v).append("\n");
                else sb.append("- ").append(k).append(": ").append(v).append("\n");
            }
        }

        if (currentCleanedHtml != null && !currentCleanedHtml.isEmpty()) {
            String v = currentCleanedHtml;
            if (v.length() > 200000) v = v.substring(0, 200000) + "...(truncated)";
            sb.append("CURRENT_PAGE_HTML_CLEANED:\n").append(v).append("\n");
        }
        sb.append("PLAN:\n").append(planText == null ? "" : planText).append("\n");
        sb.append("STEP_HTMLS_CLEANED:\n");
        appendStepHtmlsCleaned(sb, snapshots, 500000);
        return sb.toString();
    }
}
