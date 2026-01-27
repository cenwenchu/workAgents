package com.qiyi.autoweb;

import com.microsoft.playwright.Frame;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import java.util.List;
import java.util.function.Consumer;

/**
 * A High-level DSL wrapper for Playwright to simplify LLM-generated scripts
 * and reduce hallucination/errors.
 */
public class WebDSL {

    private final Page page;
    private final Frame frame;
    private final Consumer<String> logger;
    private int defaultTimeout = 30000;
    private int maxRetries = 3;

    public WebDSL(Object context, Consumer<String> logger) {
        if (context instanceof Frame) {
            this.frame = (Frame) context;
            this.page = this.frame.page();
        } else {
            this.page = (Page) context;
            this.frame = null;
        }
        this.logger = logger;
    }

    public WebDSL withDefaultTimeout(int timeoutMs) {
        this.defaultTimeout = timeoutMs;
        return this;
    }
    
    public WebDSL withMaxRetries(int retries) {
        this.maxRetries = Math.max(1, retries);
        return this;
    }

    private Locator locator(String selector) {
        if (frame != null) {
            return frame.locator(selector);
        } else {
            return page.locator(selector);
        }
    }

    // --- Logging ---
    public void log(String message) {
        if (logger != null) {
            logger.accept(message);
        } else {
            System.out.println(message);
        }
    }

    private void retry(Runnable action, String desc) {
        int attempt = 0;
        RuntimeException last = null;
        while (attempt < maxRetries) {
            try {
                action.run();
                return;
            } catch (RuntimeException e) {
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
                last = e;
                attempt++;
                log("Retry " + attempt + "/" + maxRetries + " for " + desc);
                page.waitForTimeout(500);
            }
        }
        if (last != null) throw last;
        return null;
    }

    // --- Basic Interaction ---

    public void click(String selector) {
        log("Action: Click '" + selector + "'");
        retry(() -> {
            waitFor(selector);
            highlight(selector);
            locator(selector).first().click();
        }, "click " + selector);
    }
    
    public void click(String selector, int index) {
        log("Action: Click '" + selector + "' at index " + index);
        retry(() -> {
            Locator loc = locator(selector).nth(index);
            highlight(loc);
            loc.click();
        }, "click " + selector + " at " + index);
    }

    public void type(String selector, String text) {
        log("Action: Type '" + text + "' into '" + selector + "'");
        retry(() -> {
            waitFor(selector);
            highlight(selector);
            locator(selector).first().fill(text);
        }, "type into " + selector);
    }
    
    public void check(String selector) {
        log("Action: Check '" + selector + "'");
        retry(() -> {
            waitFor(selector);
            highlight(selector);
            locator(selector).first().check();
        }, "check " + selector);
    }
    
    public void uncheck(String selector) {
        log("Action: Uncheck '" + selector + "'");
        retry(() -> {
            waitFor(selector);
            highlight(selector);
            locator(selector).first().uncheck();
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
        locator(selector).first().waitFor(new Locator.WaitForOptions().setTimeout(defaultTimeout));
    }
    
    public void wait(int millis) {
        log("Wait: " + millis + "ms");
        page.waitForTimeout(millis);
    }

    // --- Interaction ---
    
    public void hover(String selector) {
        log("Action: Hover over '" + selector + "'");
        highlight(selector);
        locator(selector).first().hover();
    }
    
    public void press(String selector, String key) {
        log("Action: Press key '" + key + "' on '" + selector + "'");
        locator(selector).first().press(key);
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
        highlight(selector);
        locator(selector).first().evaluate("el => { el.scrollTop = 0; el.dispatchEvent(new Event('scroll', { bubbles: true })); }");
    }
    
    private int getScrollTop(String selector) {
        try {
            Object v = locator(selector).first().evaluate("el => el.scrollTop");
            return (v instanceof Number) ? ((Number) v).intValue() : 0;
        } catch (Exception e) {
            return 0;
        }
    }
    
    private int getMaxScrollTop(String selector) {
        try {
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
    
    private boolean isScrollable(String selector) {
        try {
            Object v = locator(selector).first().evaluate("el => (el.scrollHeight - el.clientHeight) > 2");
            if (v instanceof Boolean) return (Boolean) v;
        } catch (Exception ignored) {}
        return false;
    }
    
    private String ensureScrollableContainer(String containerSelector, String rowSelector) {
        if (containerSelector != null && isScrollable(containerSelector)) {
            return containerSelector;
        }
        // Try to detect scrollable ancestor of the first row
        try {
            Locator row = locator(rowSelector).first();
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
        } catch (Exception ignored) {}
        // Fallback to original selector
        return containerSelector;
    }
    
    public void ensureAtTop(String selector) {
        log("Action: Ensure '" + selector + "' at top");
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
        highlight(selector);
        locator(selector).first().evaluate("el => { el.scrollTop += " + amount + "; el.dispatchEvent(new Event('scroll', { bubbles: true })); }");
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
        highlight(locator(selector).first());
    }

    private void highlight(Locator loc) {
        try {
            loc.evaluate("e => { e.style.border = '3px solid red'; e.style.backgroundColor = 'rgba(255,0,0,0.1)'; }");
            // brief pause to make it visible
            page.waitForTimeout(200);
        } catch (Exception e) {
            // Ignore highlight errors (e.g. if element detached)
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
        // Try as selector first if it looks like one, otherwise try as text
        boolean looksLikeSelector = textOrSelector.startsWith(".") || textOrSelector.startsWith("#") || textOrSelector.contains("[");
        
        if (looksLikeSelector && isVisible(textOrSelector)) {
            click(textOrSelector);
            return;
        }
        
        // Try finding by text (button, link, or generic clickable)
        String[] attempts = {
            "button:has-text('" + textOrSelector + "')",
            "a:has-text('" + textOrSelector + "')",
            "role=button[name='" + textOrSelector + "']",
            "text='" + textOrSelector + "'"
        };
        
        for (String sel : attempts) {
            if (locator(sel).first().isVisible()) {
                click(sel);
                return;
            }
        }
        
        // Fallback: just try the input as a selector even if it didn't look like one
        click(textOrSelector);
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
            Locator rows = locator(rowSelector);
            int count = rows.count();
            boolean foundNew = false;
            
            for (int j = 0; j < count; j++) {
                String text = "";
                try { text = rows.nth(j).innerText(); } catch (Exception ignored) {}
                String normalized = normalizeText(text);
                String key = md5(normalized);
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
            // 2) wheel scroll to trigger virtual renderers
            hover(containerSelector);
            page.mouse().wheel(0, wheelStep);
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
    public List<java.util.Map<String, String>> extractTableData(String containerSelector, String rowSelector, int limit, java.util.Map<String, String> columns) {
        log("Action: Extracting table data from '" + containerSelector + "'...");
        
        containerSelector = ensureScrollableContainer(containerSelector, rowSelector);
        
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
            Locator rows = locator(rowSelector);
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
                String rowKey = md5(normalized);
                if (processedKeys.contains(rowKey)) {
                    continue;
                }
                
                // 2. Extract columns
                java.util.Map<String, String> rowData = new java.util.HashMap<>();
                
                for (java.util.Map.Entry<String, String> entry : columns.entrySet()) {
                    String colName = entry.getKey();
                    String colSel = entry.getValue();
                    try {
                        // Relative selector from the row
                        Locator colLoc = row.locator(colSel).first();
                        if (colLoc.count() > 0) {
                            rowData.put(colName, colLoc.innerText());
                        } else {
                            rowData.put(colName, ""); 
                        }
                    } catch (Exception e) {
                        rowData.put(colName, "");
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
            // 2) wheel scroll to trigger virtual renderers
            hover(containerSelector);
            page.mouse().wheel(0, wheelStep);
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
        
        ensureAtTop(containerSelector);
        wait(300);
        
        int maxScrolls = 80;
        int noNewData = 0;
        int scrollStep = 300;
        int wheelStep = 800;
        
        for (int i = 0; i < maxScrolls; i++) {
            Locator rows = locator(rowSelector);
            int count = rows.count();
            boolean foundNew = false;
            
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
                
                String joined = String.join("，", cells);
                String key = md5(joined);
                if (!joined.isEmpty() && !processedKeys.contains(key)) {
                    processedKeys.add(key);
                    results.add(joined);
                    foundNew = true;
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
            scrollBy(containerSelector, scrollStep);
            wait(500);
            hover(containerSelector);
            page.mouse().wheel(0, wheelStep);
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
        log("  -> Extracted " + results.size() + " row texts.");
        return results;
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
            Locator rows = locator(rowSelector);
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
     * 1. Tries to match existing methods by name (ignoring extra arguments if fuzzy match works).
     * 2. Tries to forward the call to the underlying Playwright Page/Frame object.
     */
    public Object methodMissing(String name, Object args) {
        Object[] argArray = (args instanceof Object[]) ? (Object[]) args : new Object[]{args};
        
        log("Warning: Method '" + name + "' not found in WebDSL with " + argArray.length + " args. Attempting fallback...");

        // Strategy 1: Fuzzy Match (Find method with same name but fewer arguments)
        // e.g. getAllText(selector, index) -> getAllText(selector)
        try {
            for (java.lang.reflect.Method m : this.getClass().getMethods()) {
                if (m.getName().equals(name)) {
                    int paramCount = m.getParameterCount();
                    if (paramCount < argArray.length && paramCount > 0) {
                        // Check if the first N arguments match types (roughly)
                        // For simplicity, just try to invoke it with the first N args
                        Object[] truncatedArgs = new Object[paramCount];
                        System.arraycopy(argArray, 0, truncatedArgs, 0, paramCount);
                        
                        log("  -> Fuzzy match found: " + name + " with " + paramCount + " args. Invoking...");
                        return m.invoke(this, truncatedArgs);
                    }
                }
            }
        } catch (Exception e) {
            log("  -> Fuzzy match invocation failed: " + e.getMessage());
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
