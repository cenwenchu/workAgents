package com.qiyi.service.autoweb;

import com.microsoft.playwright.Page;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

/**
 * LLM payload 组装器
 * 负责把用户意图、页面信息、计划步骤拼成模型可读格式
 */
class PayloadSupport {
    private static boolean looksLikeAriaSnapshotJson(String s) {
        if (s == null) return false;
        String t = s.trim();
        if (t.isEmpty()) return false;
        if (!t.startsWith("{")) return false;
        return t.contains("\"ariaSnapshotText\"");
    }

    private static String truncate(String s, int maxChars) {
        if (s == null) return "";
        if (maxChars <= 0) return "";
        if (s.length() <= maxChars) return s;
        return s.substring(0, maxChars) + "...(truncated)";
    }

    private static boolean containsDigitOrMoney(String s) {
        if (s == null || s.isEmpty()) return false;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (Character.isDigit(ch)) return true;
            if (ch == '¥' || ch == '$' || ch == '￥') return true;
        }
        return false;
    }

    private static double shortItemTextRatio(Elements kids, int maxLen) {
        if (kids == null || kids.isEmpty()) return 0.0;
        int shortCnt = 0;
        int cnt = 0;
        for (Element c : kids) {
            if (c == null) continue;
            cnt++;
            String t = "";
            try { t = c.text(); } catch (Exception ignored) { t = ""; }
            int len = t == null ? 0 : t.trim().length();
            if (len <= maxLen) shortCnt++;
        }
        return cnt == 0 ? 0.0 : (shortCnt * 1.0 / cnt);
    }

    private static int avgElementComplexity(Elements els) {
        if (els == null || els.isEmpty()) return 0;
        int total = 0;
        int cnt = 0;
        for (Element e : els) {
            if (e == null) continue;
            cnt++;
            try { total += e.getAllElements().size(); } catch (Exception ignored) {}
        }
        return cnt == 0 ? 0 : (total / cnt);
    }

    private static boolean hasAncestorTag(Element el, String tagNameLower) {
        if (el == null || tagNameLower == null || tagNameLower.isEmpty()) return false;
        Element cur = el.parent();
        int guard = 0;
        while (cur != null && guard < 60) {
            String t = "";
            try { t = cur.tagName(); } catch (Exception ignored) { t = ""; }
            if (t != null && t.equalsIgnoreCase(tagNameLower)) return true;
            cur = cur.parent();
            guard++;
        }
        return false;
    }

    private static final class LevelPick {
        final int depth;
        final Elements items;
        final int itemCount;
        final double repeatRatio;
        final Element sample;

        private LevelPick(int depth, Elements items, int itemCount, double repeatRatio, Element sample) {
            this.depth = depth;
            this.items = items;
            this.itemCount = itemCount;
            this.repeatRatio = repeatRatio;
            this.sample = sample;
        }
    }

    private static LevelPick pickBestRepeatedLevel(Element container, int maxDepth) {
        if (container == null) return null;
        int depthLimit = maxDepth <= 0 ? 1 : Math.min(maxDepth, 6);
        Elements level = null;
        try { level = container.children(); } catch (Exception ignored) { level = null; }
        LevelPick best = null;
        double bestScore = 0.0;

        for (int depth = 1; depth <= depthLimit; depth++) {
            int n = level == null ? 0 : level.size();
            if (n >= 5 && n <= 450) {
                java.util.HashMap<String, Integer> sigCounts = new java.util.HashMap<>();
                java.util.HashMap<String, Element> sigSample = new java.util.HashMap<>();
                for (Element c : level) {
                    if (c == null) continue;
                    String cls = c.className();
                    if (cls == null) cls = "";
                    String firstClass = "";
                    if (!cls.isEmpty()) {
                        int sp = cls.indexOf(' ');
                        firstClass = sp >= 0 ? cls.substring(0, sp) : cls;
                    }
                    String sig = c.tagName() + "|" + firstClass;
                    sigCounts.put(sig, sigCounts.getOrDefault(sig, 0) + 1);
                    if (!sigSample.containsKey(sig)) sigSample.put(sig, c);
                }
                int maxCount = 0;
                String bestSig = "";
                for (java.util.Map.Entry<String, Integer> e : sigCounts.entrySet()) {
                    int v = e.getValue() == null ? 0 : e.getValue();
                    if (v > maxCount) {
                        maxCount = v;
                        bestSig = e.getKey();
                    }
                }
                double ratio = n == 0 ? 0.0 : (maxCount * 1.0 / n);
                if (!(ratio < 0.55 && n < 12)) {
                    double score = (n * ratio * 100.0);
                    if (score > bestScore) {
                        bestScore = score;
                        best = new LevelPick(depth, level, n, ratio, sigSample.get(bestSig));
                    }
                }
            }

            if (depth == depthLimit) break;
            if (level == null || level.isEmpty()) break;
            if (level.size() > 450) break;

            Elements next = new Elements();
            for (Element p : level) {
                if (p == null) continue;
                Elements kids = null;
                try { kids = p.children(); } catch (Exception ignored) { kids = null; }
                if (kids == null || kids.isEmpty()) continue;
                for (Element k : kids) {
                    if (k == null) continue;
                    next.add(k);
                    if (next.size() > 520) break;
                }
                if (next.size() > 520) break;
            }
            level = next;
            if (level.size() > 520) break;
        }
        return best;
    }

    private static Element pickBestRepeatedListContainer(Document doc) {
        if (doc == null) return null;
        Element best = null;
        double bestScore = 0.0;
        for (Element el : doc.getAllElements()) {
            if (el == null) continue;
            String tag = el.tagName();
            if ("html".equalsIgnoreCase(tag) || "body".equalsIgnoreCase(tag)) continue;

            LevelPick lp = pickBestRepeatedLevel(el, 4);
            if (lp == null || lp.items == null) continue;
            int n = lp.itemCount;
            double ratio = lp.repeatRatio;
            Elements kidsForStats = lp.items;

            int textLen = 0;
            try { textLen = el.text() == null ? 0 : el.text().length(); } catch (Exception ignored) { textLen = 0; }

            int linkCount = 0;
            int imgCount = 0;
            try { linkCount = el.select("a[href]").size(); } catch (Exception ignored) { linkCount = 0; }
            try { imgCount = el.select("img").size(); } catch (Exception ignored) { imgCount = 0; }
            boolean hasMoneyOrDigit = false;
            try { hasMoneyOrDigit = containsDigitOrMoney(el.text()); } catch (Exception ignored) { hasMoneyOrDigit = false; }
            int avgChildComplexity = avgElementComplexity(kidsForStats);
            double shortRatio = shortItemTextRatio(kidsForStats, 24);

            double score = (n * ratio * 100.0)
                    + Math.min(textLen, 4000) / 20.0
                    + Math.min(linkCount, 200) * 2.0
                    + Math.min(imgCount, 100) * 3.0
                    + Math.min(avgChildComplexity, 60) * 1.5
                    + (hasMoneyOrDigit ? 50.0 : 0.0);

            String id = "";
            String cls = "";
            try { id = el.id(); } catch (Exception ignored) { id = ""; }
            try { cls = el.className(); } catch (Exception ignored) { cls = ""; }
            String mark = ((tag == null ? "" : tag) + " " + (id == null ? "" : id) + " " + (cls == null ? "" : cls)).toLowerCase();
            if (mark.contains("nav") || mark.contains("menu") || mark.contains("breadcrumb")) score *= 0.35;
            if (mark.contains("filter") || mark.contains("facet") || mark.contains("sort")) score *= 0.55;
            boolean isUlOl = "ul".equalsIgnoreCase(tag) || "ol".equalsIgnoreCase(tag);
            if (isUlOl && imgCount == 0 && !hasMoneyOrDigit && avgChildComplexity <= 4 && shortRatio >= 0.85) score *= 0.08;
            if (!isUlOl && imgCount == 0 && !hasMoneyOrDigit && avgChildComplexity <= 3 && shortRatio >= 0.9 && linkCount >= n) score *= 0.12;
            if (linkCount >= 30 && imgCount == 0 && !hasMoneyOrDigit && avgChildComplexity <= 3 && shortRatio >= 0.85) score *= 0.03;
            if ("footer".equalsIgnoreCase(tag) || hasAncestorTag(el, "footer") || mark.contains("footer")) score *= 0.12;

            if ("nav".equalsIgnoreCase(tag) || "header".equalsIgnoreCase(tag) || "footer".equalsIgnoreCase(tag)) {
                score *= 0.4;
            }
            if (score > bestScore) {
                bestScore = score;
                best = el;
            }
        }
        return best;
    }

    private static String buildCssSelectorHint(Element el) {
        if (el == null) return "";
        try {
            String id = el.id();
            if (id != null) {
                String v = id.trim();
                if (!v.isEmpty() && v.length() <= 64) return "#" + v;
            }
        } catch (Exception ignored) {}
        String tag = "";
        try { tag = el.tagName(); } catch (Exception ignored) { tag = ""; }
        if (tag == null || tag.trim().isEmpty()) tag = "div";
        String cls = "";
        try { cls = el.className(); } catch (Exception ignored) { cls = ""; }
        if (cls != null && !cls.trim().isEmpty()) {
            String[] parts = cls.trim().split("\\s+");
            int keep = Math.min(parts.length, 2);
            StringBuilder sb = new StringBuilder();
            sb.append(tag);
            for (int i = 0; i < keep; i++) {
                String p = parts[i];
                if (p == null || p.isEmpty()) continue;
                if (p.length() > 40) p = p.substring(0, 40);
                sb.append('.').append(p);
            }
            return sb.toString();
        }
        return tag;
    }

    private static String inferRowSelectorHint(Element container) {
        if (container == null) return "";
        Elements kids = null;
        try { kids = container.children(); } catch (Exception ignored) { kids = null; }
        if (kids == null || kids.isEmpty()) return "";
        java.util.HashMap<String, Integer> sigCounts = new java.util.HashMap<>();
        java.util.HashMap<String, Element> sigSample = new java.util.HashMap<>();
        for (Element c : kids) {
            if (c == null) continue;
            String cls = c.className();
            if (cls == null) cls = "";
            String firstClass = "";
            if (!cls.isEmpty()) {
                int sp = cls.indexOf(' ');
                firstClass = sp >= 0 ? cls.substring(0, sp) : cls;
            }
            String sig = c.tagName() + "|" + firstClass;
            sigCounts.put(sig, sigCounts.getOrDefault(sig, 0) + 1);
            if (!sigSample.containsKey(sig)) sigSample.put(sig, c);
        }
        String bestSig = "";
        int best = 0;
        for (java.util.Map.Entry<String, Integer> e : sigCounts.entrySet()) {
            int v = e.getValue() == null ? 0 : e.getValue();
            if (v > best) {
                best = v;
                bestSig = e.getKey();
            }
        }
        Element sample = sigSample.get(bestSig);
        if (sample == null) return "";
        return buildCssSelectorHint(sample);
    }

    private static String inferRowSelectorHintDeep(Element container) {
        if (container == null) return "";
        LevelPick lp = pickBestRepeatedLevel(container, 6);
        if (lp == null || lp.sample == null) return "";
        String sampleHint = buildCssSelectorHint(lp.sample);
        if (sampleHint == null || sampleHint.trim().isEmpty()) return "";
        if (lp.depth <= 1) return sampleHint.trim();
        StringBuilder sb = new StringBuilder();
        sb.append(":scope");
        for (int i = 0; i < lp.depth - 1; i++) {
            sb.append(" > *");
        }
        sb.append(" > ").append(sampleHint.trim());
        return sb.toString();
    }

    private static String extractListSnippetFromRawHtml(String html, int maxChars) {
        String s = html == null ? "" : html.trim();
        if (s.isEmpty()) return "";
        try {
            Document doc = Jsoup.parse(s);
            Element best = null;
            int bestTr = 0;
            for (Element table : doc.select("table")) {
                int tr = 0;
                try { tr = table.select("tr").size(); } catch (Exception ignored) { tr = 0; }
                if (tr >= 8 && tr > bestTr) {
                    bestTr = tr;
                    best = table;
                }
            }
            if (best == null) best = pickBestRepeatedListContainer(doc);
            if (best == null) return truncate(s, maxChars);

            Element clone = best.clone();
            String containerHint = buildCssSelectorHint(best);
            String rowHint = inferRowSelectorHintDeep(best);

            int keep = 20;
            if (rowHint != null && !rowHint.trim().isEmpty()) {
                try {
                    Elements rows = clone.select(rowHint.trim());
                    if (rows != null && rows.size() > keep) {
                        for (int i = rows.size() - 1; i >= keep; i--) {
                            try { rows.get(i).remove(); } catch (Exception ignored) {}
                        }
                    }
                } catch (Exception ignored) {}
            } else {
                Elements kids = clone.children();
                int k = kids == null ? 0 : kids.size();
                k = Math.min(k, keep);
                if (kids != null && kids.size() > k) {
                    for (int i = kids.size() - 1; i >= k; i--) {
                        try { kids.get(i).remove(); } catch (Exception ignored) {}
                    }
                }
            }

            String out = clone.outerHtml();
            String enriched = "LIST_SELECTOR_HINTS:\n" +
                    "containerSelector: " + (containerHint == null ? "" : containerHint) + "\n" +
                    "rowSelector: " + (rowHint == null ? "" : rowHint) + "\n" +
                    "SNIPPET:\n" + out;
            return truncate(enriched, maxChars);
        } catch (Exception ignored) {
            return truncate(s, maxChars);
        }
    }

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
     * 生成 PLAN_ONLY 模式 payload（显式指定入口 URL）
     *
     * @param currentUrl 当前 URL
     * @param userPrompt 用户任务描述
     * @param entryUrl 入口 URL（优先写入 USER_PROVIDED_URL）
     * @return payload 文本
     */
    static String buildPlanOnlyPayload(String currentUrl, String userPrompt, String entryUrl) {
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
        if (entryUrl != null && !entryUrl.trim().isEmpty()) {
            sb.append("USER_PROVIDED_URL: ").append(entryUrl.trim()).append("\n");
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
     * 生成 PLAN_ENTRY 模式 payload（显式指定入口 URL）
     *
     * @param currentUrl 当前 URL
     * @param userPrompt 用户任务描述
     * @param entryUrl 入口 URL（优先写入 USER_PROVIDED_URL）
     * @return payload 文本
     */
    static String buildPlanEntryPayload(String currentUrl, String userPrompt, String entryUrl) {
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
        if (entryUrl != null && !entryUrl.trim().isEmpty()) {
            sb.append("USER_PROVIDED_URL: ").append(entryUrl.trim()).append("\n");
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

    /**
     * 生成 CODEGEN 模式 payload（显式指定当前 URL）
     *
     * @param currentUrl 当前 URL（优先作为 CURRENT_PAGE_URL）
     * @param planText 计划文本
     * @param snapshots 步骤快照
     * @return payload 文本
     */
    static String buildCodegenPayload(String currentUrl, String planText, java.util.List<AutoWebAgent.HtmlSnapshot> snapshots) {
        String cur = currentUrl == null ? "" : currentUrl.trim();
        if (!cur.isEmpty() && !"about:blank".equalsIgnoreCase(cur)) {
            cur = PlanRoutingSupport.stripUrlQuery(cur);
        } else {
            cur = chooseCurrentUrl(cur, snapshots);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("MODE: CODEGEN\n");
        if (!cur.isEmpty()) {
            sb.append("CURRENT_PAGE_URL: ").append(PlanRoutingSupport.stripUrlQuery(cur)).append("\n");
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
     * 生成 CODEGEN 模式 payload（显式指定当前 URL）
     *
     * @param currentUrl 当前 URL（优先作为 CURRENT_PAGE_URL）
     * @param planText 计划文本
     * @param snapshots 步骤快照
     * @param visualDescription 可选视觉补充描述
     * @return payload 文本
     */
    static String buildCodegenPayload(String currentUrl, String planText, java.util.List<AutoWebAgent.HtmlSnapshot> snapshots, String visualDescription) {
        String cur = currentUrl == null ? "" : currentUrl.trim();
        if (!cur.isEmpty() && !"about:blank".equalsIgnoreCase(cur)) {
            cur = PlanRoutingSupport.stripUrlQuery(cur);
        } else {
            cur = chooseCurrentUrl(cur, snapshots);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("MODE: CODEGEN\n");
        if (!cur.isEmpty()) {
            sb.append("CURRENT_PAGE_URL: ").append(PlanRoutingSupport.stripUrlQuery(cur)).append("\n");
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
            if (looksLikeAriaSnapshotJson(body)) {
                AutoWebAgent.HtmlSnapshot raw = HtmlSnapshotDao.readCachedHtml(
                        snap.stepIndex,
                        snap.url,
                        snap.entryAction,
                        AutoWebAgent.HtmlCaptureMode.RAW_HTML,
                        false
                );
                if (raw != null && raw.cleanedHtml != null && !raw.cleanedHtml.trim().isEmpty()) {
                    String ariaPart = truncate(body, 200000);
                    String rawSnippet = extractListSnippetFromRawHtml(raw.cleanedHtml, 60000);
                    body = "ARIA_SNAPSHOT:\n" + ariaPart + "\n\nRAW_HTML_LIST_SNIPPET:\n" + rawSnippet;
                }
            }
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

    /**
     * 生成 REFINE_CODE 模式 payload（显式指定当前 URL）
     *
     * @param currentUrl 当前 URL（优先作为 CURRENT_PAGE_URL）
     * @param planText 计划文本
     * @param snapshots 步骤快照
     * @param currentCleanedHtml 当前页清洗后的 HTML
     * @param userPrompt 用户任务描述
     * @param refineHint 修正提示
     * @return payload 文本
     */
    static String buildRefinePayload(String currentUrl, String planText, java.util.List<AutoWebAgent.HtmlSnapshot> snapshots, String currentCleanedHtml, String userPrompt, String refineHint) {
        return buildRefinePayload(currentUrl, planText, snapshots, currentCleanedHtml, userPrompt, refineHint, null);
    }

    static String buildRefinePayload(String currentUrl, String planText, java.util.List<AutoWebAgent.HtmlSnapshot> snapshots, String currentCleanedHtml, String userPrompt, String refineHint, String visualDescription) {
        String cur = currentUrl == null ? "" : currentUrl.trim();
        if (!cur.isEmpty() && !"about:blank".equalsIgnoreCase(cur)) {
            cur = PlanRoutingSupport.stripUrlQuery(cur);
        } else {
            cur = chooseCurrentUrl(cur, snapshots);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("MODE: REFINE_CODE\n");
        if (!cur.isEmpty()) {
            sb.append("CURRENT_PAGE_URL: ").append(PlanRoutingSupport.stripUrlQuery(cur)).append("\n");
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
