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
    private static String ACTIVE_MODEL = "DEEPSEEK";

    public static void main(String[] args) {
        cleanDebugDirectory();
        loadPrompts();
        if (args.length < 2) {
            // Default example if no args provided
            String url = "https://sc.scm121.com/tradeManage/tower/distribute";
            // String userPrompt = "请帮我查询“待发货”的订单。等表格加载出来后，" +
            //         "把第一页的每条记录整理成，用中文逗号分隔，内容中有回车换行的就去掉，然后逐行输出；" +
            //         "再输出页面底部显示的总记录数（比如“共xx条”）。" +
            //         "最后选中第一页第一条记录，并点击“审核推单”。";

            String userPrompt = "请帮我查询待发货所有的订单（包括多页的数据），并且输出订单的所有信息，输出格式为：\\\"列名:列内容（去掉回车换行）\\\"，然后用\\\"｜\\\"分隔，列的顺序保持表格的顺序，一条记录一行。输出以后，回到第一条订单，选中订单，然后点击审核推单，读取弹出页面的成功和失败的笔数，失败笔数大于0，页面上获取失败原因，也一起输出";

            System.out.println("No arguments provided. Running default example:");
            System.out.println("URL: " + url);
            System.out.println("Prompt: " + userPrompt);
            run(url, userPrompt);
        } else {
            if (args.length >= 3 && args[2] != null) {
                String modelArg = args[2].trim();
                String upper = modelArg.toUpperCase();
                if ("DEEPSEEK".equals(upper)) {
                    ACTIVE_MODEL = "DEEPSEEK";
                    System.out.println("Using model: DeepSeek (remote)");
                } else if ("QWEN-MAX".equals(upper) || "QWEN_MAX".equals(upper) || "ALIYUN_QWEN_MAX".equals(upper)) {
                    ACTIVE_MODEL = "QWEN_MAX";
                    System.out.println("Using model: Aliyun Qwen-Max (remote)");
                } else if ("GEMINI".equals(upper) || "GEMINI_FLASH".equals(upper)) {
                    ACTIVE_MODEL = "GEMINI";
                    System.out.println("Using model: Gemini (remote)");
                } else if ("MOONSHOT".equals(upper) || "MOONSHOT_MOONSHOT".equals(upper) || "MOONSHOT_V1".equals(upper)) {
                    ACTIVE_MODEL = "MOONSHOT";
                    System.out.println("Using model: Moonshot (remote)");
                } else if ("GLM".equals(upper) || "ZHIPU".equals(upper)) {
                    ACTIVE_MODEL = "GLM";
                    System.out.println("Using model: Zhipu GLM (remote)");
                } else if ("OLLAMA_MODEL_QWEN3_8B".equals(upper)
                        || "OLLAMA_QWEN3_8B".equals(upper)
                        || "OLLAMA".equals(upper)
                        || "QWEN3_8B".equals(upper)
                        || "QWEN3:8B".equals(upper)) {
                    ACTIVE_MODEL = "OLLAMA_QWEN3_8B";
                    System.out.println("Using local Ollama model: " + LLMUtil.OLLAMA_MODEL_QWEN3_8B + " @ " + LLMUtil.OLLAMA_HOST);
                } else {
                    ACTIVE_MODEL = "DEEPSEEK";
                    System.out.println("Unknown model arg '" + modelArg + "', defaulting to DeepSeek.");
                }
            } else {
                ACTIVE_MODEL = "DEEPSEEK";
            }
            run(args[0], args[1]);
        }
    }

    private static void cleanDebugDirectory() {
        try {
            Path debugDir = Paths.get(System.getProperty("user.dir"), "autoweb", "debug");
            if (Files.exists(debugDir)) {
                System.out.println("Cleaning debug directory: " + debugDir.toAbsolutePath());
                try (java.util.stream.Stream<Path> walk = Files.walk(debugDir)) {
                    walk.sorted(java.util.Comparator.reverseOrder())
                        .map(Path::toFile)
                        .forEach(java.io.File::delete);
                }
            }
            Files.createDirectories(debugDir);
        } catch (IOException e) {
            System.err.println("Warning: Failed to clean debug directory: " + e.getMessage());
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
            saveDebugArtifacts(html, cleanedHtml, null, System.out::println);

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

    private static void saveDebugArtifacts(String rawHtml, String cleanedHtml, String code, java.util.function.Consumer<String> uiLogger) {
        try {
            java.nio.file.Path debugDir = java.nio.file.Paths.get(System.getProperty("user.dir"), "autoweb", "debug");
            if (!java.nio.file.Files.exists(debugDir)) {
                java.nio.file.Files.createDirectories(debugDir);
            }
            
            if (rawHtml != null) {
                java.nio.file.Files.write(debugDir.resolve("debug_raw.html"), rawHtml.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            if (cleanedHtml != null) {
                java.nio.file.Files.write(debugDir.resolve("debug_cleaned.html"), cleanedHtml.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            if (code != null) {
                 java.nio.file.Path codePath = debugDir.resolve("debug_code.groovy");
                 java.nio.file.Files.write(codePath, code.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                 uiLogger.accept("Debug code saved to: " + codePath.toAbsolutePath());
            }
            
            if (rawHtml != null || cleanedHtml != null) {
                uiLogger.accept("Debug HTMLs saved to: " + debugDir.toAbsolutePath());
            }
        } catch (Exception ex) {
            uiLogger.accept("Failed to save debug artifacts: " + ex.getMessage());
        }
    }

    private static void saveDebugCodeVariant(String code, String modelName, String tag, java.util.function.Consumer<String> uiLogger) {
        if (code == null) return;
        try {
            java.nio.file.Path debugDir = java.nio.file.Paths.get(System.getProperty("user.dir"), "autoweb", "debug");
            if (!java.nio.file.Files.exists(debugDir)) {
                java.nio.file.Files.createDirectories(debugDir);
            }
            String safeModel = modelName == null ? "UNKNOWN" : modelName.trim().replaceAll("[^A-Za-z0-9_\\-]", "_");
            if (safeModel.isEmpty()) safeModel = "UNKNOWN";
            String safeTag = tag == null ? "code" : tag.trim().replaceAll("[^A-Za-z0-9_\\-]", "_");
            if (safeTag.isEmpty()) safeTag = "code";
            String ts = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS"));
            java.nio.file.Path codePath = debugDir.resolve("debug_code_" + safeModel + "_" + safeTag + "_" + ts + ".groovy");
            java.nio.file.Files.write(codePath, code.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            if (uiLogger != null) {
                uiLogger.accept("Debug code saved to: " + codePath.toAbsolutePath());
            }
        } catch (Exception ex) {
            if (uiLogger != null) {
                uiLogger.accept("Failed to save debug code: " + ex.getMessage());
            }
        }
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
        frame.setSize(1100, 950);
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

        // --- Action Buttons (Declared early for layout) ---
        JButton btnGetCode = new JButton("生成代码");
        JButton btnRefine = new JButton("修正代码");
        JButton btnExecute = new JButton("执行代码");

        // Customize Execute button style
        btnExecute.setFont(new Font(btnExecute.getFont().getName(), Font.BOLD, 14));
        btnExecute.setForeground(java.awt.Color.WHITE);
        btnExecute.setBackground(new java.awt.Color(0, 153, 51)); // Vibrant Green
        btnExecute.setOpaque(true);
        btnExecute.setBorderPainted(false);
        
        // --- Top Area: Settings + Prompt ---
        JPanel topContainer = new JPanel(new BorderLayout());

        // 1. Settings Area (Context + Model + Buttons)
        JPanel settingsArea = new JPanel();
        settingsArea.setLayout(new BoxLayout(settingsArea, BoxLayout.Y_AXIS));
        settingsArea.setBorder(BorderFactory.createTitledBorder("控制面板"));

        // Row 1: Context & Model Selection
        JPanel selectionPanel = new JPanel(new BorderLayout());
        
        JPanel leftSettings = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        JLabel lblContext = new JLabel("目标上下文:");
        JComboBox<ContextWrapper> contextCombo = new JComboBox<>();
        contextCombo.setPreferredSize(new Dimension(220, 25));
        
        JLabel lblModel = new JLabel("大模型(可多选):");
        String[] models = {"DeepSeek", "Qwen-Max", "Moonshot", "GLM", "Minimax", "Gemini", "Ollama Qwen3:8B"};
        JList<String> modelList = new JList<>(models);
        modelList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        JScrollPane modelScroll = new JScrollPane(modelList);
        modelScroll.setPreferredSize(new Dimension(180, 80));
        
        // Default selection
        String defaultModel = "DeepSeek";
        if ("QWEN_MAX".equals(ACTIVE_MODEL)) defaultModel = "Qwen-Max";
        else if ("GEMINI".equals(ACTIVE_MODEL)) defaultModel = "Gemini";
        else if ("MOONSHOT".equals(ACTIVE_MODEL)) defaultModel = "Moonshot";
        else if ("GLM".equals(ACTIVE_MODEL)) defaultModel = "GLM";
        else if ("MINIMAX".equals(ACTIVE_MODEL)) defaultModel = "Minimax";
        else if ("OLLAMA_QWEN3_8B".equals(ACTIVE_MODEL)) defaultModel = "Ollama Qwen3:8B";
        modelList.setSelectedValue(defaultModel, true);

        leftSettings.add(lblContext);
        leftSettings.add(contextCombo);
        leftSettings.add(lblModel);
        leftSettings.add(modelScroll);
        
        JPanel rightSettings = new JPanel(new BorderLayout());
        JPanel rightTop = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 0));
        JButton btnRefreshContext = new JButton("刷新");
        JButton btnReloadPrompts = new JButton("重载提示规则");
        rightTop.add(btnRefreshContext);
        rightTop.add(btnReloadPrompts);
        
        JPanel rightBottom = new JPanel(new FlowLayout(FlowLayout.RIGHT, 5, 5));
        rightBottom.add(btnGetCode);
        rightBottom.add(btnRefine);
        rightBottom.add(btnExecute);

        rightSettings.add(rightTop, BorderLayout.NORTH);
        rightSettings.add(rightBottom, BorderLayout.SOUTH);
        
        selectionPanel.add(leftSettings, BorderLayout.WEST);
        selectionPanel.add(rightSettings, BorderLayout.EAST);
        
        settingsArea.add(selectionPanel);
        
        topContainer.add(settingsArea, BorderLayout.NORTH);

        // 2. Prompt Panel
        JPanel promptPanel = new JPanel(new BorderLayout());
        promptPanel.setBorder(BorderFactory.createTitledBorder("用户命令"));
        JTextArea promptArea = new JTextArea(defaultPrompt);
        promptArea.setLineWrap(true);
        promptArea.setWrapStyleWord(true);
        promptArea.setRows(3);
        JScrollPane promptScroll = new JScrollPane(promptArea);
        promptPanel.add(promptScroll, BorderLayout.CENTER);

        JPanel refinePanel = new JPanel(new BorderLayout());
        refinePanel.setBorder(BorderFactory.createTitledBorder("补充说明提示信息"));
        JTextArea refineArea = new JTextArea();
        refineArea.setLineWrap(true);
        refineArea.setWrapStyleWord(true);
        refineArea.setRows(2);
        JScrollPane refineScroll = new JScrollPane(refineArea);
        refinePanel.add(refineScroll, BorderLayout.CENTER);

        JPanel promptContainer = new JPanel(new GridLayout(2, 1));
        promptContainer.add(promptPanel);
        promptContainer.add(refinePanel);
        
        topContainer.add(promptContainer, BorderLayout.CENTER);


        // --- Middle Area: Groovy Code (Tabs) ---
        JPanel codePanel = new JPanel(new BorderLayout());
        codePanel.setBorder(BorderFactory.createTitledBorder("Groovy 代码"));
        JTabbedPane codeTabs = new JTabbedPane();
        codePanel.add(codeTabs, BorderLayout.CENTER);

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
        bottomSplit.setResizeWeight(0.5);

        // Top (Settings+Prompt) vs Bottom (Code+Output)
        JSplitPane mainSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, topContainer, bottomSplit);
        mainSplit.setResizeWeight(0.15);
        
        frame.add(mainSplit, BorderLayout.CENTER);

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
            java.util.List<String> selectedModels = modelList.getSelectedValuesList();
            
            if (currentPrompt == null || currentPrompt.trim().isEmpty()) {
                JOptionPane.showMessageDialog(frame, "请先在用户命令输入框中填写要执行的指令。", "提示", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            if (selectedContext == null) {
                JOptionPane.showMessageDialog(frame, "请先选择一个目标上下文（Frame/Page）。", "错误", JOptionPane.ERROR_MESSAGE);
                return;
            }
            if (selectedModels.isEmpty()) {
                JOptionPane.showMessageDialog(frame, "请至少选择一个大模型。", "错误", JOptionPane.ERROR_MESSAGE);
                return;
            }

            btnGetCode.setEnabled(false);
            btnRefine.setEnabled(false);
            btnExecute.setEnabled(false);
            outputArea.setText(""); // Clear output
            hasExecuted.set(false);
            codeTabs.removeAll(); // Clear existing tabs
            
            uiLogger.accept("=== 开始生成代码 (" + selectedModels.size() + " 个模型) ===");
            uiLogger.accept("目标上下文: " + selectedContext.name);

            // Create tabs placeholder
            for (String model : selectedModels) {
                JTextArea ta = new JTextArea("// 正在等待 " + model + " 生成代码...\n// 请稍候...");
                ta.setFont(new Font("Monospaced", Font.PLAIN, 12));
                codeTabs.addTab(model, new JScrollPane(ta));
            }

            new Thread(() -> {
                try {
                    // 1. Prepare Context (Shared)
                    ContextWrapper workingContext = reloadAndFindContext(rootPage, selectedContext, uiLogger, contextCombo);
                    
                    String freshHtml = "";
                    int retries = 0;
                    while (retries < 10) {
                        try {
                            freshHtml = getPageContent(workingContext.context);
                        } catch (Exception contentEx) {
                             try { Thread.sleep(3000); } catch (InterruptedException ie) {}
                             retries++;
                             continue;
                        }
                        if (freshHtml.contains("ant-spin-spinning") || freshHtml.length() < 1000) {
                             try { Thread.sleep(3000); } catch (InterruptedException ie) {}
                             retries++;
                        } else {
                            break;
                        }
                    }
                    
                    if (freshHtml.isEmpty()) {
                         uiLogger.accept("错误：重新加载后未能成功获取页面内容。");
                         SwingUtilities.invokeLater(() -> {
                             btnGetCode.setEnabled(true);
                             btnRefine.setEnabled(true);
                             btnExecute.setEnabled(true);
                         });
                         return;
                    }

                    String freshCleanedHtml = HTMLCleaner.clean(freshHtml);
                    if (freshCleanedHtml.length() > 500000) {
                        freshCleanedHtml = freshCleanedHtml.substring(0, 500000) + "...(truncated)";
                    }
                    uiLogger.accept("已获取页面内容，清理后大小: " + freshCleanedHtml.length());
                    saveDebugArtifacts(freshHtml, freshCleanedHtml, null, uiLogger);
                    
                    // 2. Parallel Generation
                    java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(selectedModels.size());
                    java.util.List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();

                    String finalCleanedHtml = freshCleanedHtml;
                    
                    for (String modelName : selectedModels) {
                        futures.add(executor.submit(() -> {
                            try {
                                String generatedCode = generateGroovyScript(currentPrompt, finalCleanedHtml, uiLogger, modelName);
                                String normalizedCode = normalizeGeneratedGroovy(generatedCode);
                                if (normalizedCode != null && !normalizedCode.equals(generatedCode)) {
                                    java.util.List<String> normalizeErrors = GroovyLinter.check(normalizedCode);
                                    boolean hasSyntaxIssue = normalizeErrors.stream().anyMatch(e2 -> e2.startsWith("Syntax Error") || e2.startsWith("Parse Error"));
                                    if (!hasSyntaxIssue) {
                                        generatedCode = normalizedCode;
                                    }
                                }
                                
                                String finalCode = generatedCode;
                                saveDebugCodeVariant(finalCode, modelName, "gen", uiLogger);

                                SwingUtilities.invokeLater(() -> {
                                    int idx = codeTabs.indexOfTab(modelName);
                                    if (idx >= 0) {
                                        JScrollPane sp = (JScrollPane) codeTabs.getComponentAt(idx);
                                        JTextArea ta = (JTextArea) sp.getViewport().getView();
                                        ta.setText(finalCode);
                                    }
                                });
                            } catch (Exception genEx) {
                                SwingUtilities.invokeLater(() -> {
                                    int idx = codeTabs.indexOfTab(modelName);
                                    if (idx >= 0) {
                                        JScrollPane sp = (JScrollPane) codeTabs.getComponentAt(idx);
                                        JTextArea ta = (JTextArea) sp.getViewport().getView();
                                        ta.setText("// 生成失败: " + genEx.getMessage());
                                    }
                                });
                            }
                        }));
                    }
                    
                    // Wait for all
                    for (java.util.concurrent.Future<?> f : futures) {
                        try { f.get(); } catch (Exception ignored) {}
                    }
                    executor.shutdown();
                    
                    SwingUtilities.invokeLater(() -> {
                        btnGetCode.setEnabled(true);
                        btnRefine.setEnabled(true);
                        btnExecute.setEnabled(true);
                        uiLogger.accept("所有模型生成完成。");
                    });

                } catch (Exception ex) {
                    ex.printStackTrace();
                     SwingUtilities.invokeLater(() -> {
                        btnGetCode.setEnabled(true);
                        btnRefine.setEnabled(true);
                        btnExecute.setEnabled(true);
                    });
                     uiLogger.accept("发生异常：" + ex.getMessage());
                }
            }).start();
        });


        // --- Logic: Refine Code ---
        btnRefine.addActionListener(e -> {
            int selectedIndex = codeTabs.getSelectedIndex();
            if (selectedIndex < 0) {
                JOptionPane.showMessageDialog(frame, "请先选择一个包含代码的标签页。", "提示", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            String modelName = codeTabs.getTitleAt(selectedIndex);
            JScrollPane sp = (JScrollPane) codeTabs.getComponentAt(selectedIndex);
            JTextArea codeArea = (JTextArea) sp.getViewport().getView();
            String previousCode = codeArea.getText();

            String currentPrompt = promptArea.getText();
            String refineHint = refineArea.getText();
            String execOutput = outputArea.getText();

            if (previousCode == null || previousCode.trim().isEmpty()) {
                JOptionPane.showMessageDialog(frame, "当前没有可用于修正的代码。", "提示", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            if (refineHint == null || refineHint.trim().isEmpty()) {
                int choice = JOptionPane.showConfirmDialog(
                        frame,
                        "未填写修正说明。是否直接提交修正？",
                        "提示",
                        JOptionPane.YES_NO_OPTION,
                        JOptionPane.INFORMATION_MESSAGE
                );
                if (choice != JOptionPane.YES_OPTION) {
                    refineArea.requestFocusInWindow();
                    return;
                }
            }

            btnGetCode.setEnabled(false);
            btnRefine.setEnabled(false);
            btnExecute.setEnabled(false);
            outputArea.setText(""); 
            uiLogger.accept("=== 正在修正代码 (模型: " + modelName + ") ===");

            ContextWrapper selectedContext = (ContextWrapper) contextCombo.getSelectedItem();
            
            new Thread(() -> {
                try {
                    ContextWrapper workingContext = reloadAndFindContext(rootPage, selectedContext, uiLogger, contextCombo);
                    String freshHtml = "";
                    try {
                        freshHtml = getPageContent(workingContext.context);
                    } catch (Exception contentEx) {}

                    String freshCleanedHtml = HTMLCleaner.clean(freshHtml);
                    if (freshCleanedHtml.length() > 500000) {
                        freshCleanedHtml = freshCleanedHtml.substring(0, 500000) + "...(truncated)";
                    }
                    saveDebugArtifacts(freshHtml, freshCleanedHtml, null, uiLogger);

                    String refinedCode = generateRefinedGroovyScript(
                        currentPrompt, freshCleanedHtml, previousCode, execOutput, refineHint, uiLogger, modelName
                    );

                    String normalizedRefined = normalizeGeneratedGroovy(refinedCode);
                    if (normalizedRefined != null && !normalizedRefined.equals(refinedCode)) {
                         java.util.List<String> normalizeErrors = GroovyLinter.check(normalizedRefined);
                         if (normalizeErrors.isEmpty()) {
                             refinedCode = normalizedRefined;
                         }
                    }
                    String finalRefinedCode = refinedCode;
                    saveDebugCodeVariant(finalRefinedCode, modelName, "refine", uiLogger);

                    SwingUtilities.invokeLater(() -> {
                        codeArea.setText(finalRefinedCode);
                        btnGetCode.setEnabled(true);
                        btnRefine.setEnabled(true);
                        btnExecute.setEnabled(true);
                    });
                    uiLogger.accept("Refine 完成。");
                } catch (Exception ex) {
                    SwingUtilities.invokeLater(() -> {
                        btnGetCode.setEnabled(true);
                        btnRefine.setEnabled(true);
                        btnExecute.setEnabled(true);
                    });
                    uiLogger.accept("Refine 失败: " + ex.getMessage());
                }
            }).start();
        });


        // --- Logic: Execute Code ---
        btnExecute.addActionListener(e -> {
            int selectedIndex = codeTabs.getSelectedIndex();
            if (selectedIndex < 0) {
                JOptionPane.showMessageDialog(frame, "请先选择一个包含代码的标签页。", "提示", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            JScrollPane sp = (JScrollPane) codeTabs.getComponentAt(selectedIndex);
            JTextArea codeArea = (JTextArea) sp.getViewport().getView();
            String code = codeArea.getText();
            
            ContextWrapper selectedContext = (ContextWrapper) contextCombo.getSelectedItem();

            if (code == null || code.trim().isEmpty()) {
                JOptionPane.showMessageDialog(frame, "当前没有可执行的代码。", "提示", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            if (selectedContext == null) {
                JOptionPane.showMessageDialog(frame, "请先选择一个目标上下文。", "错误", JOptionPane.ERROR_MESSAGE);
                return;
            }

            btnGetCode.setEnabled(false);
            btnRefine.setEnabled(false);
            btnExecute.setEnabled(false);
            outputArea.setText(""); 
            uiLogger.accept("=== 开始执行代码 ===");
            
            new Thread(() -> {
                try {
                    Object executionTarget = selectedContext.context;
                    if (hasExecuted.get()) {
                         uiLogger.accept("检测到代码已执行过，正在重置页面状态...");
                         ContextWrapper freshContext = reloadAndFindContext(rootPage, selectedContext, uiLogger, contextCombo);
                         executionTarget = freshContext.context;
                    }
                    
                    executeWithGroovy(code, executionTarget, uiLogger);
                    hasExecuted.set(true);
                    
                    SwingUtilities.invokeLater(() -> {
                         btnGetCode.setEnabled(true);
                         btnRefine.setEnabled(true);
                         btnExecute.setEnabled(true);
                    });
                    uiLogger.accept("=== 执行完成 ===");
                } catch (Exception ex) {
                    SwingUtilities.invokeLater(() -> {
                        btnGetCode.setEnabled(true);
                        btnRefine.setEnabled(true);
                        btnExecute.setEnabled(true);
                    });
                    uiLogger.accept("=== 执行失败: " + ex.getMessage() + " ===");
                }
            }).start();
        });

        // Initialize frame size/location
        Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
        int width = 900;
        int height = screenSize.height - 50;
        frame.setSize(width, height);
        frame.setLocation(screenSize.width - width, 0);
        frame.setVisible(true);
        
        // Trigger initial scan
        new Thread(refreshContextAction).start();
    }

    private static String callLLMWithTimeout(java.util.concurrent.Callable<String> task, long timeoutMillis, java.util.function.Consumer<String> uiLogger, String modelName) {
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        java.util.concurrent.Future<String> future = executor.submit(task);
        try {
            return future.get(timeoutMillis, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException te) {
            future.cancel(true);
            if (uiLogger != null) {
                uiLogger.accept("LLM 调用超时，模型: " + modelName + "，已中止本次请求。");
            }
        } catch (Exception ex) {
            future.cancel(true);
            if (uiLogger != null) {
                uiLogger.accept("LLM 调用失败，模型: " + modelName + "，错误: " + ex.getMessage());
            }
        } finally {
            executor.shutdownNow();
        }
        return "";
    }
    
    private static String getModelKey(String displayName) {
        if (displayName == null) return "DEEPSEEK";
        switch (displayName) {
            case "Qwen-Max": return "QWEN_MAX";
            case "Gemini": return "GEMINI";
            case "Ollama Qwen3:8B": return "OLLAMA_QWEN3_8B";
            case "Minimax": return "MINIMAX";
            case "Moonshot": return "MOONSHOT";
            case "GLM": return "GLM";
            case "DeepSeek": return "DEEPSEEK";
            default: return "DEEPSEEK";
        }
    }

    private static String callModel(String modelName, String prompt, java.util.function.Consumer<String> uiLogger) {
        String modelKey = getModelKey(modelName);
        System.out.println("Calling LLM (model=" + modelKey + ")...");
        long t0 = System.currentTimeMillis();
        String code = "";
        
        switch (modelKey) {
            case "QWEN_MAX":
                code = LLMUtil.chatWithAliyun(prompt);
                break;
            case "MINIMAX":
                code = callLLMWithTimeout(() -> LLMUtil.chatWithMinimax(prompt), 180000L, uiLogger, "Minimax");
                if (code == null || code.trim().isEmpty()) {
                    if (uiLogger != null) uiLogger.accept("Minimax 调用未返回结果或发生错误。");
                }
                break;
            case "MOONSHOT":
                code = callLLMWithTimeout(() -> LLMUtil.chatWithMoonshot(prompt), 180000L, uiLogger, "Moonshot");
                if (code == null || code.trim().isEmpty()) {
                    if (uiLogger != null) uiLogger.accept("Moonshot 调用未返回结果或发生错误。");
                }
                break;
            case "GLM":
                code = callLLMWithTimeout(() -> LLMUtil.chatWithGLM(prompt), 180000L, uiLogger, "GLM");
                if (code == null || code.trim().isEmpty()) {
                    if (uiLogger != null) uiLogger.accept("GLM 调用未返回结果或发生错误。");
                }
                break;
            case "GEMINI":
                code = callLLMWithTimeout(() -> LLMUtil.chatWithGemini(prompt), 180000L, uiLogger, "Gemini");
                if (code == null || code.trim().isEmpty()) {
                    if (uiLogger != null) uiLogger.accept("Gemini 调用未返回结果或发生错误。");
                }
                break;
            case "OLLAMA_QWEN3_8B":
                code = LLMUtil.chatWithOllama(prompt, LLMUtil.OLLAMA_MODEL_QWEN3_8B, null, false);
                break;
            case "DEEPSEEK":
            default:
                code = LLMUtil.chatWithDeepSeek(prompt);
                break;
        }
        
        if (code != null) {
            code = code.replaceAll("```groovy", "").replaceAll("```java", "").replaceAll("```", "").trim();
        }
        long elapsed = System.currentTimeMillis() - t0;
        if (uiLogger != null) {
            uiLogger.accept(String.format("模型 %s 生成耗时: %.2f秒", modelName, elapsed / 1000.0));
        }
        return code;
    }

    private static String generateGroovyScript(String userPrompt, String cleanedHtml, java.util.function.Consumer<String> uiLogger, String modelName) {
        if (GROOVY_SCRIPT_PROMPT_TEMPLATE == null || GROOVY_SCRIPT_PROMPT_TEMPLATE.isEmpty()) {
            loadPrompts();
        }

        String prompt = String.format(GROOVY_SCRIPT_PROMPT_TEMPLATE, userPrompt, cleanedHtml);
        
        if (uiLogger != null) {
            uiLogger.accept("Prompt Context Length (Get Code): " + prompt.length() + " chars");
        }

        return callModel(modelName, prompt, uiLogger);
    }

    private static String generateRefinedGroovyScript(
        String originalUserPrompt,
        String cleanedHtml,
        String previousCode,
        String execOutput,
        String refineHint,
        java.util.function.Consumer<String> uiLogger,
        String modelName
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
        
        if (uiLogger != null) {
            uiLogger.accept("Prompt Context Length (Refine Code): " + prompt.length() + " chars");
        }

        return callModel(modelName, prompt, uiLogger);
    }
    
    private static String normalizeGeneratedGroovy(String code) {
        if (code == null) return null;
        String normalized = code;
        normalized = normalized.replaceAll("(?m)^(\\s*)(PLAN:|THINK:|ANALYSIS:|REASONING:|思考过程|计划)\\b", "$1// $2");
        normalized = normalized.replaceAll("(?m)^(\\s*)(-\\s*[Pp]lan\\b.*)", "$1// $2");
        normalized = normalized.replaceAll("(?m)^(\\s*)(\\*\\s*[Pp]lan\\b.*)", "$1// $2");
        normalized = normalized.replaceAll("(?m)^(\\s*)(\\[Plan\\].*)", "$1// $2");
        normalized = normalized.replaceAll("(?m)^(\\s*)(<plan>.*)</plan>\\s*$", "$1// $2");
        normalized = normalized.replaceAll("(?m)^(\\s*)(<think>.*)</think>\\s*$", "$1// $2");
        normalized = normalized.replaceAll("(?m)^(\\s*)(思考:.*)", "$1// $2");

        boolean applyNormalization =
                normalized.contains("web.extractList(") ||
                normalized.matches("(?s).*\\browCount\\b\\s*=\\s*web\\.count\\(.*") ||
                normalized.contains("rowTexts") ||
                normalized.contains("joinedRow");
        if (applyNormalization) {
            String replacement = "def rows = web.extractFirstPageRows(containerSelector, rowSelector, cellSelector)\n" +
                    "rows.each { row -> web.log(row) }\n";
            java.util.regex.Pattern blockPatternA = java.util.regex.Pattern.compile(
                    "(?s)def\\s+rowCount\\s*=\\s*web\\.count\\([^\\n]*\\).*?allRowsOutput\\.each\\s*\\{.*?\\}\\s*"
            );
            java.util.regex.Pattern blockPatternB = java.util.regex.Pattern.compile(
                    "(?s)def\\s+rowCount\\s*=\\s*web\\.count\\([^\\n]*\\).*?(?=def\\s+totalCountText|def\\s+totalText|web\\.getText\\()"
            );
            normalized = blockPatternA.matcher(normalized).replaceAll(replacement);
            normalized = blockPatternB.matcher(normalized).replaceAll(replacement);
            normalized = normalized.replaceAll("(?s)def\\s+rowTexts\\s*=\\s*\\[\\].*?def\\s+joinedRow\\s*=.*?web\\.log\\(joinedRow\\).*?(?=def\\s+totalCountText|def\\s+totalText|web\\.getText\\()", "");
            normalized = normalized.replaceAll("(?s)int\\s+rowCount\\s*=\\s*web\\.count\\([^\\n]*\\).*?for\\s*\\(\\s*int\\s+i\\s*=\\s*0;.*?\\)\\s*\\{.*?web\\.log\\(joinedRow\\)\\s*;?\\s*\\}.*?(?=def\\s+totalCountText|def\\s+totalText|web\\.getText\\()", replacement);
            
            java.util.regex.Pattern getTextLogAssignPattern = java.util.regex.Pattern.compile("(?s)(?:String|def|var)?\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*web\\.getText\\((\"|')(.*?)\\2\\)\\s*\\n\\s*web\\.log\\(\\1\\)");
            java.util.regex.Matcher getTextLogAssignMatcher = getTextLogAssignPattern.matcher(normalized);
            StringBuffer getTextLogAssignBuffer = new StringBuffer();
            while (getTextLogAssignMatcher.find()) {
                String varName = getTextLogAssignMatcher.group(1);
                String sel = getTextLogAssignMatcher.group(3);
                String replacementBlock = "def " + varName + " = web.getText(\"" + sel.replace("\"", "\\\"") + "\")\nweb.log(" + varName + ")";
                getTextLogAssignMatcher.appendReplacement(getTextLogAssignBuffer, java.util.regex.Matcher.quoteReplacement(replacementBlock));
            }
            getTextLogAssignMatcher.appendTail(getTextLogAssignBuffer);
            normalized = getTextLogAssignBuffer.toString();
            
            java.util.regex.Pattern getTextLogPattern = java.util.regex.Pattern.compile("(?s)web\\.getText\\((\"|')(.*?)\\1\\)\\s*\\n\\s*web\\.log\\(([^\\)]+)\\)");
            java.util.regex.Matcher getTextLogMatcher = getTextLogPattern.matcher(normalized);
            StringBuffer getTextLogBuffer = new StringBuffer();
            while (getTextLogMatcher.find()) {
                String sel = getTextLogMatcher.group(2);
                String varName = getTextLogMatcher.group(3).trim();
                String replacementBlock = "def " + varName + " = web.getText(\"" + sel.replace("\"", "\\\"") + "\")\nweb.log(" + varName + ")";
                getTextLogMatcher.appendReplacement(getTextLogBuffer, java.util.regex.Matcher.quoteReplacement(replacementBlock));
            }
            getTextLogMatcher.appendTail(getTextLogBuffer);
            normalized = getTextLogBuffer.toString();
        }
        return normalized;
    }

    private static void executeWithGroovy(String scriptCode, Object pageOrFrame, java.util.function.Consumer<String> logger) throws Exception {
        // 1. Static Linting
        java.util.List<String> lintErrors = GroovyLinter.check(scriptCode);
        if (!lintErrors.isEmpty()) {
            StringBuilder sb = new StringBuilder("Static Analysis Found Issues:\n");
            for (String err : lintErrors) {
                sb.append("- ").append(err).append("\n");
            }
            logger.accept(sb.toString());
            
            // Abort on security violations
            boolean hasSecurityError = lintErrors.stream().anyMatch(e -> e.startsWith("Security Error"));
            boolean hasSyntaxError = lintErrors.stream().anyMatch(e -> e.startsWith("Syntax Error") || e.startsWith("Parse Error"));
            if (hasSecurityError || hasSyntaxError) {
                 throw new RuntimeException("Execution aborted due to static analysis violations.");
            }
        }

        try {
            groovy.lang.Binding binding = new groovy.lang.Binding();
            binding.setVariable("page", pageOrFrame);
            
            // Inject WebDSL
            WebDSL dsl = new WebDSL(pageOrFrame, logger);
            binding.setVariable("web", dsl);
            
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
