package com.qiyi.autoweb;

import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import com.alibaba.fastjson2.JSON;
import com.qiyi.config.AppConfig;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 面向 LLM 的高层自动化 DSL
 * 封装 Playwright 常用能力，降低脚本生成幻觉与执行错误
 */
public class WebDSL {

    private final Page page;
    private Frame frame;
    private String savedFrameName;
    private String savedFrameUrl;
    private final Consumer<String> logger;
    private int defaultTimeout;
    private int maxRetries = 3;
    
    private static final Pattern ARIA_LABEL_ONLY_PATTERN = Pattern.compile("^\\s*\\[\\s*aria-label\\s*=\\s*(['\"])(.*?)\\1\\s*\\]\\s*$", Pattern.CASE_INSENSITIVE);

    /**
     * 创建 DSL 上下文，支持 Page 或 Frame
     */
    public WebDSL(Object context, Consumer<String> logger) {
        if (context instanceof Frame) {
            this.frame = (Frame) context;
            this.page = this.frame.page();
            try {
                this.savedFrameName = this.frame.name();
                this.savedFrameUrl = this.frame.url();
            } catch (Exception ignored) {}
        } else {
            this.page = (Page) context;
            this.frame = null;
        }
        this.logger = logger;
        this.defaultTimeout = AppConfig.getInstance().getAutowebWaitForLoadStateTimeoutMs();
    }

    /**
     * 设置默认超时时间
     */
    public WebDSL withDefaultTimeout(int timeoutMs) {
        this.defaultTimeout = timeoutMs > 0 ? timeoutMs : AppConfig.getInstance().getAutowebWaitForLoadStateTimeoutMs();
        return this;
    }
    
    /**
     * 设置操作重试次数
     */
    public WebDSL withMaxRetries(int retries) {
        this.maxRetries = Math.max(1, retries);
        return this;
    }

    /**
     * 确保 Frame 可用，必要时自动恢复
     */
    private Frame ensureFrame() {
        if (frame == null) return null;
        if (!frame.isDetached()) return frame;
        
        log("Notice: Current frame is detached. Attempting to re-locate frame...");
        
        // 1. Try name
        if (savedFrameName != null && !savedFrameName.isEmpty()) {
            Frame f = page.frame(savedFrameName);
            if (f != null && !f.isDetached()) {
                this.frame = f;
                log("  -> Recovered frame by name: " + savedFrameName);
                return f;
            }
        }
        
        // 2. Try URL match
        if (savedFrameUrl != null && !savedFrameUrl.isEmpty()) {
            for (Frame f : page.frames()) {
                if (!f.isDetached() && savedFrameUrl.equals(f.url())) {
                    this.frame = f;
                    log("  -> Recovered frame by URL: " + savedFrameUrl);
                    return f;
                }
            }
        }
        
        log("  -> Failed to recover frame. Falling back to main page context.");
        this.frame = null; // Downgrade to page
        return null;
    }

    /**
     * 生成更稳健的定位器，支持 a11y 风格别名
     */
    private Locator locator(String selector) {
        Frame f = ensureFrame();
        Locator a11y = tryBuildA11yLocator(f, selector);
        if (a11y != null) return a11y;
        
        String normalized = normalizeSelectorString(selector);
        if (selector != null && normalized != null && !normalized.equals(selector)) {
            log("Selector: Normalize '" + selector + "' -> '" + normalized + "'");
        }
        if (f != null) {
            return f.locator(normalized);
        } else {
            return page.locator(normalized);
        }
    }
    
    private String extractOnlyAriaLabel(String selector) {
        if (selector == null) return null;
        Matcher m = ARIA_LABEL_ONLY_PATTERN.matcher(selector);
        if (!m.matches()) return null;
        String v = m.group(2);
        if (v == null) return null;
        v = v.trim();
        return v.isEmpty() ? null : v;
    }
    
    private String normalizeLabelTextForSearch(String label) {
        if (label == null) return null;
        String v = label.trim();
        if (v.startsWith("请输入")) v = v.substring("请输入".length()).trim();
        if (v.isEmpty()) return null;
        return v;
    }
    
    private Locator resolveInputLocatorFromAriaLabel(String labelRaw) {
        String label = firstNonEmpty(labelRaw);
        if (label == null) return null;
        String label2 = normalizeLabelTextForSearch(label);
        Frame f = ensureFrame();
        String[] candidates = (label2 != null && !label2.equals(label))
                ? new String[]{label, label2}
                : new String[]{label};
        
        for (String v : candidates) {
            try {
                Locator byLabel = (f != null) ? f.getByLabel(v) : page.getByLabel(v);
                waitForLocatorAttached(byLabel.first(), 500);
                if (byLabel.count() > 0) return byLabel;
            } catch (Exception ignored) {}
            try {
                Locator byPlaceholder = (f != null) ? f.getByPlaceholder(v) : page.getByPlaceholder(v);
                waitForLocatorAttached(byPlaceholder.first(), 500);
                if (byPlaceholder.count() > 0) return byPlaceholder;
            } catch (Exception ignored) {}
            try {
                String esc = escapeForSelectorValue(v);
                String roleSel = "role=textbox[name=\"" + esc + "\"]";
                Locator byRole = (f != null) ? f.locator(roleSel) : page.locator(roleSel);
                waitForLocatorAttached(byRole.first(), 500);
                if (byRole.count() > 0) return byRole;
            } catch (Exception ignored) {}
            try {
                String esc = escapeForSelectorValue(v);
                String css = "input[placeholder=\"" + esc + "\"], textarea[placeholder=\"" + esc + "\"]";
                Locator byCss = (f != null) ? f.locator(css) : page.locator(css);
                waitForLocatorAttached(byCss.first(), 500);
                if (byCss.count() > 0) return byCss;
            } catch (Exception ignored) {}
        }
        
        return null;
    }
    
    private boolean isLoginPageLikely() {
        try {
            String title = page.title();
            if (title == null) title = "";
            boolean titleLogin = title.contains("登录");
            if (!titleLogin) return false;
            boolean hasPwd = false;
            boolean hasLoginBtn = false;
            boolean hasBrand = false;
            try { hasPwd = page.locator("input[type='password']").count() > 0; } catch (Exception ignored) {}
            try { hasLoginBtn = page.locator("button:has-text('登录'), input[type='submit'][value*='登录']").count() > 0; } catch (Exception ignored) {}
            try { hasBrand = page.locator("text=聚水潭").count() > 0; } catch (Exception ignored) {}
            return hasPwd || hasLoginBtn || hasBrand;
        } catch (Exception ignored) {
            return false;
        }
    }
    
    private boolean isTimeoutError(RuntimeException e) {
        if (e == null) return false;
        String m = e.getMessage();
        if (m == null) m = "";
        return m.contains("Timeout") || m.contains("TimeoutError") || m.contains("timeout");
    }
    
    private RuntimeException rewriteIfLogin(RuntimeException e, String desc) {
        if (!isTimeoutError(e)) return null;
        if (!isLoginPageLikely()) return null;
        String url = "";
        String title = "";
        try { url = page.url(); } catch (Exception ignored) {}
        try { title = page.title(); } catch (Exception ignored) {}
        String msg = "当前页面疑似登录页，无法继续执行（" + (desc == null ? "" : desc) + "）。请先在浏览器完成登录后重试。title=" + (title == null ? "" : title) + ", url=" + (url == null ? "" : url);
        return new RuntimeException(msg, e);
    }
    
    private static final Pattern A11Y_ALIAS_PATTERN = Pattern.compile("^\\s*(a11y\\s*:\\s*)?(textbox|combobox|button|link|checkbox|radio|tab|option|menuitem)\\s*(\\[.*\\])?\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern A11Y_ATTR_PATTERN = Pattern.compile("\\[\\s*([a-zA-Z_][\\w-]*)\\s*=\\s*(['\"])(.*?)\\2\\s*\\]");
    
    private Locator tryBuildA11yLocator(Frame f, String selector) {
        if (selector == null) return null;
        if (selector.contains(">>")) return null;
        
        Matcher m = A11Y_ALIAS_PATTERN.matcher(selector);
        if (!m.matches()) return null;
        
        boolean forcedA11y = m.group(1) != null && !m.group(1).trim().isEmpty();
        String alias = m.group(2) == null ? "" : m.group(2).toLowerCase();
        Map<String, String> attrs = parseA11yAttrs(selector);
        // 对 button/link 等“既可能是 a11y 别名、也可能是 HTML 标签名”的场景做保护：
        // 没有 label/placeholder/role 等强 a11y 特征时，避免误把普通 CSS selector 当成 a11y selector
        if (!forcedA11y && isAmbiguousTagAlias(alias) && !hasA11yOnlyAttrs(attrs)) return null;
        String role = aliasToRole(alias);
        
        // 优先级：placeholder/label 更稳定（受 UI 文案影响小、定位更精准），其次走 role+name，最后退到纯 role
        String placeholder = firstNonEmpty(attrs.get("placeholder"));
        if (placeholder != null) {
            if (alias.equals("textbox") || alias.equals("combobox")) {
                log("Selector: A11y '" + selector + "' -> byPlaceholder '" + placeholder + "'");
                if (f != null) return f.getByPlaceholder(placeholder);
                return page.getByPlaceholder(placeholder);
            }
        }
        
        String label = firstNonEmpty(attrs.get("label"));
        if (label != null) {
            if (alias.equals("textbox") || alias.equals("combobox") || alias.equals("checkbox") || alias.equals("radio")) {
                log("Selector: A11y '" + selector + "' -> byLabel '" + label + "'");
                if (f != null) return f.getByLabel(label);
                return page.getByLabel(label);
            }
        }
        
        String name = firstNonEmpty(attrs.get("name"));
        if (name == null) name = firstNonEmpty(attrs.get("text"));
        if (name == null) name = firstNonEmpty(attrs.get("title"));
        if (name == null) name = label;
        if (name != null) {
            String roleSelector = buildRoleSelector(role, name, attrs);
            if (roleSelector != null) {
                log("Selector: A11y '" + selector + "' -> '" + roleSelector + "'");
                if (f != null) return f.locator(roleSelector);
                return page.locator(roleSelector);
            }
        }
        
        AriaRole ariaRole = aliasToAriaRole(alias);
        if (ariaRole != null) {
            log("Selector: A11y '" + selector + "' -> getByRole " + ariaRole);
            if (f != null) return f.getByRole(ariaRole);
            return page.getByRole(ariaRole);
        }
        
        return null;
    }
    
    private String normalizeSelectorString(String selector) {
        if (selector == null) return null;
        String trimmed = selector.trim();
        if (trimmed.isEmpty()) return selector;
        String lower = trimmed.toLowerCase();
        if (!(lower.contains("textbox")
                || lower.contains("combobox")
                || lower.contains("button")
                || lower.contains("link")
                || lower.contains("checkbox")
                || lower.contains("radio")
                || lower.contains("tab")
                || lower.contains("option")
                || lower.contains("menuitem"))) return selector;
        
        // 支持形如：textbox[label="用户名"] >> button[text="登录"] 的链式 selector
        if (trimmed.contains(">>")) {
            String[] parts = trimmed.split(">>");
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < parts.length; i++) {
                String part = parts[i].trim();
                if (!part.isEmpty()) {
                    part = normalizeA11yAliasSegment(part);
                }
                if (i > 0) sb.append(" >> ");
                sb.append(part);
            }
            return sb.toString();
        }
        
        return normalizeA11yAliasSegment(trimmed);
    }
    
    private String normalizeA11yAliasSegment(String segment) {
        Matcher m = A11Y_ALIAS_PATTERN.matcher(segment);
        if (!m.matches()) return segment;
        
        String aliasLower = m.group(2) == null ? "" : m.group(2).toLowerCase();
        boolean forcedA11y = m.group(1) != null && !m.group(1).trim().isEmpty();
        Map<String, String> attrs = parseA11yAttrs(segment);
        if (!forcedA11y && isAmbiguousTagAlias(aliasLower) && !hasA11yOnlyAttrs(attrs)) return segment;
        
        // 将 a11y 别名降级为更通用的 CSS/role 选择器，提升可移植性与执行稳定性
        String alias = aliasLower;
        String role = aliasToRole(alias);
        
        String placeholder = firstNonEmpty(attrs.get("placeholder"));
        if (placeholder != null) {
            String escaped = escapeForSelectorValue(placeholder);
            if (alias.equals("textbox")) {
                return "input[placeholder=\"" + escaped + "\"], textarea[placeholder=\"" + escaped + "\"]";
            }
            if (alias.equals("combobox")) {
                return "input[placeholder=\"" + escaped + "\"]";
            }
        }
        
        String label = firstNonEmpty(attrs.get("label"));
        if (label != null) {
            String roleSelector = buildRoleSelector(role, label, attrs);
            if (roleSelector != null) return roleSelector;
        }
        
        String name = firstNonEmpty(attrs.get("name"));
        if (name != null) {
            String roleSelector = buildRoleSelector(role, name, attrs);
            if (roleSelector != null) return roleSelector;
        }
        
        if (role != null) return "role=" + role;
        return segment;
    }
    
    private String buildRoleSelector(String role, String name, Map<String, String> attrs) {
        if (role == null) return null;
        StringBuilder sb = new StringBuilder();
        sb.append("role=").append(role);
        if (name != null) {
            String escaped = escapeForSelectorValue(name);
            sb.append("[name=\"").append(escaped).append("\"]");
        }
        String checked = normalizeRoleBool(attrs.get("checked"));
        if (checked != null) sb.append("[checked=").append(checked).append("]");
        String selected = normalizeRoleBool(attrs.get("selected"));
        if (selected != null) sb.append("[selected=").append(selected).append("]");
        String pressed = normalizeRoleBool(attrs.get("pressed"));
        if (pressed != null) sb.append("[pressed=").append(pressed).append("]");
        String expanded = normalizeRoleBool(attrs.get("expanded"));
        if (expanded != null) sb.append("[expanded=").append(expanded).append("]");
        String disabled = normalizeRoleBool(attrs.get("disabled"));
        if (disabled != null) sb.append("[disabled=").append(disabled).append("]");
        return sb.toString();
    }
    
    private String normalizeRoleBool(String v) {
        if (v == null) return null;
        String t = v.trim().toLowerCase();
        if (t.isEmpty()) return null;
        if (t.equals("true") || t.equals("false")) return t;
        if (t.equals("mixed")) return t;
        return null;
    }
    
    private String aliasToRole(String alias) {
        if (alias == null) return null;
        switch (alias.toLowerCase()) {
            case "textbox": return "textbox";
            case "combobox": return "combobox";
            case "button": return "button";
            case "link": return "link";
            case "checkbox": return "checkbox";
            case "radio": return "radio";
            case "tab": return "tab";
            case "option": return "option";
            case "menuitem": return "menuitem";
            default: return null;
        }
    }
    
    private boolean isAmbiguousTagAlias(String alias) {
        if (alias == null) return false;
        String a = alias.toLowerCase();
        return a.equals("button") || a.equals("option") || a.equals("link");
    }
    
    private boolean hasA11yOnlyAttrs(Map<String, String> attrs) {
        if (attrs == null || attrs.isEmpty()) return false;
        for (String k : attrs.keySet()) {
            if (k == null) continue;
            String key = k.toLowerCase();
            if (key.equals("text") || key.equals("label")) return true;
            if (key.equals("pressed") || key.equals("expanded") || key.equals("selected") || key.equals("checked") || key.equals("disabled")) return true;
        }
        return false;
    }
    
    private AriaRole aliasToAriaRole(String alias) {
        if (alias == null) return null;
        switch (alias.toLowerCase()) {
            case "textbox": return AriaRole.TEXTBOX;
            case "combobox": return AriaRole.COMBOBOX;
            case "button": return AriaRole.BUTTON;
            case "link": return AriaRole.LINK;
            case "checkbox": return AriaRole.CHECKBOX;
            case "radio": return AriaRole.RADIO;
            case "tab": return AriaRole.TAB;
            case "option": return AriaRole.OPTION;
            case "menuitem": return AriaRole.MENUITEM;
            default: return null;
        }
    }
    
    private Map<String, String> parseA11yAttrs(String s) {
        Map<String, String> out = new HashMap<>();
        if (s == null) return out;
        Matcher m = A11Y_ATTR_PATTERN.matcher(s);
        while (m.find()) {
            String k = m.group(1);
            String v = m.group(3);
            if (k == null) continue;
            k = k.trim().toLowerCase();
            if (v == null) v = "";
            out.put(k, v);
        }
        return out;
    }
    
    private String firstNonEmpty(String v) {
        if (v == null) return null;
        String t = v.trim();
        if (t.isEmpty()) return null;
        return t;
    }
    
    private String escapeForSelectorValue(String v) {
        if (v == null) return "";
        return v.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    public void navigate(String url) {
        String u = url == null ? "" : url.trim();
        u = u.replace("`", "").trim();
        if ((u.startsWith("'") && u.endsWith("'")) || (u.startsWith("\"") && u.endsWith("\""))) {
            if (u.length() >= 2) u = u.substring(1, u.length() - 1).trim();
        }
        log("Action: Navigate to '" + u + "'");
        page.navigate(u);
    }

    /**
     * Alias for navigate.
     */
    public void open(String url) {
        navigate(url);
    }

    public String getCurrentUrl() {
        return page.url();
    }

    public String getTitle() {
        return page.title();
    }

    public void waitForUrl(String urlRegex) {
        log("Wait: For URL matching '" + urlRegex + "'");
        page.waitForURL(urlRegex, new Page.WaitForURLOptions().setTimeout(defaultTimeout));
    }
    
    public void waitForLoadState() {
        log("Wait: For load state (networkidle)");
        try {
            page.waitForLoadState(com.microsoft.playwright.options.LoadState.NETWORKIDLE, new Page.WaitForLoadStateOptions().setTimeout(defaultTimeout));
        } catch (Exception e) {
            page.waitForLoadState(com.microsoft.playwright.options.LoadState.LOAD, new Page.WaitForLoadStateOptions().setTimeout(defaultTimeout));
        }
    }

    /**
     * Executes an action that triggers a new page (tab/window), waits for it, 
     * and returns a new WebDSL instance for that page.
     */
    public WebDSL waitForNewPage(Runnable triggerAction) {
        log("Action: Waiting for new page...");
        try {
            Page newPage = page.context().waitForPage(() -> {
                triggerAction.run();
            });
            try {
                newPage.waitForLoadState(com.microsoft.playwright.options.LoadState.NETWORKIDLE, new Page.WaitForLoadStateOptions().setTimeout(defaultTimeout));
            } catch (Exception ignored) {
                newPage.waitForLoadState(com.microsoft.playwright.options.LoadState.LOAD, new Page.WaitForLoadStateOptions().setTimeout(defaultTimeout));
            }
            log("  -> New page opened: " + newPage.title() + " (" + newPage.url() + ")");
            return new WebDSL(newPage, this.logger);
        } catch (Exception e) {
            log("Error waiting for new page: " + e.getMessage());
            throw e;
        }
    }

    /**
     * Executes an action that triggers navigation in the CURRENT page.
     */
    public void waitForNavigation(Runnable triggerAction) {
        log("Action: Waiting for navigation...");
        try {
            page.waitForNavigation(() -> {
                triggerAction.run();
            });
            log("  -> Navigation completed. Current URL: " + page.url());
        } catch (Exception e) {
            log("Error waiting for navigation: " + e.getMessage());
            throw e;
        }
    }

    public void reload() {
        log("Action: Reload page");
        page.reload();
        waitForLoadState();
    }
    
    public void goBack() {
        log("Action: Go back");
        page.goBack();
        waitForLoadState();
    }

    /**
     * Tries to return to the first page of results.
     * Strategy:
     * 1. Try to find and click the "Page 1" button directly if visible.
     * 2. If not found (e.g. hidden behind ellipsis), try finding "Previous Page" button and click it repeatedly until disabled.
     * 3. Fallback to reload().
     */
    public void returnToFirstPage() {
        log("Action: Returning to first page...");
        
        // 1. Try clicking "Page 1" directly (Fast Path)
        // Many frameworks (AntD, Bootstrap) keep Page 1 visible even when deep in pagination.
        String[] pageOneSelectors = {
            ".ant-pagination-item-1",                // Ant Design
            ".el-pager li.number:text('1')",         // Element UI
            "li.pagination-item-1",
            "ul.pagination li:first-child a",
            "button[aria-label='Page 1']",
            "button:text-is('1')"
        };

        for (String sel : pageOneSelectors) {
            try {
                Locator loc = locator(sel).first();
                // Check visibility: if 1 is hidden behind '...', this will fail, which is good -> go to step 2
                if (loc.isVisible()) {
                    // Check if already active/selected
                    String classAttr = loc.getAttribute("class");
                    if (classAttr != null && (classAttr.contains("active") || classAttr.contains("selected"))) {
                        log("  -> Already on Page 1 (" + sel + ")");
                        return;
                    }
                    
                    // If visible but not active, click it
                    log("  -> Clicking Page 1 button: " + sel);
                    highlight(loc);
                    loc.click();
                    // Wait for it to become active to ensure page transition
                    try {
                        loc.waitFor(new Locator.WaitForOptions()
                            .setTimeout(5000)
                            .setState(com.microsoft.playwright.options.WaitForSelectorState.VISIBLE));
                        // Heuristic: wait a bit more for table to refresh
                        wait(2000); 
                    } catch (Exception e) {
                        wait(2000); // Fallback wait
                    }
                    return;
                }
            } catch (Exception ignored) {}
        }
        
        // 2. Iterative "Previous Page" (Robust Path)
        // User suggestion: "Most safe is clicking previous page until first page"
        log("  -> 'Page 1' not directly accessible. Trying 'Previous' button loop...");
        String[] prevSelectors = {
            ".ant-pagination-prev",          // Ant Design
            "button.btn-prev",               // Element UI
            "li.page-item.prev",             // Bootstrap (container)
            "a[aria-label='Previous']",      // Bootstrap (link)
            "button[aria-label='Previous']", // Generic
            "button:has-text('<')",          // Generic symbol
            ".pagination-prev"               // Generic class
        };
        
        for (String prevSel : prevSelectors) {
            try {
                Locator prevBtn = locator(prevSel).first();
                if (prevBtn.isVisible()) {
                    int maxClicks = 50; // Safety limit
                    int clicks = 0;
                    
                    while (clicks < maxClicks) {
                        // Check disabled state
                        boolean isDisabled = prevBtn.isDisabled();
                        String classAttr = prevBtn.getAttribute("class");
                        if (classAttr != null && (classAttr.contains("disabled") || classAttr.contains("disable"))) {
                            isDisabled = true;
                        }
                        String ariaDisabled = prevBtn.getAttribute("aria-disabled");
                        if ("true".equals(ariaDisabled)) {
                            isDisabled = true;
                        }
                        
                        if (isDisabled) {
                            log("  -> Reached start (Previous button disabled).");
                            return;
                        }
                        
                        log("  -> Clicking Previous (" + prevSel + ") - Step " + (clicks + 1));
                        highlight(prevBtn);
                        prevBtn.click();
                        wait(1000); // Wait for page load
                        clicks++;
                    }
                    
                    if (clicks >= maxClicks) {
                         log("  -> Warning: Reached max clicks on Previous button. Stopping.");
                    }
                    return; // We tried our best with this selector
                }
            } catch (Exception ignored) {}
        }
        
        // 3. Fallback: Reload
        log("  -> Page 1 / Previous button not found. Falling back to reload().");
        reload();
    }

    // --- Modal / Popup Handling ---

    /**
     * Extracts data from a modal/popup using regex patterns on its text content.
     * @param modalSelector Selector for the modal container (e.g. ".ant-modal-content")
     * @param regexPatterns Map of field name to regex pattern. The regex MUST contain at least one capturing group ().
     *                      Example: {"success": "成功.*?(\\d+)"}
     * @return Map of extracted values. If a pattern doesn't match, the value will be null.
     */
    public java.util.Map<String, String> extractModalData(String modalSelector, java.util.Map<String, String> regexPatterns) {
        log("Action: Extracting data from modal '" + modalSelector + "'");
        java.util.Map<String, String> results = new java.util.HashMap<>();
        try {
            Locator modalLoc = resolveModalLocator(modalSelector);
            if (modalLoc == null) throw new RuntimeException("Modal not found");
            try {
                modalLoc.waitFor(new Locator.WaitForOptions()
                        .setTimeout(2000)
                        .setState(com.microsoft.playwright.options.WaitForSelectorState.VISIBLE));
            } catch (Exception ignored) {
                modalLoc = resolveModalLocator(null);
            }
            if (modalLoc == null || modalLoc.count() == 0) throw new RuntimeException("Modal not found");
            if (!modalLoc.isVisible()) throw new RuntimeException("Modal not visible");
            // Wait a bit for content to settle
            wait(500);
            String text = modalLoc.innerText();
            // Normalize text for easier matching (replace newlines with spaces)
            String normalizedText = normalizeText(text);
            log("  -> Modal Text (preview): " + (normalizedText.length() > 100 ? normalizedText.substring(0, 100) + "..." : normalizedText));
            
            for (java.util.Map.Entry<String, String> entry : regexPatterns.entrySet()) {
                String key = entry.getKey();
                String regex = entry.getValue();
                try {
                    java.util.regex.Pattern p = java.util.regex.Pattern.compile(regex);
                    java.util.regex.Matcher m = p.matcher(normalizedText);
                    if (m.find() && m.groupCount() >= 1) {
                        results.put(key, m.group(1));
                        log("  -> Extracted " + key + ": " + m.group(1));
                    } else {
                        // Try matching against original text if normalized failed
                        m = p.matcher(text);
                        if (m.find() && m.groupCount() >= 1) {
                            results.put(key, m.group(1));
                            log("  -> Extracted " + key + ": " + m.group(1));
                        } else {
                            log("  -> Warning: Could not match pattern for '" + key + "' using regex: " + regex);
                        }
                    }
                } catch (Exception re) {
                    log("  -> Regex Error for '" + key + "': " + re.getMessage());
                }
            }
        } catch (Exception e) {
            log("Error extracting modal data: " + e.getMessage());
        }
        return results;
    }

    /**
     * Closes a modal by clicking a specific button (e.g. "X" or "Close").
     * Handles waiting and verification.
     */
    public void closeModal() {
        closeModal(null, null);
    }

    public void closeModal(String modalSelector) {
        closeModal(modalSelector, null);
    }

    public void closeModal(String modalSelector, String closeButtonSelector) {
        log("Action: Closing modal '" + modalSelector + "' using button '" + closeButtonSelector + "'");
        try {
            Locator loc = resolveModalLocator(modalSelector);
            if (loc == null || loc.count() == 0 || !loc.isVisible()) {
                loc = resolveModalLocator(null);
            }
            if (loc != null && loc.count() > 0 && loc.isVisible()) {
                boolean closed = false;
                try {
                    String btn = closeButtonSelector == null ? "" : closeButtonSelector.trim();
                    if (!btn.isEmpty()) {
                        boolean treatAsSelector = isSelectorLike(btn) || btn.startsWith("role=") || btn.startsWith("text=") || btn.startsWith("xpath=") || btn.startsWith("css=");
                        if (treatAsSelector) {
                            Locator b = loc.locator(btn).first();
                            waitForLocatorAttached(b, 250);
                            if (b.count() > 0 && b.isVisible()) {
                                highlight(b);
                                try { b.click(); } catch (Exception e) { if (!tryDomClickFallback(b)) throw e; }
                                closed = true;
                            }
                        } else if (looksLikeTextRegex(btn)) {
                            String pattern = escapeForRegexLiteralInSelector(btn);
                            String[] sels = new String[] {
                                    "role=button[name=/" + pattern + "/]",
                                    "role=link[name=/" + pattern + "/]",
                                    "text=/" + pattern + "/"
                            };
                            for (String s : sels) {
                                if (s == null || s.isEmpty()) continue;
                                Locator b = loc.locator(s).first();
                                waitForLocatorAttached(b, 250);
                                if (b.count() == 0) continue;
                                if (!b.isVisible()) continue;
                                highlight(b);
                                try { b.click(); } catch (Exception e) { if (!tryDomClickFallback(b)) throw e; }
                                closed = true;
                                break;
                            }
                        } else {
                            String escaped = escapeForSelectorValue(btn);
                            String[] sels = new String[] {
                                    "role=button[name=\"" + escaped + "\"]",
                                    "button:has-text(\"" + escaped + "\")",
                                    "role=link[name=\"" + escaped + "\"]",
                                    "a:has-text(\"" + escaped + "\")",
                                    "text=\"" + escaped + "\""
                            };
                            for (String s : sels) {
                                if (s == null || s.isEmpty()) continue;
                                Locator b = loc.locator(s).first();
                                waitForLocatorAttached(b, 250);
                                if (b.count() == 0) continue;
                                if (!b.isVisible()) continue;
                                highlight(b);
                                try { b.click(); } catch (Exception e) { if (!tryDomClickFallback(b)) throw e; }
                                closed = true;
                                break;
                            }
                        }
                    }
                } catch (Exception ignored) {}
                
                if (!closed) {
                    String[] fallbackButtons = new String[] {
                            ".ant-modal-close",
                            ".ant-drawer-close",
                            "button:has-text('关闭')",
                            "button:has-text('确定')",
                            "button:has-text('取消')",
                            "button[aria-label='Close']",
                            "role=button[name='关闭']",
                            "role=button[name='确定']",
                            "role=button[name='×']",
                            "text='×'"
                    };
                    for (String sel : fallbackButtons) {
                        try {
                            Locator btn = loc.locator(sel).first();
                            if (btn.count() == 0) continue;
                            if (!btn.isVisible()) continue;
                            highlight(btn);
                            btn.click();
                            closed = true;
                            break;
                        } catch (Exception ignored) {}
                    }
                }
                if (!closed) {
                    try {
                        page.keyboard().press("Escape");
                        closed = true;
                    } catch (Exception ignored) {}
                }
                // Wait for it to disappear (optional, short timeout)
                try {
                    loc.waitFor(new Locator.WaitForOptions()
                            .setState(com.microsoft.playwright.options.WaitForSelectorState.HIDDEN)
                            .setTimeout(2000));
                } catch (Exception ignore) {}
            }
        } catch (Exception e) {
             log("Warning: Failed to close modal cleanly: " + e.getMessage());
        }
    }

    private Locator resolveModalLocator(String preferredSelector) {
        String preferred = preferredSelector == null ? "" : preferredSelector.trim();
        String[] candidates = new String[] {
                preferred,
                ".ant-modal-content",
                ".ant-modal",
                ".ant-drawer-content",
                ".ant-drawer",
                ".el-dialog",
                ".el-dialog__wrapper",
                "[role='dialog']",
                "[aria-modal='true']",
                "[data-testid*='modal']",
                ".modal"
        };
        
        for (String sel : candidates) {
            if (sel == null) continue;
            String s = sel.trim();
            if (s.isEmpty()) continue;
            try {
                Locator loc = locator(s).first();
                waitForLocatorAttached(loc, 500);
                if (loc.count() == 0) continue;
                if (!loc.isVisible()) continue;
                if (!preferred.isEmpty() && !s.equals(preferred)) {
                    log("  -> Modal fallback selector: '" + s + "'");
                }
                return loc;
            } catch (Exception ignored) {}
        }
        return null;
    }
    
    /**
     * Attempts to dismiss common nuisance popups if they exist.
     * @param selectors List of selectors to try clicking (e.g. ".close-ad", "button:has-text('Not Now')")
     */
    public void dismissPopups() {
        dismissPopups(
                ".ant-modal-close",
                ".ant-drawer-close",
                ".ant-notification-notice-close",
                ".ant-message-notice-close",
                "button:has-text('关闭')",
                "button:has-text('我知道了')",
                "button:has-text('知道了')",
                "button:has-text('取消')",
                "button:has-text('确定')",
                "button:has-text('以后再说')",
                "button[aria-label='Close']",
                "[aria-label='Close']",
                "[role='dialog'] button:has-text('关闭')"
        );
        try { page.keyboard().press("Escape"); } catch (Exception ignored) {}
    }

    public void dismissPopups(String... selectors) {
         for (String sel : selectors) {
             try {
                 String s = sel == null ? "" : sel.trim();
                 if (s.isEmpty()) continue;
                 boolean treatAsSelector = isSelectorLike(s) || s.startsWith("role=") || s.startsWith("text=") || s.startsWith("xpath=") || s.startsWith("css=");
                 if (!treatAsSelector) {
                     Locator byText = tryResolveClickTargetByText(s);
                     if (byText != null) {
                         waitForLocatorAttached(byText, 200);
                         if (byText.count() > 0 && byText.isVisible()) {
                             log("Action: Dismissing popup via text '" + s + "'");
                             highlight(byText);
                             try { byText.click(); } catch (Exception e) { if (!tryDomClickFallback(byText)) throw e; }
                             wait(500);
                             continue;
                         }
                     }
                 }
                 Locator loc = locator(s).first();
                 waitForLocatorAttached(loc, 200);
                 if (loc.count() > 0 && loc.isVisible()) {
                     log("Action: Dismissing popup via '" + s + "'");
                     highlight(loc);
                     try { loc.click(); } catch (Exception e) { if (!tryDomClickFallback(loc)) throw e; }
                     wait(500);
                 }
             } catch (Exception ignored) {}
         }
    }

    // --- Logging ---
    public void log(String message) {
        StorageSupport.log(logger, "DSL", message, null);
    }

    private void retry(Runnable action, String desc) {
        int attempt = 0;
        RuntimeException last = null;
        while (attempt < maxRetries) {
            try {
                action.run();
                return;
            } catch (RuntimeException e) {
                RuntimeException login = rewriteIfLogin(e, desc);
                if (login != null) throw login;
                last = e;
                attempt++;
                log("Retry " + attempt + "/" + maxRetries + " for " + desc);
                page.waitForTimeout(500);
            }
        }
        if (last != null) throw last;
    }
    
    private <T> T retrySupply(java.util.function.Supplier<T> supplier, String desc) {
        int attempt = 0;
        RuntimeException last = null;
        while (attempt < maxRetries) {
            try {
                return supplier.get();
            } catch (RuntimeException e) {
                RuntimeException login = rewriteIfLogin(e, desc);
                if (login != null) throw login;
                last = e;
                attempt++;
                log("Retry " + attempt + "/" + maxRetries + " for " + desc);
                page.waitForTimeout(500);
            }
        }
        if (last != null) throw last;
        return null;
    }

    private boolean looksLikePlainText(String selector) {
        if (selector == null) return false;
        String s = selector.trim();
        if (s.isEmpty()) return false;
        if (s.startsWith("role=") || s.startsWith("text=") || s.startsWith("xpath=") || s.startsWith("css=")) return false;
        if (isSelectorLike(s)) return false;
        if (looksLikeTextRegex(s)) return true;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) > 127) return true;
        }
        return false;
    }
    
    private boolean looksLikeTextRegex(String text) {
        if (text == null) return false;
        String s = text.trim();
        if (s.isEmpty()) return false;
        if (s.contains("\\")) return true;
        if (s.contains(".*")) return true;
        if (s.contains(".+")) return true;
        return false;
    }
    
    private String escapeForRegexLiteralInSelector(String pattern) {
        if (pattern == null) return "";
        String s = pattern.trim();
        if (s.isEmpty()) return "";
        s = s.replace("\r", " ").replace("\n", " ");
        s = s.replace("/", "\\/");
        return s;
    }

    private Locator tryResolveClickTargetByText(String text) {
        if (!looksLikePlainText(text)) return null;
        String raw = text == null ? "" : text.trim();
        Locator bestAttached = null;
        
        if (looksLikeTextRegex(raw)) {
            String pattern = escapeForRegexLiteralInSelector(raw);
            String[] regexSelectors = new String[] {
                    "role=button[name=/" + pattern + "/]",
                    "role=link[name=/" + pattern + "/]",
                    "role=menuitem[name=/" + pattern + "/]",
                    "role=tab[name=/" + pattern + "/]",
                    "role=option[name=/" + pattern + "/]",
                    "text=/" + pattern + "/"
            };
            for (String sel : regexSelectors) {
                try {
                    Locator loc = locator(sel).first();
                    waitForLocatorAttached(loc, 250);
                    if (loc.count() == 0) continue;
                    bestAttached = loc;
                    if (loc.isVisible()) return loc;
                } catch (Exception ignored) {}
            }
        }
        
        String escaped = escapeForSelectorValue(raw);
        String[] selectors = new String[] {
                "role=button[name=\"" + escaped + "\"]",
                "role=link[name=\"" + escaped + "\"]",
                "role=menuitem[name=\"" + escaped + "\"]",
                "role=tab[name=\"" + escaped + "\"]",
                "role=option[name=\"" + escaped + "\"]",
                "text=\"" + escaped + "\""
        };
        for (String sel : selectors) {
            try {
                Locator loc = locator(sel).first();
                waitForLocatorAttached(loc, 250);
                if (loc.count() == 0) continue;
                bestAttached = loc;
                if (loc.isVisible()) return loc;
            } catch (Exception ignored) {}
        }
        return bestAttached;
    }

    private boolean tryDomClickFallback(Locator loc) {
        if (loc == null) return false;
        try {
            if (loc.count() == 0) return false;
        } catch (Exception ignored) {
            return false;
        }
        try {
            Object v = loc.evaluate("el => {\n" +
                    "  const uniq = [];\n" +
                    "  const add = (x) => { if (!x) return; if (uniq.indexOf(x) >= 0) return; uniq.push(x); };\n" +
                    "  const isVisible = (x) => {\n" +
                    "    try {\n" +
                    "      const s = window.getComputedStyle(x);\n" +
                    "      if (!s || s.display === 'none' || s.visibility === 'hidden' || s.opacity === '0') return false;\n" +
                    "      const r = x.getBoundingClientRect();\n" +
                    "      return (r.width > 0 && r.height > 0);\n" +
                    "    } catch (e) { return false; }\n" +
                    "  };\n" +
                    "  const isClickable = (x) => {\n" +
                    "    try {\n" +
                    "      const tag = (x.tagName || '').toLowerCase();\n" +
                    "      const role = (x.getAttribute && x.getAttribute('role')) ? x.getAttribute('role') : '';\n" +
                    "      if (tag === 'button' || tag === 'a' || tag === 'label' || tag === 'option') return true;\n" +
                    "      if (tag === 'input' || tag === 'select' || tag === 'textarea') return true;\n" +
                    "      if (role === 'button' || role === 'checkbox' || role === 'tab' || role === 'menuitem' || role === 'option' || role === 'combobox') return true;\n" +
                    "      const cls = (x.className || '').toString();\n" +
                    "      if (cls && /ant-btn|el-button|btn|clickable|ant-select|el-select/i.test(cls)) return true;\n" +
                    "      if (typeof x.onclick === 'function') return true;\n" +
                    "      return false;\n" +
                    "    } catch (e) { return false; }\n" +
                    "  };\n" +
                    "  const tryClick = (x) => { try { x.click(); return true; } catch (e) { return false; } };\n" +
                    "  const tryClickClosest = (from) => {\n" +
                    "    try {\n" +
                    "      if (!from || !from.closest) return false;\n" +
                    "      const c = from.closest('.ant-select-item-option,[role=option],button,[role=button],a,[role=menuitem],[role=tab],[role=checkbox],label,.el-select-dropdown__item,.ant-select-selector,.ant-select-selection-overflow-item,.ant-select-selection-overflow-item-suffix,.ant-select,[role=combobox]');\n" +
                    "      if (c && isVisible(c) && isClickable(c)) return tryClick(c);\n" +
                    "    } catch (e) {}\n" +
                    "    return false;\n" +
                    "  };\n" +
                    "  if (tryClickClosest(el)) return true;\n" +
                    "  add(el);\n" +
                    "  let p = el;\n" +
                    "  for (let i = 0; i < 6 && p; i++) { add(p); p = p.parentElement; }\n" +
                    "  const parent = el && el.parentElement;\n" +
                    "  if (parent) {\n" +
                    "    add(parent);\n" +
                    "    try {\n" +
                    "      const kids = parent.children;\n" +
                    "      for (let i = 0; i < kids.length; i++) add(kids[i]);\n" +
                    "    } catch (e) {}\n" +
                    "  }\n" +
                    "  for (let i = 0; i < uniq.length; i++) {\n" +
                    "    const c = uniq[i];\n" +
                    "    if (!c) continue;\n" +
                    "    if (tryClickClosest(c)) return true;\n" +
                    "    let t = null;\n" +
                    "    try { t = c.querySelector && c.querySelector('button,[role=button],a,[role=menuitem],[role=tab],[role=option],[role=checkbox],label,input,select,textarea,[contenteditable=true]'); } catch (e) { t = null; }\n" +
                    "    if (t && isVisible(t) && (isClickable(t) || t !== c)) { try { t.click(); return true; } catch (e) {} }\n" +
                    "    if (isVisible(c) && isClickable(c)) { try { c.click(); return true; } catch (e) {} }\n" +
                    "  }\n" +
                    "  try { el.click(); return true; } catch (e) {}\n" +
                    "  return false;\n" +
                    "}");
            return (v instanceof Boolean) && (Boolean) v;
        } catch (Exception ignored) {}
        return false;
    }

    // --- Basic Interaction ---

    public void click(String selector) {
        log("Action: Click '" + selector + "'");
        try {
            String s = selector == null ? "" : selector;
            if ((s.contains("role=\"listbox\"") || s.contains("role=listbox") || s.contains("[role=\"listbox\"]") || s.contains("[role=listbox]")) && s.contains("text=")) {
                log("  -> 检测到 listbox 选项点击。下拉选择场景请优先使用 web.selectDropdown(\"控件名/selector\", \"选项文本\")。");
            }
        } catch (Exception ignored) {}
        retry(() -> {
            Locator loc = locator(selector).first();
            int count = 0;
            try { count = loc.count(); } catch (Exception ignored) {}
            if (count == 0 && isCheckboxIntent(selector)) {
                log("  -> Click target not found. Trying checkbox fallback...");
                Locator alt = tryFallbackCheckboxLocator(selector);
                if (alt != null) loc = alt.first();
            } else if (count == 0) {
                Locator alt = tryResolveClickTargetByText(selector);
                if (alt != null) loc = alt.first();
            }
            
            boolean hasTarget = false;
            try { hasTarget = loc.count() > 0; } catch (Exception ignored) { hasTarget = false; }
            if (hasTarget) {
                boolean visible = false;
                try { visible = loc.isVisible(); } catch (Exception ignored) { visible = false; }
                if (!visible) {
                    try { loc.scrollIntoViewIfNeeded(); } catch (Exception ignored) {}
                    try { visible = loc.isVisible(); } catch (Exception ignored) { visible = false; }
                    if (!visible) {
                        if (tryDomClickFallback(loc)) return;
                        throw new RuntimeException("Click target not visible: " + selector);
                    }
                }
            }
            
            try {
                loc.waitFor(new Locator.WaitForOptions().setTimeout(defaultTimeout));
            } catch (Exception ignored) {}
            
            highlight(loc);
            try {
                loc.click();
            } catch (Exception e) {
                if (tryDomClickFallback(loc)) return;
                throw (e instanceof RuntimeException) ? (RuntimeException) e : new RuntimeException(e);
            }
        }, "click " + selector);
    }
    
    public void click(String selector, int index) {
        log("Action: Click '" + selector + "' at index " + index);
        retry(() -> {
            Locator loc = locator(selector).nth(index);
            highlight(loc);
            try {
                loc.click();
            } catch (Exception e) {
                if (tryDomClickFallback(loc)) return;
                throw (e instanceof RuntimeException) ? (RuntimeException) e : new RuntimeException(e);
            }
        }, "click " + selector + " at " + index);
    }

    public void type(String selector, String text) {
        log("Action: Type '" + text + "' into '" + selector + "'");
        retry(() -> {
            String ariaLabel = extractOnlyAriaLabel(selector);
            Locator target = locator(selector).first();
            try {
                waitFor(selector);
                target = locator(selector).first();
            } catch (RuntimeException e) {
                if (ariaLabel != null) {
                    Locator alt = resolveInputLocatorFromAriaLabel(ariaLabel);
                    if (alt != null) {
                        log("  -> Type fallback: aria-label '" + ariaLabel + "'");
                        target = alt.first();
                        target.waitFor(new Locator.WaitForOptions().setTimeout(defaultTimeout));
                    } else {
                        throw e;
                    }
                } else {
                    throw e;
                }
            }
            highlight(target);
            try {
                target.fill(text);
            } catch (Exception fillEx) {
                try { if (target.count() > 0 && !target.isVisible()) tryDomClickFallback(target); } catch (Exception ignored) {}
                Locator input = null;
                try {
                    input = target.locator("input, textarea, [contenteditable=true]").first();
                    waitForLocatorAttached(input, 300);
                    if (input != null && input.count() > 0 && input.isVisible()) {
                        highlight(input);
                        input.fill(text);
                        return;
                    }
                } catch (Exception ignored) {}
                try {
                    Locator wrap = target.locator("xpath=ancestor-or-self::*[.//input or .//textarea or @contenteditable='true'][1]").first();
                    waitForLocatorAttached(wrap, 300);
                    if (wrap != null && wrap.count() > 0) {
                        input = wrap.locator("input, textarea, [contenteditable=true]").first();
                        waitForLocatorAttached(input, 300);
                        if (input != null && input.count() > 0 && input.isVisible()) {
                            highlight(input);
                            input.fill(text);
                            return;
                        }
                    }
                } catch (Exception ignored) {}
                try {
                    Locator dd = resolveDropdownTrigger(selector);
                    if (dd != null) {
                        selectDropdown(selector, text);
                        return;
                    }
                } catch (Exception ignored) {}
                throw (fillEx instanceof RuntimeException) ? (RuntimeException) fillEx : new RuntimeException(fillEx);
            }
        }, "type into " + selector);
    }
    
    public void check(String selector) {
        log("Action: Check '" + selector + "'");
        retry(() -> {
            Locator loc = locator(selector).first();
            int count = 0;
            try { count = loc.count(); } catch (Exception ignored) {}
            if (count == 0) {
                log("  -> Check target not found. Trying checkbox fallback...");
                Locator alt = tryFallbackCheckboxLocator(selector);
                if (alt != null) {
                    Locator a = alt.first();
                    waitForLocatorAttached(a, 2000);
                    if (a.count() > 0) {
                        if (a.isVisible()) {
                            highlight(a);
                            try { a.check(); return; } catch (Exception ignored) {}
                            a.click();
                            return;
                        } else {
                            a.evaluate("el => { const label = el.closest('label'); if (label) { label.click(); return; } const box = el.closest('.ant-checkbox') || el.closest('[role=checkbox]'); if (box) { box.click(); return; } if (el.parentElement) el.parentElement.click(); }");
                            return;
                        }
                    }
                }
            }
            
            if (loc.isVisible()) {
                waitFor(selector);
                highlight(loc);
                loc.check();
                return;
            }
            loc.evaluate("el => { const label = el.closest('label'); if (label) { label.click(); return; } const box = el.closest('.ant-checkbox') || el.closest('[role=checkbox]'); if (box) { box.click(); return; } if (el.parentElement) el.parentElement.click(); }");
        }, "check " + selector);
    }
    
    public void uncheck(String selector) {
        log("Action: Uncheck '" + selector + "'");
        retry(() -> {
            Locator loc = locator(selector).first();
            if (loc.isVisible()) {
                waitFor(selector);
                highlight(loc);
                loc.uncheck();
            } else {
                loc.evaluate("el => { const label = el.closest('label'); if (label) { label.click(); return; } if (el.parentElement) el.parentElement.click(); }");
            }
        }, "uncheck " + selector);
    }
    
    public boolean isChecked(String selector) {
        return locator(selector).first().isChecked();
    }
    
    /**
     * Selects an option from a <select> element by value or label.
     */
    public void selectOption(String selector, String valueOrLabel) {
        log("Action: Select option '" + valueOrLabel + "' in '" + selector + "'");
        highlight(selector);
        Locator loc = locator(selector).first();
        try {
            // Try by value first
            loc.selectOption(new com.microsoft.playwright.options.SelectOption().setValue(valueOrLabel));
        } catch (Exception e) {
            // Fallback to label
            loc.selectOption(new com.microsoft.playwright.options.SelectOption().setLabel(valueOrLabel));
        }
    }
    
    public void selectDropdown(String dropdownNameOrSelector, String optionText) {
        selectDropdown(dropdownNameOrSelector, optionText, 12);
    }
    
    public void selectDropdown(String dropdownNameOrSelector, String optionText, int maxScrolls) {
        String dd = dropdownNameOrSelector == null ? "" : dropdownNameOrSelector.trim();
        String opt = optionText == null ? "" : optionText.trim();
        log("Action: Select dropdown '" + dd + "' -> '" + opt + "'");
        retry(() -> {
            Locator trigger = resolveDropdownTrigger(dd);
            if (trigger == null) throw new RuntimeException("Dropdown not found: " + dd);
            waitForLocatorAttached(trigger, defaultTimeout);
            boolean opened = openDropdownTrigger(trigger);
            if (!opened) throw new RuntimeException("Dropdown did not open: " + dd);
            wait(120);
            
            if (tryClickDropdownOption(opt)) return;
            
            int max = Math.max(0, maxScrolls);
            Locator container = resolveDropdownPopupContainer();
            for (int i = 0; i < max; i++) {
                if (tryClickDropdownOption(opt)) return;
                boolean scrolled = false;
                if (container == null) container = resolveDropdownPopupContainer();
                if (container != null) {
                    try {
                        if (container.count() > 0 && container.first().isVisible()) {
                            highlight(container.first());
                            container.first().hover();
                            page.mouse().wheel(0, 800);
                            scrolled = true;
                        }
                    } catch (Exception ignored) {}
                }
                if (!scrolled) {
                    page.mouse().wheel(0, 800);
                }
                wait(150);
            }
            
            if (tryTypeDropdownAndEnter(trigger, opt)) return;

            java.util.List<String> visibleOptions = collectVisibleDropdownOptions(30);
            String url = "";
            try { url = getCurrentUrl(); } catch (Exception ignored) {}
            StringBuilder sb = new StringBuilder("Dropdown option not found/clickable: ");
            sb.append(opt).append(" | dropdown=").append(dd);
            if (url != null && !url.isEmpty()) sb.append(" | url=").append(url);
            if (visibleOptions != null && !visibleOptions.isEmpty()) sb.append(" | visibleOptions=").append(visibleOptions);
            throw new RuntimeException(sb.toString());
        }, "selectDropdown " + dd + " -> " + opt);
    }

    public String getText(String selector) {
        return retrySupply(() -> {
            waitFor(selector);
            return locator(selector).first().innerText();
        }, "getText " + selector);
    }
    
    public String getText(String selector, int index) {
        return retrySupply(() -> locator(selector).nth(index).innerText(), "getText " + selector + " at " + index);
    }
    
    public List<String> getAllText(String selector) {
        return locator(selector).allInnerTexts();
    }

    public List<String> getAllText(String selector, int index) {
        return locator(selector).nth(index).allInnerTexts();
    }

    private Locator resolveDropdownTrigger(String dropdownNameOrSelector) {
        String s = dropdownNameOrSelector == null ? "" : dropdownNameOrSelector.trim();
        if (s.isEmpty()) return null;
        
        try {
            if (isSelectorLike(s) || s.startsWith("role=") || s.startsWith("text=") || s.startsWith("xpath=")) {
                Locator base = locator(s);
                waitForLocatorAttached(base.first(), 800);
                if (base.count() == 0) return null;
                try {
                    Locator ant = base.first().locator("xpath=ancestor-or-self::*[contains(@class,'ant-select')][1]");
                    waitForLocatorAttached(ant, 200);
                    if (ant.count() > 0) {
                        Locator t = ant.first().locator(".ant-select-selection-overflow-item-suffix, .ant-select-selector, [role=combobox]").first();
                        waitForLocatorAttached(t, 200);
                        if (t.count() > 0) return t;
                    }
                } catch (Exception ignored) {}
                Locator selfOrAncestorCombobox = base.first().locator("xpath=ancestor-or-self::*[@role='combobox'][1]");
                try {
                    waitForLocatorAttached(selfOrAncestorCombobox, 200);
                    if (selfOrAncestorCombobox.count() > 0) return selfOrAncestorCombobox.first();
                } catch (Exception ignored) {}
                Locator descendantCombobox = base.first().locator("[role=combobox]");
                try {
                    waitForLocatorAttached(descendantCombobox, 200);
                    if (descendantCombobox.count() > 0) return descendantCombobox.first();
                } catch (Exception ignored) {}
                return base.first();
            }
        } catch (Exception ignored) {}
        
        String escaped = escapeForSelectorValue(s);
        Frame f = ensureFrame();
        try {
            Locator byRole = (f != null) ? f.locator("role=combobox[name=\"" + escaped + "\"]") : page.locator("role=combobox[name=\"" + escaped + "\"]");
            waitForLocatorAttached(byRole, 600);
            if (byRole.count() > 0) {
                Locator cb = byRole.first();
                try {
                    Locator ant = cb.locator("xpath=ancestor-or-self::*[contains(@class,'ant-select')][1]");
                    waitForLocatorAttached(ant, 200);
                    if (ant.count() > 0) {
                        Locator t = ant.first().locator(".ant-select-selection-overflow-item-suffix, .ant-select-selector, [role=combobox]").first();
                        waitForLocatorAttached(t, 200);
                        if (t.count() > 0) return t;
                    }
                } catch (Exception ignored) {}
                return cb;
            }
        } catch (Exception ignored) {}
        
        try {
            Locator byLabel = (f != null) ? f.getByLabel(s) : page.getByLabel(s);
            waitForLocatorAttached(byLabel, 600);
            if (byLabel.count() > 0) {
                Locator lb = byLabel.first();
                try {
                    Locator ant = lb.locator("xpath=ancestor-or-self::*[contains(@class,'ant-select')][1]");
                    waitForLocatorAttached(ant, 200);
                    if (ant.count() > 0) {
                        Locator t = ant.first().locator(".ant-select-selection-overflow-item-suffix, .ant-select-selector, [role=combobox]").first();
                        waitForLocatorAttached(t, 200);
                        if (t.count() > 0) return t;
                    }
                } catch (Exception ignored) {}
                return lb;
            }
        } catch (Exception ignored) {}
        
        try {
            Locator text = (f != null) ? f.locator("text=\"" + escaped + "\"") : page.locator("text=\"" + escaped + "\"");
            waitForLocatorAttached(text.first(), 400);
            if (text.count() == 0) return null;
            try {
                Locator formItem = text.first().locator("xpath=ancestor-or-self::*[contains(@class,'ant-form-item')][1]");
                waitForLocatorAttached(formItem, 200);
                if (formItem.count() > 0) {
                    Locator ant = formItem.first().locator(".ant-select").first();
                    waitForLocatorAttached(ant, 200);
                    if (ant.count() > 0) {
                        Locator t = ant.locator(".ant-select-selection-overflow-item-suffix, .ant-select-selector, [role=combobox]").first();
                        waitForLocatorAttached(t, 200);
                        if (t.count() > 0) return t;
                    }
                }
            } catch (Exception ignored) {}
            Locator nextCombobox = text.first().locator("xpath=following::*[@role='combobox'][1]");
            waitForLocatorAttached(nextCombobox, 400);
            if (nextCombobox.count() > 0) return nextCombobox.first();
            Locator ancestorCombobox = text.first().locator("xpath=ancestor-or-self::*[@role='combobox'][1]");
            waitForLocatorAttached(ancestorCombobox, 400);
            if (ancestorCombobox.count() > 0) return ancestorCombobox.first();
        } catch (Exception ignored) {}
        
        return null;
    }
    
    private boolean isDropdownOpen(Locator trigger) {
        try {
            Locator c = resolveDropdownPopupContainer();
            if (c != null && c.count() > 0 && c.first().isVisible()) return true;
        } catch (Exception ignored) {}
        if (trigger == null) return false;
        try {
            Object v = trigger.evaluate("el => {\n" +
                    "  try {\n" +
                    "    const ant = el && el.closest ? el.closest('.ant-select') : null;\n" +
                    "    if (ant && ant.classList && ant.classList.contains('ant-select-open')) return true;\n" +
                    "    const cb = (el && el.closest) ? (el.closest('[role=combobox]') || (el.querySelector ? el.querySelector('[role=combobox]') : null)) : null;\n" +
                    "    if (cb && cb.getAttribute && cb.getAttribute('aria-expanded') === 'true') return true;\n" +
                    "    const elsel = el && el.closest ? el.closest('.el-select') : null;\n" +
                    "    if (elsel && elsel.classList && (elsel.classList.contains('is-visible') || elsel.classList.contains('is-focus') || elsel.classList.contains('is-opened'))) return true;\n" +
                    "  } catch (e) {}\n" +
                    "  return false;\n" +
                    "}");
            return (v instanceof Boolean) && (Boolean) v;
        } catch (Exception ignored) {}
        return false;
    }
    
    private boolean openDropdownTrigger(Locator trigger) {
        if (trigger == null) return false;
        try { if (trigger.count() == 0) return false; } catch (Exception ignored) { return false; }
        
        try {
            if (isDropdownOpen(trigger)) return true;
        } catch (Exception ignored) {}
        
        java.util.ArrayList<Locator> candidates = new java.util.ArrayList<>();
        try {
            Locator ant = trigger.locator("xpath=ancestor-or-self::*[contains(@class,'ant-select')][1]").first();
            waitForLocatorAttached(ant, 150);
            if (ant.count() > 0) {
                candidates.add(ant.locator(".ant-select-selection-overflow-item-suffix").first());
                candidates.add(ant.locator(".ant-select-selection-overflow-item").first());
                candidates.add(ant.locator(".ant-select-selector").first());
                candidates.add(ant.locator(".ant-select-arrow").first());
                candidates.add(ant);
            }
        } catch (Exception ignored) {}
        try {
            Locator elsel = trigger.locator("xpath=ancestor-or-self::*[contains(@class,'el-select')][1]").first();
            waitForLocatorAttached(elsel, 150);
            if (elsel.count() > 0) {
                candidates.add(elsel.locator(".el-input__inner").first());
                candidates.add(elsel.locator(".el-select__caret").first());
                candidates.add(elsel);
            }
        } catch (Exception ignored) {}
        try {
            candidates.add(trigger.locator("xpath=ancestor-or-self::*[@role='combobox'][1]").first());
        } catch (Exception ignored) {}
        candidates.add(trigger);
        
        for (Locator c : candidates) {
            if (c == null) continue;
            try { if (c.count() == 0) continue; } catch (Exception ignored) { continue; }
            try {
                if (c.isVisible()) {
                    try { c.scrollIntoViewIfNeeded(); } catch (Exception ignored) {}
                    highlight(c);
                    try { c.click(); } catch (Exception e) { if (!tryDomClickFallback(c)) throw e; }
                } else {
                    if (!tryDomClickFallback(c)) continue;
                }
            } catch (Exception ignored) {}
            try { page.waitForTimeout(80); } catch (Exception ignored) {}
            if (isDropdownOpen(trigger)) return true;
        }
        
        try {
            if (trigger.isVisible()) {
                highlight(trigger);
                try { trigger.scrollIntoViewIfNeeded(); } catch (Exception ignored) {}
                trigger.evaluate("el => { const fire = (t) => { try { t.dispatchEvent(new MouseEvent('mousedown', { bubbles: true })); } catch (e) {} try { t.dispatchEvent(new MouseEvent('mouseup', { bubbles: true })); } catch (e) {} try { t.click(); } catch (e) {} }; const ant = el.closest('.ant-select'); if (ant) { const t = ant.querySelector('.ant-select-selection-overflow-item-suffix') || ant.querySelector('.ant-select-selection-overflow-item') || ant.querySelector('.ant-select-selector') || ant; fire(t); return; } const elsel = el.closest('.el-select'); if (elsel) { const t = elsel.querySelector('.el-input__inner') || elsel; fire(t); return; } fire(el); }");
            } else {
                trigger.evaluate("el => { const fire = (t) => { try { t.dispatchEvent(new MouseEvent('mousedown', { bubbles: true })); } catch (e) {} try { t.dispatchEvent(new MouseEvent('mouseup', { bubbles: true })); } catch (e) {} try { t.click(); } catch (e) {} }; const ant = el.closest('.ant-select'); if (ant) { const t = ant.querySelector('.ant-select-selection-overflow-item-suffix') || ant.querySelector('.ant-select-selection-overflow-item') || ant.querySelector('.ant-select-selector') || ant; fire(t); return; } const elsel = el.closest('.el-select'); if (elsel) { const t = elsel.querySelector('.el-input__inner') || elsel; fire(t); return; } const label = el.closest('label'); if (label) { fire(label); return; } if (el.parentElement) { fire(el.parentElement); return; } fire(el); }");
            }
        } catch (Exception ignored) {}
        
        try { page.waitForTimeout(120); } catch (Exception ignored) {}
        return isDropdownOpen(trigger);
    }
    
    private boolean tryClickDropdownOption(String optionText) {
        String opt = optionText == null ? "" : optionText.trim();
        if (opt.isEmpty()) return false;
        String escaped = escapeForSelectorValue(opt);
        
        String[] selectors = new String[] {
                "role=option[name=\"" + escaped + "\"]",
                "[role=option]:has-text(\"" + escaped + "\")",
                ".ant-select-item-option-content:has-text(\"" + escaped + "\")",
                ".ant-select-item-option:has-text(\"" + escaped + "\")",
                ".el-select-dropdown__item:has-text(\"" + escaped + "\")",
                "[role=listbox] >> text=\"" + escaped + "\"",
                "text=\"" + escaped + "\""
        };
        
        Locator container = resolveDropdownPopupContainer();
        if (container != null) {
            for (String sel : selectors) {
                if (sel == null || sel.trim().isEmpty()) continue;
                try {
                    Locator loc = container.locator(sel).first();
                    waitForLocatorAttached(loc, 300);
                    if (loc.count() == 0) continue;
                    if (!loc.isVisible()) continue;
                    highlight(loc);
                    try {
                        loc.click();
                    } catch (Exception e) {
                        Locator wrap = null;
                        try {
                            wrap = loc.locator("xpath=ancestor-or-self::*[@role='option' or contains(@class,'ant-select-item-option') or contains(@class,'el-select-dropdown__item')][1]").first();
                            waitForLocatorAttached(wrap, 200);
                        } catch (Exception ignored) { wrap = null; }
                        if (wrap != null && wrap.count() > 0 && wrap.isVisible()) {
                            highlight(wrap);
                            try { wrap.click(); } catch (Exception ignored) { if (!tryDomClickFallback(wrap)) throw e; }
                        } else if (!tryDomClickFallback(loc)) {
                            throw e;
                        }
                    }
                    return true;
                } catch (Exception ignored) {}
            }
        }
        
        for (String sel : selectors) {
            if (sel == null || sel.trim().isEmpty()) continue;
            try {
                Locator loc = locator(sel).first();
                waitForLocatorAttached(loc, 300);
                if (loc.count() == 0) throw new RuntimeException("no match in current context");
                if (!loc.isVisible()) throw new RuntimeException("not visible in current context");
                highlight(loc);
                try {
                    loc.click();
                } catch (Exception e) {
                    Locator wrap = null;
                    try {
                        wrap = loc.locator("xpath=ancestor-or-self::*[@role='option' or contains(@class,'ant-select-item-option') or contains(@class,'el-select-dropdown__item')][1]").first();
                        waitForLocatorAttached(wrap, 200);
                    } catch (Exception ignored) { wrap = null; }
                    if (wrap != null && wrap.count() > 0 && wrap.isVisible()) {
                        highlight(wrap);
                        try { wrap.click(); } catch (Exception ignored) { if (!tryDomClickFallback(wrap)) throw e; }
                    } else if (!tryDomClickFallback(loc)) {
                        throw e;
                    }
                }
                return true;
            } catch (Exception ignored) {}
            
            try {
                Frame f = ensureFrame();
                if (f == null) continue;
                Locator loc = page.locator(sel).first();
                waitForLocatorAttached(loc, 300);
                if (loc.count() == 0) continue;
                if (!loc.isVisible()) continue;
                highlight(loc);
                try {
                    loc.click();
                } catch (Exception e) {
                    Locator wrap = null;
                    try {
                        wrap = loc.locator("xpath=ancestor-or-self::*[@role='option' or contains(@class,'ant-select-item-option') or contains(@class,'el-select-dropdown__item')][1]").first();
                        waitForLocatorAttached(wrap, 200);
                    } catch (Exception ignored2) { wrap = null; }
                    if (wrap != null && wrap.count() > 0 && wrap.isVisible()) {
                        highlight(wrap);
                        try { wrap.click(); } catch (Exception ignored2) { if (!tryDomClickFallback(wrap)) throw e; }
                    } else if (!tryDomClickFallback(loc)) {
                        throw e;
                    }
                }
                return true;
            } catch (Exception ignored) {}
        }
        
        return false;
    }
    
    private Locator resolveDropdownPopupContainer() {
        String[] selectors = new String[] {
                ".ant-select-dropdown:visible",
                ".ant-select-dropdown .rc-virtual-list-holder:visible",
                ".rc-virtual-list-holder:visible",
                ".el-select-dropdown:visible",
                ".el-scrollbar__wrap:visible",
                "[role=listbox]:visible"
        };
        for (String sel : selectors) {
            try {
                Locator loc = locator(sel).first();
                waitForLocatorAttached(loc, 250);
                if (loc.count() == 0) continue;
                if (!loc.isVisible()) continue;
                return loc;
            } catch (Exception ignored) {}
            try {
                Frame f = ensureFrame();
                if (f == null) continue;
                Locator loc = page.locator(sel).first();
                waitForLocatorAttached(loc, 250);
                if (loc.count() == 0) continue;
                if (!loc.isVisible()) continue;
                return loc;
            } catch (Exception ignored) {}
        }
        return null;
    }

    private java.util.List<String> collectVisibleDropdownOptions(int maxItems) {
        int max = Math.max(0, maxItems);
        if (max == 0) return java.util.Collections.emptyList();
        Locator container = resolveDropdownPopupContainer();
        if (container == null) return java.util.Collections.emptyList();
        String[] itemSelectors = new String[] {
                "[role=option]",
                ".ant-select-item-option-content",
                ".ant-select-item-option",
                ".el-select-dropdown__item"
        };
        java.util.LinkedHashSet<String> uniq = new java.util.LinkedHashSet<>();
        for (String sel : itemSelectors) {
            try {
                Locator items = container.locator(sel);
                waitForLocatorAttached(items.first(), 120);
                int c = 0;
                try { c = items.count(); } catch (Exception ignored) { c = 0; }
                int take = Math.min(c, max - uniq.size());
                for (int i = 0; i < take; i++) {
                    try {
                        String t = items.nth(i).innerText();
                        if (t != null) t = t.trim();
                        if (t != null && !t.isEmpty()) uniq.add(t);
                    } catch (Exception ignored) {}
                    if (uniq.size() >= max) break;
                }
            } catch (Exception ignored) {}
            if (uniq.size() >= max) break;
        }
        return new java.util.ArrayList<>(uniq);
    }
    
    private boolean tryTypeDropdownAndEnter(Locator trigger, String optionText) {
        String opt = optionText == null ? "" : optionText.trim();
        if (opt.isEmpty()) return false;
        if (trigger == null) return false;
        
        try {
            Locator input = null;
            try {
                input = trigger.locator("input").first();
                waitForLocatorAttached(input, 300);
            } catch (Exception ignored) {
                input = null;
            }
            if (input == null || input.count() == 0) {
                try {
                    input = locator(".ant-select-selection-search-input").first();
                    waitForLocatorAttached(input, 300);
                } catch (Exception ignored) {
                    input = null;
                }
            }
            if (input == null || input.count() == 0) return false;
            if (!input.isVisible()) return false;
            
            highlight(input);
            input.fill(opt);
            input.press("Enter");
            wait(150);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }
    
    /**
     * Extracts currently visible row texts without scrolling.
     * Useful for "first page" data where we should avoid scrolling.
     */
    public List<String> extractVisibleList(String rowSelector, int limit) {
        log("Action: Extract visible list for '" + rowSelector + "' (Limit: " + limit + ")");
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        java.util.List<String> results = new java.util.ArrayList<>();
        try {
            Locator rows = locator(rowSelector);
            int count = rows.count();
            for (int i = 0; i < count; i++) {
                String text = rows.nth(i).innerText();
                if (text != null) {
                    text = text.trim();
                }
                if (text != null && !text.isEmpty() && seen.add(text)) {
                    results.add(text);
                    if (limit > 0 && results.size() >= limit) break;
                }
            }
        } catch (Exception e) {
            log("Warning: extractVisibleList failed: " + e.getMessage());
        }
        log("  -> Extracted " + results.size() + " visible items.");
        return results;
    }

    // --- Waiting ---

    public void waitFor(String selector) {
        log("Wait: For element '" + selector + "'");
        String ariaLabel = extractOnlyAriaLabel(selector);
        Locator loc = locator(selector).first();
        try {
            boolean isCheckboxSelector = selector.contains("checkbox") || selector.contains("ant-checkbox-input");
            if (isCheckboxSelector && loc.count() > 0 && !loc.isVisible()) {
                loc.waitFor(new Locator.WaitForOptions()
                        .setTimeout(defaultTimeout)
                        .setState(com.microsoft.playwright.options.WaitForSelectorState.ATTACHED));
                return;
            }
        } catch (Exception ignored) {}
        try {
            loc.waitFor(new Locator.WaitForOptions().setTimeout(defaultTimeout));
        } catch (RuntimeException e) {
            if (ariaLabel != null) {
                Locator alt = resolveInputLocatorFromAriaLabel(ariaLabel);
                if (alt != null) {
                    log("  -> Wait fallback: aria-label '" + ariaLabel + "'");
                    alt.first().waitFor(new Locator.WaitForOptions().setTimeout(defaultTimeout));
                    return;
                }
            }
            Locator alt = tryResolveClickTargetByText(selector);
            if (alt != null) {
                log("  -> Wait fallback: text '" + selector + "'");
                alt.first().waitFor(new Locator.WaitForOptions().setTimeout(defaultTimeout));
                return;
            }
            RuntimeException login = rewriteIfLogin(e, "waitFor " + selector);
            if (login != null) throw login;
            throw e;
        }
    }
    
    public void waitFor(String selector, Number timeout) {
        log("Wait: For element '" + selector + "' with timeout " + timeout);
        String ariaLabel = extractOnlyAriaLabel(selector);
        Locator loc = locator(selector).first();
        try {
            loc.waitFor(new Locator.WaitForOptions().setTimeout(timeout.doubleValue()));
        } catch (RuntimeException e) {
            if (ariaLabel != null) {
                Locator alt2 = resolveInputLocatorFromAriaLabel(ariaLabel);
                if (alt2 != null) {
                    log("  -> Wait fallback: aria-label '" + ariaLabel + "'");
                    alt2.first().waitFor(new Locator.WaitForOptions().setTimeout(timeout.doubleValue()));
                    return;
                }
            }
            Locator alt = tryResolveClickTargetByText(selector);
            if (alt != null) {
                log("  -> Wait fallback: text '" + selector + "'");
                alt.first().waitFor(new Locator.WaitForOptions().setTimeout(timeout.doubleValue()));
                return;
            }
            RuntimeException login = rewriteIfLogin(e, "waitFor " + selector);
            if (login != null) throw login;
            throw e;
        }
    }

    /**
     * Fallback for waitFor with options (ignored)
     */
    public void waitFor(String selector, Object options) {
        if (options instanceof Number) {
            waitFor(selector, (Number) options);
            return;
        }
        log("Wait: For element '" + selector + "' (ignoring extra options)");
        waitFor(selector);
    }

    public void wait(int millis) {
        log("Wait: " + millis + "ms");
        page.waitForTimeout(millis);
    }

    // --- Interaction ---
    
    public void hover(String selector) {
        log("Action: Hover over '" + selector + "'");
        retry(() -> {
            Locator loc = locator(selector).first();
            int c = 0;
            try { c = loc.count(); } catch (Exception ignored) {}
            if (c == 0) {
                Locator alt = tryResolveClickTargetByText(selector);
                if (alt != null) loc = alt.first();
            }
            try { loc.scrollIntoViewIfNeeded(); } catch (Exception ignored) {}
            try {
                highlight(loc);
                loc.hover();
                return;
            } catch (Exception ignored) {}
            try {
                Locator h = loc.locator("xpath=ancestor-or-self::*[self::button or self::a or @role='button' or @role='combobox'][1]").first();
                waitForLocatorAttached(h, 300);
                if (h.count() > 0) {
                    try { h.scrollIntoViewIfNeeded(); } catch (Exception ignored2) {}
                    highlight(h);
                    h.hover();
                    return;
                }
            } catch (Exception ignored) {}
            throw new RuntimeException("Hover failed: " + selector);
        }, "hover " + selector);
    }
    
    public void press(String selector, String key) {
        log("Action: Press key '" + key + "' on '" + selector + "'");
        retry(() -> {
            Locator loc = locator(selector).first();
            try {
                loc.press(key);
                return;
            } catch (Exception ignored) {}
            try {
                Locator input = loc.locator("input, textarea, [contenteditable=true]").first();
                waitForLocatorAttached(input, 300);
                if (input.count() > 0) {
                    highlight(input);
                    input.press(key);
                    return;
                }
            } catch (Exception ignored) {}
            throw new RuntimeException("Press failed: " + selector + " -> " + key);
        }, "press " + selector + " " + key);
    }
    
    /**
     * Scrolls element into view if needed.
     */
    public void scrollIntoView(String selector) {
        log("Action: Scroll into view '" + selector + "'");
        locator(selector).first().scrollIntoViewIfNeeded();
    }

    /**
     * Scrolls the window to the bottom.
     */
    public void scrollToBottom() {
        log("Action: Scroll window to bottom");
        page.evaluate("window.scrollTo(0, document.body.scrollHeight)");
    }
    
    /**
     * Scrolls the window to the top.
     */
    public void scrollToTop() {
        log("Action: Scroll window to top");
        page.evaluate("window.scrollTo(0, 0)");
    }

    /**
     * Scrolls a specific container to its bottom (useful for virtual tables).
     */
    public void scrollToBottom(String selector) {
        log("Action: Scroll '" + selector + "' to bottom");
        highlight(selector);
        locator(selector).first().evaluate("el => { el.scrollTop = el.scrollHeight; el.dispatchEvent(new Event('scroll', { bubbles: true })); }");
    }

    /**
     * Scrolls a specific container to its top (useful for virtual tables).
     */
    public void scrollToTop(String selector) {
        log("Action: Scroll '" + selector + "' to top");
        if (!exists(selector)) {
            log("Warning: scrollToTop target not found: '" + selector + "'");
            scrollToTop();
            return;
        }
        highlight(selector);
        locator(selector).first().evaluate("el => { el.scrollTop = 0; el.dispatchEvent(new Event('scroll', { bubbles: true })); }");
    }
    
    private int getScrollTop(String selector) {
        try {
            if (!exists(selector)) return 0;
            Object v = locator(selector).first().evaluate("el => el.scrollTop");
            return (v instanceof Number) ? ((Number) v).intValue() : 0;
        } catch (Exception e) {
            return 0;
        }
    }
    
    private int getMaxScrollTop(String selector) {
        try {
            if (!exists(selector)) return 0;
            Object v = locator(selector).first().evaluate("el => el.scrollHeight - el.clientHeight");
            return (v instanceof Number) ? ((Number) v).intValue() : 0;
        } catch (Exception e) {
            return 0;
        }
    }
    
    private boolean isAtTop(String selector) {
        return getScrollTop(selector) <= 2;
    }
    
    private boolean isAtBottom(String selector) {
        int top = getScrollTop(selector);
        int max = getMaxScrollTop(selector);
        return max > 0 && (max - top) <= 2;
    }
    
    private String normalizeText(String s) {
        if (s == null) return "";
        return s.replaceAll("[\\r\\n]+", " ").replaceAll("\\s+", " ").trim();
    }
    
    private String md5(String s) {
        try {
            byte[] bytes = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(bytes);
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return s;
        }
    }
    
    private void writeDebugFile(String fileName, List<String> lines) {
        try {
            java.nio.file.Path dir = java.nio.file.Paths.get(System.getProperty("user.dir"), "autoweb", "debug");
            java.nio.file.Files.createDirectories(dir);
            java.nio.file.Path path = dir.resolve(fileName);
            java.nio.file.Files.write(path, lines, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception ignored) {}
    }
    
    public int getTotalCount() {
        try {
            String txt = getText(".ant-pagination-total-text");
            if (txt != null) {
                String digits = txt.replaceAll("[^0-9]", "");
                if (!digits.isEmpty()) {
                    return Integer.parseInt(digits);
                }
            }
        } catch (Exception ignored) {}
        return -1;
    }
    
    public int getPageSize() {
        String[] candidates = {
            ".ant-pagination-options .ant-select-selection-item",
            ".ant-pagination-options .ant-select-selector .ant-select-selection-item",
            ".ant-pagination-options"
        };
        for (String sel : candidates) {
            try {
                if (count(sel) > 0) {
                    String t = locator(sel).first().innerText();
                    if (t != null) {
                        String digits = t.replaceAll("[^0-9]", "");
                        if (!digits.isEmpty()) {
                            return Integer.parseInt(digits);
                        }
                    }
                }
            } catch (Exception ignored) {}
        }
        // Fallback: scan common option contents
        try {
            Locator opts = locator(".ant-select-item-option-content");
            int c = opts.count();
            for (int i = 0; i < c; i++) {
                String t = opts.nth(i).innerText();
                if (t != null && t.contains("条/页")) {
                    String digits = t.replaceAll("[^0-9]", "");
                    if (!digits.isEmpty()) {
                        return Integer.parseInt(digits);
                    }
                }
            }
        } catch (Exception ignored) {}
        return -1;
    }
    
    public int resolvePageSize() {
        int size = getPageSize();
        if (size < 1) {
            int total = getTotalCount();
            if (total > 0) size = total;
        }
        return size;
    }
    
    public List<String> extractFirstPageRows(String containerSelector, String rowSelector, String cellSelector) {
        int size = resolvePageSize();
        if (size < 1) size = -1;
        return extractRowTexts(containerSelector, rowSelector, cellSelector, size);
    }
    
    public List<java.util.Map<String, String>> extractFirstPageTable(String containerSelector, String rowSelector, java.util.Map<?, ?> columns) {
        int size = resolvePageSize();
        if (size < 1) size = -1;
        return extractTableData(containerSelector, rowSelector, size, columns);
    }
    
    /**
     * Fallback method for simpler calls (inferred selectors)
     */
    public List<java.util.Map<String, String>> extractFirstPageTable(java.util.Map<?, ?> columns) {
        // Heuristic to find table
        String[] containers = {".art-table-body", ".ant-table-tbody", ".el-table__body-wrapper", "table tbody", "table"};
        String[] rows = {".art-table-row", ".ant-table-row", ".el-table__row", "tr", "tr"};
        
        for (int i=0; i<containers.length; i++) {
             // check if container exists and has rows
             try {
                 if (count(containers[i]) > 0 && count(containers[i] + " " + rows[i]) > 0) {
                     log("Action: Heuristic found table: " + containers[i]);
                     return extractFirstPageTable(containers[i], rows[i], columns);
                 }
             } catch (Exception ignored) {}
        }
        // Fallback to global tr if nothing matches
        return extractFirstPageTable(null, "tr", columns);
    }

    public List<java.util.Map<String, String>> extractPagesTable(String containerSelector, String rowSelector, java.util.Map<?, ?> columns, String nextBtnSelector, int maxPages) {
        log("Action: Extract pages table using next button '" + nextBtnSelector + "' (Max pages: " + maxPages + ")");
        List<java.util.Map<String, String>> allData = new java.util.ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        int pageCount = 0;
        
        while (pageCount < maxPages) {
            // Extract current page
            List<java.util.Map<String, String>> pageData = extractFirstPageTable(containerSelector, rowSelector, columns);
            if (pageData == null || pageData.isEmpty()) {
                List<java.util.Map<String, String>> fallback = extractFirstPageTable(columns);
                if (fallback != null && !fallback.isEmpty()) {
                    log("  -> Fallback: heuristic table extraction succeeded on page " + (pageCount + 1));
                    pageData = fallback;
                }
            }
            if (pageData != null && !pageData.isEmpty()) {
                int added = 0;
                for (java.util.Map<String, String> row : pageData) {
                    String signature = JSON.toJSONString(row);
                    if (seen.add(signature)) {
                        allData.add(row);
                        added++;
                    }
                }
                log("Extracted " + added + " new rows from page " + (pageCount + 1));
            } else {
                log("Warning: No data found on page " + (pageCount + 1));
            }
            
            pageCount++;
            if (pageCount >= maxPages) break;

            // Check next button
            try {
                Locator nextBtn = resolveNextPaginationButton(nextBtnSelector);
                if (nextBtn == null || nextBtn.count() == 0 || !nextBtn.isVisible()) {
                    log("Next button not found or invisible. Stopping.");
                    break;
                }
                
                String classAttr = nextBtn.getAttribute("class");
                if (nextBtn.isDisabled() || (classAttr != null && classAttr.toLowerCase().contains("disabled"))) {
                    log("Next button is disabled. Stopping.");
                    break;
                }
                
                highlight(nextBtn);
                nextBtn.click();
                
                // Wait for load
                page.waitForTimeout(2000); 
                
            } catch (Exception e) {
                log("Error handling next button: " + e.getMessage());
                break;
            }
        }
        
        return allData;
    }
    
    private void waitForLocatorAttached(Locator loc, int timeoutMs) {
        try {
            if (loc == null) return;
            loc.waitFor(new Locator.WaitForOptions()
                    .setTimeout(timeoutMs)
                    .setState(com.microsoft.playwright.options.WaitForSelectorState.ATTACHED));
        } catch (Exception ignored) {}
    }
    
    private Locator tryFallbackCheckboxLocator(String selector) {
        if (selector == null) return null;
        String s = selector.trim();
        if (s.isEmpty()) return null;
        
        String[] candidates = new String[] {
                s.replace("input[type='checkbox']", ".ant-checkbox-input").replace("input[type=\"checkbox\"]", ".ant-checkbox-input").replace("input[type=checkbox]", ".ant-checkbox-input"),
                ".art-table-row:first-child .ant-checkbox-input",
                ".ant-table-row:first-child .ant-checkbox-input",
                "role=checkbox",
                "a11y:checkbox"
        };
        
        for (String c : candidates) {
            if (c == null) continue;
            String t = c.trim();
            if (t.isEmpty()) continue;
            try {
                Locator loc = locator(t);
                if (loc.count() > 0) {
                    if (!t.equals(s)) log("  -> Checkbox fallback selector: '" + t + "'");
                    return loc;
                }
            } catch (Exception ignored) {}
        }
        log("  -> Checkbox fallback exhausted: '" + selector + "'");
        return null;
    }
    
    private boolean isCheckboxIntent(String selector) {
        if (selector == null) return false;
        String s = selector.toLowerCase();
        return s.contains("checkbox")
                || s.contains(".ant-checkbox")
                || s.contains("[role=checkbox]")
                || s.contains("role=checkbox")
                || s.contains("input[type=\"checkbox\"")
                || s.contains("input[type='checkbox'")
                || s.contains("input[type=checkbox");
    }
    
    private Locator resolveNextPaginationButton(String preferredSelector) {
        String preferred = preferredSelector == null ? "" : preferredSelector.trim();
        String[] candidates = new String[] {
                preferredSelector,
                ".ant-pagination-next:not(.ant-pagination-disabled) button",
                ".ant-pagination-next:not(.ant-pagination-disabled)",
                ".ant-pagination-next button",
                "li:has-text('下一页') button",
                "li[title*='下一页'] button",
                "button[aria-label*='下一页']",
                "button[aria-label*='Next']",
                "role=button[name=\"right\"]",
                "role=button[name=\"下一页\"]"
        };
        
        for (String sel : candidates) {
            if (sel == null) continue;
            String s = sel.trim();
            if (s.isEmpty()) continue;
            try {
                Locator loc = locator(s).first();
                waitForLocatorAttached(loc, 800);
                if (loc.count() == 0) continue;
                if (!loc.isVisible()) continue;
                String classAttr = null;
                try { classAttr = loc.getAttribute("class"); } catch (Exception ignored) {}
                boolean disabled = false;
                try { disabled = loc.isDisabled(); } catch (Exception ignored) {}
                if (classAttr != null && classAttr.toLowerCase().contains("disabled")) disabled = true;
                String ariaDisabled = null;
                try { ariaDisabled = loc.getAttribute("aria-disabled"); } catch (Exception ignored) {}
                if ("true".equalsIgnoreCase(ariaDisabled)) disabled = true;
                if (disabled) continue;
                if (!preferred.isEmpty() && !s.equals(preferred)) {
                    log("  -> Next button fallback selector: '" + s + "'");
                }
                return loc;
            } catch (Exception ignored) {}
        }
        log("  -> Next button not found for preferred='" + preferredSelector + "'");
        return null;
    }
    
    private boolean isScrollable(String selector) {
        try {
            Object v = locator(selector).first().evaluate("el => (el.scrollHeight - el.clientHeight) > 2");
            if (v instanceof Boolean) return (Boolean) v;
        } catch (Exception ignored) {}
        return false;
    }

    private boolean isSelectorLike(String selector) {
        if (selector == null) return false;
        String trimmed = selector.trim();
        if (trimmed.isEmpty()) return false;
        if (trimmed.matches(".*[\\.\\#\\[\\]:>].*")) return true;
        if (trimmed.contains("nth-child") || trimmed.contains("td") || trimmed.contains("th") || trimmed.contains("tr")) return true;
        if (trimmed.contains("text=") || trimmed.contains(">>")) return true;
        return false;
    }

    private java.util.Map<String, Integer> buildHeaderIndex(String containerSelector) {
        java.util.Map<String, Integer> headerIndex = new java.util.HashMap<>();
        Locator headerCells = null;
        try {
            page.evaluate("() => document.querySelectorAll('[data-webdsl-table-root]').forEach(el => el.removeAttribute('data-webdsl-table-root'))");
        } catch (Exception ignored) {}
        try {
            if (containerSelector != null && !containerSelector.trim().isEmpty()) {
                Locator container = locator(containerSelector).first();
                try {
                    container.evaluate("el => { let p = el; while (p) { const cls = (p.className && p.className.toString) ? p.className.toString() : ''; if (cls.includes('art-table') || cls.includes('table')) { p.setAttribute('data-webdsl-table-root','1'); return true; } p = p.parentElement; } return false; }");
                } catch (Exception ignored) {}
                Locator root = locator("[data-webdsl-table-root='1']").first();
                if (root != null && root.count() > 0) {
                    headerCells = root.locator(".art-table-header-cell, .art-table-header th, thead th, th");
                }
            }
        } catch (Exception ignored) {}
        if (headerCells == null || headerCells.count() == 0) {
            headerCells = locator(".art-table-header-cell, .art-table-header th, thead th, th");
        }
        int count = headerCells.count();
        for (int i = 0; i < count; i++) {
            String text = "";
            try { text = normalizeText(headerCells.nth(i).innerText()); } catch (Exception ignored) {}
            if (!text.isEmpty() && !headerIndex.containsKey(text)) {
                headerIndex.put(text, i + 1);
            }
        }
        return headerIndex;
    }

    private java.util.Map<String, String> resolveColumnSelectors(String containerSelector, java.util.Map<?, ?> columns) {
        java.util.Map<String, String> resolved = new java.util.HashMap<>();
        java.util.Map<String, Integer> headerIndex = buildHeaderIndex(containerSelector);
        for (java.util.Map.Entry<?, ?> entry : columns.entrySet()) {
            String colName = entry.getKey() == null ? "" : entry.getKey().toString();
            String colSel = entry.getValue() == null ? "" : entry.getValue().toString();
            String resolvedSel = colSel;
            if (!isSelectorLike(colSel)) {
                Integer idx = headerIndex.get(colSel);
                if (idx == null && !colName.isEmpty()) {
                    idx = headerIndex.get(colName);
                }
                if (idx != null) {
                    resolvedSel = "td:nth-child(" + idx + ")";
                }
            }
            resolved.put(colName, resolvedSel);
        }
        return resolved;
    }
    
    private Locator getRowsInContainer(String containerSelector, String rowSelector) {
        try {
            if (containerSelector != null && !containerSelector.trim().isEmpty()) {
                Locator containers = locator(containerSelector);
                int c = containers.count();
                for (int i = 0; i < c; i++) {
                    Locator container = containers.nth(i);
                    try {
                        if (!container.isVisible()) continue;
                    } catch (Exception ignored) {}
                    Locator rows = container.locator(rowSelector);
                    if (rows.count() > 0) {
                        return rows;
                    }
                }
                if (c > 0) {
                    return containers.first().locator(rowSelector);
                }
            }
        } catch (Exception ignored) {}
        return locator(rowSelector);
    }
    
    private String ensureScrollableContainer(String containerSelector, String rowSelector) {
        if (containerSelector != null && !containerSelector.trim().isEmpty()) {
            try {
                Locator containers = locator(containerSelector);
                int c = containers.count();
                if (c == 0) {
                    log("Warning: containerSelector not found: '" + containerSelector + "'. Fallback to auto-detect via rowSelector/common containers.");
                    containerSelector = null;
                } else {
                for (int i = 0; i < c; i++) {
                    Locator container = containers.nth(i);
                    try {
                        if (!container.isVisible()) continue;
                    } catch (Exception ignored) {}
                    boolean scrollable = false;
                    try {
                        Object v = container.evaluate("el => (el.scrollHeight - el.clientHeight) > 2");
                        if (v instanceof Boolean) scrollable = (Boolean) v;
                    } catch (Exception ignored) {}
                    if (scrollable) {
                        container.evaluate("el => el.setAttribute('data-webdsl-scroll-target','1')");
                        return "[data-webdsl-scroll-target='1']";
                    }
                }
                for (int i = 0; i < c; i++) {
                    Locator container = containers.nth(i);
                    try {
                        if (!container.isVisible()) continue;
                    } catch (Exception ignored) {}
                    Locator rowsInContainer = container.locator(rowSelector);
                    if (rowsInContainer.count() == 0) continue;
                    try {
                        rowsInContainer.first().evaluate("el => { " +
                                "let p = el.parentElement; " +
                                "while (p) { " +
                                "  const sh = p.scrollHeight; const ch = p.clientHeight; " +
                                "  if ((sh - ch) > 2) { " +
                                "    p.setAttribute('data-webdsl-scroll-target','1'); " +
                                "    return true; " +
                                "  } " +
                                "  p = p.parentElement; " +
                                "} " +
                                "return false; " +
                                "}");
                        Locator detected = locator("[data-webdsl-scroll-target='1']").first();
                        if (detected != null && detected.count() > 0) {
                            log("Action: Detected scrollable container via ancestor search");
                            return "[data-webdsl-scroll-target='1']";
                        }
                    } catch (Exception ignored) {}
                }
                }
            } catch (Exception ignored) {}
            if (containerSelector != null && isScrollable(containerSelector)) {
                return containerSelector;
            }
        }
        // Try to detect scrollable ancestor of the first row
        try {
            if (rowSelector == null || rowSelector.trim().isEmpty()) throw new RuntimeException("empty rowSelector");
            Locator rows = locator(rowSelector);
            int rc = rows.count();
            if (rc == 0) throw new RuntimeException("rowSelector not found");
            Locator row = rows.first();
            // Mark scrollable ancestor with a data attribute
            row.evaluate("el => { " +
                    "let p = el.parentElement; " +
                    "while (p) { " +
                    "  const sh = p.scrollHeight; const ch = p.clientHeight; " +
                    "  if ((sh - ch) > 2) { " +
                    "    p.setAttribute('data-webdsl-scroll-target','1'); " +
                    "    return true; " +
                    "  } " +
                    "  p = p.parentElement; " +
                    "} " +
                    "return false; " +
                "}");
            Locator detected = locator("[data-webdsl-scroll-target='1']").first();
            if (detected != null && detected.count() > 0) {
                log("Action: Detected scrollable container via ancestor search");
                return "[data-webdsl-scroll-target='1']";
            }
            log("Warning: failed to detect scrollable container via rowSelector='" + rowSelector + "' (visibleRows=" + rc + ")");
        } catch (Exception ignored) {}
        
        String[] commonContainers = new String[] {
                ".art-table-body",
                ".ant-table-body",
                ".ant-table-content",
                ".ant-table-tbody",
                ".el-table__body-wrapper"
        };
        for (String c : commonContainers) {
            try {
                if (exists(c) && isScrollable(c)) return c;
            } catch (Exception ignored) {}
        }
        // Fallback to original selector
        return containerSelector;
    }
    
    public void ensureAtTop(String selector) {
        log("Action: Ensure '" + selector + "' at top");
        if (!exists(selector)) {
            log("Warning: EnsureAtTop target not found: '" + selector + "'. Scrolling window top as fallback");
            scrollToTop();
            page.waitForTimeout(150);
            return;
        }
        // Try multiple strategies to reliably return to top
        for (int i = 0; i < 5 && !isAtTop(selector); i++) {
            try {
                locator(selector).first().evaluate("el => el.scrollTop = 0");
            } catch (Exception ignored) {}
            page.waitForTimeout(100);
        }
        if (!isAtTop(selector)) {
            // Use wheel up bursts
            hover(selector);
            for (int i = 0; i < 4 && !isAtTop(selector); i++) {
                page.mouse().wheel(0, -1200);
                page.waitForTimeout(150);
            }
        }
        if (!isAtTop(selector)) {
            // Use Home key
            try {
                page.keyboard().press("Home");
                page.waitForTimeout(150);
            } catch (Exception ignored) {}
        }
        // Final check, fall back to window top
        if (!isAtTop(selector)) {
            log("Warning: Container did not reach top, scrolling window top as fallback");
            scrollToTop();
            page.waitForTimeout(150);
        }
    }
    
    /**
     * Robustly ensures a scrollable container is at top.
     * Combines programmatic scrollTop with reverse wheel events to trigger virtual list updates.
     */
    public void ensureTop(String selector) {
        log("Action: Ensure '" + selector + "' is at top");
        try {
            // Step 1: Direct scrollTop = 0
            locator(selector).first().evaluate("el => { el.scrollTop = 0; el.dispatchEvent(new Event('scroll', { bubbles: true })); }");
            page.waitForTimeout(300);
            
            // Step 2: Reverse wheel to force virtualization refresh
            hover(selector);
            for (int i = 0; i < 6; i++) {
                page.mouse().wheel(0, -1200);
                page.waitForTimeout(200);
            }
        } catch (Exception e) {
            log("Warning: ensureTop failed: " + e.getMessage());
        }
    }

    /**
     * Scrolls a container by a specific amount (useful for incremental loading).
     * Triggers a 'scroll' event to ensure virtual lists detect the change.
     */
    public void scrollBy(String selector, int amount) {
        log("Action: Scroll '" + selector + "' by " + amount + "px");
        if (!exists(selector)) {
            log("Warning: scrollBy target not found: '" + selector + "'");
            scrollWindowBy(amount);
            return;
        }
        highlight(selector);
        locator(selector).first().evaluate("el => { el.scrollTop += " + amount + "; el.dispatchEvent(new Event('scroll', { bubbles: true })); }");
    }

    private void scrollWindowBy(int amount) {
        try {
            log("Action: Scroll window by " + amount + "px");
            page.evaluate("amount => window.scrollBy(0, amount)", amount);
        } catch (Exception ignored) {}
    }

    /**
     * Simulates a mouse wheel scroll.
     * Useful for virtual lists that listen to wheel events instead of scroll events.
     */
    public void mouseWheel(int deltaX, int deltaY) {
        log("Action: Mouse Wheel x=" + deltaX + ", y=" + deltaY);
        page.mouse().wheel(deltaX, deltaY);
    }

    /**
     * Hovers over an element and scrolls with the mouse wheel.
     */
    public void mouseWheel(String selector, int deltaY) {
        log("Action: Mouse Wheel over '" + selector + "' by " + deltaY);
        hover(selector);
        page.mouse().wheel(0, deltaY);
    }

    // --- Visual Feedback ---
    
    private void highlight(String selector) {
        try {
            Locator loc = locator(selector).first();
            int c = 0;
            try { c = loc.count(); } catch (Exception ignored) {}
            if (c == 0) return;
            highlight(loc);
        } catch (Exception ignored) {}
    }

    private void highlight(Locator loc) {
        try {
            int c = 0;
            try { c = loc.count(); } catch (Exception ignored) {}
            if (c == 0) return;
            loc.evaluate("e => { e.style.border = '3px solid red'; e.style.backgroundColor = 'rgba(255,0,0,0.1)'; }");
            // brief pause to make it visible
            page.waitForTimeout(200);
        } catch (Exception e) {
            // Ignore highlight errors (e.g. if element detached)
        }
    }

    private boolean exists(String selector) {
        if (selector == null) return false;
        String s = selector.trim();
        if (s.isEmpty()) return false;
        try {
            return locator(s).count() > 0;
        } catch (Exception ignored) {
            return false;
        }
    }
    
    // --- Advanced / Convenience ---
    
    public int count(String selector) {
        return locator(selector).count();
    }
    
    public boolean isVisible(String selector) {
        return locator(selector).first().isVisible();
    }
    
    /**
     * Tries to find a button by text or selector and click it.
     */
    public void clickButton(String textOrSelector) {
        String raw = textOrSelector == null ? "" : textOrSelector.trim();
        if (raw.isEmpty()) throw new RuntimeException("clickButton: empty input");

        boolean treatAsSelector = isSelectorLike(raw) || raw.startsWith("role=") || raw.startsWith("text=") || raw.startsWith("xpath=") || raw.startsWith("css=");
        if (treatAsSelector) {
            try {
                if (isVisible(raw)) {
                    click(raw);
                    return;
                }
            } catch (Exception ignored) {}
        }

        String[] attempts;
        if (looksLikeTextRegex(raw)) {
            String pattern = escapeForRegexLiteralInSelector(raw);
            attempts = new String[] {
                    "role=button[name=/" + pattern + "/]",
                    "role=link[name=/" + pattern + "/]",
                    "text=/" + pattern + "/"
            };
        } else {
            String escaped = escapeForSelectorValue(raw);
            attempts = new String[] {
                    "role=button[name=\"" + escaped + "\"]",
                    "button:has-text(\"" + escaped + "\")",
                    "role=link[name=\"" + escaped + "\"]",
                    "a:has-text(\"" + escaped + "\")",
                    "text=\"" + escaped + "\""
            };
        }

        for (String sel : attempts) {
            try {
                Locator loc = locator(sel).first();
                waitForLocatorAttached(loc, 250);
                if (loc.count() == 0) continue;
                if (!loc.isVisible()) continue;
                click(sel);
                return;
            } catch (Exception ignored) {}
        }

        if (treatAsSelector) {
            click(raw);
            return;
        }
        throw new RuntimeException("Button not found/clickable: " + raw);
    }

    public void clickTab(String textOrSelector) {
        String raw = textOrSelector == null ? "" : textOrSelector.trim();
        if (raw.isEmpty()) throw new RuntimeException("clickTab: empty input");

        boolean treatAsSelector = isSelectorLike(raw) || raw.startsWith("role=") || raw.startsWith("text=") || raw.startsWith("xpath=") || raw.startsWith("css=");
        if (treatAsSelector) {
            try {
                if (isVisible(raw)) {
                    click(raw);
                    return;
                }
            } catch (Exception ignored) {}
        }

        String[] attempts;
        if (looksLikeTextRegex(raw)) {
            String pattern = escapeForRegexLiteralInSelector(raw);
            attempts = new String[] {
                    "role=tab[name=/" + pattern + "/]",
                    "[role=tab] >> text=/" + pattern + "/",
                    "text=/" + pattern + "/"
            };
        } else {
            String escaped = escapeForSelectorValue(raw);
            attempts = new String[] {
                    "role=tab[name=\"" + escaped + "\"]",
                    "[role=tab]:has-text(\"" + escaped + "\")",
                    ".ant-tabs-tab:has-text(\"" + escaped + "\")",
                    ".el-tabs__item:has-text(\"" + escaped + "\")",
                    "text=\"" + escaped + "\""
            };
        }

        for (String sel : attempts) {
            try {
                Locator loc = locator(sel).first();
                waitForLocatorAttached(loc, 250);
                if (loc.count() == 0) continue;
                if (!loc.isVisible()) continue;
                click(sel);
                return;
            } catch (Exception ignored) {}
        }

        if (treatAsSelector) {
            click(raw);
            return;
        }
        throw new RuntimeException("Tab not found/clickable: " + raw);
    }
    
    public void clickWithRetry(String selector) {
        click(selector);
    }

    // --- Table Helpers ---
    
    /**
     * Finds a row index where a cell contains the given text.
     * Returns -1 if not found.
     */
    public int findRowIndex(String rowSelector, String cellText) {
        Locator rows = locator(rowSelector);
        int count = rows.count();
        for (int i = 0; i < count; i++) {
            String rowContent = rows.nth(i).innerText();
            if (rowContent.contains(cellText)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Advanced: Extracts text from a virtual scrolling list.
     * Automatically handles scrolling, deduplication, and state restoration.
     *
     * @param containerSelector The scrollable container selector (e.g., '.art-table-body').
     * @param rowSelector       The selector for individual rows (e.g., '.art-table-row').
     * @param limit             Maximum number of items to extract (use -1 for no limit).
     * @return List of extracted text from each row.
     */
    public List<String> extractList(String containerSelector, String rowSelector, int limit) {
        log("Action: Extracting list from '" + containerSelector + "' (Limit: " + limit + ")");
        
        containerSelector = ensureScrollableContainer(containerSelector, rowSelector);
        
        java.util.Set<String> processed = new java.util.HashSet<>();
        java.util.List<String> results = new java.util.ArrayList<>();
        
        int noNewDataCount = 0;
        int maxScrolls = 80; // Safety break
        int scrollStep = 300;
        int wheelStep = 800;
        
        // Ensure start from top
        ensureAtTop(containerSelector);
        wait(300);

        for (int i = 0; i < maxScrolls; i++) {
            Locator rows = getRowsInContainer(containerSelector, rowSelector);
            int count = rows.count();
            boolean foundNew = false;
            
            for (int j = 0; j < count; j++) {
                Locator row = rows.nth(j);
                String text = "";
                try { text = row.innerText(); } catch (Exception ignored) {}
                String normalized = normalizeText(text);
                String key = buildStableRowKey(row, normalized);
                if (!normalized.isEmpty() && !processed.contains(key)) {
                    processed.add(key);
                    results.add(normalized);
                    foundNew = true;
                    
                    if (limit > 0 && results.size() >= limit) {
                        log("  -> Reached limit of " + limit + " items.");
                        break;
                    }
                }
            }
            
            if (limit > 0 && results.size() >= limit) break;

            if (!foundNew) {
                noNewDataCount++;
                if (noNewDataCount >= 3) {
                    log("  -> No new data found for 3 consecutive scrolls. Stopping.");
                    break;
                }
            } else {
                noNewDataCount = 0;
            }
            
            // Scroll down (combined strategy)
            if (isAtBottom(containerSelector)) {
                log("  -> Container reached bottom.");
                break;
            }
            // 1) element scroll
            scrollBy(containerSelector, scrollStep);
            wait(500);
            wait(500);
            
            // Escalate steps if no new data is found
            if (!foundNew) {
                scrollStep = Math.min(scrollStep * 2, 1600);
                wheelStep = Math.min(wheelStep * 2, 2400);
            } else {
                // Reset when new data discovered
                scrollStep = 300;
                wheelStep = 800;
            }
        }
        
        // Restore state to top
        ensureAtTop(containerSelector);
        wait(300);
        
        log("  -> Extracted " + results.size() + " unique items.");
        return results;
    }

    /**
     * Advanced: Extracts structured data from a virtual scrolling table.
     * 
     * @param containerSelector Selector for the scrollable container.
     * @param rowSelector       Selector for the rows.
     * @param limit             Max items to extract.
     * @param columns           Map of "ColumnName" -> "Relative Selector". 
     *                          Example: {"OrderNo": "td:nth-child(2)", "Status": ".status-label"}
     * @return List of Maps, where each Map represents a row's data.
     */
    public List<java.util.Map<String, String>> extractTableData(String containerSelector, String rowSelector, int limit, java.util.Map<?, ?> columns) {
        log("Action: Extracting table data from '" + containerSelector + "'...");
        
        containerSelector = ensureScrollableContainer(containerSelector, rowSelector);
        java.util.Map<String, String> resolvedColumns = resolveColumnSelectors(containerSelector, columns);
        
        java.util.Set<String> processedKeys = new java.util.HashSet<>();
        List<java.util.Map<String, String>> results = new java.util.ArrayList<>();
        
        // Ensure start from top
        ensureAtTop(containerSelector);
        wait(300);
        
        int maxScrolls = 80;
        int noNewDataCount = 0;
        int scrollStep = 300;
        int wheelStep = 800;
        
        for (int i = 0; i < maxScrolls; i++) {
            Locator rows = getRowsInContainer(containerSelector, rowSelector);
            int count = rows.count();
            boolean foundNew = false;
            
            for (int j = 0; j < count; j++) {
                Locator row = rows.nth(j);
                
                String raw = "";
                try { raw = row.innerText(); } catch (Exception ignored) {}
                String normalized = normalizeText(raw);
                if (normalized.isEmpty()) {
                    continue;
                }
                String rowKey = buildStableRowKey(row, normalized);
                if (processedKeys.contains(rowKey)) {
                    continue;
                }
                
                // 2. Extract columns
                java.util.Map<String, String> rowData = new java.util.HashMap<>();
                
                for (java.util.Map.Entry<?, ?> entry : columns.entrySet()) {
                    String colName = entry.getKey() == null ? "" : entry.getKey().toString();
                    String rawColSel = entry.getValue() == null ? "" : entry.getValue().toString();
                    String colSel = resolvedColumns.getOrDefault(colName, rawColSel);
                    String aliasKey = null;
                    if (!rawColSel.isEmpty() && !isSelectorLike(rawColSel) && !colName.isEmpty() && colName.matches("\\d+")) {
                        aliasKey = rawColSel;
                    }
                    try {
                        // Relative selector from the row
                        Locator colLoc = row.locator(colSel).first();
                        if (colLoc.count() > 0) {
                            String val = colLoc.innerText();
                            rowData.put(colName, val);
                            if (aliasKey != null && !aliasKey.equals(colName)) {
                                rowData.put(aliasKey, val);
                            }
                        } else {
                            rowData.put(colName, ""); 
                            if (aliasKey != null && !aliasKey.equals(colName)) {
                                rowData.put(aliasKey, "");
                            }
                        }
                    } catch (Exception e) {
                        rowData.put(colName, "");
                        if (aliasKey != null && !aliasKey.equals(colName)) {
                            rowData.put(aliasKey, "");
                        }
                    }
                }
                
                processedKeys.add(rowKey);
                results.add(rowData);
                foundNew = true;
                
                if (limit > 0 && results.size() >= limit) {
                    log("  -> Reached limit of " + limit + " items.");
                    break;
                }
            }
            
            if (limit > 0 && results.size() >= limit) break;
            
            if (!foundNew) {
                noNewDataCount++;
                if (noNewDataCount >= 3) {
                    log("  -> No new data found for 3 consecutive scrolls. Stopping.");
                    break;
                }
            } else {
                noNewDataCount = 0;
            }
            
            // Scroll down (combined strategy)
            if (isAtBottom(containerSelector)) {
                log("  -> Container reached bottom.");
                break;
            }
            // 1) element scroll
            scrollBy(containerSelector, scrollStep);
            wait(500);
            wait(500);
            
            // Escalate steps if no new data is found
            if (!foundNew) {
                scrollStep = Math.min(scrollStep * 2, 1600);
                wheelStep = Math.min(wheelStep * 2, 2400);
            } else {
                // Reset when new data discovered
                scrollStep = 300;
                wheelStep = 800;
            }
        }
        
        // Restore state to top
        ensureAtTop(containerSelector);
        wait(300);
        
        log("  -> Extracted " + results.size() + " structured rows.");
        return results;
    }
    
    /**
     * Extracts row texts (comma-joined cells) for the first page using MD5 dedup.
     * Generic and robust for virtual lists.
     */
    public List<String> extractRowTexts(String containerSelector, String rowSelector, String cellSelector, int limit) {
        log("Action: Extracting row texts from '" + containerSelector + "'...");
        
        containerSelector = ensureScrollableContainer(containerSelector, rowSelector);
        
        java.util.Set<String> processedKeys = new java.util.HashSet<>();
        List<String> results = new java.util.ArrayList<>();
        List<String> debug = new java.util.ArrayList<>();
        debug.add("start=" + java.time.LocalDateTime.now());
        debug.add("container=" + containerSelector);
        debug.add("rowSelector=" + rowSelector);
        debug.add("cellSelector=" + cellSelector);
        debug.add("limit=" + limit);
        try {
            int rowsInContainer = getRowsInContainer(containerSelector, rowSelector).count();
            debug.add("rowsInContainer(start)=" + rowsInContainer);
            if (rowsInContainer > 0) {
                Locator firstRow = getRowsInContainer(containerSelector, rowSelector).first();
                String firstText = "";
                try { firstText = normalizeText(firstRow.innerText()); } catch (Exception ignored) {}
                debug.add("firstRow(start)=" + firstText);
            }
        } catch (Exception ignored) {}
        
        ensureAtTop(containerSelector);
        wait(300);
        
        int maxScrolls = 80;
        int noNewData = 0;
        int scrollStep = 300;
        int wheelStep = 800;
        
        for (int i = 0; i < maxScrolls; i++) {
            Locator rows = getRowsInContainer(containerSelector, rowSelector);
            int count = rows.count();
            boolean foundNew = false;
            debug.add("scroll=" + i + " visibleRows=" + count);
            
            for (int j = 0; j < count; j++) {
                Locator row = rows.nth(j);
                // gather cells
                List<String> cells = new java.util.ArrayList<>();
                try {
                    Locator cellLocs = row.locator(cellSelector);
                    int cc = cellLocs.count();
                    for (int k = 0; k < cc; k++) {
                        String t = normalizeText(cellLocs.nth(k).innerText());
                        if (!t.isEmpty()) cells.add(t);
                    }
                } catch (Exception ignored) {}
                
                // Use JSON format for row text to ensure consistency across LLMs
                String joined = JSON.toJSONString(cells);
                String key;
                String head = cells.isEmpty() ? "" : cells.get(0);
                if (!head.isEmpty() && head.matches("\\d+")) {
                    key = "idx:" + head;
                } else {
                    String keySource = joined;
                    if (!cells.isEmpty()) {
                        int keyCount = Math.min(3, cells.size());
                        // Use JSON for key source as well for consistency
                        keySource = JSON.toJSONString(cells.subList(0, keyCount));
                    }
                    key = "md5:" + md5(keySource);
                }
                if (!joined.isEmpty() && !processedKeys.contains(key)) {
                    processedKeys.add(key);
                    results.add(joined);
                    foundNew = true;
                    debug.add("add index=" + results.size() + " rowIndex=" + j + " key=" + key + " head=" + head);
                    if (limit > 0 && results.size() >= limit) {
                        break;
                    }
                }
            }
            
            if (limit > 0 && results.size() >= limit) break;
            
            if (!foundNew) {
                noNewData++;
                if (noNewData >= 3) {
                    log("  -> No new row texts for 3 consecutive scrolls. Stopping.");
                    break;
                }
            } else {
                noNewData = 0;
            }
            
            if (isAtBottom(containerSelector)) break;
            int adaptiveStep = (count <= 3) ? 120 : scrollStep;
            int adaptiveWheel = (count <= 3) ? 200 : wheelStep;
            scrollBy(containerSelector, adaptiveStep);
            wait(500);
            hover(containerSelector);
            page.mouse().wheel(0, adaptiveWheel);
            wait(500);
            
            if (!foundNew) {
                scrollStep = Math.min(scrollStep * 2, 1600);
                wheelStep = Math.min(wheelStep * 2, 2400);
            } else {
                scrollStep = 300;
                wheelStep = 800;
            }
        }
        
        ensureAtTop(containerSelector);
        wait(300);
        try {
            int rowsInContainer = getRowsInContainer(containerSelector, rowSelector).count();
            debug.add("rowsInContainer(end)=" + rowsInContainer);
            if (rowsInContainer > 0) {
                Locator firstRow = getRowsInContainer(containerSelector, rowSelector).first();
                String firstText = "";
                try { firstText = normalizeText(firstRow.innerText()); } catch (Exception ignored) {}
                debug.add("firstRow(end)=" + firstText);
            }
        } catch (Exception ignored) {}
        debug.add("total=" + results.size());
        writeDebugFile("table_extract_debug.txt", debug);
        log("  -> Extracted " + results.size() + " row texts.");
        return results;
    }

    private String buildStableRowKey(Locator row, String normalizedText) {
        try {
            String attrKey = "";
            try { attrKey = row.evaluate("el => el.getAttribute('data-row-key') || el.getAttribute('data-rowid') || el.getAttribute('data-row-id') || ''").toString(); } catch (Exception ignored) {}
            if (attrKey != null && !attrKey.isEmpty()) {
                return "rowkey:" + attrKey;
            }
            Locator cells = row.locator("td, .art-table-cell, .ant-table-cell");
            int cc = cells.count();
            if (cc > 0) {
                StringBuilder sb = new StringBuilder();
                int take = Math.min(2, cc);
                for (int i = 0; i < take; i++) {
                    try {
                        String t = normalizeText(cells.nth(i).innerText());
                        if (!t.isEmpty()) {
                            if (sb.length() > 0) sb.append("|");
                            sb.append(t);
                        }
                    } catch (Exception ignored) {}
                }
                if (sb.length() > 0) {
                    return "cells:" + md5(sb.toString());
                }
            }
        } catch (Exception ignored) {}
        return "md5:" + md5(normalizedText);
    }

    /**
     * Advanced: Locates an item in a virtual list and scrolls it into view.
     * This ensures the item is visible and ready for interaction.
     *
     * @param containerSelector The scrollable container.
     * @param rowSelector       The row selector.
     * @param textToFind        The text to search for (partial match).
     * @return true if found and scrolled to, false otherwise.
     */
    public boolean locateItem(String containerSelector, String rowSelector, String textToFind) {
        log("Action: Locating item containing '" + textToFind + "' in '" + containerSelector + "'");
        
        // Start from top to be sure
        scrollToTop(containerSelector);
        wait(500);
        
        int maxScrolls = 50;

        for (int i = 0; i < maxScrolls; i++) {
            Locator rows = getRowsInContainer(containerSelector, rowSelector);
            int count = rows.count();
            
            // Search in current view
            for (int j = 0; j < count; j++) {
                Locator row = rows.nth(j);
                String content = row.innerText();
                if (content != null && content.contains(textToFind)) {
                    log("  -> Found item at visible index " + j);
                    highlight(row);
                    row.scrollIntoViewIfNeeded();
                    return true;
                }
            }
            
            // Scroll down to find more
            mouseWheel(containerSelector, 800);
            wait(800);
            
            // Optimization: If no items are visible, maybe we are scrolling a wrong container?
            if (count == 0) {
                 log("  -> Warning: No rows found with selector '" + rowSelector + "'. Stopping.");
                 break;
            }
        }
        
        log("  -> Item '" + textToFind + "' not found after " + maxScrolls + " scrolls.");
        return false;
    }

    // --- Groovy Dynamic Fallback ---

    /**
     * Handles dynamic method calls from Groovy that are not explicitly defined.
     * Implements a "Smart Dispatch" strategy to find the best matching method 
     * by adapting arguments (truncation, padding, type conversion).
     */
    public Object methodMissing(String name, Object args) {
        Object[] argArray = (args instanceof Object[]) ? (Object[]) args : new Object[]{args};
        
        log("Warning: Method '" + name + "' not found in WebDSL with " + argArray.length + " args. Searching for best match...");

        java.lang.reflect.Method bestMethod = null;
        Object[] bestConvertedArgs = null;
        int bestScore = -1;

        for (java.lang.reflect.Method m : this.getClass().getMethods()) {
            if (!m.getName().equals(name)) continue;

            Class<?>[] paramTypes = m.getParameterTypes();
            int paramCount = paramTypes.length;
            
            // Calculate Match Score
            // Base score: Preference for exact parameter count
            int score = 0;
            if (paramCount == argArray.length) {
                score = 100;
            } else if (paramCount < argArray.length) {
                // Truncation allowed but penalized
                score = 50 - (argArray.length - paramCount) * 10;
            } else {
                // Padding allowed but heavily penalized (risky)
                score = 20 - (paramCount - argArray.length) * 5;
            }
            
            if (score < 0) continue; // Too simplistic mismatch

            Object[] convertedArgs = new Object[paramCount];
            boolean possible = true;

            for (int i = 0; i < paramCount; i++) {
                Object inputArg = (i < argArray.length) ? argArray[i] : null;
                Class<?> targetType = paramTypes[i];
                
                if (inputArg == null) {
                    if (targetType.isPrimitive()) {
                        possible = false; // Cannot pass null to primitive
                        break;
                    }
                    convertedArgs[i] = null;
                    continue;
                }
                
                // Type Conversion Logic
                if (targetType.isAssignableFrom(inputArg.getClass())) {
                    convertedArgs[i] = inputArg;
                    score += 10; // Perfect/Assignable match
                } else if (targetType == String.class) {
                    convertedArgs[i] = inputArg.toString(); // GString or Object to String
                    score += 8;
                } else if (Number.class.isAssignableFrom(targetType) && inputArg instanceof Number) {
                    if (targetType == Long.class || targetType == long.class) {
                        convertedArgs[i] = ((Number) inputArg).longValue();
                    } else if (targetType == Integer.class || targetType == int.class) {
                        convertedArgs[i] = ((Number) inputArg).intValue();
                    } else if (targetType == Double.class || targetType == double.class) {
                        convertedArgs[i] = ((Number) inputArg).doubleValue();
                    } else {
                        convertedArgs[i] = inputArg; 
                    }
                    score += 8;
                } else if ((targetType == boolean.class || targetType == Boolean.class) && inputArg instanceof Boolean) {
                    convertedArgs[i] = inputArg;
                    score += 10;
                } else {
                    // Type mismatch
                    possible = false;
                    break;
                }
            }
            
            if (possible) {
                if (score > bestScore) {
                    bestScore = score;
                    bestMethod = m;
                    bestConvertedArgs = convertedArgs;
                }
            }
        }

        if (bestMethod != null) {
             log("  -> Smart Match found: " + bestMethod.getName() + " (Score: " + bestScore + "). Invoking...");
             try {
                 return bestMethod.invoke(this, bestConvertedArgs);
             } catch (Exception e) {
                 log("  -> Smart Match invocation failed: " + e.getMessage());
                 throw new RuntimeException("Error invoking smart-matched method " + name, e);
             }
        }

        // Strategy 2: Forward to Playwright Context (Page or Frame)
        Object target = (frame != null) ? frame : page;
        log("  -> Forwarding to Playwright " + target.getClass().getSimpleName() + "...");
        
        try {
            // Use Groovy's InvokerHelper to dynamically invoke the method on the target Java object
            // This requires groovy-all on the classpath, which is present in this environment.
            Class<?> invokerClass = Class.forName("org.codehaus.groovy.runtime.InvokerHelper");
            java.lang.reflect.Method invokeMethod = invokerClass.getMethod("invokeMethod", Object.class, String.class, Object.class);
            return invokeMethod.invoke(null, target, name, args);
        } catch (Exception e) {
            String errorMsg = "Method '" + name + "' failed on fallback object: " + e.getMessage();
            log("Error: " + errorMsg);
            throw new RuntimeException(errorMsg, e);
        }
    }
}
