package com.qiyi.autoweb;

import com.microsoft.playwright.Page;
import com.qiyi.util.LLMUtil;
import com.qiyi.util.PlayWrightUtil;

public class AutoWebAgent {

    public static void main(String[] args) {
        if (args.length < 2) {
            // Default example if no args provided
            String url = "https://sc.scm121.com/tradeManage/tower/distribute";
            String userPrompt = "执行查询待发货的订单，选中其中第一条，点击审核推单";
            System.out.println("No arguments provided. Running default example:");
            System.out.println("URL: " + url);
            System.out.println("Prompt: " + userPrompt);
            run(url, userPrompt);
        } else {
            run(args[0], args[1]);
        }
    }

    public static void run(String url, String userPrompt) {
        PlayWrightUtil.Connection connection = PlayWrightUtil.connectAndAutomate();
        if (connection == null) {
            System.err.println("Failed to connect to browser.");
            return;
        }

        try {
            Page page = null;
            // Try to find if the page is already open
            for (com.microsoft.playwright.BrowserContext context : connection.browser.contexts()) {
                for (Page p : context.pages()) {
                    if (p.url().startsWith(url)) {
                        page = p;
                        break;
                    }
                }
                if (page != null) break;
            }

            if (page == null) {
                System.out.println("Page not found, creating new page and navigating...");
                // 优先使用现有的上下文（即用户配置目录的上下文），以保留登录态
                if (!connection.browser.contexts().isEmpty()) {
                    page = connection.browser.contexts().get(0).newPage();
                } else {
                    page = connection.browser.newPage();
                }
                page.navigate(url);
            } else {
                System.out.println("Found existing page: " + page.title());
                page.bringToFront();
            }

            // Check if we are on the target page, if not wait (e.g. for login)
            long maxWaitTime = 120000; // 120 seconds
            long interval = 2000; // 2 seconds
            long startTime = System.currentTimeMillis();

            while (!page.url().startsWith(url)) {
                if (System.currentTimeMillis() - startTime > maxWaitTime) {
                    throw new RuntimeException("Timeout waiting for target URL. Current URL: " + page.url());
                }
                System.out.println("Current URL: " + page.url() + ". Waiting for target URL: " + url + " (Login might be required)...");
                page.waitForTimeout(interval);
            }

            try {
                page.waitForLoadState();
            } catch (Exception e) {
                System.out.println("Wait for load state timed out or failed, continuing...");
            }
            
            // Wait extra time for dynamic content (React/Vue rendering)
            System.out.println("Waiting 5 seconds for dynamic content to render...");
            page.waitForTimeout(5000);

            // Check for iframes
            com.microsoft.playwright.Frame contentFrame = null;
            String frameName = "";
            double maxArea = 0;
            
            System.out.println("Checking frames...");
            for (com.microsoft.playwright.Frame f : page.frames()) {
                // Skip the main frame itself to find nested content frames
                if (f == page.mainFrame()) continue;
                
                try {
                    com.microsoft.playwright.ElementHandle element = f.frameElement();
                    if (element != null) {
                        com.microsoft.playwright.options.BoundingBox box = element.boundingBox();
                        if (box != null) {
                            double area = box.width * box.height;
                            System.out.println(" - Frame: " + f.name() + " | URL: " + f.url() + " | Area: " + area);
                            
                            // Check if visible (width and height > 0)
                            if (box.width > 0 && box.height > 0) {
                                // Select the largest visible frame
                                if (area > maxArea) {
                                    maxArea = area;
                                    contentFrame = f;
                                    frameName = f.name();
                                }
                            }
                        }
                    }
                } catch (Exception e) {
                    System.out.println(" - Error checking frame " + f.name() + ": " + e.getMessage());
                }
            }

            if (contentFrame != null) {
                System.out.println("   -> Identified largest frame as content frame: " + frameName + " (Area: " + maxArea + ")");
            } else {
                System.out.println("   -> No significant child frame found.");
            }

            String html;
            boolean isFrame = false;
            
            if (contentFrame != null) {
                System.out.println("Using content from frame: " + frameName);
                try {
                    // Ensure frame is loaded
                    contentFrame.waitForLoadState(com.microsoft.playwright.options.LoadState.DOMCONTENTLOADED);
                } catch (Exception e) {
                    System.out.println("Frame load state wait failed: " + e.getMessage());
                }
                html = contentFrame.content();
                isFrame = true;
            } else {
                System.out.println("Using main page content.");
                html = page.content();
            }

            System.out.println("HTML before clean Size: " + html.length());

            String cleanedHtml = HTMLCleaner.clean(html);

            System.out.println("HTML cleaned. Size: " + cleanedHtml.length());
            
            // Limit HTML size if too large for LLM
            if (cleanedHtml.length() > 100000) {
                cleanedHtml = cleanedHtml.substring(0, 100000) + "...(truncated)";
            }
            
            System.out.println("HTML finally cleaned. Size: " + cleanedHtml.length());

            // Save HTMLs for debugging (fixed filenames to avoid accumulation)
            // Use project directory for debug output
            java.nio.file.Path debugDir = java.nio.file.Paths.get(System.getProperty("user.dir"), "autoweb_debug");
            try {
                if (!java.nio.file.Files.exists(debugDir)) {
                    java.nio.file.Files.createDirectories(debugDir);
                }
                
                java.nio.file.Path rawPath = debugDir.resolve("debug_raw.html");
                java.nio.file.Path cleanedPath = debugDir.resolve("debug_cleaned.html");
                
                java.nio.file.Files.write(rawPath, html.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                java.nio.file.Files.write(cleanedPath, cleanedHtml.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                
                System.out.println("Debug HTML saved to:");
                System.out.println(" - Raw: " + rawPath.toAbsolutePath());
                System.out.println(" - Cleaned: " + cleanedPath.toAbsolutePath());
            } catch (Exception ex) {
                System.err.println("Failed to save debug HTML files: " + ex.getMessage());
            }

            String prompt = String.format(
                "You are a Playwright automation expert.\n" +
                "Task: %s\n" +
                "HTML Content (simplified): \n%s\n\n" +
                "Requirement:\n" +
                "1. Write a Groovy script to perform the task based on the HTML structure.\n" +
                "2. You have direct access to a variable named 'page' of type 'com.microsoft.playwright.Page'. DO NOT declare it.\n" +
                "3. Use robust selectors (prefer text content or specific attributes over complex XPaths if possible). \n" +
                "   - Look for buttons or links that match the user's intent (e.g., '查询', '审核', '推单').\n" +
                "   - If 'checking the first item' is requested, look for checkboxes in tables.\n" +
                "4. Handle potential exceptions or allow them to bubble up.\n" +
                "5. Add simple System.out.println logs to indicate progress steps.\n" +
                "6. Output ONLY the Groovy code. No markdown code blocks, no explanations. Do not include ```groovy or ```.",
                userPrompt, cleanedHtml
            );

            System.out.println("Generating code with LLM...");
            String code = LLMUtil.chatWithDeepSeek(prompt);
            
            // Clean up code block markers if present (just in case)
            code = code.replaceAll("```groovy", "").replaceAll("```java", "").replaceAll("```", "").trim();
            
            System.out.println("Generated Code:\n" + code);

            // Save Generated Code for debugging
            try {
                java.nio.file.Path codePath = debugDir.resolve("debug_code.groovy");
                java.nio.file.Files.write(codePath, code.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                System.out.println("Debug Code saved to: " + codePath.toAbsolutePath());
            } catch (Exception ex) {
                System.err.println("Failed to save debug code file: " + ex.getMessage());
            }
            
            // Execute with GroovyShell
            executeWithGroovy(code, page);

        } catch (Exception e) {
            e.printStackTrace();
            // 异常时退出
            System.exit(1);
        } finally {
            // 关闭 Playwright 连接，释放资源，使程序能够退出
            if (connection != null && connection.playwright != null) {
                connection.playwright.close();
            }
        }
    }
    
    private static void executeWithGroovy(String scriptCode, Page page) throws Exception {
        try {
            groovy.lang.Binding binding = new groovy.lang.Binding();
            binding.setVariable("page", page);
            groovy.lang.GroovyShell shell = new groovy.lang.GroovyShell(binding);
            shell.evaluate(scriptCode);
            System.out.println("Groovy script executed successfully.");
        } catch (Exception e) {
            System.err.println("Groovy execution failed: " + e.getMessage());
            // 抛出异常以便主程序捕获并退出
            throw e;
        }
    }
}
