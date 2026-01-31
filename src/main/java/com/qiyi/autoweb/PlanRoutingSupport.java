package com.qiyi.autoweb;

import com.microsoft.playwright.Page;

/**
 * 计划解析与页面上下文路由工具
 * 提供 URL 解析、入口选择、上下文扫描与执行前导航能力
 */
class PlanRoutingSupport {
    /**
     * 判断字符串是否看起来是 URL
     */
    static boolean looksLikeUrl(String s) {
        if (s == null) return false;
        String v = s.trim();
        return v.startsWith("http://") || v.startsWith("https://");
    }

    /**
     * 去除 URL 的 query 参数，保留 hash
     */
    static String stripUrlQuery(String s) {
        if (s == null) return "";
        String v = s.trim();
        if (v.isEmpty()) return v;
        int q = v.indexOf('?');
        if (q < 0) return v;
        int h = v.indexOf('#');
        if (h >= 0 && h > q) return (v.substring(0, q) + v.substring(h)).trim();
        return v.substring(0, q).trim();
    }

    /**
     * 去掉反引号/引号包裹的 URL token
     */
    static String normalizeUrlToken(String s) {
        if (s == null) return null;
        String v = s.trim();
        if (v.isEmpty()) return v;
        for (int i = 0; i < 3; i++) {
            String t = v.trim();
            if (t.length() >= 2 && t.startsWith("`") && t.endsWith("`")) {
                v = t.substring(1, t.length() - 1).trim();
                continue;
            }
            if (t.length() >= 2 && t.startsWith("\"") && t.endsWith("\"")) {
                v = t.substring(1, t.length() - 1).trim();
                continue;
            }
            if (t.length() >= 2 && t.startsWith("'") && t.endsWith("'")) {
                v = t.substring(1, t.length() - 1).trim();
                continue;
            }
            break;
        }
        if (v.contains("`")) v = v.replace("`", "").trim();
        return v;
    }

    /**
     * 等待页面 URL 前缀匹配目标地址（忽略 query）
     */
    static boolean waitForUrlPrefix(Page page, String expectedPrefix, long maxWaitMs, long intervalMs, java.util.function.Consumer<String> uiLogger, String stage) {
        if (page == null) return false;
        if (expectedPrefix == null || expectedPrefix.trim().isEmpty()) return true;
        String target = stripUrlQuery(expectedPrefix);
        if (!looksLikeUrl(target)) return true;
        long start = System.currentTimeMillis();
        long deadline = start + Math.max(0, maxWaitMs);
        int tries = 0;
        String lastUrl = "";
        while (System.currentTimeMillis() < deadline) {
            String cur = StorageSupport.safePageUrl(page);
            if (cur == null) cur = "";
            cur = stripUrlQuery(cur);
            if (!cur.isEmpty() && cur.startsWith(target)) return true;

            if (uiLogger != null) {
                boolean shouldLog = tries == 0 || tries % 5 == 0 || !cur.equals(lastUrl);
                if (shouldLog) {
                    long elapsed = System.currentTimeMillis() - start;
                    long remain = Math.max(0, maxWaitMs - elapsed);
                    uiLogger.accept((stage == null ? "等待页面" : stage) + ": 当前URL=" + (cur.isEmpty() ? "(empty)" : cur) + "，等待到目标URL前缀=" + target + "（忽略参数，可能需要登录），剩余=" + (remain / 1000) + "s");
                }
            }
            lastUrl = cur;
            tries++;
            try {
                synchronized (AutoWebAgent.PLAYWRIGHT_LOCK) {
                    page.waitForTimeout(Math.max(0, intervalMs));
                }
            } catch (Exception ignored) {}
        }
        if (uiLogger != null) {
            String cur = StorageSupport.safePageUrl(page);
            uiLogger.accept((stage == null ? "等待页面" : stage) + ": 等待超时，未到达目标URL前缀=" + target + "，current=" + stripUrlQuery(cur == null ? "" : cur));
        }
        return false;
    }

    /**
     * 从计划、快照与用户提示中选出执行入口 URL
     */
    static String chooseExecutionEntryUrl(AutoWebAgent.ModelSession session, String currentPrompt) {
        try {
            if (session != null && session.steps != null && !session.steps.isEmpty()) {
                java.util.List<AutoWebAgent.PlanStep> steps = new java.util.ArrayList<>(session.steps);
                steps.sort(java.util.Comparator.comparingInt(a -> a == null ? Integer.MAX_VALUE : a.index));
                for (AutoWebAgent.PlanStep s : steps) {
                    if (s == null || s.targetUrl == null) continue;
                    String u = s.targetUrl.trim();
                    if (u.isEmpty()) continue;
                    if ("CURRENT_PAGE".equalsIgnoreCase(u)) continue;
                    if (looksLikeUrl(u)) return u;
                }
            }
            if (session != null && session.stepSnapshots != null && !session.stepSnapshots.isEmpty()) {
                java.util.List<AutoWebAgent.HtmlSnapshot> snaps = new java.util.ArrayList<>(session.stepSnapshots.values());
                snaps.sort(java.util.Comparator.comparingInt(a -> a == null ? Integer.MAX_VALUE : a.stepIndex));
                for (AutoWebAgent.HtmlSnapshot s : snaps) {
                    if (s == null || s.url == null) continue;
                    String u = s.url.trim();
                    if (looksLikeUrl(u)) return u;
                }
            }
            if (session != null && session.userPrompt != null) {
                String u = extractFirstUrlFromText(session.userPrompt);
                if (looksLikeUrl(u)) return u;
            }
        } catch (Exception ignored) {}
        String u = extractFirstUrlFromText(currentPrompt);
        return looksLikeUrl(u) ? u : null;
    }

    /**
     * 执行前导航到目标 URL，并等待页面加载完成
     */
    static boolean ensureRootPageAtUrl(Page rootPage, String targetUrl, java.util.function.Consumer<String> uiLogger) {
        if (rootPage == null) return false;
        if (targetUrl == null || targetUrl.trim().isEmpty()) return false;
        String desiredRaw = targetUrl.trim();
        String desired = stripUrlQuery(desiredRaw);
        if (!looksLikeUrl(desired)) return false;

        synchronized (AutoWebAgent.PLAYWRIGHT_LOCK) {
            String current = StorageSupport.safePageUrl(rootPage);
            if (current == null) current = "";
            current = stripUrlQuery(current);
            boolean alreadyOk = false;
            if (!current.isEmpty() && !"about:blank".equalsIgnoreCase(current)) {
                alreadyOk = current.startsWith(desired) || desired.startsWith(current);
            }
            if (alreadyOk) {
                if (uiLogger != null) uiLogger.accept("执行前导航: 已在目标页面 | current=" + current);
                return false;
            }
            if (uiLogger != null) uiLogger.accept("执行前导航: current=" + (current.isEmpty() ? "(empty)" : current) + " -> target=" + desired);
            try {
                rootPage.bringToFront();
            } catch (Exception ignored) {}
            try {
                rootPage.navigate(desiredRaw, new Page.NavigateOptions().setTimeout(30000));
            } catch (Exception navEx) {
                if (uiLogger != null) uiLogger.accept("执行前导航失败: " + navEx.getMessage());
                return false;
            }
            try {
                rootPage.waitForLoadState(com.microsoft.playwright.options.LoadState.NETWORKIDLE, new Page.WaitForLoadStateOptions().setTimeout(15000));
            } catch (Exception ignored) {
                try {
                    rootPage.waitForLoadState(com.microsoft.playwright.options.LoadState.DOMCONTENTLOADED, new Page.WaitForLoadStateOptions().setTimeout(15000));
                } catch (Exception ignored2) {}
            }
            try { rootPage.waitForTimeout(500); } catch (Exception ignored) {}
            boolean ok = waitForUrlPrefix(rootPage, desiredRaw, 120000, 2000, uiLogger, "执行前导航");
            if (!ok) {
                String cur = StorageSupport.safePageUrl(rootPage);
                throw new RuntimeException("执行前未到达目标页面，可能需要登录。current=" + (cur == null ? "" : cur));
            }
            try {
                if (uiLogger != null) uiLogger.accept("执行前导航完成: current=" + StorageSupport.safePageUrl(rootPage));
            } catch (Exception ignored) {}
            return true;
        }
    }

    /**
     * 扫描页面与 iframe 上下文，选择最佳内容区域
     */
    static AutoWebAgent.ScanResult scanContexts(Page page) {
        synchronized (AutoWebAgent.PLAYWRIGHT_LOCK) {
            AutoWebAgent.ScanResult result = new AutoWebAgent.ScanResult();

            AutoWebAgent.ContextWrapper mainPageWrapper = new AutoWebAgent.ContextWrapper();
            mainPageWrapper.context = page;
            mainPageWrapper.name = "Main Page";
            result.wrappers.add(mainPageWrapper);
            result.best = mainPageWrapper;

            double maxArea = 0;

            System.out.println("Scanning frames...");
            AutoWebAgent.ContextWrapper firstFrame = null;
            AutoWebAgent.ContextWrapper bestUrlFrame = null;
            String pageUrl = "";
            try {
                pageUrl = page.url();
                if (pageUrl == null) pageUrl = "";
                pageUrl = pageUrl.trim();
            } catch (Exception ignored) {}
            for (com.microsoft.playwright.Frame f : page.frames()) {
                if (f == page.mainFrame()) continue;

                try {
                    AutoWebAgent.ContextWrapper fw = new AutoWebAgent.ContextWrapper();
                    fw.context = f;
                    String fName = f.name();
                    if (fName == null || fName.isEmpty()) fName = "anonymous";
                    fw.name = "Frame: " + fName + " (" + f.url() + ")";

                    result.wrappers.add(fw);
                    if (firstFrame == null) firstFrame = fw;

                    String fUrl = "";
                    try {
                        fUrl = f.url();
                        if (fUrl == null) fUrl = "";
                        fUrl = fUrl.trim();
                    } catch (Exception ignored) {}
                    if (!fUrl.isEmpty() && !"about:blank".equalsIgnoreCase(fUrl) && looksLikeUrl(fUrl)) {
                        boolean differentFromPage = pageUrl.isEmpty() || !fUrl.equalsIgnoreCase(pageUrl);
                        if (differentFromPage) {
                            if (bestUrlFrame == null) {
                                bestUrlFrame = fw;
                            } else {
                                String bestUrl = "";
                                try {
                                    if (bestUrlFrame.context instanceof com.microsoft.playwright.Frame) {
                                        bestUrl = ((com.microsoft.playwright.Frame) bestUrlFrame.context).url();
                                    }
                                } catch (Exception ignored) {}
                                if (bestUrl == null) bestUrl = "";
                                bestUrl = bestUrl.trim();
                                if (bestUrl.isEmpty() || fUrl.length() > bestUrl.length()) {
                                    bestUrlFrame = fw;
                                }
                            }
                        }
                    }

                    com.microsoft.playwright.ElementHandle element = f.frameElement();
                    double area = 0;
                    boolean isVisible = false;

                    if (element != null) {
                        com.microsoft.playwright.options.BoundingBox box = element.boundingBox();
                        if (box != null) {
                            area = box.width * box.height;
                            if (box.width > 0 && box.height > 0) {
                                isVisible = true;
                            }
                        }
                    }

                    System.out.println(" - Found Frame: " + fName + " | Area: " + area + " | Visible: " + isVisible);

                    if (isVisible && area > maxArea) {
                        maxArea = area;
                        result.best = fw;
                    }
                } catch (Exception e) {
                    System.out.println(" - Error checking frame " + f.name() + ": " + e.getMessage());
                }
            }

            if (result.best == mainPageWrapper && firstFrame != null) {
                if (bestUrlFrame != null) {
                    System.out.println(" - No definitely visible frame found. Fallback to URL frame: " + bestUrlFrame.name);
                    result.best = bestUrlFrame;
                } else {
                    System.out.println(" - No definitely visible frame found. Fallback to first found frame: " + firstFrame.name);
                    result.best = firstFrame;
                }
            }

            System.out.println("Scan complete. Best candidate: " + result.best.name);
            return result;
        }
    }

    static AutoWebAgent.ContextWrapper selectBestContext(Page rootPage, java.util.function.Consumer<String> uiLogger) {
        AutoWebAgent.ScanResult res = scanContexts(rootPage);
        if (res != null && res.best != null) {
            if (uiLogger != null) uiLogger.accept("已自动选中最佳上下文: " + res.best.name);
            return res.best;
        }
        AutoWebAgent.ContextWrapper fallback = new AutoWebAgent.ContextWrapper();
        fallback.context = rootPage;
        fallback.name = "Main Page";
        if (uiLogger != null) uiLogger.accept("未能找到合适的上下文，回退使用主页面。");
        return fallback;
    }

    static AutoWebAgent.ContextWrapper reloadAndFindContext(Page rootPage, java.util.function.Consumer<String> uiLogger) {
        if (uiLogger != null) uiLogger.accept("正在刷新页面并重新识别最佳上下文...");
        synchronized (AutoWebAgent.PLAYWRIGHT_LOCK) {
            try {
                rootPage.reload(new Page.ReloadOptions().setTimeout(15000));
            } catch (Exception reloadEx) {
                if (uiLogger != null) uiLogger.accept("Warning during reload: " + reloadEx.getMessage());
            }

            try {
                rootPage.waitForLoadState(com.microsoft.playwright.options.LoadState.DOMCONTENTLOADED,
                        new Page.WaitForLoadStateOptions().setTimeout(12000));
            } catch (Exception ignored) {}

            long deadline = System.currentTimeMillis() + 8000;
            int tries = 0;
            while (System.currentTimeMillis() < deadline) {
                AutoWebAgent.ContextWrapper best = selectBestContext(rootPage, null);
                if (best != null && best.name != null && !"Main Page".equals(best.name)) {
                    if (uiLogger != null) uiLogger.accept("已识别到内容上下文: " + best.name);
                    return best;
                }
                tries++;
                if (uiLogger != null && tries % 4 == 0) uiLogger.accept("等待 iframe 就绪中...");
                try { rootPage.waitForTimeout(500); } catch (Exception ignored) {}
            }
            return selectBestContext(rootPage, uiLogger);
        }
    }

    static AutoWebAgent.ContextWrapper waitAndFindContext(Page rootPage, java.util.function.Consumer<String> uiLogger) {
        if (rootPage == null) {
            AutoWebAgent.ContextWrapper fallback = new AutoWebAgent.ContextWrapper();
            fallback.context = null;
            fallback.name = "Main Page";
            return fallback;
        }
        synchronized (AutoWebAgent.PLAYWRIGHT_LOCK) {
            long deadline = System.currentTimeMillis() + 8000;
            while (System.currentTimeMillis() < deadline) {
                AutoWebAgent.ContextWrapper best = selectBestContext(rootPage, null);
                if (best != null && best.name != null && !"Main Page".equals(best.name)) {
                    if (uiLogger != null) uiLogger.accept("已自动选中最佳上下文: " + best.name);
                    return best;
                }
                try { rootPage.waitForTimeout(500); } catch (Exception ignored) {}
            }
            return selectBestContext(rootPage, uiLogger);
        }
    }

    static String extractFirstUrlFromText(String text) {
        if (text == null) return null;
        try {
            java.util.regex.Pattern p = java.util.regex.Pattern.compile("https?://[^\\s`'\"，。,）\\)]+");
            java.util.regex.Matcher m = p.matcher(text);
            if (m.find()) {
                String url = m.group();
                if (url == null) return null;
                url = url.trim();
                while (!url.isEmpty()) {
                    char last = url.charAt(url.length() - 1);
                    if (last == '，' || last == '。' || last == ',' || last == '.' || last == ')' || last == '）') {
                        url = url.substring(0, url.length() - 1).trim();
                        continue;
                    }
                    break;
                }
                return url.isEmpty() ? null : url;
            }
        } catch (Exception ignored) {}
        return null;
    }

    static java.util.LinkedHashMap<String, String> extractUrlMappingsFromText(String text) {
        java.util.LinkedHashMap<String, String> out = new java.util.LinkedHashMap<>();
        if (text == null) return out;

        java.util.regex.Pattern lineLabelUrl = java.util.regex.Pattern.compile("(?m)^\\s*([^\\n:：]{1,40})\\s*[:：]\\s*(https?://[^\\s`'\"，。,）\\)]+)\\s*$");
        java.util.regex.Matcher m1 = lineLabelUrl.matcher(text);
        while (m1.find()) {
            String label = m1.group(1);
            String url = m1.group(2);
            url = sanitizeUrl(url);
            if (url == null) continue;
            label = label == null ? "" : label.trim();
            if (!label.isEmpty()) out.put(label, url);
        }

        java.util.regex.Pattern urlParenLabel = java.util.regex.Pattern.compile("(https?://[^\\s`'\"，。,）\\)]+)\\s*[（(]\\s*([^\\)）\\n]{1,40})\\s*[)）]");
        java.util.regex.Matcher m2 = urlParenLabel.matcher(text);
        while (m2.find()) {
            String url = sanitizeUrl(m2.group(1));
            String label = m2.group(2);
            if (url == null) continue;
            label = label == null ? "" : label.trim();
            if (!label.isEmpty()) out.put(label, url);
        }

        java.util.regex.Pattern urlUsedFor = java.util.regex.Pattern.compile("(https?://[^\\s`'\"，。,）\\)]+)[^\\n]{0,30}?(?:用于|用来|做|处理|完成)\\s*([^\\n，。,]{1,40})");
        java.util.regex.Matcher m3 = urlUsedFor.matcher(text);
        while (m3.find()) {
            String url = sanitizeUrl(m3.group(1));
            String label = m3.group(2);
            if (url == null) continue;
            label = label == null ? "" : label.trim();
            if (!label.isEmpty()) out.put(label, url);
        }

        java.util.regex.Pattern anyUrl = java.util.regex.Pattern.compile("https?://[^\\s`'\"，。,）\\)]+");
        java.util.regex.Matcher m4 = anyUrl.matcher(text);
        int idx = 1;
        while (m4.find()) {
            String url = sanitizeUrl(m4.group());
            if (url == null) continue;
            boolean exists = out.values().stream().anyMatch(v -> v.equals(url));
            if (exists) continue;
            out.put("URL_" + idx, url);
            idx++;
        }

        return out;
    }

    private static String sanitizeUrl(String url) {
        if (url == null) return null;
        String v = url.trim();
        while (!v.isEmpty()) {
            char last = v.charAt(v.length() - 1);
            if (last == '，' || last == '。' || last == ',' || last == '.' || last == ')' || last == '）') {
                v = v.substring(0, v.length() - 1).trim();
                continue;
            }
            break;
        }
        return v.isEmpty() ? null : v;
    }

    static String firstQuotedToken(String s) {
        if (s == null) return null;
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("['\"“”](.+?)['\"“”]");
        java.util.regex.Matcher m = p.matcher(s);
        if (m.find()) {
            String t = m.group(1);
            return (t == null || t.trim().isEmpty()) ? null : t.trim();
        }
        return null;
    }

    static AutoWebAgent.PlanParseResult parsePlanFromText(String text) {
        AutoWebAgent.PlanParseResult res = new AutoWebAgent.PlanParseResult();
        if (text == null) return res;

        String src = text;
        int ps = src.indexOf("PLAN_START");
        int pe = src.indexOf("PLAN_END");
        if (ps >= 0 && pe > ps) {
            res.planText = src.substring(ps, pe + "PLAN_END".length());
        } else {
            res.planText = src;
        }

        String upper = src.toUpperCase();
        res.hasQuestion = upper.contains("QUESTION:");
        res.confirmed = !upper.contains("STATUS: UNKNOWN") && !res.hasQuestion;

        java.util.regex.Pattern stepHeader = java.util.regex.Pattern.compile("(?mi)^\\s*\\**Step\\s+(\\d+)\\**[:：]?\\s*$");
        java.util.regex.Matcher mh = stepHeader.matcher(src);
        java.util.List<Integer> stepStarts = new java.util.ArrayList<>();
        java.util.List<Integer> stepNums = new java.util.ArrayList<>();
        while (mh.find()) {
            stepStarts.add(mh.start());
            try {
                stepNums.add(Integer.parseInt(mh.group(1)));
            } catch (Exception ignored) {
                stepNums.add(stepNums.size() + 1);
            }
        }
        if (stepNums.isEmpty()) {
            res.confirmed = false;
            return res;
        }
        stepStarts.add(src.length());

        for (int i = 0; i < stepNums.size(); i++) {
            int start = stepStarts.get(i);
            int end = stepStarts.get(i + 1);
            String block = src.substring(start, Math.min(end, src.length()));

            AutoWebAgent.PlanStep step = new AutoWebAgent.PlanStep();
            step.index = stepNums.get(i);
            step.description = matchFirst(block, "(?mi)^\\s*-\\s*\\**Description\\**\\s*[:：]\\s*(.*)$");
            step.targetUrl = matchFirst(block, "(?mi)^\\s*-\\s*\\**Target\\s+URL\\**\\s*[:：]\\s*(.*)$");
            step.entryAction = matchFirst(block, "(?mi)^\\s*-\\s*\\**Entry\\s+Point\\s+Action\\**\\s*[:：]\\s*(.*)$");
            step.status = matchFirst(block, "(?mi)^\\s*-\\s*\\**Status\\**\\s*[:：]\\s*(.*)$");

            if (step.targetUrl != null) {
                step.targetUrl = step.targetUrl.trim().replaceAll("[`*]", "");
            }
            if (step.entryAction != null) step.entryAction = step.entryAction.trim();
            if (step.status != null) step.status = step.status.trim();
            res.steps.add(step);
        }

        if (!res.steps.isEmpty()) {
            boolean anyUnknown = false;
            for (AutoWebAgent.PlanStep s : res.steps) {
                if (s.status != null && s.status.toUpperCase().contains("UNKNOWN")) {
                    anyUnknown = true;
                    break;
                }
            }
            res.confirmed = res.confirmed && !anyUnknown;
        }

        return res;
    }

    private static java.util.List<String> inferMissingEntryLabels(java.util.List<String> modelNames, java.util.Map<String, AutoWebAgent.ModelSession> sessionsByModel) {
        java.util.LinkedHashSet<String> labels = new java.util.LinkedHashSet<>();
        if (modelNames == null || modelNames.isEmpty() || sessionsByModel == null) {
            return new java.util.ArrayList<>(labels);
        }
        for (String model : modelNames) {
            if (model == null) continue;
            AutoWebAgent.ModelSession session = sessionsByModel.get(model);
            if (session == null || session.steps == null) continue;
            for (AutoWebAgent.PlanStep step : session.steps) {
                if (step == null) continue;
                String status = step.status == null ? "" : step.status.trim().toUpperCase();
                String targetUrl = step.targetUrl == null ? "" : step.targetUrl.trim();
                String entryAction = step.entryAction == null ? "" : step.entryAction.trim();

                boolean needs = false;
                if (!status.isEmpty() && status.contains("UNKNOWN")) needs = true;
                if (targetUrl.isEmpty()) needs = true;
                String targetUpper = targetUrl.toUpperCase();
                if (targetUpper.contains("UNKNOWN")) needs = true;
                if (targetUrl.startsWith("[") && targetUrl.endsWith("]")) needs = true;
                if (targetUrl.contains("用户") && targetUpper.contains("URL")) needs = true;
                if (!needs) {
                    String ea = entryAction.toUpperCase();
                    if (ea.contains("POPUP") || ea.contains("INPUT")) {
                        if (!looksLikeUrl(targetUrl) && !"CURRENT_PAGE".equalsIgnoreCase(targetUrl)) {
                            needs = true;
                        }
                    }
                }
                if (!needs) continue;

                String label = null;
                String desc = step.description == null ? "" : step.description.trim();
                if (!desc.isEmpty()) {
                    if (desc.startsWith("[") && desc.endsWith("]") && desc.length() > 2) {
                        desc = desc.substring(1, desc.length() - 1).trim();
                    }
                    java.util.regex.Matcher m = java.util.regex.Pattern.compile("([\\u4e00-\\u9fa5A-Za-z0-9_\\-]{2,40}页面)").matcher(desc);
                    if (m.find()) label = m.group(1);
                }
                if ((label == null || label.trim().isEmpty()) && !desc.isEmpty()) {
                    label = desc;
                }
                if (label == null || label.trim().isEmpty()) {
                    label = "Step " + step.index + " 入口地址";
                }
                label = label.trim();
                if (!label.isEmpty()) labels.add(label);
            }
        }
        return new java.util.ArrayList<>(labels);
    }

    static String buildEntryInputHint(java.util.List<String> needModels, java.util.Map<String, AutoWebAgent.ModelSession> sessionsByModel) {
        java.util.List<String> labels = inferMissingEntryLabels(needModels, sessionsByModel);
        StringBuilder sb = new StringBuilder();
        sb.append("需要补充以下操作入口地址（单个地址一行）：\n");
        if (labels.isEmpty()) {
            sb.append("- 入口地址（示例：订单管理页面）\n");
        } else {
            for (String l : labels) {
                sb.append("- ").append(l).append("\n");
            }
        }
        sb.append("\n请按上述名称逐行输入（推荐格式：名称: URL）：\n");
        if (!labels.isEmpty()) {
            int n = Math.min(6, labels.size());
            for (int i = 0; i < n; i++) {
                sb.append(labels.get(i)).append(": https://\n");
            }
        } else {
            sb.append("订单管理页面: https://\n");
        }
        if (needModels != null && !needModels.isEmpty()) {
            sb.append("\n影响模型: ").append(String.join("，", needModels));
        }
        return sb.toString();
    }

    static String matchFirst(String src, String regex) {
        if (src == null) return null;
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(regex);
        java.util.regex.Matcher m = p.matcher(src);
        if (m.find()) {
            String v = m.group(1);
            return v == null ? null : v.trim();
        }
        return null;
    }
}
