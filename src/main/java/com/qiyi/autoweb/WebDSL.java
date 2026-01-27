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

    // --- Basic Interaction ---

    public void click(String selector) {
        log("Action: Click '" + selector + "'");
        highlight(selector);
        locator(selector).first().click();
    }
    
    public void click(String selector, int index) {
        log("Action: Click '" + selector + "' at index " + index);
        Locator loc = locator(selector).nth(index);
        highlight(loc);
        loc.click();
    }

    public void type(String selector, String text) {
        log("Action: Type '" + text + "' into '" + selector + "'");
        highlight(selector);
        locator(selector).first().fill(text);
    }
    
    public void check(String selector) {
        log("Action: Check '" + selector + "'");
        highlight(selector);
        locator(selector).first().check();
    }
    
    public void uncheck(String selector) {
        log("Action: Uncheck '" + selector + "'");
        highlight(selector);
        locator(selector).first().uncheck();
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
        return locator(selector).first().innerText();
    }
    
    public String getText(String selector, int index) {
        return locator(selector).nth(index).innerText();
    }
    
    public List<String> getAllText(String selector) {
        return locator(selector).allInnerTexts();
    }

    public List<String> getAllText(String selector, int index) {
        return locator(selector).nth(index).allInnerTexts();
    }

    // --- Waiting ---

    public void waitFor(String selector) {
        log("Wait: For element '" + selector + "'");
        // Use a reasonable default timeout (e.g., 30s)
        locator(selector).first().waitFor(new Locator.WaitForOptions().setTimeout(30000));
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
