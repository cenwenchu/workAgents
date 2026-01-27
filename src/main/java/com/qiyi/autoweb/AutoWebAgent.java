package com.qiyi.autoweb;

import com.microsoft.playwright.Page;
import com.qiyi.util.LLMUtil;
import com.qiyi.util.PlayWrightUtil;
import javax.swing.*;
import java.awt.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.io.IOException;

public class AutoWebAgent {

    private static String GROOVY_SCRIPT_PROMPT_TEMPLATE = "";
    private static String REFINED_GROOVY_SCRIPT_PROMPT_TEMPLATE = "";

    public static void main(String[] args) {
        loadPrompts();
        if (args.length < 2) {
            // Default example if no args provided
            String url = "https://sc.scm121.com/tradeManage/tower/distribute";
            String userPrompt = "查询待发货的订单，然后会得到的结果表带有表头'序号','订单号','商品信息'等字段，选中其中的第一条结果，"
                            +"并且把第一条的记录所有字段的值都提取出来输出，然后再查看一下当前的结果有多少记录，输出记录数";
            System.out.println("No arguments provided. Running default example:");
            System.out.println("URL: " + url);
            System.out.println("Prompt: " + userPrompt);
            run(url, userPrompt);
        } else {
            run(args[0], args[1]);
        }
    }

    private static void loadPrompts() {
        try {
            // Use user.dir to find the skills directory
            Path skillsDir = Paths.get(System.getProperty("user.dir"), "autoweb", "skills");
            
            Path groovyPromptPath = skillsDir.resolve("groovy_script_prompt.txt");
            if (Files.exists(groovyPromptPath)) {
                GROOVY_SCRIPT_PROMPT_TEMPLATE = new String(Files.readAllBytes(groovyPromptPath), java.nio.charset.StandardCharsets.UTF_8);
                System.out.println("Loaded groovy_script_prompt.txt");
            } else {
                System.err.println("Warning: groovy_script_prompt.txt not found at " + groovyPromptPath.toAbsolutePath());
            }

            Path refinedPromptPath = skillsDir.resolve("refined_groovy_script_prompt.txt");
            if (Files.exists(refinedPromptPath)) {
                REFINED_GROOVY_SCRIPT_PROMPT_TEMPLATE = new String(Files.readAllBytes(refinedPromptPath), java.nio.charset.StandardCharsets.UTF_8);
                System.out.println("Loaded refined_groovy_script_prompt.txt");
            } else {
                System.err.println("Warning: refined_groovy_script_prompt.txt not found at " + refinedPromptPath.toAbsolutePath());
            }
            
        } catch (IOException e) {
            System.err.println("Error loading prompts: " + e.getMessage());
        }
    }

    public static void run(String url, String userPrompt) {
        loadPrompts();
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
                page.waitForLoadState(com.microsoft.playwright.options.LoadState.NETWORKIDLE, new com.microsoft.playwright.Page.WaitForLoadStateOptions().setTimeout(10000));
            } catch (Exception e) {
                System.out.println("Wait for NETWORKIDLE timed out or failed, continuing...");
            }
            
            // Wait extra time for dynamic content (React/Vue rendering)
            System.out.println("Waiting 5 seconds for dynamic content to render...");
            page.waitForTimeout(5000);

            // Check for iframes with retry logic
            com.microsoft.playwright.Frame contentFrame = null;
            String frameName = "";
            double maxArea = 0;
            
            System.out.println("Checking frames (scanning up to 10 seconds)...");
            for (int i = 0; i < 5; i++) {
                maxArea = 0;
                contentFrame = null;
                for (com.microsoft.playwright.Frame f : page.frames()) {
                    // Skip the main frame itself to find nested content frames
                    if (f == page.mainFrame()) continue;
                    
                    try {
                        com.microsoft.playwright.ElementHandle element = f.frameElement();
                        if (element != null) {
                            com.microsoft.playwright.options.BoundingBox box = element.boundingBox();
                            if (box != null) {
                                double area = box.width * box.height;
                                System.out.println(" - [" + i + "] Frame: " + f.name() + " | URL: " + f.url() + " | Area: " + area);
                                
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
                    break;
                }
                
                System.out.println("   -> No significant child frame found yet. Waiting 2s...");
                page.waitForTimeout(2000);
            }

            String html = "";
            boolean isFrame = false;
            
            if (contentFrame != null) {
                System.out.println("Using content from frame: " + frameName);
                try {
                    // Ensure frame is loaded
                    contentFrame.waitForLoadState(com.microsoft.playwright.options.LoadState.DOMCONTENTLOADED);
                    contentFrame.waitForLoadState(com.microsoft.playwright.options.LoadState.NETWORKIDLE, new com.microsoft.playwright.Frame.WaitForLoadStateOptions().setTimeout(5000));
                } catch (Exception e) {
                    System.out.println("Frame load state wait failed: " + e.getMessage());
                }
                html = contentFrame.content();
                isFrame = true;
            } else {
                System.out.println("Using main page content.");
                html = page.content();
            }

            // Retry logic for empty content
            int retries = 0;
            while (html.length() < 1000 && retries < 3) {
                 System.out.println("Content seems empty (" + html.length() + " chars). Waiting and retrying... (" + (retries + 1) + "/3)");
                 page.waitForTimeout(3000);
                 if (contentFrame != null) {
                     html = contentFrame.content();
                 } else {
                     html = page.content();
                 }
                 retries++;
            }

            System.out.println("HTML before clean Size: " + html.length());

            String cleanedHtml = HTMLCleaner.clean(html);

            System.out.println("HTML cleaned. Size: " + cleanedHtml.length());
            
            // Limit HTML size if too large for LLM
            if (cleanedHtml.length() > 500000) {
                cleanedHtml = cleanedHtml.substring(0, 500000) + "...(truncated)";
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

            // Launch UI
            System.out.println("Launching Control UI...");
            String finalCleanedHtml = cleanedHtml;
            // Use contentFrame if available, otherwise use page
            Object executionContext = (contentFrame != null) ? contentFrame : page;
            SwingUtilities.invokeLater(() -> createGUI(executionContext, finalCleanedHtml, userPrompt, connection));

        } catch (Exception e) {
            e.printStackTrace();
            System.err.println("Error during initialization: " + e.getMessage());
            if (connection != null && connection.playwright != null) {
                connection.playwright.close();
            }
            System.exit(1);
        }
    }
    
    // A simple wrapper to hold the current execution context (Page or Frame)
    static class ContextWrapper {
        Object context;
        String name;
        @Override
        public String toString() {
            return name;
        }
    }

    static class ScanResult {
        java.util.List<ContextWrapper> wrappers = new java.util.ArrayList<>();
        ContextWrapper best;
    }

    private static ScanResult scanContexts(Page page) {
        ScanResult result = new ScanResult();
        
        // Main Page
        ContextWrapper mainPageWrapper = new ContextWrapper();
        mainPageWrapper.context = page;
        mainPageWrapper.name = "Main Page";
        result.wrappers.add(mainPageWrapper);
        result.best = mainPageWrapper; // Default

        // Check frames
        double maxArea = 0;
        
        System.out.println("Scanning frames...");
        ContextWrapper firstFrame = null;
        for (com.microsoft.playwright.Frame f : page.frames()) {
            // Skip the main frame itself to find nested content frames
            if (f == page.mainFrame()) continue;
            
            try {
                ContextWrapper fw = new ContextWrapper();
                fw.context = f;
                // Use a descriptive name
                String fName = f.name();
                if (fName == null || fName.isEmpty()) fName = "anonymous";
                fw.name = "Frame: " + fName + " (" + f.url() + ")";
                
                result.wrappers.add(fw);
                if (firstFrame == null) firstFrame = fw;

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

                // Select the largest visible frame
                if (isVisible && area > maxArea) {
                    maxArea = area;
                    result.best = fw;
                }
            } catch (Exception e) {
                System.out.println(" - Error checking frame " + f.name() + ": " + e.getMessage());
            }
        }
        
        // Fallback: If no "visible" frame found but we have frames, use the first one
        // This handles cases where boundingBox might be reported incorrectly or lazily
        if (result.best == mainPageWrapper && firstFrame != null) {
             System.out.println(" - No definitely visible frame found. Fallback to first found frame: " + firstFrame.name);
             result.best = firstFrame;
        }
        
        System.out.println("Scan complete. Best candidate: " + result.best.name);
        return result;
    }

    // Helper method to reload page and find context (Shared by Get Code and Refine Code)
    private static ContextWrapper reloadAndFindContext(
            Page rootPage, 
            ContextWrapper selectedContext, 
            java.util.function.Consumer<String> uiLogger,
            JComboBox<ContextWrapper> contextCombo
    ) {
        // 0. Reload Page to clean state (Always reload the main page to ensure clean state)
        uiLogger.accept("Reloading page to ensure clean state...");
        // Just reload the root page always, it's safer and "simpler"
        try {
            rootPage.reload(); 
            // Use NETWORKIDLE to ensure most resources are loaded
            rootPage.waitForLoadState(com.microsoft.playwright.options.LoadState.NETWORKIDLE);
        } catch (Exception reloadEx) {
             uiLogger.accept("Warning during reload: " + reloadEx.getMessage());
        }
        
        // Wait a bit for dynamic content after reload
        try { Thread.sleep(5000); } catch (InterruptedException ie) {}

        // 0.5 Re-scan to find the fresh context (Simulate "Opening new page")
        uiLogger.accept("Scanning for frames after reload...");
        ScanResult res = scanContexts(rootPage);
        
        // Retry scanning if only Main Page is found or best is Main Page, up to 30 times (30 seconds)
        // This is crucial because frames might load slower than the main page DOM
        int scanRetries = 0;
        String targetFrameName = (selectedContext != null && selectedContext.name != null && selectedContext.name.startsWith("Frame:")) ? selectedContext.name : null;
        
        while (scanRetries < 30) {
            // Success condition 1: We found a valid best context that is NOT Main Page
            if (res.best != null && !"Main Page".equals(res.best.name)) {
                 // If we were looking for a specific frame, check if we found it (loose match)
                 if (targetFrameName != null) {
                     boolean foundTarget = false;
                     for (ContextWrapper cw : res.wrappers) {
                         if (cw.name.equals(targetFrameName)) {
                             res.best = cw; // Force select the same frame
                             foundTarget = true;
                             break;
                         }
                     }
                     if (foundTarget) break; // Found our specific frame!
                 } else {
                     break; // Found some frame, good enough
                 }
            }
            
            try { Thread.sleep(1000); } catch (InterruptedException ie) {}
            // Only log every 5 retries to avoid spamming
            if (scanRetries % 5 == 0) {
                uiLogger.accept("Retrying frame scan (" + (scanRetries + 1) + "/30)...");
            }
            res = scanContexts(rootPage);
            scanRetries++;
        }
        
        // Update UI with new contexts
        ScanResult finalRes = res;
        SwingUtilities.invokeLater(() -> {
            contextCombo.removeAllItems();
            for (ContextWrapper w : finalRes.wrappers) {
                contextCombo.addItem(w);
            }
            if (finalRes.best != null) {
                contextCombo.setSelectedItem(finalRes.best);
            }
        });
        
        // Use the new best context for code generation
        ContextWrapper workingContext;
        if (res.best != null) {
            workingContext = res.best;
            uiLogger.accept("已自动选中最佳上下文: " + workingContext.name);
        } else {
            // Fallback to main page if something weird happens
            workingContext = new ContextWrapper();
            workingContext.context = rootPage;
            workingContext.name = "主页面";
            uiLogger.accept("未能找到合适的上下文，回退使用主页面。");
        }
        
        return workingContext;
    }

    private static String getPageContent(Object pageOrFrame) {
        if (pageOrFrame instanceof Page) {
            return ((Page) pageOrFrame).content();
        } else if (pageOrFrame instanceof com.microsoft.playwright.Frame) {
            return ((com.microsoft.playwright.Frame) pageOrFrame).content();
        }
        return "";
    }

    private static void createGUI(Object initialContext, String initialCleanedHtml, String defaultPrompt, PlayWrightUtil.Connection connection) {
        // We need the root Page object to re-scan frames later.
        Page rootPage;
        if (initialContext instanceof com.microsoft.playwright.Frame) {
            rootPage = ((com.microsoft.playwright.Frame) initialContext).page();
        } else {
            rootPage = (Page) initialContext;
        }

        // State tracking for execution
        java.util.concurrent.atomic.AtomicBoolean hasExecuted = new java.util.concurrent.atomic.AtomicBoolean(false);

        JFrame frame = new JFrame("AutoWeb 网页自动化控制台");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(600, 950);
        frame.setLayout(new BorderLayout());

        // Close Playwright on exit
        frame.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent windowEvent) {
                if (connection != null && connection.playwright != null) {
                    connection.playwright.close();
                    System.out.println("Playwright 连接已关闭。");
                }
            }
        });

        // --- Top Area: Settings + Prompt ---
        JPanel topContainer = new JPanel(new BorderLayout());

        // 1. Settings Panel (Context Selector)
        JPanel settingsPanel = new JPanel(new BorderLayout());
        settingsPanel.setBorder(BorderFactory.createTitledBorder("上下文选择"));
        
        JPanel leftSettings = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        JLabel lblContext = new JLabel("目标上下文:");
        JComboBox<ContextWrapper> contextCombo = new JComboBox<>();
        contextCombo.setPreferredSize(new Dimension(250, 25));
        leftSettings.add(lblContext);
        leftSettings.add(contextCombo);
        
        JPanel rightSettings = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0));
        JButton btnRefreshContext = new JButton("刷新 / 扫描");
        JButton btnReloadPrompts = new JButton("重载提示规则");
        rightSettings.add(btnRefreshContext);
        rightSettings.add(btnReloadPrompts);
        
        settingsPanel.add(leftSettings, BorderLayout.WEST);
        settingsPanel.add(rightSettings, BorderLayout.EAST);
        
        topContainer.add(settingsPanel, BorderLayout.NORTH);

        // 2. Prompt Panel
        JPanel promptPanel = new JPanel(new BorderLayout());
        promptPanel.setBorder(BorderFactory.createTitledBorder("用户命令"));
        JTextArea promptArea = new JTextArea(defaultPrompt);
        promptArea.setLineWrap(true);
        promptArea.setWrapStyleWord(true);
        promptArea.setRows(4);
        JScrollPane promptScroll = new JScrollPane(promptArea);
        promptPanel.add(promptScroll, BorderLayout.CENTER);

        JPanel refinePanel = new JPanel(new BorderLayout());
        refinePanel.setBorder(BorderFactory.createTitledBorder("Refine 修正提示"));
        JTextArea refineArea = new JTextArea();
        refineArea.setLineWrap(true);
        refineArea.setWrapStyleWord(true);
        refineArea.setRows(3);
        JScrollPane refineScroll = new JScrollPane(refineArea);
        refinePanel.add(refineScroll, BorderLayout.CENTER);

        JPanel promptContainer = new JPanel(new BorderLayout());
        promptContainer.add(promptPanel, BorderLayout.NORTH);
        promptContainer.add(refinePanel, BorderLayout.CENTER);
        
        topContainer.add(promptContainer, BorderLayout.CENTER);


        // --- Middle Area: Groovy Code ---
        JPanel codePanel = new JPanel(new BorderLayout());
        codePanel.setBorder(BorderFactory.createTitledBorder("Groovy 代码"));
        JTextArea codeArea = new JTextArea();
        codeArea.setFont(new Font("Monospaced", Font.PLAIN, 12));
        JScrollPane codeScroll = new JScrollPane(codeArea);
        codePanel.add(codeScroll, BorderLayout.CENTER);


        // --- Bottom Area: Output Log ---
        JPanel outputPanel = new JPanel(new BorderLayout());
        outputPanel.setBorder(BorderFactory.createTitledBorder("执行日志"));
        JTextArea outputArea = new JTextArea();
        outputArea.setFont(new Font("Monospaced", Font.PLAIN, 12));
        outputArea.setEditable(false);
        JScrollPane outputScroll = new JScrollPane(outputArea);
        outputPanel.add(outputScroll, BorderLayout.CENTER);


        // --- Split Panes ---
        // Code vs Output
        JSplitPane bottomSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, codePanel, outputPanel);
        bottomSplit.setResizeWeight(0.7);

        // Top (Settings+Prompt) vs Bottom (Code+Output)
        JSplitPane mainSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, topContainer, bottomSplit);
        mainSplit.setResizeWeight(0.25);
        
        frame.add(mainSplit, BorderLayout.CENTER);


        // --- Buttons ---
        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton btnGetCode = new JButton("生成代码");
        JButton btnRefine = new JButton("修正代码");
        JButton btnExecute = new JButton("执行代码");
        btnExecute.setEnabled(false);
        
        buttonPanel.add(btnGetCode);
        buttonPanel.add(btnRefine);
        buttonPanel.add(btnExecute);
        frame.add(buttonPanel, BorderLayout.SOUTH);


        // --- Helper: UI Logger ---
        java.util.function.Consumer<String> uiLogger = (msg) -> {
             SwingUtilities.invokeLater(() -> {
                 outputArea.append(msg + "\n");
                 outputArea.setCaretPosition(outputArea.getDocument().getLength());
             });
             System.out.println(msg);
        };
        
        // --- Logic: Refresh Contexts ---
        Runnable refreshContextAction = () -> {
            uiLogger.accept("正在扫描可用的页面和 iframe...");
            ScanResult res = scanContexts(rootPage);
            
            SwingUtilities.invokeLater(() -> {
                contextCombo.removeAllItems();
                for (ContextWrapper w : res.wrappers) {
                    contextCombo.addItem(w);
                }
                if (res.best != null) {
                    contextCombo.setSelectedItem(res.best);
                }
                uiLogger.accept("上下文列表已更新，自动选择: " + (res.best != null ? res.best.name : "无"));
            });
        };

        btnRefreshContext.addActionListener(e -> {
            new Thread(refreshContextAction).start();
        });

        btnReloadPrompts.addActionListener(e -> {
            loadPrompts();
            JOptionPane.showMessageDialog(frame, "提示规则已重新载入！", "成功", JOptionPane.INFORMATION_MESSAGE);
        });


        // --- Logic: Get Code ---
        btnGetCode.addActionListener(e -> {
            String currentPrompt = promptArea.getText();
            ContextWrapper selectedContext = (ContextWrapper) contextCombo.getSelectedItem();
            
            if (currentPrompt == null || currentPrompt.trim().isEmpty()) {
                JOptionPane.showMessageDialog(frame, "请先在用户命令输入框中填写要执行的指令。", "提示", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            if (selectedContext == null) {
                JOptionPane.showMessageDialog(frame, "请先选择一个目标上下文（Frame/Page）。", "错误", JOptionPane.ERROR_MESSAGE);
                return;
            }

            btnGetCode.setEnabled(false);
            btnRefine.setEnabled(false);
            btnExecute.setEnabled(false);
            outputArea.setText(""); // Clear output before new operation
            // Reset execution state since we are starting a new generation cycle
            hasExecuted.set(false);
            
            uiLogger.accept("=== 开始生成代码 ===");
            uiLogger.accept("目标上下文: " + selectedContext.name);
            codeArea.setText("// 正在为上下文生成代码: " + selectedContext.name + "...\n// 请稍候...");
            
            new Thread(() -> {
                try {
                    // Use helper to reload and find context
                    ContextWrapper workingContext = reloadAndFindContext(rootPage, selectedContext, uiLogger, contextCombo);

                    // 1. Get Content from workingContext
                    String freshHtml = "";
                    int retries = 0;
                    while (retries < 10) { // Increased retries to 10
                        try {
                            freshHtml = getPageContent(workingContext.context);
                        } catch (Exception contentEx) {
                             // Retry silently unless debug is needed
                             try { Thread.sleep(3000); } catch (InterruptedException ie) {} // Wait longer (3s)
                             retries++;
                             continue;
                        }
                        
                        // Check for loading spinners or empty content
                        if (freshHtml.contains("ant-spin-spinning") || freshHtml.length() < 1000) {
                             // Retry silently
                             try { Thread.sleep(3000); } catch (InterruptedException ie) {} // Wait longer (3s)
                             retries++;
                        } else {
                            break;
                        }
                    }
                    
                    if (freshHtml.isEmpty()) {
                         uiLogger.accept("错误：重新加载后未能成功获取页面内容，请检查页面是否正常加载。");
                         SwingUtilities.invokeLater(() -> {
                            codeArea.setText("// 错误：未能成功获取页面内容，请稍后重试。");
                            btnGetCode.setEnabled(true);
                            btnRefine.setEnabled(true);
                        });
                         return; // Exit thread
                    }


                    String freshCleanedHtml = HTMLCleaner.clean(freshHtml);
                    
                    if (freshCleanedHtml.length() > 100000) {
                        freshCleanedHtml = freshCleanedHtml.substring(0, 100000) + "...(truncated)";
                    }
                    uiLogger.accept("已获取页面内容，清理后大小: " + freshCleanedHtml.length());
                    
                    // 2. Generate Code
                    String code = generateGroovyScript(currentPrompt, freshCleanedHtml);
                    SwingUtilities.invokeLater(() -> {
                        codeArea.setText(code);
                        btnGetCode.setEnabled(true);
                        btnRefine.setEnabled(true);
                        btnExecute.setEnabled(true);
                    });
                    uiLogger.accept("代码生成完成。");
                    
                } catch (Exception ex) {
                     SwingUtilities.invokeLater(() -> {
                        codeArea.setText("// 错误：" + ex.getMessage());
                        btnGetCode.setEnabled(true);
                        btnRefine.setEnabled(true);
                    });
                     uiLogger.accept("发生异常：" + ex.getMessage());
                }
            }).start();
        });


        // --- Logic: Refine Code ---
        btnRefine.addActionListener(e -> {
            String currentPrompt = promptArea.getText();
            String refineHint = refineArea.getText();
            String previousCode = codeArea.getText();
            String execOutput = outputArea.getText();

            if (previousCode == null || previousCode.trim().isEmpty()) {
                JOptionPane.showMessageDialog(frame, "当前没有可用于修正的代码。", "提示", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            if (refineHint == null || refineHint.trim().isEmpty()) {
                JOptionPane.showMessageDialog(frame, "请先在 Refine 提示框中填写修正说明。", "提示", JOptionPane.INFORMATION_MESSAGE);
                return;
            }

            btnGetCode.setEnabled(false);
            btnRefine.setEnabled(false);
            btnExecute.setEnabled(false);
            outputArea.setText(""); // Clear output before new operation (execOutput already captured)
            uiLogger.accept("=== 正在根据执行结果和提示修正代码 ===");

            // Capture selected context for refine too, to guide post-reload search
            ContextWrapper selectedContext = (ContextWrapper) contextCombo.getSelectedItem();
            
            new Thread(() -> {
                try {
                    // Use helper to reload and find context (This restores the page state!)
                    ContextWrapper workingContext = reloadAndFindContext(rootPage, selectedContext, uiLogger, contextCombo);
                    
                    String freshHtml = "";
                    try {
                        freshHtml = getPageContent(workingContext.context);
                    } catch (Exception contentEx) {
                        uiLogger.accept("Failed to get page content for refine: " + contentEx.getMessage());
                    }

                    String freshCleanedHtml = HTMLCleaner.clean(freshHtml);
                    if (freshCleanedHtml.length() > 100000) {
                        freshCleanedHtml = freshCleanedHtml.substring(0, 100000) + "...(truncated)";
                    }

                    String refinedCode = generateRefinedGroovyScript(
                        currentPrompt,
                        freshCleanedHtml,
                        previousCode,
                        execOutput,
                        refineHint
                    );

                    String finalRefinedCode = refinedCode;
                    SwingUtilities.invokeLater(() -> {
                        codeArea.setText(finalRefinedCode);
                        btnGetCode.setEnabled(true);
                        btnRefine.setEnabled(true);
                        btnExecute.setEnabled(true);
                    });
                    uiLogger.accept("Refine 代码生成完成。");
                } catch (Exception ex) {
                    SwingUtilities.invokeLater(() -> {
                        btnGetCode.setEnabled(true);
                        btnRefine.setEnabled(true);
                    });
                    uiLogger.accept("Refine 失败: " + ex.getMessage());
                }
            }).start();
        });


        // --- Logic: Execute Code ---
        btnExecute.addActionListener(e -> {
            String code = codeArea.getText();
            ContextWrapper selectedContext = (ContextWrapper) contextCombo.getSelectedItem();

            if (code == null || code.trim().isEmpty()) {
                JOptionPane.showMessageDialog(frame, "当前没有可执行的 Groovy 代码。", "提示", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            if (selectedContext == null) {
                JOptionPane.showMessageDialog(frame, "请先选择一个目标上下文（Frame/Page）。", "错误", JOptionPane.ERROR_MESSAGE);
                return;
            }

            btnGetCode.setEnabled(false);
            btnRefine.setEnabled(false);
            btnExecute.setEnabled(false);
            // Clear output area before execution
            outputArea.setText(""); 
            uiLogger.accept("=== 开始执行代码 ===");
            
            new Thread(() -> {
                try {
                    Object executionTarget = selectedContext.context;
                    
                    // Check if already executed once, if so, reload page to restore state
                    if (hasExecuted.get()) {
                         uiLogger.accept("检测到代码已执行过，正在重置页面状态以确保环境纯净...");
                         // Reload and find context again
                         ContextWrapper freshContext = reloadAndFindContext(rootPage, selectedContext, uiLogger, contextCombo);
                         executionTarget = freshContext.context;
                         uiLogger.accept("页面状态已重置，使用新上下文: " + freshContext.name);
                    } else {
                        uiLogger.accept("首次执行，使用当前上下文: " + selectedContext.name);
                    }
                    
                    executeWithGroovy(code, executionTarget, uiLogger);
                    
                    // Mark as executed
                    hasExecuted.set(true);
                    
                    SwingUtilities.invokeLater(() -> {
                         btnGetCode.setEnabled(true);
                         btnRefine.setEnabled(true);
                         btnExecute.setEnabled(true);
                    });
                    uiLogger.accept("=== 代码执行完成 ===");
                } catch (Exception ex) {
                    SwingUtilities.invokeLater(() -> {
                        btnGetCode.setEnabled(true);
                        btnRefine.setEnabled(true);
                        btnExecute.setEnabled(true);
                    });
                    uiLogger.accept("=== 代码执行失败: " + ex.getMessage() + " ===");
                }
            }).start();
        });

        // Initialize frame size/location
        Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
        int width = 800;
        int height = screenSize.height - 50;
        frame.setSize(width, height);
        frame.setLocation(screenSize.width - width, 0);
        frame.setVisible(true);
        
        // Trigger initial scan
        new Thread(refreshContextAction).start();
    }

    private static String generateGroovyScript(String userPrompt, String cleanedHtml) {
        if (GROOVY_SCRIPT_PROMPT_TEMPLATE == null || GROOVY_SCRIPT_PROMPT_TEMPLATE.isEmpty()) {
            loadPrompts();
        }

        String prompt = String.format(GROOVY_SCRIPT_PROMPT_TEMPLATE, userPrompt, cleanedHtml);

        System.out.println("Generating code with LLM...");
        String code = LLMUtil.chatWithDeepSeek(prompt);
        
        // Clean up code block markers if present
        if (code != null) {
            code = code.replaceAll("```groovy", "").replaceAll("```java", "").replaceAll("```", "").trim();
        }
        return code;
    }

    private static String generateRefinedGroovyScript(
        String originalUserPrompt,
        String cleanedHtml,
        String previousCode,
        String execOutput,
        String refineHint
    ) {
        if (REFINED_GROOVY_SCRIPT_PROMPT_TEMPLATE == null || REFINED_GROOVY_SCRIPT_PROMPT_TEMPLATE.isEmpty()) {
            loadPrompts();
        }

        String prompt = String.format(
            REFINED_GROOVY_SCRIPT_PROMPT_TEMPLATE,
            originalUserPrompt,
            cleanedHtml,
            previousCode,
            execOutput,
            refineHint
        );

        System.out.println("Refining code with LLM...");
        String code = LLMUtil.chatWithDeepSeek(prompt);
        if (code != null) {
            code = code.replaceAll("```groovy", "").replaceAll("```java", "").replaceAll("```", "").trim();
        }
        return code;
    }

    private static void executeWithGroovy(String scriptCode, Object pageOrFrame, java.util.function.Consumer<String> logger) throws Exception {
        try {
            groovy.lang.Binding binding = new groovy.lang.Binding();
            binding.setVariable("page", pageOrFrame);
            
            // Redirect print output to our UI logger
            binding.setVariable("out", new java.io.PrintWriter(new java.io.Writer() {
                private StringBuilder buffer = new StringBuilder();
                @Override
                public void write(char[] cbuf, int off, int len) {
                    buffer.append(cbuf, off, len);
                    checkBuffer();
                }
                @Override
                public void flush() { checkBuffer(); }
                @Override
                public void close() { flush(); }
                
                private void checkBuffer() {
                    int newline = buffer.indexOf("\n");
                    while (newline != -1) {
                        String line = buffer.substring(0, newline);
                        logger.accept(line); // Log to UI
                        buffer.delete(0, newline + 1);
                        newline = buffer.indexOf("\n");
                    }
                }
            }, true)); // Auto-flush

            groovy.lang.GroovyShell shell = new groovy.lang.GroovyShell(binding);
            shell.evaluate(scriptCode);
            logger.accept("Groovy script executed successfully.");
        } catch (Exception e) {
            logger.accept("Groovy execution failed: " + e.getMessage());
            // 抛出异常以便主程序捕获并退出
            throw e;
        }
    }

}
