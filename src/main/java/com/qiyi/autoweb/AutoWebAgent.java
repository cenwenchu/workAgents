package com.qiyi.autoweb;

import com.microsoft.playwright.Page;
import com.qiyi.util.LLMUtil;
import com.qiyi.util.PlayWrightUtil;
import javax.swing.*;
import java.awt.*;

public class AutoWebAgent {

    public static void main(String[] args) {
        if (args.length < 2) {
            // Default example if no args provided
            String url = "https://sc.scm121.com/tradeManage/tower/distribute";
            String userPrompt = "查询待发货的订单，然后会得到的结果表带有表头'序号','订单号','商品信息'等字段，选中其中的第一条结果";
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

        JFrame frame = new JFrame("AutoWeb Agent Controller");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setSize(600, 950);
        frame.setLayout(new BorderLayout());

        // Close Playwright on exit
        frame.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent windowEvent) {
                if (connection != null && connection.playwright != null) {
                    connection.playwright.close();
                    System.out.println("Playwright connection closed.");
                }
            }
        });

        // --- Top Area: Settings + Prompt ---
        JPanel topContainer = new JPanel(new BorderLayout());

        // 1. Settings Panel (Context Selector)
        JPanel settingsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        settingsPanel.setBorder(BorderFactory.createTitledBorder("Context Selection"));
        
        JLabel lblContext = new JLabel("Target:");
        JComboBox<ContextWrapper> contextCombo = new JComboBox<>();
        contextCombo.setPreferredSize(new Dimension(300, 25));
        JButton btnRefreshContext = new JButton("Refresh / Scan");
        
        settingsPanel.add(lblContext);
        settingsPanel.add(contextCombo);
        settingsPanel.add(btnRefreshContext);
        
        topContainer.add(settingsPanel, BorderLayout.NORTH);

        // 2. Prompt Panel
        JPanel promptPanel = new JPanel(new BorderLayout());
        promptPanel.setBorder(BorderFactory.createTitledBorder("User Prompt"));
        JTextArea promptArea = new JTextArea(defaultPrompt);
        promptArea.setLineWrap(true);
        promptArea.setWrapStyleWord(true);
        promptArea.setRows(4);
        JScrollPane promptScroll = new JScrollPane(promptArea);
        promptPanel.add(promptScroll, BorderLayout.CENTER);
        
        topContainer.add(promptPanel, BorderLayout.CENTER);


        // --- Middle Area: Groovy Code ---
        JPanel codePanel = new JPanel(new BorderLayout());
        codePanel.setBorder(BorderFactory.createTitledBorder("Groovy Code"));
        JTextArea codeArea = new JTextArea();
        codeArea.setFont(new Font("Monospaced", Font.PLAIN, 12));
        JScrollPane codeScroll = new JScrollPane(codeArea);
        codePanel.add(codeScroll, BorderLayout.CENTER);


        // --- Bottom Area: Output Log ---
        JPanel outputPanel = new JPanel(new BorderLayout());
        outputPanel.setBorder(BorderFactory.createTitledBorder("Execution Output"));
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
        JButton btnGetCode = new JButton("Get Code");
        JButton btnExecute = new JButton("Execute Code");
        JButton btnPickSelector = new JButton("Pick Selector");
        
        buttonPanel.add(btnGetCode);
        buttonPanel.add(btnPickSelector);
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
        
        // --- Setup Selector Picker Binding ---
        // We expose a function that the browser can call when an element is picked
        try {
            rootPage.exposeFunction("onElementPicked", (args) -> {
                String selector = args[0].toString();
                SwingUtilities.invokeLater(() -> {
                    // Check if text is selected in codeArea
                    String selectedText = codeArea.getSelectedText();
                    if (selectedText != null && !selectedText.isEmpty()) {
                        codeArea.replaceSelection(selector);
                        uiLogger.accept("Replaced '" + selectedText + "' with '" + selector + "'");
                    } else {
                        // Insert at cursor
                        codeArea.insert(selector, codeArea.getCaretPosition());
                        uiLogger.accept("Inserted selector: " + selector);
                    }
                    // Bring window to front
                    frame.toFront();
                    frame.requestFocus();
                });
                return null;
            });
        } catch (Exception e) {
            // Function might already exist if re-running, ignore
            System.out.println("Binding onElementPicked already exists or failed: " + e.getMessage());
        }


        // --- Logic: Pick Selector ---
        btnPickSelector.addActionListener(e -> {
            ContextWrapper selectedContext = (ContextWrapper) contextCombo.getSelectedItem();
            if (selectedContext == null) {
                JOptionPane.showMessageDialog(frame, "Please select a context first.", "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
            
            uiLogger.accept("=== Selector Picker Mode Started ===");
            uiLogger.accept("Context: " + selectedContext.name);
            uiLogger.accept("Please CLICK an element in the browser window...");
            
            // Inject JS to capture the next click
            // JS Logic: 
            // 1. Add capturing click listener
            // 2. Prevent default/propagation
            // 3. Generate selector
            // 4. Call window.onElementPicked
            // 5. Remove listener
            
            String js = 
                "(function() {" +
                "  function getCssSelector(el) {" +
                "    if (!(el instanceof Element)) return;" +
                "    var path = [];" +
                "    while (el.nodeType === Node.ELEMENT_NODE) {" +
                "        var selector = el.nodeName.toLowerCase();" +
                "        if (el.id) {" +
                "            selector += '#' + el.id;" +
                "            path.unshift(selector);" +
                "            break;" +
                "        } else {" +
                "            var sib = el, nth = 1;" +
                "            while (sib = sib.previousElementSibling) {" +
                "                if (sib.nodeName.toLowerCase() == selector)" +
                "                   nth++;" +
                "            }" +
                "            if (nth != 1)" +
                "                selector += ':nth-of-type('+nth+')';" +
                "        }" +
                "        path.unshift(selector);" +
                "        el = el.parentNode;" +
                "    }" +
                "    return path.join(' > ');" +
                "  }" +
                "  function handler(e) {" +
                "    e.preventDefault();" +
                "    e.stopPropagation();" +
                "    var sel = '';" +
                "    if (e.target.innerText && e.target.innerText.trim().length > 0 && e.target.innerText.length < 30) {" +
                "         sel = \"text='\" + e.target.innerText.trim() + \"'\";" +
                "    } else {" +
                "         sel = getCssSelector(e.target);" +
                "    }" +
                "    var codeFormat = 'page.locator(\"' + sel + '\")';" +
                "    window.onElementPicked(codeFormat);" +
                "    document.removeEventListener('click', handler, true);" +
                "  }" +
                "  document.addEventListener('click', handler, {capture: true, once: true});" +
                "})();";

            new Thread(() -> {
                try {
                    // Execute in the selected context (Page or Frame)
                    if (selectedContext.context instanceof Page) {
                        ((Page)selectedContext.context).evaluate(js);
                    } else if (selectedContext.context instanceof com.microsoft.playwright.Frame) {
                        ((com.microsoft.playwright.Frame)selectedContext.context).evaluate(js);
                    }
                } catch (Exception ex) {
                    uiLogger.accept("Error starting picker: " + ex.getMessage());
                }
            }).start();
        });



        // --- Logic: Refresh Contexts ---
        Runnable refreshContextAction = () -> {
            uiLogger.accept("Scanning for frames...");
            ScanResult res = scanContexts(rootPage);
            
            SwingUtilities.invokeLater(() -> {
                contextCombo.removeAllItems();
                for (ContextWrapper w : res.wrappers) {
                    contextCombo.addItem(w);
                }
                if (res.best != null) {
                    contextCombo.setSelectedItem(res.best);
                }
                uiLogger.accept("Context list updated. Auto-selected: " + (res.best != null ? res.best.name : "None"));
            });
        };

        btnRefreshContext.addActionListener(e -> {
            new Thread(refreshContextAction).start();
        });


        // --- Logic: Get Code ---
        btnGetCode.addActionListener(e -> {
            String currentPrompt = promptArea.getText();
            ContextWrapper selectedContext = (ContextWrapper) contextCombo.getSelectedItem();
            
            if (selectedContext == null) {
                JOptionPane.showMessageDialog(frame, "Please select a context first.", "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }

            btnGetCode.setEnabled(false);
            uiLogger.accept("=== Starting Code Generation ===");
            uiLogger.accept("Target Context: " + selectedContext.name);
            codeArea.setText("// Generating code for: " + selectedContext.name + "...\n// Please wait...");
            
            new Thread(() -> {
                try {
                    ContextWrapper workingContext = selectedContext;

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
                    if (res.best != null) {
                        workingContext = res.best;
                        uiLogger.accept("Auto-selected best context: " + workingContext.name);
                    } else {
                        // Fallback to main page if something weird happens
                        workingContext = new ContextWrapper();
                        workingContext.context = rootPage;
                        workingContext.name = "Main Page";
                        uiLogger.accept("Fallback to Main Page context.");
                    }

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
                         uiLogger.accept("Error: Failed to retrieve page content after reload. Please check if the page is loading correctly.");
                         SwingUtilities.invokeLater(() -> {
                            codeArea.setText("// Error: Failed to retrieve page content. Please try again.");
                            btnGetCode.setEnabled(true);
                        });
                         return; // Exit thread
                    }


                    String freshCleanedHtml = HTMLCleaner.clean(freshHtml);
                    
                    if (freshCleanedHtml.length() > 100000) {
                        freshCleanedHtml = freshCleanedHtml.substring(0, 100000) + "...(truncated)";
                    }
                    uiLogger.accept("Content retrieved. Size: " + freshCleanedHtml.length());
                    
                    // 2. Generate Code
                    String code = generateGroovyScript(currentPrompt, freshCleanedHtml);
                    SwingUtilities.invokeLater(() -> {
                        codeArea.setText(code);
                        btnGetCode.setEnabled(true);
                    });
                    uiLogger.accept("Code generated successfully.");
                    
                } catch (Exception ex) {
                     SwingUtilities.invokeLater(() -> {
                        codeArea.setText("// Error: " + ex.getMessage());
                        btnGetCode.setEnabled(true);
                    });
                     uiLogger.accept("Error: " + ex.getMessage());
                }
            }).start();
        });


        // --- Logic: Execute Code ---
        btnExecute.addActionListener(e -> {
            String code = codeArea.getText();
            ContextWrapper selectedContext = (ContextWrapper) contextCombo.getSelectedItem();

            if (code == null || code.trim().isEmpty()) {
                JOptionPane.showMessageDialog(frame, "No code to execute!", "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }
            if (selectedContext == null) {
                JOptionPane.showMessageDialog(frame, "Please select a context first.", "Error", JOptionPane.ERROR_MESSAGE);
                return;
            }

            btnExecute.setEnabled(false);
            // Clear output area before execution
            outputArea.setText(""); 
            uiLogger.accept("=== Executing Code ===");
            uiLogger.accept("Target Context: " + selectedContext.name);
            
            new Thread(() -> {
                try {
                    executeWithGroovy(code, selectedContext.context, uiLogger);
                    SwingUtilities.invokeLater(() -> {
                         btnExecute.setEnabled(true);
                    });
                    uiLogger.accept("=== Execution Finished ===");
                } catch (Exception ex) {
                    SwingUtilities.invokeLater(() -> {
                        btnExecute.setEnabled(true);
                    });
                    uiLogger.accept("=== Execution Failed: " + ex.getMessage() + " ===");
                }
            }).start();
        });

        // Initialize frame size/location
        Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
        int width = 600;
        int height = screenSize.height - 50;
        frame.setSize(width, height);
        frame.setLocation(screenSize.width - width, 0);
        frame.setVisible(true);
        
        // Trigger initial scan
        new Thread(refreshContextAction).start();
    }

    private static String generateGroovyScript(String userPrompt, String cleanedHtml) {
        String prompt = String.format(
            "You are a Playwright automation expert for web automation in Groovy.\n" +
            "User Task (natural language): %s\n" +
            "Target HTML (cleaned snapshot of the current page/frame):\n%s\n\n" +
            "GLOBAL PRINCIPLES:\n" +
            "- Always combine USER TASK + HTML CONTENT to design the script.\n" +
            "- First, extract key intents and entities from the user task (filters, columns, buttons, operations).\n" +
            "- Then, map these intents to REAL elements that exist in the HTML above.\n" +
            "- Only when there is not enough information in BOTH the user task and the HTML, you may fall back to generic heuristics.\n" +
            "- You have direct access to variable 'page' (Page or Frame). DO NOT declare it.\n" +
            "- Use RELAXED and ROBUST selectors. Avoid brittle full DOM chains.\n" +
            "- STRICT HTML-GROUNDED SELECTORS: all ids/classes/text used in selectors must exist in the provided HTML snippet. \n" +
            "- NO GUESSING: Do NOT assume/hallucinate class prefixes (like .ant-, .el-, .mui-) unless they explicitly appear in the provided HTML. Use the EXACT class names found in the snippet.\n" +
            "- **CRITICAL: WAIT BEFORE ACTION**: The page might be dynamic. Always wait for elements to appear before clicking or reading.\n" +
            "\n" +
            "SECTION 1: CLICKING / INTERACTING WITH SINGLE ELEMENTS (buttons, tabs, checkboxes)\n" +
            "- Prefer visible text, aria-label, title, or meaningful class names.\n" +
            "- GOOD: page.locator(\"text='待发货'\").click()\n" +
            "- GOOD: page.locator(\"button:has-text('搜 索')\").click()\n" +
            "- AVOID: guessing raw input checkboxes (e.g., input[type='checkbox']) in modern UI libraries.\n" +
            "- For checkboxes/radios, prefer clicking the VISIBLE label or wrapper element instead of hidden <input>.\n" +
            "- STRICT MODE WARNING: Do NOT use '.or()' to combine selectors that might BOTH exist (e.g., wrapper AND input). This causes a crash.\n" +
            "  * BAD: locator('.ant-checkbox-wrapper').or(locator('input'))\n" +
            "  * GOOD: locator('.ant-checkbox-wrapper')\n" +
            "\n" +
            "SECTION 2: LOCATING LISTS / TABLES (GENERIC STRUCTURAL APPROACH)\n" +
            "- **CRITICAL**: Do NOT search for specific framework classes (like .ant-table-row, .art-table-row) unless you see them in the HTML.\n" +
            "- **Step 1: Identify Row Pattern (GENERIC STRUCTURE FIRST)**\n" +
            "  - Inside the Body Area, look for **REPEATING SIBLING ELEMENTS**.\n" +
            "  - **PRIORITY 1 (Standard Table)**: If the structure is `<table>`, simply use `tbody tr`.\n" +
            "  - **PRIORITY 2 (Div Table)**: If using `div`s, look for direct children of the body container that repeat.\n" +
            "  - **AVOID PREFIXES**: Do NOT rely on 'ant-', 'art-', 'el-' prefixes. Instead, check if the element tag is `tr` or if it has a generic class like `row`.\n" +
            "  - **Selector Strategy**: `page.locator('tbody tr')` or `page.locator('.table-body > div')`.\n" +
            "\n" +
            "SECTION 3: ACCESSING COLUMNS / CELL VALUES\n" +
            "- When the user refers to a specific column (e.g., '订单号' column), map it to a REAL header in the HTML.\n" +
            "- Strategy:\n" +
            "  1) Locate the table container.\n" +
            "  2) Inspect header cells (<th>) and find the index of the target column by its visible text.\n" +
            "  3) Use that index to access the corresponding <td> in each row.\n" +
            "- Example approach:\n" +
            "  def headerCells = table.locator('thead tr th')\n" +
            "  int colIndex = -1\n" +
            "  for (int i = 0; i < headerCells.count(); i++) {\n" +
            "      def text = headerCells.nth(i).innerText().trim()\n" +
            "      if (text.contains('订单号')) { colIndex = i; break }\n" +
            "  }\n" +
            "  if (colIndex >= 0) {\n" +
            "      def firstRowCell = table.locator('tbody tr').first().locator('td').nth(colIndex)\n" +
            "      def orderNo = firstRowCell.innerText().trim()\n" +
            "  }\n" +
            "- If exact header text from the user is not present, choose the closest matching header in HTML.\n" +
            "\n" +
            "SECTION 4: OPERATIONS ON LIST ITEMS (select row, row-level buttons, batch actions)\n" +
            "- Selecting rows:\n" +
            "  - If the user says '选中第一条', prefer using the first visible data row in the identified table.\n" +
            "  - Try checkboxes inside the first row, or click the row itself if the UI supports row selection.\n" +
            "- Row-level buttons (e.g., '审核', '推单'):\n" +
            "  - For a given row (first row or matched by some cell value), search inside that row for a button with the requested text.\n" +
            "  - Example: row.locator(\"button:has-text('审核')\").click()\n" +
            "- Batch action buttons above the list:\n" +
            "  - Look for buttons near the table header area or in toolbars that contain the requested text.\n" +
            "- Always ensure you operate on the row/table that matches the result list determined in SECTION 2.\n" +
            "\n" +
            "SECTION 5: PAGINATION OF RESULT LISTS\n" +
            "- If the user mentions pagination (next page, previous page, go to page X), look for a pagination widget near the bottom of the result list.\n" +
            "- Common patterns: '下一页', '上一页', '>' '<', numeric page buttons.\n" +
            "- Use robust selectors like:\n" +
            "  page.locator(\".ant-pagination-next button\").click()\n" +
            "  or page.locator(\"text='下一页'\").click()\n" +
            "- After clicking a pagination control, wait for the table to refresh (e.g., wait for some row text to change, or re-wait for the tbody rows).\n" +
            "- When iterating multiple pages, structure the code in a loop and include clear logs for each page.\n" +
            "\n" +
            "WAITING AND LOGGING RULES (APPLY TO ALL SECTIONS)\n" +
            "- **MANDATORY**: Before interacting with ANY element (especially '待发货' or table rows), WAIT for it to be visible.\n" +
            "- Use: `page.locator('...').waitFor(new com.microsoft.playwright.Locator.WaitForOptions().setTimeout(60000))`\n" +
            "- If searching for text like '待发货', wait for it first: `page.locator(\"text='待发货'\").waitFor(...)`\n" +
            "- Use 'println' (not System.out.println) for logging so it appears in the UI:\n" +
            "  - e.g., println '开始查询待发货订单', println '等待查询结果加载', println '已选中第一条订单', etc.\n" +
            "\n" +
            "VISUAL FEEDBACK (OPTIONAL BUT RECOMMENDED)\n" +
            "- Before performing click/check/fill on a locator, highlight the element so the user can see it.\n" +
            "- Use this pattern where appropriate:\n" +
            "  def el = page.locator('selector')\n" +
            "  el.scrollIntoViewIfNeeded()\n" +
            "  el.evaluate(\"e => e.style.border = '3px solid red'\")\n" +
            "  page.waitForTimeout(500)\n" +
            "  el.click()\n" +
            "\n" +
            "OUTPUT FORMAT\n" +
            "- Output ONLY valid Groovy code. No markdown, no explanations, no ```.\n",
            userPrompt, cleanedHtml
        );

        System.out.println("Generating code with LLM...");
        String code = LLMUtil.chatWithDeepSeek(prompt);
        
        // Clean up code block markers if present
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
