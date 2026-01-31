package com.qiyi.autoweb;

import com.microsoft.playwright.Page;
import com.qiyi.autoweb.AutoWebAgent.ContextWrapper;
import com.qiyi.autoweb.AutoWebAgent.HtmlSnapshot;
import com.qiyi.autoweb.AutoWebAgent.HtmlCaptureMode;
import com.qiyi.autoweb.AutoWebAgent.ModelSession;
import com.qiyi.autoweb.AutoWebAgent.PlanParseResult;
import com.qiyi.autoweb.AutoWebAgent.PlanStep;
import com.qiyi.util.PlayWrightUtil;

import javax.swing.*;
import java.awt.*;

import static com.qiyi.autoweb.AutoWebAgent.*;

/**
 * Swing 控制台 UI
 * 负责计划/代码生成、修正、执行的交互与多模型会话管理
 */
class AutoWebAgentUI {
    private static JFrame AGENT_FRAME;

    /**
     * 获取当前控制台窗口引用
     */
    static JFrame getAgentFrame() {
        return AGENT_FRAME;
    }

    /**
     * 窗口状态快照
     */
    static class FrameState {
        Rectangle bounds;
        int extendedState;
        boolean visible;
        boolean alwaysOnTop;
    }

    /**
     * 捕获窗口状态
     */
    static FrameState captureFrameState() {
        JFrame frame = AGENT_FRAME;
        if (frame == null) return null;
        FrameState state = new FrameState();
        try { state.bounds = frame.getBounds(); } catch (Exception ignored) {}
        try { state.extendedState = frame.getExtendedState(); } catch (Exception ignored) {}
        try { state.visible = frame.isVisible(); } catch (Exception ignored) {}
        try { state.alwaysOnTop = frame.isAlwaysOnTop(); } catch (Exception ignored) {}
        return state;
    }

    /**
     * 恢复窗口状态
     */
    static void restoreFrameIfNeeded(FrameState state) {
        if (state == null) return;
        JFrame frame = AGENT_FRAME;
        if (frame == null) return;
        try {
            if (state.bounds != null) frame.setBounds(state.bounds);
            frame.setExtendedState(state.extendedState);
            frame.setAlwaysOnTop(state.alwaysOnTop);
            if (state.visible) frame.setVisible(true);
        } catch (Exception ignored) {}
    }

    /**
     * 最小化窗口
     */
    static void minimizeFrameIfNeeded(FrameState state) {
        JFrame frame = AGENT_FRAME;
        if (frame == null) return;
        try {
            frame.setState(Frame.ICONIFIED);
        } catch (Exception ignored) {}
    }

    /**
     * 创建并初始化控制台界面
     */
    static void createGUI(Object initialContext, String initialCleanedHtml, String defaultPrompt, PlayWrightUtil.Connection connection) {
        Page rootPage;
        if (initialContext instanceof com.microsoft.playwright.Frame) {
            rootPage = ((com.microsoft.playwright.Frame) initialContext).page();
        } else {
            rootPage = (Page) initialContext;
        }
        java.util.concurrent.atomic.AtomicReference<Page> rootPageRef = new java.util.concurrent.atomic.AtomicReference<>(rootPage);
        java.util.concurrent.atomic.AtomicReference<PlayWrightUtil.Connection> connectionRef = new java.util.concurrent.atomic.AtomicReference<>(connection);

        java.util.concurrent.atomic.AtomicBoolean hasExecuted = new java.util.concurrent.atomic.AtomicBoolean(false);
        java.util.concurrent.atomic.AtomicBoolean forceNewPageOnExecute = new java.util.concurrent.atomic.AtomicBoolean(false);
        java.util.concurrent.atomic.AtomicLong uiEpoch = new java.util.concurrent.atomic.AtomicLong(0);
        java.util.concurrent.ConcurrentHashMap<String, ModelSession> sessionsByModel = new java.util.concurrent.ConcurrentHashMap<>();

        JFrame frame = new JFrame("AutoWeb 网页自动化控制台");
        AGENT_FRAME = frame;
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setLayout(new BorderLayout());

        JButton btnPlan = new JButton("生成计划");
        JButton btnGetCode = new JButton("生成代码");
        JButton btnExecute = new JButton("执行代码");
        JButton btnRefinePlan = new JButton("修正计划");
        JButton btnRefine = new JButton("修正代码");
        JButton btnStepExecute = new JButton("分步执行");

        java.util.function.Consumer<String> setStage = (stage) -> {};
        
        JPanel topContainer = new JPanel(new BorderLayout());

        JPanel settingsArea = new JPanel();
        settingsArea.setLayout(new BoxLayout(settingsArea, BoxLayout.Y_AXIS));
        settingsArea.setBorder(BorderFactory.createTitledBorder("控制面板"));

        JPanel selectionPanel = new JPanel();
        selectionPanel.setLayout(new BoxLayout(selectionPanel, BoxLayout.X_AXIS));

        JPanel modelPanel = new JPanel(new BorderLayout());
        JLabel lblModel = new JLabel("大模型(可多选):");
        String[] models = {"DeepSeek", "Qwen-Max", "Moonshot", "GLM", "Minimax", "Gemini", "Ollama Qwen3:8B"};
        JList<String> modelList = new JList<>(models);
        modelList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        JScrollPane modelScroll = new JScrollPane(modelList);
        modelScroll.setPreferredSize(new Dimension(160, 90));
        
        String defaultModel = "DeepSeek";
        if ("QWEN_MAX".equals(ACTIVE_MODEL)) defaultModel = "Qwen-Max";
        else if ("GEMINI".equals(ACTIVE_MODEL)) defaultModel = "Gemini";
        else if ("MOONSHOT".equals(ACTIVE_MODEL)) defaultModel = "Moonshot";
        else if ("GLM".equals(ACTIVE_MODEL)) defaultModel = "GLM";
        else if ("MINIMAX".equals(ACTIVE_MODEL)) defaultModel = "Minimax";
        else if ("OLLAMA_QWEN3_8B".equals(ACTIVE_MODEL)) defaultModel = "Ollama Qwen3:8B";
        modelList.setSelectedValue(defaultModel, true);

        modelPanel.add(lblModel, BorderLayout.NORTH);
        modelPanel.add(modelScroll, BorderLayout.CENTER);

        Dimension actionButtonSize = new Dimension(120, 28);
        btnPlan.setPreferredSize(actionButtonSize);
        btnGetCode.setPreferredSize(actionButtonSize);
        btnExecute.setPreferredSize(actionButtonSize);
        btnRefinePlan.setPreferredSize(actionButtonSize);
        btnRefine.setPreferredSize(actionButtonSize);
        btnStepExecute.setPreferredSize(actionButtonSize);
        btnPlan.setMaximumSize(actionButtonSize);
        btnGetCode.setMaximumSize(actionButtonSize);
        btnExecute.setMaximumSize(actionButtonSize);
        btnRefinePlan.setMaximumSize(actionButtonSize);
        btnRefine.setMaximumSize(actionButtonSize);
        btnStepExecute.setMaximumSize(actionButtonSize);

        JPanel planCodePanel = new JPanel();
        planCodePanel.setLayout(new BoxLayout(planCodePanel, BoxLayout.Y_AXIS));
        btnPlan.setAlignmentX(Component.LEFT_ALIGNMENT);
        btnGetCode.setAlignmentX(Component.LEFT_ALIGNMENT);
        btnExecute.setAlignmentX(Component.LEFT_ALIGNMENT);
        planCodePanel.add(btnPlan);
        planCodePanel.add(Box.createVerticalStrut(8));
        planCodePanel.add(btnGetCode);
        planCodePanel.add(Box.createVerticalStrut(8));
        planCodePanel.add(btnExecute);

        JPanel refineExecutePanel = new JPanel();
        refineExecutePanel.setLayout(new BoxLayout(refineExecutePanel, BoxLayout.Y_AXIS));
        btnRefinePlan.setAlignmentX(Component.LEFT_ALIGNMENT);
        btnRefine.setAlignmentX(Component.LEFT_ALIGNMENT);
        btnStepExecute.setAlignmentX(Component.LEFT_ALIGNMENT);
        refineExecutePanel.add(btnRefinePlan);
        refineExecutePanel.add(Box.createVerticalStrut(8));
        refineExecutePanel.add(btnRefine);
        refineExecutePanel.add(Box.createVerticalStrut(8));
        refineExecutePanel.add(btnStepExecute);

        JButton btnReloadPrompts = new JButton("重载提示规则");
        JButton btnUsageHelp = new JButton("查看使用说明");
        btnReloadPrompts.setAlignmentX(Component.LEFT_ALIGNMENT);
        btnUsageHelp.setAlignmentX(Component.LEFT_ALIGNMENT);
        
        JPanel reloadContainer = new JPanel();
        reloadContainer.setLayout(new BoxLayout(reloadContainer, BoxLayout.Y_AXIS));
        JButton btnClearAll = new JButton("清空");
        btnClearAll.setAlignmentX(Component.LEFT_ALIGNMENT);
        reloadContainer.add(btnReloadPrompts);
        reloadContainer.add(Box.createVerticalStrut(6));
        reloadContainer.add(btnClearAll);
        reloadContainer.add(Box.createVerticalStrut(6));
        reloadContainer.add(btnUsageHelp);

        selectionPanel.add(modelPanel);
        selectionPanel.add(Box.createHorizontalStrut(12));
        selectionPanel.add(planCodePanel);
        selectionPanel.add(Box.createHorizontalStrut(12));
        selectionPanel.add(refineExecutePanel);
        selectionPanel.add(Box.createHorizontalStrut(12));
        selectionPanel.add(reloadContainer);
        
        settingsArea.add(selectionPanel);

        JCheckBox chkUseA11yTree = new JCheckBox("采集 HTML 使用 Accessibility Tree");
        chkUseA11yTree.setAlignmentX(Component.LEFT_ALIGNMENT);
        chkUseA11yTree.setSelected(true);
        settingsArea.add(Box.createVerticalStrut(6));
        settingsArea.add(chkUseA11yTree);

        JCheckBox chkA11yInterestingOnly = new JCheckBox("Accessibility Tree 仅保留语义节点(interestingOnly)");
        chkA11yInterestingOnly.setAlignmentX(Component.LEFT_ALIGNMENT);
        chkA11yInterestingOnly.setSelected(true);
        chkA11yInterestingOnly.setEnabled(true);
        chkUseA11yTree.addActionListener(e -> chkA11yInterestingOnly.setEnabled(chkUseA11yTree.isSelected()));
        settingsArea.add(Box.createVerticalStrut(4));
        settingsArea.add(chkA11yInterestingOnly);
        
        topContainer.add(settingsArea, BorderLayout.NORTH);

        JPanel promptPanel = new JPanel(new BorderLayout());
        promptPanel.setBorder(BorderFactory.createTitledBorder("用户任务"));
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

        JPanel codePanel = new JPanel(new BorderLayout());
        codePanel.setBorder(BorderFactory.createTitledBorder("模型回复内容"));
        JTabbedPane codeTabs = new JTabbedPane();
        codePanel.add(codeTabs, BorderLayout.CENTER);

        JPanel outputPanel = new JPanel(new BorderLayout());
        outputPanel.setBorder(BorderFactory.createTitledBorder("执行日志"));
        JTextArea outputArea = new JTextArea();
        outputArea.setFont(new Font("Monospaced", Font.PLAIN, 12));
        outputArea.setEditable(false);
        JScrollPane outputScroll = new JScrollPane(outputArea);
        outputPanel.add(outputScroll, BorderLayout.CENTER);

        JSplitPane bottomSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, codePanel, outputPanel);
        bottomSplit.setResizeWeight(0.5);

        JSplitPane mainSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, topContainer, bottomSplit);
        mainSplit.setResizeWeight(0.15);
        
        frame.add(mainSplit, BorderLayout.CENTER);

        java.util.function.Consumer<String> uiLogger = (msg) -> {
             SwingUtilities.invokeLater(() -> {
                 outputArea.append(msg + "\n");
                 outputArea.setCaretPosition(outputArea.getDocument().getLength());
             });
             System.out.println(msg);
        };

        btnReloadPrompts.addActionListener(e -> {
            GroovySupport.loadPrompts();
            JOptionPane.showMessageDialog(frame, "提示规则已重新载入！", "成功", JOptionPane.INFORMATION_MESSAGE);
        });
        
        btnUsageHelp.addActionListener(e -> {
            String text =
                    "使用流程：\n" +
                    "1) 在“用户任务”输入要做的事，然后可以选择一个或多个大模型，用于后续操作。\n" +
                    "2) 点“生成计划”：大模型会将用户任务分解为多个步骤的计划；每个步骤都需要知道访问哪个页面操作，因此若缺操作入口地址，会弹窗让你补充（支持多行、例如  订单管理页面: `http://xxxxxxx`）。\n" +
                    "3) 当计划生成完毕，点“生成代码”，我们将会获取大模型需要操作的页面数据，采集并压缩，然后发给大模型去生成可以执行任务的代码。\n" +
                    "4) 当代码生成完毕，点“执行代码”，执行脚本并在“执行日志”里输出过程，开始执行用户的任务。\n" +
                    "5) “修正代码”用于重新生成代码，主要是当代码执行出错，或者不达预期的时候，用户可以补充一些说明，让大模型去修改代码，然后后续执行修改后的代码，看是否符合预期（支持多次交互修正）。";
            JTextArea ta = new JTextArea(text, 12, 60);
            ta.setEditable(false);
            ta.setLineWrap(true);
            ta.setWrapStyleWord(true);
            ta.setCaretPosition(0);
            JScrollPane sp = new JScrollPane(ta);
            JOptionPane.showMessageDialog(frame, sp, "使用说明", JOptionPane.INFORMATION_MESSAGE);
        });
        
        btnClearAll.addActionListener(e -> {
            boolean busy = !btnPlan.isEnabled()
                    || !btnGetCode.isEnabled()
                    || !btnRefinePlan.isEnabled()
                    || !btnRefine.isEnabled()
                    || !btnStepExecute.isEnabled()
                    || !btnExecute.isEnabled()
                    || !btnClearAll.isEnabled();
            if (busy) {
                int confirm = JOptionPane.showConfirmDialog(
                        frame,
                        "检测到当前可能有任务正在执行/生成中。\n清空将重置界面并清除缓存文件。\n\n是否仍要继续清空？",
                        "清空确认",
                        JOptionPane.YES_NO_OPTION,
                        JOptionPane.WARNING_MESSAGE
                );
                if (confirm != JOptionPane.YES_OPTION) return;
            }
            
            try { modelList.clearSelection(); } catch (Exception ignored) {}
            try { promptArea.setText(""); } catch (Exception ignored) {}
            try { refineArea.setText(""); } catch (Exception ignored) {}
            try { outputArea.setText(""); } catch (Exception ignored) {}
            try { codeTabs.removeAll(); } catch (Exception ignored) {}
            try { uiEpoch.incrementAndGet(); } catch (Exception ignored) {}
            
            try {
                sessionsByModel.clear();
            } catch (Exception ignored) {}
            try { hasExecuted.set(false); } catch (Exception ignored) {}
            try { forceNewPageOnExecute.set(true); } catch (Exception ignored) {}
            
            setActionButtonsEnabled(btnPlan, btnGetCode, btnRefinePlan, btnRefine, btnStepExecute, btnExecute, btnClearAll, true);
            setStage.accept("NONE");
            
            int deleted = 0;
            deleted += AutoWebAgentUtils.clearDirFiles(java.nio.file.Paths.get(System.getProperty("user.dir"), "autoweb", "cache"), uiLogger);
            deleted += AutoWebAgentUtils.clearDirFiles(java.nio.file.Paths.get(System.getProperty("user.dir"), "autoweb", "debug"), uiLogger);
            uiLogger.accept("清空完成：已重置界面，已删除缓存/调试文件数=" + deleted);
        });

        btnPlan.addActionListener(e -> {
            String currentPrompt = promptArea.getText();
            java.util.List<String> selectedModels = modelList.getSelectedValuesList();
            
            if (currentPrompt == null || currentPrompt.trim().isEmpty()) {
                JOptionPane.showMessageDialog(frame, "请先在“用户任务”输入框中填写要执行的任务。", "提示", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            if (selectedModels.isEmpty()) {
                JOptionPane.showMessageDialog(frame, "请至少选择一个大模型。", "错误", JOptionPane.ERROR_MESSAGE);
                return;
            }

            final long planEpoch = uiEpoch.get();
            java.util.List<String> pendingEntryModels = new java.util.ArrayList<>();
            for (String modelName : selectedModels) {
                ModelSession s = sessionsByModel.get(modelName);
                if (s == null) continue;
                if (s.userPrompt == null || !s.userPrompt.equals(currentPrompt)) continue;
                if (s.planText == null || s.planText.trim().isEmpty()) continue;
                if (s.planConfirmed) continue;
                pendingEntryModels.add(modelName);
            }
            if (!pendingEntryModels.isEmpty()) {
                String msg = "检测到已有计划尚未确认入口地址。\n影响模型: " + String.join("，", pendingEntryModels) + "\n\n请选择：补充入口地址，或重新生成计划。";
                Object[] options = new Object[]{"补充入口地址", "重新生成计划", "取消"};
                int choice = JOptionPane.showOptionDialog(
                        frame,
                        msg,
                        "生成计划",
                        JOptionPane.YES_NO_CANCEL_OPTION,
                        JOptionPane.QUESTION_MESSAGE,
                        null,
                        options,
                        options[0]
                );
                if (choice == 2 || choice == JOptionPane.CLOSED_OPTION) {
                    return;
                }
                if (choice == 0) {
                    for (String model : pendingEntryModels) {
                        if (codeTabs.indexOfTab(model) >= 0) continue;
                        ModelSession s = sessionsByModel.get(model);
                        JTextArea ta = new JTextArea(s == null || s.planText == null ? "" : s.planText);
                        ta.setFont(new Font("Monospaced", Font.PLAIN, 12));
                        codeTabs.addTab(model, new JScrollPane(ta));
                    }

                    String entryInput = promptForMultilineInputBlocking(
                            frame,
                            "补充入口地址",
                            buildEntryInputHint(pendingEntryModels, sessionsByModel)
                    );
                    if (entryInput == null || entryInput.trim().isEmpty()) {
                        return;
                    }

                    setStage.accept("PLAN");
                    setActionButtonsEnabled(btnPlan, btnGetCode, btnRefinePlan, btnRefine, btnStepExecute, btnExecute, btnClearAll, false);
                    outputArea.setText("");
                    uiLogger.accept("=== UI: 点击生成计划 | action=补充入口地址 | models=" + pendingEntryModels.size() + " ===");
                    uiLogger.accept("开始提交入口地址并修正规划...");
                    
                    java.util.List<String> refineModels = new java.util.ArrayList<>(pendingEntryModels);
                    new Thread(() -> {
                        try {
                            if (uiEpoch.get() != planEpoch) return;
                            refreshRootPageRefIfNeeded(rootPageRef, connectionRef, uiLogger, "修正规划前刷新页面");
                            String currentUrlForRefine = safePageUrl(rootPageRef.get());
                            java.util.concurrent.ExecutorService ex2 = java.util.concurrent.Executors.newFixedThreadPool(refineModels.size());
                            java.util.List<java.util.concurrent.Future<?>> fs2 = new java.util.ArrayList<>();
                            for (String modelName : refineModels) {
                                fs2.add(ex2.submit(() -> {
                                    try {
                                        if (uiEpoch.get() != planEpoch) return;
                                        ModelSession session = sessionsByModel.computeIfAbsent(modelName, k -> new ModelSession());
                                        uiLogger.accept("阶段开始: model=" + modelName + ", action=PLAN_REFINE");
                                        uiLogger.accept("PLAN_REFINE Debug: model=" + modelName + ", entryInput='" + entryInput + "'");
                                        String payload = buildPlanRefinePayload(currentUrlForRefine, currentPrompt, entryInput);
                                        uiLogger.accept("PLAN_REFINE Payload Hash: " + payload.hashCode() + " | Length: " + payload.length());
                                        uiLogger.accept("阶段中: model=" + modelName + ", planMode=" + extractModeFromPayload(payload));
                                        String text = generateGroovyScript(currentPrompt, payload, uiLogger, modelName);
                                        String finalText = text == null ? "" : text;
                                        if (uiEpoch.get() != planEpoch) return;
                                        AutoWebAgentUtils.saveDebugCodeVariant(finalText, modelName, "plan_refine", uiLogger);
                                        PlanParseResult parsed = parsePlanFromText(finalText);
                                        if (!parsed.confirmed) {
                                            uiLogger.accept("PLAN_REFINE 未通过: model=" + modelName + " | Confirmed=false. LLM Output:\n" + finalText);
                                        }
                                        session.planText = parsed.planText;
                                        session.steps = parsed.steps;
                                        session.planConfirmed = parsed.confirmed;
                                        session.lastArtifactType = "PLAN";
                                        session.htmlPrepared = false;
                                        session.stepSnapshots.clear();
                                        SwingUtilities.invokeLater(() -> {
                                            int idx = codeTabs.indexOfTab(modelName);
                                            if (idx >= 0) {
                                                JScrollPane sp = (JScrollPane) codeTabs.getComponentAt(idx);
                                                JTextArea ta = (JTextArea) sp.getViewport().getView();
                                                ta.setText(finalText);
                                            }
                                        });
                                        uiLogger.accept("阶段结束: model=" + modelName + ", action=PLAN_REFINE, confirmed=" + parsed.confirmed + ", steps=" + (parsed.steps == null ? 0 : parsed.steps.size()));
                                    } catch (Exception e2) {
                                        uiLogger.accept("PLAN_REFINE 失败: model=" + modelName + ", err=" + e2.getMessage());
                                    }
                                }));
                            }
                            for (java.util.concurrent.Future<?> f2 : fs2) {
                                try { f2.get(); } catch (Exception ignored2) {}
                            }
                            ex2.shutdown();
                        } finally {
                            if (uiEpoch.get() == planEpoch) {
                                setStage.accept("NONE");
                                SwingUtilities.invokeLater(() -> {
                                    setActionButtonsEnabled(btnPlan, btnGetCode, btnRefinePlan, btnRefine, btnStepExecute, btnExecute, btnClearAll, true);
                                    uiLogger.accept("所有模型生成完成。");
                                    if (isPlanReadyForModels(selectedModels, sessionsByModel, currentPrompt)) {
                                        showPlanReadyDialog(frame);
                                    }
                                });
                            }
                        }
                    }).start();
                    return;
                }
            }

            setStage.accept("PLAN");
            setActionButtonsEnabled(btnPlan, btnGetCode, btnRefinePlan, btnRefine, btnStepExecute, btnExecute, btnClearAll, false);
            outputArea.setText("");
            hasExecuted.set(false);
            codeTabs.removeAll();
            
            uiLogger.accept("=== UI: 点击生成计划 | models=" + selectedModels.size() + " ===");

            for (String model : selectedModels) {
                JTextArea ta = new JTextArea("// 正在等待 " + model + " 生成计划...\n// 请稍候...");
                ta.setFont(new Font("Monospaced", Font.PLAIN, 12));
                codeTabs.addTab(model, new JScrollPane(ta));
            }

            new Thread(() -> {
                try {
                    if (uiEpoch.get() != planEpoch) return;
                    uiLogger.accept("规划阶段：仅发送用户任务与提示规则，不采集 HTML。");
                    refreshRootPageRefIfNeeded(rootPageRef, connectionRef, uiLogger, "生成计划前刷新页面");
                    String currentUrlForPlan = safePageUrl(rootPageRef.get());
                    java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(selectedModels.size());
                    java.util.List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();
                    java.util.Set<String> needsEntryModels = java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());
                    
                    for (String modelName : selectedModels) {
                        futures.add(executor.submit(() -> {
                            try {
                                if (uiEpoch.get() != planEpoch) return;
                                uiLogger.accept("阶段开始: model=" + modelName + ", action=PLAN");
                                String combinedForUrl = currentPrompt;
                                boolean hasUrl = extractFirstUrlFromText(combinedForUrl) != null || !extractUrlMappingsFromText(combinedForUrl).isEmpty();
                                
                                String payload = hasUrl
                                        ? buildPlanOnlyPayload(currentUrlForPlan, combinedForUrl)
                                        : buildPlanEntryPayload(currentUrlForPlan, combinedForUrl);
                                uiLogger.accept("阶段中: model=" + modelName + ", planMode=" + extractModeFromPayload(payload));
                                
                                String planResult = generateGroovyScript(currentPrompt, payload, uiLogger, modelName);
                                String finalPlanResult = planResult == null ? "" : planResult;
                                if (uiEpoch.get() != planEpoch) return;
                                ModelSession session = sessionsByModel.computeIfAbsent(modelName, k -> new ModelSession());
                                AutoWebAgentUtils.saveDebugCodeVariant(finalPlanResult, modelName, "plan", uiLogger);
                                PlanParseResult parsed = parsePlanFromText(finalPlanResult);
                                session.userPrompt = currentPrompt;
                                session.planText = parsed.planText;
                                session.steps = parsed.steps;
                                session.planConfirmed = parsed.confirmed;
                                session.lastArtifactType = "PLAN";
                                session.htmlPrepared = false;
                                session.stepSnapshots.clear();

                                SwingUtilities.invokeLater(() -> {
                                    int idx = codeTabs.indexOfTab(modelName);
                                    if (idx >= 0) {
                                        JScrollPane sp = (JScrollPane) codeTabs.getComponentAt(idx);
                                        JTextArea ta = (JTextArea) sp.getViewport().getView();
                                        ta.setText(finalPlanResult);
                                    }
                                });
                                if (parsed.steps == null || parsed.steps.isEmpty()) {
                                    uiLogger.accept("规划输出格式异常: " + modelName + " 未生成 Step 块，无法采集 HTML。请重新点“生成计划”或用“修正代码”让模型按要求输出 PLAN_START/Step/PLAN_END。");
                                }
                                if (parsed.hasQuestion || !parsed.confirmed) {
                                    needsEntryModels.add(modelName);
                                    uiLogger.accept("规划未完成: " + modelName + " 仍需要入口信息。将弹窗提示输入入口地址。");
                                } else {
                                    uiLogger.accept("规划已确认: " + modelName + "。点击“生成代码”将按计划采集 HTML 并生成脚本。");
                                }
                                uiLogger.accept("阶段结束: model=" + modelName + ", action=PLAN, confirmed=" + parsed.confirmed + ", steps=" + (parsed.steps == null ? 0 : parsed.steps.size()));
                            } catch (Exception genEx) {
                                try {
                                    uiLogger.accept("PLAN 失败: model=" + modelName + ", err=" + genEx.getMessage());
                                    saveDebugArtifact(newDebugTimestamp(), modelName, "PLAN", "exception", stackTraceToString(genEx), uiLogger);
                                } catch (Exception ignored) {}
                                if (uiEpoch.get() == planEpoch) {
                                    SwingUtilities.invokeLater(() -> {
                                        int idx = codeTabs.indexOfTab(modelName);
                                        if (idx >= 0) {
                                            JScrollPane sp = (JScrollPane) codeTabs.getComponentAt(idx);
                                            JTextArea ta = (JTextArea) sp.getViewport().getView();
                                            ta.setText("// 生成失败: " + genEx.getMessage());
                                        }
                                    });
                                }
                            }
                        }));
                    }
                    
                    for (java.util.concurrent.Future<?> f : futures) {
                        try { f.get(); } catch (Exception ignored) {}
                    }
                    executor.shutdown();

                    java.util.List<String> needList = new java.util.ArrayList<>(needsEntryModels);
                    needList.sort(String::compareTo);
                    if (needList.isEmpty()) {
                        if (uiEpoch.get() == planEpoch) {
                            setStage.accept("NONE");
                            SwingUtilities.invokeLater(() -> {
                                setActionButtonsEnabled(btnPlan, btnGetCode, btnRefinePlan, btnRefine, btnStepExecute, btnExecute, btnClearAll, true);
                                uiLogger.accept("所有模型生成完成。");
                                if (isPlanReadyForModels(selectedModels, sessionsByModel, currentPrompt)) {
                                    showPlanReadyDialog(frame);
                                }
                            });
                        }
                        return;
                    }

                    String entryInput = promptForMultilineInputBlocking(
                            frame,
                            "补充入口地址",
                            buildEntryInputHint(needList, sessionsByModel)
                    );
                    if (entryInput == null || entryInput.trim().isEmpty()) {
                        if (uiEpoch.get() == planEpoch) {
                            setStage.accept("NONE");
                            SwingUtilities.invokeLater(() -> {
                                setActionButtonsEnabled(btnPlan, btnGetCode, btnRefinePlan, btnRefine, btnStepExecute, btnExecute, btnClearAll, true);
                                uiLogger.accept("已取消入口地址输入，规划未确认的模型仍需入口信息。");
                            });
                        }
                        return;
                    }

                    if (uiEpoch.get() != planEpoch) return;
                    uiLogger.accept("开始提交入口地址并修正规划...");
                    refreshRootPageRefIfNeeded(rootPageRef, connectionRef, uiLogger, "修正规划前刷新页面");
                    String currentUrlForRefine = safePageUrl(rootPageRef.get());
                    java.util.List<String> refineModels = new java.util.ArrayList<>(needList);
                    new Thread(() -> {
                        try {
                            if (uiEpoch.get() != planEpoch) return;
                            java.util.concurrent.ExecutorService ex2 = java.util.concurrent.Executors.newFixedThreadPool(refineModels.size());
                            java.util.List<java.util.concurrent.Future<?>> fs2 = new java.util.ArrayList<>();
                            for (String modelName : refineModels) {
                                fs2.add(ex2.submit(() -> {
                                    try {
                                        if (uiEpoch.get() != planEpoch) return;
                                        ModelSession session = sessionsByModel.computeIfAbsent(modelName, k -> new ModelSession());
                                        uiLogger.accept("阶段开始: model=" + modelName + ", action=PLAN_REFINE");
                                        uiLogger.accept("PLAN_REFINE Debug: model=" + modelName + ", entryInput='" + entryInput + "'");
                                        String payload = buildPlanRefinePayload(currentUrlForRefine, currentPrompt, entryInput);
                                        uiLogger.accept("PLAN_REFINE Payload Hash: " + payload.hashCode() + " | Length: " + payload.length());
                                        uiLogger.accept("阶段中: model=" + modelName + ", planMode=" + extractModeFromPayload(payload));
                                        String text = generateGroovyScript(currentPrompt, payload, uiLogger, modelName);
                                        String finalText = text == null ? "" : text;
                                        if (uiEpoch.get() != planEpoch) return;
                                        AutoWebAgentUtils.saveDebugCodeVariant(finalText, modelName, "plan_refine", uiLogger);
                                        PlanParseResult parsed = parsePlanFromText(finalText);
                                        if (!parsed.confirmed) {
                                            uiLogger.accept("PLAN_REFINE 未通过: model=" + modelName + " | Confirmed=false. LLM Output:\n" + finalText);
                                        }
                                        session.planText = parsed.planText;
                                        session.steps = parsed.steps;
                                        session.planConfirmed = parsed.confirmed;
                                        session.lastArtifactType = "PLAN";
                                        session.htmlPrepared = false;
                                        session.stepSnapshots.clear();
                                        SwingUtilities.invokeLater(() -> {
                                            int idx = codeTabs.indexOfTab(modelName);
                                            if (idx >= 0) {
                                                JScrollPane sp = (JScrollPane) codeTabs.getComponentAt(idx);
                                                JTextArea ta = (JTextArea) sp.getViewport().getView();
                                                ta.setText(finalText);
                                            }
                                        });
                                        uiLogger.accept("阶段结束: model=" + modelName + ", action=PLAN_REFINE, confirmed=" + parsed.confirmed + ", steps=" + (parsed.steps == null ? 0 : parsed.steps.size()));
                                    } catch (Exception e2) {
                                        uiLogger.accept("PLAN_REFINE 失败: model=" + modelName + ", err=" + e2.getMessage());
                                    }
                                }));
                            }
                            for (java.util.concurrent.Future<?> f2 : fs2) {
                                try { f2.get(); } catch (Exception ignored2) {}
                            }
                            ex2.shutdown();
                        } finally {
                            if (uiEpoch.get() == planEpoch) {
                                setStage.accept("NONE");
                                SwingUtilities.invokeLater(() -> setActionButtonsEnabled(btnPlan, btnGetCode, btnRefinePlan, btnRefine, btnStepExecute, btnExecute, btnClearAll, true));
                            }
                        }
                    }).start();

                } catch (Exception ex) {
                    if (uiEpoch.get() == planEpoch) {
                        ex.printStackTrace();
                        setStage.accept("NONE");
                         SwingUtilities.invokeLater(() -> {
                            setActionButtonsEnabled(btnPlan, btnGetCode, btnRefinePlan, btnRefine, btnStepExecute, btnExecute, btnClearAll, true);
                        });
                         uiLogger.accept("发生异常：" + ex.getMessage());
                    }
                }
            }).start();
        });

        btnGetCode.addActionListener(e -> {
            String currentPrompt = promptArea.getText();
            java.util.List<String> selectedModels = modelList.getSelectedValuesList();

            if (currentPrompt == null || currentPrompt.trim().isEmpty()) {
                JOptionPane.showMessageDialog(frame, "请先在“用户任务”输入框中填写要执行的任务。", "提示", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            if (selectedModels.isEmpty()) {
                JOptionPane.showMessageDialog(frame, "请至少选择一个大模型。", "错误", JOptionPane.ERROR_MESSAGE);
                return;
            }

            refreshRootPageRefIfNeeded(rootPageRef, connectionRef, uiLogger, "生成代码前刷新页面");

            java.util.List<String> readyModels = new java.util.ArrayList<>();
            java.util.List<String> notReadyModels = new java.util.ArrayList<>();
            for (String modelName : selectedModels) {
                ModelSession session = sessionsByModel.get(modelName);
                String reason = null;
                if (session == null || session.planText == null || session.planText.trim().isEmpty()) {
                    reason = "未生成计划";
                } else if (session.userPrompt == null || !session.userPrompt.equals(currentPrompt)) {
                    reason = "计划对应的用户任务已变化，请重新生成计划";
                } else if (!session.planConfirmed) {
                    if (session.steps == null || session.steps.isEmpty()) {
                        reason = "计划未确认且无步骤";
                    } else {
                        PlanStep firstStep = session.steps.get(0);
                        String target = firstStep == null ? "" : firstStep.targetUrl;
                        boolean hasTarget = looksLikeUrl(target);
                        String currentUrl = safePageUrl(rootPageRef.get());
                        boolean hasLivePage = currentUrl != null && !currentUrl.isEmpty() && !"about:blank".equalsIgnoreCase(currentUrl);
                        
                        if (!hasTarget && !hasLivePage) {
                            reason = "计划未确认，且第一步无具体URL，当前浏览器也未打开网页";
                        }
                    }
                } else if (session.steps == null || session.steps.isEmpty()) {
                    reason = "计划缺少步骤（无法采集 HTML）";
                } else if (!"PLAN".equals(session.lastArtifactType)) {
                    reason = "请先生成计划";
                }

                if (reason == null) {
                    readyModels.add(modelName);
                } else {
                    notReadyModels.add(modelName + "（" + reason + "）");
                }
            }

            if (uiLogger != null) {
                uiLogger.accept("Code Check: prompt='" + currentPrompt + "', ready=" + readyModels + ", notReady=" + notReadyModels);
            }

            StringBuilder tip = new StringBuilder();
            if (!readyModels.isEmpty()) {
                tip.append("可生成代码: ").append(String.join("，", readyModels)).append("\n");
            } else {
                tip.append("当前没有任何模型满足“可生成代码”的条件。\n");
            }
            if (!notReadyModels.isEmpty()) {
                tip.append("不可生成代码: ").append(String.join("，", notReadyModels)).append("\n");
            }
            JOptionPane.showMessageDialog(frame, tip.toString(), "生成代码检查", JOptionPane.INFORMATION_MESSAGE);

            if (readyModels.isEmpty()) {
                return;
            }

            setStage.accept("CODEGEN");
            setActionButtonsEnabled(btnPlan, btnGetCode, btnRefinePlan, btnRefine, btnStepExecute, btnExecute, btnClearAll, false);
            outputArea.setText("");
            hasExecuted.set(false);
            codeTabs.removeAll();

            uiLogger.accept("=== UI: 点击生成代码 | selectedModels=" + selectedModels.size() + ", readyModels=" + readyModels.size() + " ===");

            for (String model : selectedModels) {
                JTextArea ta = new JTextArea("// 正在等待 " + model + " 生成代码...\n// 请稍候...");
                ta.setFont(new Font("Monospaced", Font.PLAIN, 12));
                codeTabs.addTab(model, new JScrollPane(ta));
            }

            new Thread(() -> {
                try {
                    int total = selectedModels.size();
                    int llmThreads = Math.max(1, Math.min(readyModels.size(), selectedModels.size()));
                    
                    java.util.concurrent.ExecutorService htmlExecutor = java.util.concurrent.Executors.newSingleThreadExecutor();
                    java.util.concurrent.ExecutorService llmExecutor = java.util.concurrent.Executors.newFixedThreadPool(llmThreads);
                    java.util.List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();

                    try {
                        for (int i = 0; i < total; i++) {
                            String modelName = selectedModels.get(i);
                            int order = i + 1;

                            futures.add(llmExecutor.submit(() -> {
                                SwingUtilities.invokeLater(() -> {
                                    int idx = codeTabs.indexOfTab(modelName);
                                    if (idx >= 0) {
                                        JScrollPane sp = (JScrollPane) codeTabs.getComponentAt(idx);
                                        JTextArea ta = (JTextArea) sp.getViewport().getView();
                                        ta.setText("// 排队号: " + order + "/" + total + "\n// 状态: 等待开始\n");
                                    }
                                });

                                ModelSession session = sessionsByModel.get(modelName);
                                if (!readyModels.contains(modelName)) {
                                    return;
                                }

                                try {
                                    if (session.steps == null || session.steps.isEmpty()) {
                                        uiLogger.accept(modelName + ": 计划缺少步骤，无法采集 HTML。请重新生成计划。");
                                        return;
                                    }

                                    if (!session.htmlPrepared) {
                                        SwingUtilities.invokeLater(() -> {
                                            int idx = codeTabs.indexOfTab(modelName);
                                            if (idx >= 0) {
                                                JScrollPane sp = (JScrollPane) codeTabs.getComponentAt(idx);
                                                JTextArea ta = (JTextArea) sp.getViewport().getView();
                                                ta.setText("// 排队号: " + order + "/" + total + "\n// 状态: 等待采集 HTML（单线程队列）\n");
                                            }
                                        });
                                        
                                        java.util.concurrent.Future<?> htmlFuture = htmlExecutor.submit(() -> {
                                            if (session.htmlPrepared) return null;
                                            uiLogger.accept(modelName + ": 开始按计划采集 HTML（Step 数: " + session.steps.size() + "）...");
                                            refreshRootPageRefIfNeeded(rootPageRef, connectionRef, uiLogger, "采集 HTML 前刷新页面");
                                            HtmlCaptureMode mode = chkUseA11yTree.isSelected() ? HtmlCaptureMode.ARIA_SNAPSHOT : HtmlCaptureMode.RAW_HTML;
                                            boolean a11yInterestingOnly = chkA11yInterestingOnly.isSelected();
                                            java.util.List<HtmlSnapshot> snaps = prepareStepHtmls(rootPageRef.get(), session.steps, uiLogger, mode, a11yInterestingOnly);
                                            java.util.Map<Integer, HtmlSnapshot> map = new java.util.HashMap<>();
                                            for (HtmlSnapshot s : snaps) map.put(s.stepIndex, s);
                                            session.stepSnapshots = map;
                                            session.htmlPrepared = true;
                                            uiLogger.accept(modelName + ": HTML 采集完成（snapshots=" + session.stepSnapshots.size() + "）");
                                            return null;
                                        });
                                        try { htmlFuture.get(); } catch (Exception ignored) {}
                                    }

                                    SwingUtilities.invokeLater(() -> {
                                        int idx = codeTabs.indexOfTab(modelName);
                                        if (idx >= 0) {
                                            JScrollPane sp = (JScrollPane) codeTabs.getComponentAt(idx);
                                            JTextArea ta = (JTextArea) sp.getViewport().getView();
                                            ta.setText("// 排队号: " + order + "/" + total + "\n// 状态: 正在调用模型生成代码...\n");
                                        }
                                    });

                                    java.util.List<HtmlSnapshot> snaps = new java.util.ArrayList<>(session.stepSnapshots.values());
                                    snaps.sort(java.util.Comparator.comparingInt(a -> a.stepIndex));
                                    
                                    refreshRootPageRefIfNeeded(rootPageRef, connectionRef, uiLogger, "生成 Payload 前刷新页面");
                                    String payload = buildCodegenPayload(rootPageRef.get(), session.planText, snaps);
                                    int htmlLen = 0;
                                    try {
                                        int h = payload == null ? -1 : payload.indexOf("STEP_HTMLS_CLEANED:");
                                        if (h >= 0) {
                                            int start = payload.indexOf('\n', h);
                                            if (start >= 0 && start + 1 <= payload.length()) {
                                                htmlLen = payload.substring(start + 1).length();
                                            }
                                        }
                                    } catch (Exception ignored) {}
                                    HtmlCaptureMode captureModeForLog = chkUseA11yTree.isSelected() ? HtmlCaptureMode.ARIA_SNAPSHOT : HtmlCaptureMode.RAW_HTML;
                                    uiLogger.accept("将要提交给大模型的 操作页面网页的长度为 " + htmlLen + " ，采集模式为 " + captureModeForLog);
                                    uiLogger.accept("阶段中: model=" + modelName + ", action=CODEGEN, payloadMode=" + extractModeFromPayload(payload) + ", steps=" + session.steps.size() + ", snapshots=" + snaps.size());
                                    String generatedCode = generateGroovyScript(currentPrompt, payload, uiLogger, modelName);
                                    String normalizedCode = normalizeGeneratedGroovy(generatedCode);
                                    if (normalizedCode != null && !normalizedCode.equals(generatedCode)) {
                                        java.util.List<String> normalizeErrors = GroovyLinter.check(normalizedCode);
                                        boolean hasSyntaxIssue = normalizeErrors.stream().anyMatch(e2 -> e2.startsWith("Syntax Error") || e2.startsWith("Parse Error"));
                                        if (!hasSyntaxIssue) {
                                            generatedCode = normalizedCode;
                                        }
                                    }

                                    String finalCode = generatedCode == null ? "" : generatedCode;
                                    AutoWebAgentUtils.saveDebugCodeVariant(finalCode, modelName, "gen", uiLogger);
                                    session.lastArtifactType = "CODE";

                                    SwingUtilities.invokeLater(() -> {
                                        int idx = codeTabs.indexOfTab(modelName);
                                        if (idx >= 0) {
                                            JScrollPane sp = (JScrollPane) codeTabs.getComponentAt(idx);
                                            JTextArea ta = (JTextArea) sp.getViewport().getView();
                                            ta.setText(finalCode);
                                        }
                                    });
                                } catch (Exception ex) {
                                    try {
                                        uiLogger.accept("CODEGEN 失败: model=" + modelName + ", err=" + ex.getMessage());
                                        saveDebugArtifact(newDebugTimestamp(), modelName, "CODEGEN", "exception", stackTraceToString(ex), uiLogger);
                                    } catch (Exception ignored) {}
                                    SwingUtilities.invokeLater(() -> {
                                        int idx = codeTabs.indexOfTab(modelName);
                                        if (idx >= 0) {
                                            JScrollPane sp = (JScrollPane) codeTabs.getComponentAt(idx);
                                            JTextArea ta = (JTextArea) sp.getViewport().getView();
                                            ta.setText("// 生成失败: " + ex.getMessage());
                                        }
                                    });
                                }
                            }));
                        }

                        for (java.util.concurrent.Future<?> f : futures) {
                            try { f.get(); } catch (Exception ignored) {}
                        }
                    } finally {
                        try { llmExecutor.shutdown(); } catch (Exception ignored) {}
                        try { htmlExecutor.shutdown(); } catch (Exception ignored) {}
                    }

                    setStage.accept("NONE");
                    SwingUtilities.invokeLater(() -> {
                        setActionButtonsEnabled(btnPlan, btnGetCode, btnRefinePlan, btnRefine, btnStepExecute, btnExecute, btnClearAll, true);
                        uiLogger.accept("所有模型生成完成。");
                    });
                } catch (Exception ex) {
                    setStage.accept("NONE");
                    SwingUtilities.invokeLater(() -> {
                        setActionButtonsEnabled(btnPlan, btnGetCode, btnRefinePlan, btnRefine, btnStepExecute, btnExecute, btnClearAll, true);
                    });
                    uiLogger.accept("发生异常：" + ex.getMessage());
                }
            }).start();
        });

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

            setActionButtonsEnabled(btnPlan, btnGetCode, btnRefinePlan, btnRefine, btnStepExecute, btnExecute, btnClearAll, false);
            outputArea.setText(""); 
            uiLogger.accept("=== UI: 点击修正代码 | model=" + modelName + " ===");
            
            new Thread(() -> {
                try {
                    ModelSession session = sessionsByModel.computeIfAbsent(modelName, k -> new ModelSession());
                    if (session.userPrompt == null || session.userPrompt.trim().isEmpty()) {
                        session.userPrompt = currentPrompt;
                    }

                    boolean looksLikePlan = false;
                    try {
                        looksLikePlan = previousCode != null
                                && (previousCode.contains("PLAN_START") || previousCode.contains("PLAN_END"))
                                && !previousCode.contains("web.");
                    } catch (Exception ignored) {}
                    if (looksLikePlan) {
                        SwingUtilities.invokeLater(() -> {
                            setActionButtonsEnabled(btnPlan, btnGetCode, btnRefinePlan, btnRefine, btnStepExecute, btnExecute, btnClearAll, true);
                            JOptionPane.showMessageDialog(frame, "当前标签页内容像是“计划”而不是“代码”。请先点击“生成代码”，或重新点击“生成计划”。", "提示", JOptionPane.INFORMATION_MESSAGE);
                        });
                        uiLogger.accept("已取消修正：检测到当前标签页为计划文本。");
                        return;
                    }

                    uiLogger.accept("阶段开始: model=" + modelName + ", action=REFINE_CODE");

                    ContextWrapper workingContext = reloadAndFindContext(rootPageRef.get(), uiLogger);
                    String freshHtml = "";
                    HtmlCaptureMode mode = chkUseA11yTree.isSelected() ? HtmlCaptureMode.ARIA_SNAPSHOT : HtmlCaptureMode.RAW_HTML;
                    boolean a11yInterestingOnly = chkA11yInterestingOnly.isSelected();
                    try { freshHtml = getPageContent(workingContext.context, mode, a11yInterestingOnly); } catch (Exception ignored) {}
                    String freshCleanedHtml = cleanCapturedContent(freshHtml, mode);
                    AutoWebAgentUtils.saveDebugArtifacts(freshHtml, freshCleanedHtml, null, uiLogger);

                    if (!session.planConfirmed) {
                        PlanParseResult parsed = parsePlanFromText(previousCode);
                        if (parsed.steps != null && !parsed.steps.isEmpty() && parsed.confirmed) {
                            session.planText = parsed.planText;
                            session.steps = parsed.steps;
                            session.planConfirmed = true;
                        }
                    }

                    if (session.planConfirmed && !session.htmlPrepared) {
                        boolean a11yInterestingOnly2 = chkA11yInterestingOnly.isSelected();
                        java.util.List<HtmlSnapshot> snaps = prepareStepHtmls(rootPageRef.get(), session.steps, uiLogger, mode, a11yInterestingOnly2);
                        java.util.Map<Integer, HtmlSnapshot> map = new java.util.HashMap<>();
                        for (HtmlSnapshot s : snaps) map.put(s.stepIndex, s);
                        session.stepSnapshots = map;
                        session.htmlPrepared = true;
                    }

                    java.util.List<HtmlSnapshot> stepSnaps = new java.util.ArrayList<>(session.stepSnapshots.values());
                    stepSnaps.sort(java.util.Comparator.comparingInt(a -> a.stepIndex));
                    String payload = buildRefinePayload(rootPageRef.get(), session.planText, stepSnaps, freshCleanedHtml, currentPrompt, refineHint);
                    uiLogger.accept("阶段中: model=" + modelName + ", action=REFINE_CODE, payloadMode=" + extractModeFromPayload(payload) + ", snapshots=" + stepSnaps.size());
                    String promptForRefine = currentPrompt;
                    try {
                        if (session.userPrompt != null && !session.userPrompt.equals(currentPrompt)) {
                            promptForRefine = "原用户任务:\n" + session.userPrompt + "\n\n当前用户任务:\n" + currentPrompt;
                        }
                    } catch (Exception ignored) {}
                    String refinedCode = generateRefinedGroovyScript(
                            promptForRefine, payload, previousCode, execOutput, refineHint, uiLogger, modelName
                    );

                    String normalizedRefined = normalizeGeneratedGroovy(refinedCode);
                    if (normalizedRefined != null && !normalizedRefined.equals(refinedCode)) {
                        java.util.List<String> normalizeErrors = GroovyLinter.check(normalizedRefined);
                        if (normalizeErrors.isEmpty()) {
                            refinedCode = normalizedRefined;
                        }
                    }
                    String finalRefinedCode = refinedCode == null ? "" : refinedCode;
                    AutoWebAgentUtils.saveDebugCodeVariant(finalRefinedCode, modelName, "refine", uiLogger);
                    session.lastArtifactType = "CODE";

                    SwingUtilities.invokeLater(() -> {
                        codeArea.setText(finalRefinedCode);
                        setActionButtonsEnabled(btnPlan, btnGetCode, btnRefinePlan, btnRefine, btnStepExecute, btnExecute, btnClearAll, true);
                    });
                    setStage.accept(finalRefinedCode.trim().isEmpty() ? "NONE" : "READY_EXECUTE");
                    uiLogger.accept("Refine 完成。");
                    uiLogger.accept("阶段结束: model=" + modelName + ", action=REFINE_CODE, bytes(code)=" + utf8Bytes(finalRefinedCode));
                } catch (Exception ex) {
                    SwingUtilities.invokeLater(() -> {
                        setActionButtonsEnabled(btnPlan, btnGetCode, btnRefinePlan, btnRefine, btnStepExecute, btnExecute, btnClearAll, true);
                    });
                    setStage.accept("NONE");
                    uiLogger.accept("Refine 失败: " + ex.getMessage());
                }
            }).start();
        });

        btnExecute.addActionListener(e -> {
            int selectedIndex = codeTabs.getSelectedIndex();
            if (selectedIndex < 0) {
                JOptionPane.showMessageDialog(frame, "请先选择一个包含代码的标签页。", "提示", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            String modelName = codeTabs.getTitleAt(selectedIndex);
            JScrollPane sp = (JScrollPane) codeTabs.getComponentAt(selectedIndex);
            JTextArea codeArea = (JTextArea) sp.getViewport().getView();
            String code = codeArea.getText();

            if (code == null || code.trim().isEmpty()) {
                JOptionPane.showMessageDialog(frame, "当前没有可执行的代码。", "提示", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            
            if (code.contains("QUESTION:") && (!code.contains("web.click") && !code.contains("web.extract"))) {
                int confirm = JOptionPane.showConfirmDialog(frame, 
                    "检测到代码中包含 'QUESTION:' 且似乎没有具体执行逻辑。\n模型可能正在请求更多信息。\n\n是否仍要强制执行？", 
                    "执行确认", 
                    JOptionPane.YES_NO_OPTION, 
                    JOptionPane.WARNING_MESSAGE);
                if (confirm != JOptionPane.YES_OPTION) {
                    return;
                }
            }

            setActionButtonsEnabled(btnPlan, btnGetCode, btnRefinePlan, btnRefine, btnStepExecute, btnExecute, btnClearAll, false);
            outputArea.setText(""); 
            uiLogger.accept("=== 开始执行代码 ===");
            setStage.accept("EXECUTING");
            
            new Thread(() -> {
                try {
                    String currentPrompt = promptArea.getText();
                    ModelSession session = sessionsByModel.get(modelName);
                    String entryUrl = chooseExecutionEntryUrl(session, currentPrompt);
                    uiLogger.accept("执行准备: model=" + modelName + ", entryUrl=" + (entryUrl == null ? "(null)" : entryUrl));

                    Page liveRootPage = ensureLiveRootPage(rootPageRef, connectionRef, forceNewPageOnExecute, hasExecuted, uiLogger);
                    String beforeUrl = safePageUrl(liveRootPage);
                    boolean hasLivePage = !beforeUrl.isEmpty() && !"about:blank".equalsIgnoreCase(beforeUrl);
                    if (entryUrl == null || entryUrl.trim().isEmpty()) {
                        if (!hasLivePage) {
                            throw new RuntimeException("未找到入口URL，且当前浏览器没有可用页面。请在“用户任务”里包含入口链接（https://...），或先生成计划并补充入口地址。");
                        } else {
                            uiLogger.accept("执行前导航: 未提供入口URL，将使用当前页面 | current=" + beforeUrl);
                        }
                    }
                    ensureRootPageAtUrl(liveRootPage, entryUrl, uiLogger);

                    ContextWrapper bestContext = waitAndFindContext(liveRootPage, uiLogger);
                    Object executionTarget = bestContext == null ? liveRootPage : bestContext.context;
                    if (hasExecuted.get()) {
                         uiLogger.accept("检测到代码已执行过，正在重置页面状态...");
                         ContextWrapper freshContext = reloadAndFindContext(liveRootPage, uiLogger);
                         executionTarget = freshContext.context;
                    }
                    
                    executeWithGroovy(code, executionTarget, uiLogger);
                    hasExecuted.set(true);
                    
                    SwingUtilities.invokeLater(() -> setActionButtonsEnabled(btnPlan, btnGetCode, btnRefinePlan, btnRefine, btnStepExecute, btnExecute, btnClearAll, true));
                    setStage.accept("READY_EXECUTE");
                    uiLogger.accept("=== 执行完成 ===");
                } catch (Exception ex) {
                    SwingUtilities.invokeLater(() -> setActionButtonsEnabled(btnPlan, btnGetCode, btnRefinePlan, btnRefine, btnStepExecute, btnExecute, btnClearAll, true));
                    setStage.accept("READY_EXECUTE");
                    uiLogger.accept("=== 执行失败: " + ex.getMessage() + " ===");
                }
            }).start();
        });

        Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
        int width = 800;
        int height = screenSize.height - 50;
        frame.setSize(width, height);
        frame.setLocation(screenSize.width - width, 0);
        frame.setVisible(true);
    }

    private static void setActionButtonsEnabled(
            JButton btnPlan,
            JButton btnGetCode,
            JButton btnRefinePlan,
            JButton btnRefine,
            JButton btnStepExecute,
            JButton btnExecute,
            JButton btnClearAll,
            boolean enabled
    ) {
        if (btnPlan != null) btnPlan.setEnabled(enabled);
        if (btnGetCode != null) btnGetCode.setEnabled(enabled);
        if (btnRefinePlan != null) btnRefinePlan.setEnabled(enabled);
        if (btnRefine != null) btnRefine.setEnabled(enabled);
        if (btnStepExecute != null) btnStepExecute.setEnabled(enabled);
        if (btnExecute != null) btnExecute.setEnabled(enabled);
        if (btnClearAll != null) btnClearAll.setEnabled(enabled);
    }

    private static Page pickMostRecentLivePage(PlayWrightUtil.Connection connection) {
        if (connection == null) return null;
        try {
            java.util.List<com.microsoft.playwright.BrowserContext> contexts = connection.browser.contexts();
            Page lastAny = null;
            Page lastNonBlank = null;
            for (com.microsoft.playwright.BrowserContext ctx : contexts) {
                if (ctx == null) continue;
                java.util.List<Page> pages = ctx.pages();
                if (pages == null || pages.isEmpty()) continue;
                for (Page p : pages) {
                    if (p == null) continue;
                    boolean closed;
                    try {
                        closed = p.isClosed();
                    } catch (Exception e) {
                        closed = true;
                    }
                    if (closed) continue;
                    lastAny = p;
                    String u = safePageUrl(p);
                    if (!u.isEmpty() && !"about:blank".equalsIgnoreCase(u)) {
                        lastNonBlank = p;
                    }
                }
            }
            return lastNonBlank != null ? lastNonBlank : lastAny;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Page refreshRootPageRefIfNeeded(
            java.util.concurrent.atomic.AtomicReference<Page> rootPageRef,
            java.util.concurrent.atomic.AtomicReference<PlayWrightUtil.Connection> connectionRef,
            java.util.function.Consumer<String> uiLogger,
            String stage
    ) {
        if (rootPageRef == null) return null;
        synchronized (PLAYWRIGHT_LOCK) {
            Page current = rootPageRef.get();
            Page candidate = pickMostRecentLivePage(connectionRef == null ? null : connectionRef.get());
            if (candidate == null || candidate == current) return current;

            String before = safePageUrl(current);
            String after = safePageUrl(candidate);
            rootPageRef.set(candidate);
            if (uiLogger != null) {
                uiLogger.accept((stage == null ? "刷新页面" : stage) + ": current=" + (before.isEmpty() ? "(empty)" : before) + " -> " + (after.isEmpty() ? "(empty)" : after));
            }
            return candidate;
        }
    }

    private static Page ensureLiveRootPage(
            java.util.concurrent.atomic.AtomicReference<Page> rootPageRef,
            java.util.concurrent.atomic.AtomicReference<PlayWrightUtil.Connection> connectionRef,
            java.util.concurrent.atomic.AtomicBoolean forceNewPageOnExecute,
            java.util.concurrent.atomic.AtomicBoolean hasExecuted,
            java.util.function.Consumer<String> uiLogger
    ) {
        Page rootPage = rootPageRef == null ? null : rootPageRef.get();
        boolean forceNewPage = forceNewPageOnExecute != null && forceNewPageOnExecute.get();
        boolean pageOk = rootPage != null;
        if (pageOk) {
            try {
                if (rootPage.isClosed()) pageOk = false;
            } catch (Exception e) {
                pageOk = false;
            }
        }
        PlayWrightUtil.Connection connection = connectionRef == null ? null : connectionRef.get();
        boolean connectionOk = connection != null;
        if (connectionOk) {
            try {
                connection.browser.contexts();
            } catch (Exception e) {
                connectionOk = false;
            }
        }
        if (pageOk && connectionOk && !forceNewPage) return rootPage;

        if (uiLogger != null) uiLogger.accept("执行前检测到页面已关闭，正在重新连接浏览器...");
        if (!connectionOk) {
            connection = PlayWrightUtil.connectAndAutomate();
            if (connection == null) {
                throw new RuntimeException("无法重新连接浏览器，请确认 Chrome 调试端口已可用。");
            }
            if (connectionRef != null) connectionRef.set(connection);
        }

        Page newPage;
        try {
            if (connection.browser.contexts().isEmpty()) {
                newPage = connection.browser.newPage();
            } else {
                com.microsoft.playwright.BrowserContext context = connection.browser.contexts().get(0);
                newPage = context.newPage();
            }
            try { newPage.bringToFront(); } catch (Exception ignored) {}
        } catch (Exception e) {
            throw new RuntimeException("重新创建页面失败: " + e.getMessage());
        }
        if (rootPageRef != null) rootPageRef.set(newPage);
        if (forceNewPageOnExecute != null) forceNewPageOnExecute.set(false);
        if (hasExecuted != null) hasExecuted.set(false);
        if (uiLogger != null) uiLogger.accept("已重新连接浏览器并恢复页面。");
        return newPage;
    }

    private static String promptForMultilineInput(Component parent, String title, String message) {
        JPanel panel = new JPanel(new BorderLayout(8, 8));
        JTextArea ta = new JTextArea(6, 60);
        ta.setLineWrap(true);
        ta.setWrapStyleWord(true);
        panel.add(new JLabel("<html>" + (message == null ? "" : message).replace("\n", "<br/>") + "</html>"), BorderLayout.NORTH);
        panel.add(new JScrollPane(ta), BorderLayout.CENTER);
        int result = JOptionPane.showConfirmDialog(parent, panel, title, JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE);
        if (result != JOptionPane.OK_OPTION) return null;
        String v = ta.getText();
        return v == null ? null : v.trim();
    }
    
    private static String promptForMultilineInputBlocking(Component parent, String title, String message) {
        if (SwingUtilities.isEventDispatchThread()) {
            return promptForMultilineInput(parent, title, message);
        }
        java.util.concurrent.atomic.AtomicReference<String> ref = new java.util.concurrent.atomic.AtomicReference<>(null);
        try {
            SwingUtilities.invokeAndWait(() -> ref.set(promptForMultilineInput(parent, title, message)));
        } catch (Exception ignored) {}
        return ref.get();
    }

    private static boolean isPlanReadyForModels(java.util.List<String> models, java.util.Map<String, ModelSession> sessionsByModel, String currentPrompt) {
        if (models == null || models.isEmpty()) return false;
        for (String modelName : models) {
            if (modelName == null || modelName.trim().isEmpty()) return false;
            ModelSession s = sessionsByModel == null ? null : sessionsByModel.get(modelName);
            if (s == null) return false;
            if (s.userPrompt == null || currentPrompt == null || !s.userPrompt.equals(currentPrompt)) return false;
            if (s.planText == null || s.planText.trim().isEmpty()) return false;
            if (!s.planConfirmed) return false;
            if (s.steps == null || s.steps.isEmpty()) return false;
            if (!"PLAN".equals(s.lastArtifactType)) return false;
        }
        return true;
    }

    private static void showPlanReadyDialog(JFrame frame) {
        if (frame == null) return;
        Runnable show = () -> {
            String msg = "计划已生成，可以点击“生成代码”。\n回车后关闭弹窗（也可以手动点击确认）。";
            JDialog dialog = new JDialog(frame, "提示", true);
            JPanel panel = new JPanel(new BorderLayout(12, 12));
            panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

            JTextArea ta = new JTextArea(msg, 4, 40);
            ta.setEditable(false);
            ta.setLineWrap(true);
            ta.setWrapStyleWord(true);
            ta.setOpaque(false);
            ta.setBorder(null);

            JButton ok = new JButton("确认");
            ok.addActionListener(ev -> dialog.dispose());
            JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
            btnPanel.add(ok);

            panel.add(ta, BorderLayout.CENTER);
            panel.add(btnPanel, BorderLayout.SOUTH);
            dialog.setContentPane(panel);
            dialog.getRootPane().setDefaultButton(ok);
            dialog.pack();
            dialog.setLocationRelativeTo(frame);
            ok.requestFocusInWindow();
            dialog.setVisible(true);
        };
        if (SwingUtilities.isEventDispatchThread()) {
            show.run();
        } else {
            SwingUtilities.invokeLater(show);
        }
    }
}
