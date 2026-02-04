package com.qiyi.autoweb;

import com.microsoft.playwright.Page;
import com.qiyi.autoweb.AutoWebAgent.ContextWrapper;
import com.qiyi.autoweb.AutoWebAgent.HtmlSnapshot;
import com.qiyi.autoweb.AutoWebAgent.HtmlCaptureMode;
import com.qiyi.autoweb.AutoWebAgent.ModelSession;
import com.qiyi.autoweb.AutoWebAgent.PlanParseResult;
import com.qiyi.autoweb.AutoWebAgent.PlanStep;
import com.qiyi.config.AppConfig;
import com.qiyi.util.LLMUtil;
import com.qiyi.util.PlayWrightUtil;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;

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
        java.util.concurrent.ConcurrentHashMap<String, String> latestCodeByModel = new java.util.concurrent.ConcurrentHashMap<>();
        java.util.concurrent.ConcurrentHashMap<String, java.util.Set<Integer>> checkedPlanStepsByModel = new java.util.concurrent.ConcurrentHashMap<>();
        java.util.concurrent.ConcurrentHashMap<String, java.util.Map<Integer, Boolean>> stepStatusByModel = new java.util.concurrent.ConcurrentHashMap<>();
        java.util.concurrent.ConcurrentHashMap<String, java.util.Map<Integer, Boolean>> stepRunningByModel = new java.util.concurrent.ConcurrentHashMap<>();
        java.util.concurrent.ConcurrentHashMap<String, java.util.Map<Integer, String>> stepErrorByModel = new java.util.concurrent.ConcurrentHashMap<>();
        java.util.concurrent.ConcurrentHashMap<String, String> executionSummaryByModel = new java.util.concurrent.ConcurrentHashMap<>();
        java.util.concurrent.ConcurrentHashMap<String, Integer> stepCursorByModel = new java.util.concurrent.ConcurrentHashMap<>();
        java.util.concurrent.ConcurrentHashMap<String, Boolean> lastStepExecSingleByModel = new java.util.concurrent.ConcurrentHashMap<>();
        java.util.concurrent.ConcurrentHashMap<String, Thread> runningThreadByModel = new java.util.concurrent.ConcurrentHashMap<>();
        java.util.concurrent.atomic.AtomicBoolean tableRefreshing = new java.util.concurrent.atomic.AtomicBoolean(false);
        java.util.concurrent.atomic.AtomicBoolean tabLocked = new java.util.concurrent.atomic.AtomicBoolean(false);
        java.util.concurrent.atomic.AtomicInteger lockedTabIndex = new java.util.concurrent.atomic.AtomicInteger(-1);
        java.util.concurrent.atomic.AtomicBoolean tabReverting = new java.util.concurrent.atomic.AtomicBoolean(false);

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
        Color lightBlue = new Color(173, 216, 230);
        btnExecute.setBackground(lightBlue);
        btnStepExecute.setBackground(lightBlue);
        btnExecute.setOpaque(true);
        btnStepExecute.setOpaque(true);

        java.util.function.Consumer<String> setStage = (stage) -> {};
        
        JPanel topContainer = new JPanel(new BorderLayout());

        JPanel settingsArea = new JPanel();
        settingsArea.setLayout(new BoxLayout(settingsArea, BoxLayout.Y_AXIS));
        settingsArea.setBorder(BorderFactory.createTitledBorder("控制面板"));

        JPanel selectionPanel = new JPanel();
        selectionPanel.setLayout(new BoxLayout(selectionPanel, BoxLayout.X_AXIS));

        JPanel modelPanel = new JPanel(new BorderLayout());
        JLabel lblModel = new JLabel("大模型(可多选):");
        String[] models = supportedModelDisplayNames();
        JList<String> modelList = new JList<>(models);
        modelList.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        JScrollPane modelScroll = new JScrollPane(modelList);
        modelScroll.setPreferredSize(new Dimension(120, 90));
        
        String defaultModel = modelKeyToDisplayName(ACTIVE_MODEL);
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
        btnRefine.setAlignmentX(Component.LEFT_ALIGNMENT);
        btnStepExecute.setAlignmentX(Component.LEFT_ALIGNMENT);
        JButton btnClearAll = new JButton("清空");
        btnClearAll.setAlignmentX(Component.LEFT_ALIGNMENT);
        btnClearAll.setPreferredSize(actionButtonSize);
        btnClearAll.setMaximumSize(actionButtonSize);
        refineExecutePanel.add(btnClearAll);
        refineExecutePanel.add(Box.createVerticalStrut(8));
        refineExecutePanel.add(btnRefine);
        refineExecutePanel.add(Box.createVerticalStrut(8));
        refineExecutePanel.add(btnStepExecute);

        JButton btnInterruptExecution = new JButton("中断执行");
        JButton btnReloadPrompts = new JButton("重载提示规则");
        JButton btnUsageHelp = new JButton("查看使用说明");
        btnInterruptExecution.setAlignmentX(Component.LEFT_ALIGNMENT);
        btnReloadPrompts.setAlignmentX(Component.LEFT_ALIGNMENT);
        btnUsageHelp.setAlignmentX(Component.LEFT_ALIGNMENT);
        
        JPanel reloadContainer = new JPanel();
        reloadContainer.setLayout(new BoxLayout(reloadContainer, BoxLayout.Y_AXIS));
        reloadContainer.add(btnInterruptExecution);
        reloadContainer.add(Box.createVerticalStrut(6));
        reloadContainer.add(btnReloadPrompts);
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

        JCheckBox chkUseA11yTree = new JCheckBox("使用简化Html采集模式（Accessibility tree ）");
        chkUseA11yTree.setAlignmentX(Component.LEFT_ALIGNMENT);
        chkUseA11yTree.setSelected(true);
        JCheckBox chkA11yInterestingOnly = new JCheckBox("Accessibility Tree 仅保留语义节点(interestingOnly)");
        chkA11yInterestingOnly.setAlignmentX(Component.LEFT_ALIGNMENT);
        chkA11yInterestingOnly.setSelected(false);
        chkA11yInterestingOnly.setEnabled(true);
        chkUseA11yTree.addActionListener(e -> chkA11yInterestingOnly.setEnabled(chkUseA11yTree.isSelected()));
        JCheckBox chkUseVisualSupplement = new JCheckBox("使用视觉补充能力");
        chkUseVisualSupplement.setAlignmentX(Component.LEFT_ALIGNMENT);
        chkUseVisualSupplement.setSelected(true);
        JPanel a11yPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        a11yPanel.add(chkUseA11yTree);
        a11yPanel.add(chkUseVisualSupplement);
        a11yPanel.add(chkA11yInterestingOnly);
        settingsArea.add(Box.createVerticalStrut(6));
        settingsArea.add(a11yPanel);
        
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
        refinePanel.setBorder(BorderFactory.createTitledBorder("补充任务说明"));
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

        JTabbedPane codeTabs = new JTabbedPane();
        codeTabs.setPreferredSize(new Dimension(0, 34));
        codeTabs.setMinimumSize(new Dimension(0, 34));

        javax.swing.table.DefaultTableModel planCodeTableModel = new javax.swing.table.DefaultTableModel(new Object[]{"全选/状态", "Plan", "Code"}, 0) {
            public boolean isCellEditable(int row, int column) {
                return column == 0;
            }
            public Class<?> getColumnClass(int columnIndex) {
                return columnIndex == 0 ? Object.class : String.class;
            }
        };
        JTable planCodeTable = new JTable(planCodeTableModel);
        planCodeTable.setFont(new Font("Monospaced", Font.PLAIN, 12));
        planCodeTable.setRowHeight(22);
        planCodeTable.setFillsViewportHeight(true);
        planCodeTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        planCodeTable.setShowHorizontalLines(true);
        planCodeTable.setShowVerticalLines(true);
        planCodeTable.setGridColor(new Color(140, 140, 140));
        planCodeTable.setRowMargin(3);
        planCodeTable.setIntercellSpacing(new Dimension(2, 2));
        planCodeTable.getColumnModel().getColumn(0).setPreferredWidth(60);
        planCodeTable.getColumnModel().getColumn(0).setMinWidth(52);
        planCodeTable.getColumnModel().getColumn(0).setMaxWidth(70);
        planCodeTable.getColumnModel().getColumn(0).setCellRenderer(new StepSelectStatusRenderer());
        planCodeTable.getColumnModel().getColumn(0).setCellEditor(new StepSelectStatusEditor());
        planCodeTable.getColumnModel().getColumn(1).setCellRenderer(new MultiLineTableCellRenderer());
        planCodeTable.getColumnModel().getColumn(2).setCellRenderer(new CodeStepCellRenderer());
        applyTableHeaderStyle(planCodeTable);
        javax.swing.table.JTableHeader planCodeHeader = planCodeTable.getTableHeader();
        if (planCodeHeader != null) {
            planCodeHeader.addMouseListener(new java.awt.event.MouseAdapter() {
                @Override
                public void mouseClicked(java.awt.event.MouseEvent e) {
                    if (e == null) return;
                    int viewCol = planCodeHeader.columnAtPoint(e.getPoint());
                    if (viewCol < 0) return;
                    int modelCol = planCodeTable.convertColumnIndexToModel(viewCol);
                    if (modelCol != 0) return;
                    int idx = codeTabs.getSelectedIndex();
                    if (idx < 0) return;
                    String modelName = codeTabs.getTitleAt(idx);
                    if (modelName == null || modelName.trim().isEmpty()) return;
                    Object orderObj = planCodeTable.getClientProperty("stepOrder");
                    if (!(orderObj instanceof java.util.List)) return;
                    java.util.List<Integer> order = (java.util.List<Integer>) orderObj;
                    if (order.isEmpty()) return;

                    java.util.Set<Integer> set = checkedPlanStepsByModel.computeIfAbsent(modelName, k -> new java.util.HashSet<>());
                    boolean allChecked = true;
                    for (Integer stepIndex : order) {
                        if (stepIndex == null) continue;
                        if (!set.contains(stepIndex)) {
                            allChecked = false;
                            break;
                        }
                    }
                    boolean target = !allChecked;

                    if (planCodeTable.isEditing()) {
                        try { planCodeTable.getCellEditor().stopCellEditing(); } catch (Exception ignored) {}
                    }

                    tableRefreshing.set(true);
                    try {
                        set.clear();
                        if (target) {
                            for (Integer stepIndex : order) {
                                if (stepIndex != null) set.add(stepIndex);
                            }
                        }
                        for (int r = 0; r < planCodeTableModel.getRowCount(); r++) {
                            Object cell = planCodeTableModel.getValueAt(r, 0);
                            StepCellData data = cell instanceof StepCellData ? (StepCellData) cell : new StepCellData(false, null, false);
                            data.checked = target;
                            planCodeTableModel.setValueAt(data, r, 0);
                        }
                    } finally {
                        tableRefreshing.set(false);
                    }
                    planCodeTable.repaint();
                    planCodeHeader.repaint();
                }
            });
        }
        JScrollPane planCodeScroll = new JScrollPane(planCodeTable);
        applyGentleScrollSpeed(planCodeScroll);

        JTextArea execSummaryArea = new JTextArea(5, 10);
        execSummaryArea.setEditable(false);
        execSummaryArea.setLineWrap(true);
        execSummaryArea.setWrapStyleWord(true);
        JScrollPane execSummaryScroll = new JScrollPane(execSummaryArea);
        applyGentleScrollSpeed(execSummaryScroll);
        JPanel execSummaryPanel = new JPanel(new BorderLayout());
        execSummaryPanel.setBorder(BorderFactory.createTitledBorder("结果执行展示区"));
        execSummaryPanel.add(execSummaryScroll, BorderLayout.CENTER);

        JPanel planCodeDisplayPanel = new JPanel(new BorderLayout());
        planCodeDisplayPanel.setBorder(BorderFactory.createTitledBorder("Plan&Code 展示区"));
        planCodeDisplayPanel.add(planCodeScroll, BorderLayout.CENTER);

        JPanel planCodeTabsPanel = new JPanel(new BorderLayout());
        planCodeTabsPanel.add(codeTabs, BorderLayout.NORTH);
        planCodeTabsPanel.add(planCodeDisplayPanel, BorderLayout.CENTER);

        Runnable applyTabLockState = () -> {
            try {
                int count = codeTabs.getTabCount();
                if (count <= 0) return;
                boolean locked = tabLocked.get();
                int lockedIdx = lockedTabIndex.get();
                if (locked && (lockedIdx < 0 || lockedIdx >= count)) {
                    lockedIdx = codeTabs.getSelectedIndex();
                    lockedTabIndex.set(lockedIdx);
                }
                for (int i = 0; i < count; i++) {
                    if (locked) {
                        codeTabs.setEnabledAt(i, i == lockedIdx);
                    } else {
                        codeTabs.setEnabledAt(i, true);
                    }
                }
            } catch (Exception ignored) {}
        };

        Runnable lockCodeTabs = () -> {
            try {
                lockedTabIndex.set(codeTabs.getSelectedIndex());
                tabLocked.set(true);
                applyTabLockState.run();
            } catch (Exception ignored) {}
        };
        Runnable unlockCodeTabs = () -> {
            try {
                tabLocked.set(false);
                lockedTabIndex.set(-1);
                applyTabLockState.run();
            } catch (Exception ignored) {}
        };

        codeTabs.addContainerListener(new java.awt.event.ContainerAdapter() {
            public void componentAdded(java.awt.event.ContainerEvent e) {
                if (tabLocked.get()) SwingUtilities.invokeLater(applyTabLockState);
            }
            public void componentRemoved(java.awt.event.ContainerEvent e) {
                if (tabLocked.get()) SwingUtilities.invokeLater(applyTabLockState);
            }
        });

        JButton btnToggleLeftPanel = new JButton(toVerticalHtml("收起左侧"));
        btnToggleLeftPanel.setFont(btnToggleLeftPanel.getFont().deriveFont(Font.BOLD, 12f));
        btnToggleLeftPanel.setBackground(new Color(255, 232, 176));
        btnToggleLeftPanel.setForeground(new Color(60, 40, 0));
        btnToggleLeftPanel.setOpaque(true);
        btnToggleLeftPanel.setFocusPainted(false);
        btnToggleLeftPanel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(200, 150, 60), 1),
                BorderFactory.createEmptyBorder(8, 2, 8, 2)
        ));
        btnToggleLeftPanel.setPreferredSize(new Dimension(22, 140));
        JPanel rightContainer = new JPanel(new BorderLayout());
        JSplitPane rightSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, planCodeTabsPanel, execSummaryPanel);
        rightSplit.setResizeWeight(0.83);
        rightContainer.add(rightSplit, BorderLayout.CENTER);

        Runnable refreshPlanCodePanel = () -> {
            int idx = codeTabs.getSelectedIndex();
            tableRefreshing.set(true);
            try {
                if (idx < 0) {
                    updatePlanCodeTableRows(
                            planCodeTable,
                            planCodeTableModel,
                            java.util.Collections.emptyList(),
                            java.util.Collections.emptyList(),
                            java.util.Collections.emptySet(),
                            java.util.Collections.emptyMap(),
                            java.util.Collections.emptyMap()
                    );
                    execSummaryArea.setText("");
                    return;
                }
                String modelName = codeTabs.getTitleAt(idx);
                String codeText = modelName == null ? "" : latestCodeByModel.getOrDefault(modelName, "");
                if (codeText == null || codeText.trim().isEmpty()) {
                    String loaded = loadLatestDebugCodeVariant(modelName);
                    if (loaded != null && !loaded.trim().isEmpty()) {
                        codeText = loaded;
                        if (modelName != null) latestCodeByModel.put(modelName, loaded);
                    } else {
                        codeText = codeText == null ? "" : codeText;
                    }
                }
                ModelSession s = sessionsByModel.get(modelName);
                java.util.Set<Integer> checked = checkedPlanStepsByModel.getOrDefault(modelName, java.util.Collections.emptySet());
                java.util.Map<Integer, Boolean> statusMap = stepStatusByModel.getOrDefault(modelName, java.util.Collections.emptyMap());
                java.util.Map<Integer, Boolean> runningMap = stepRunningByModel.getOrDefault(modelName, java.util.Collections.emptyMap());
                java.util.List<String> planRows = buildPlanStepRows(s, codeText);
                java.util.List<String> codeRows = buildCodeStepRows(codeText, s);
                updatePlanCodeTableRows(
                        planCodeTable,
                        planCodeTableModel,
                        planRows,
                        codeRows,
                        checked,
                        statusMap,
                        runningMap
                );
                adjustPlanCodeColumnWidths(planCodeTable);
                adjustPlanCodeRowHeights(planCodeTable, 1, 2);
                int rowCount = planCodeTable.getRowCount();
                if (rowCount > 0) {
                    int headerHeight = planCodeTable.getTableHeader() == null ? 0 : planCodeTable.getTableHeader().getPreferredSize().height;
                    int height = 0;
                    for (int r = 0; r < rowCount; r++) {
                        height += planCodeTable.getRowHeight(r);
                    }
                    if (height <= 0) {
                        height = rowCount * planCodeTable.getRowHeight();
                    }
                    height = Math.max(120, height + headerHeight + 16);
                    Dimension extent = planCodeScroll.getViewport().getExtentSize();
                    int width = Math.max(400, Math.max(planCodeTable.getPreferredSize().width, extent.width));
                    Dimension target = new Dimension(width, height);
                    planCodeTable.setPreferredScrollableViewportSize(target);
                    planCodeTable.setPreferredSize(target);
                    planCodeTable.setSize(target);
                    planCodeScroll.getViewport().setViewSize(target);
                }
                planCodeTable.revalidate();
                planCodeTable.repaint();
                planCodeScroll.revalidate();
                planCodeScroll.repaint();
                planCodeDisplayPanel.revalidate();
                planCodeDisplayPanel.repaint();
                execSummaryArea.setText(executionSummaryByModel.getOrDefault(modelName, ""));
            } finally {
                tableRefreshing.set(false);
            }
        };

        planCodeTable.addComponentListener(new ComponentAdapter() {
            public void componentResized(ComponentEvent e) {
                adjustPlanCodeColumnWidths(planCodeTable);
                adjustPlanCodeRowHeights(planCodeTable, 1, 2);
                SwingUtilities.invokeLater(refreshPlanCodePanel);
            }
            public void componentShown(ComponentEvent e) {
                SwingUtilities.invokeLater(refreshPlanCodePanel);
            }
        });
        planCodeScroll.getViewport().addChangeListener(e -> SwingUtilities.invokeLater(refreshPlanCodePanel));

        codeTabs.addChangeListener(e -> {
            if (tabLocked.get() && !tabReverting.get()) {
                int target = lockedTabIndex.get();
                if (target >= 0 && target < codeTabs.getTabCount() && codeTabs.getSelectedIndex() != target) {
                    SwingUtilities.invokeLater(() -> {
                        if (tabLocked.get() && !tabReverting.get()) {
                            try {
                                tabReverting.set(true);
                                if (target >= 0 && target < codeTabs.getTabCount()) {
                                    codeTabs.setSelectedIndex(target);
                                }
                            } catch (Exception ignored) {
                            } finally {
                                tabReverting.set(false);
                            }
                        }
                    });
                    return;
                }
            }
            int idx = codeTabs.getSelectedIndex();
            if (idx >= 0) {
                String modelName = codeTabs.getTitleAt(idx);
                if (modelName != null) {
                    String codeText = latestCodeByModel.getOrDefault(modelName, "");
                    if (codeText == null || codeText.trim().isEmpty()) {
                        String loaded = loadLatestDebugCodeVariant(modelName);
                        if (loaded != null && !loaded.trim().isEmpty()) {
                            latestCodeByModel.put(modelName, loaded);
                        }
                    }
                }
            }
            SwingUtilities.invokeLater(refreshPlanCodePanel);
        });

        planCodeTableModel.addTableModelListener(e -> {
            if (tableRefreshing.get()) return;
            if (e.getType() != javax.swing.event.TableModelEvent.UPDATE) return;
            if (e.getColumn() != 0) return;
            int row = e.getFirstRow();
            if (row < 0) return;
            int idx = codeTabs.getSelectedIndex();
            if (idx < 0) return;
            String modelName = codeTabs.getTitleAt(idx);
            Object orderObj = planCodeTable.getClientProperty("stepOrder");
            if (!(orderObj instanceof java.util.List)) return;
            java.util.List<Integer> order = (java.util.List<Integer>) orderObj;
            if (row >= order.size()) return;
            Integer stepIndex = order.get(row);
            if (stepIndex == null) return;
            Object cell = planCodeTableModel.getValueAt(row, 0);
            boolean checked = cell instanceof StepCellData && ((StepCellData) cell).checked;
            java.util.Set<Integer> set = checkedPlanStepsByModel.computeIfAbsent(modelName, k -> new java.util.HashSet<>());
            if (checked) set.add(stepIndex); else set.remove(stepIndex);
        });

        JPanel outputPanel = new JPanel(new BorderLayout());
        outputPanel.setBorder(BorderFactory.createTitledBorder("执行日志"));
        JTextArea outputArea = new JTextArea();
        outputArea.setFont(new Font("Monospaced", Font.PLAIN, 12));
        outputArea.setEditable(false);
        JScrollPane outputScroll = new JScrollPane(outputArea);
        outputPanel.add(outputScroll, BorderLayout.CENTER);
        
        JSplitPane mainSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, topContainer, outputPanel);
        mainSplit.setResizeWeight(0.2);

        JSplitPane mainHorizontalSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, mainSplit, rightContainer);
        mainHorizontalSplit.setResizeWeight(0.5);
        mainSplit.setMinimumSize(new Dimension(0, 0));
        int mainHorizontalDefaultDividerSize = mainHorizontalSplit.getDividerSize();
        int mainHorizontalToggleDividerSize = Math.max(mainHorizontalDefaultDividerSize, 26);
        mainHorizontalSplit.setDividerSize(mainHorizontalToggleDividerSize);
        java.util.concurrent.atomic.AtomicInteger mainHorizontalLastDividerLocation = new java.util.concurrent.atomic.AtomicInteger(-1);
        java.util.concurrent.atomic.AtomicBoolean mainHorizontalCollapsed = new java.util.concurrent.atomic.AtomicBoolean(false);
        java.util.concurrent.atomic.AtomicReference<Rectangle> mainHorizontalExpandedBounds = new java.util.concurrent.atomic.AtomicReference<>(null);
        if (mainHorizontalSplit.getUI() instanceof javax.swing.plaf.basic.BasicSplitPaneUI) {
            javax.swing.plaf.basic.BasicSplitPaneDivider divider =
                    ((javax.swing.plaf.basic.BasicSplitPaneUI) mainHorizontalSplit.getUI()).getDivider();
            divider.setLayout(new BorderLayout());
            divider.removeAll();
            divider.add(btnToggleLeftPanel, BorderLayout.CENTER);
            divider.revalidate();
            divider.repaint();
        }
        Runnable collapseLeftPanel = () -> {
            if (mainHorizontalCollapsed.get()) return;
            Rectangle expandedBounds = null;
            try {
                expandedBounds = new Rectangle(frame.getBounds());
                mainHorizontalExpandedBounds.set(expandedBounds);
            } catch (Exception ignored) {}
            int dividerSize = mainHorizontalSplit.getDividerSize();
            int loc = mainHorizontalSplit.getDividerLocation();
            if (loc > 0) mainHorizontalLastDividerLocation.set(loc);
            int total = mainHorizontalSplit.getWidth();
            int rightWidth = Math.max(360, total - loc - dividerSize);
            Insets ins = frame.getInsets();
            int insetLeft = (ins == null) ? 0 : ins.left;
            int insetRight = (ins == null) ? 0 : ins.right;
            int insetW = insetLeft + insetRight;
            int rightStartOnScreen = -1;
            if (expandedBounds != null) {
                rightStartOnScreen = expandedBounds.x + insetLeft + loc + dividerSize;
            }
            mainHorizontalSplit.setDividerLocation(0);
            mainHorizontalCollapsed.set(true);
            btnToggleLeftPanel.setText(toVerticalHtml("展开左侧"));
            int targetW = rightWidth + mainHorizontalToggleDividerSize + insetW;
            if (expandedBounds != null && rightStartOnScreen >= 0) {
                Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
                int maxX = Math.max(0, screen.width - targetW);
                int desiredX = rightStartOnScreen - insetLeft - mainHorizontalToggleDividerSize;
                int targetX = Math.min(Math.max(0, desiredX), maxX);
                frame.setBounds(targetX, expandedBounds.y, targetW, expandedBounds.height);
            } else {
                frame.setSize(targetW, frame.getHeight());
            }
        };
        Runnable expandLeftPanel = () -> {
            if (!mainHorizontalCollapsed.get()) return;
            mainHorizontalSplit.setDividerSize(mainHorizontalToggleDividerSize);
            Rectangle restoreBounds = mainHorizontalExpandedBounds.get();
            if (restoreBounds != null) {
                frame.setBounds(restoreBounds);
            }
            int restore = mainHorizontalLastDividerLocation.get();
            if (restore <= 0) {
                int total = mainHorizontalSplit.getWidth();
                restore = Math.max(240, (int) (total * 0.5));
            }
            mainHorizontalSplit.setDividerLocation(restore);
            mainHorizontalCollapsed.set(false);
            btnToggleLeftPanel.setText(toVerticalHtml("收起左侧"));
        };
        btnToggleLeftPanel.addActionListener(ev -> {
            if (mainHorizontalCollapsed.get()) expandLeftPanel.run();
            else collapseLeftPanel.run();
        });
        
        frame.add(mainHorizontalSplit, BorderLayout.CENTER);

        java.util.function.Consumer<String> uiLogger = (msg) -> {
             String out;
             try {
                 String s = msg == null ? "" : msg;
                 String t = s.trim();
                 if (!t.isEmpty() && t.startsWith("[")) out = s;
                 else out = StorageSupport.formatLog("UI", s, null);
             } catch (Exception ignored) {
                 out = msg == null ? "" : msg;
             }
             final String outFinal = out;
             SwingUtilities.invokeLater(() -> {
                 outputArea.append(outFinal + "\n");
                 outputArea.setCaretPosition(outputArea.getDocument().getLength());
             });
             System.out.println(outFinal);
        };

        btnReloadPrompts.addActionListener(e -> {
            GroovySupport.loadPrompts(uiLogger);
            JOptionPane.showMessageDialog(frame, "提示规则已重新载入！", "成功", JOptionPane.INFORMATION_MESSAGE);
        });
        
        btnUsageHelp.addActionListener(e -> {
            String text =
                    "使用流程：\n" +
                    "1) 在“用户任务”输入要做的事，然后可以选择一个或多个大模型，用于后续操作。\n" +
                    "2) 点“生成计划”：大模型会将用户任务分解为多个步骤的计划；每个步骤都需要知道访问哪个页面操作，因此若缺操作入口地址，会弹窗让你补充（支持多行、例如  订单管理页面: `http://xxxxxxx`）。\n" +
                    "3) 当计划生成完毕，点“生成代码”，我们将会获取大模型需要操作的页面数据，采集并压缩，然后发给大模型去生成可以执行任务的代码。\n" +
                    "4) 当代码生成完毕，点“执行代码”，执行脚本并在“执行日志”里输出过程，开始执行用户的任务。\n" +
                    "\n" +
                    "分步执行：\n" +
                    "- 选中 1 条或多条 Step：按选中的 Step 顺序依次执行（一次执行多条），结束后会自动清空选中状态。\n" +
                    "\n" +
                    "修正代码：\n" +
                    "- 若右侧 Plan&Code 展示区未选中任何行：则会根据补充任务说明内的提示，结合上次执行结果，提交给大模型修正任务的代码。\n" +
                    "- 若右侧选中 1 行或多行：会定向提示大模型优先修正这些行对应的步骤的任务代码，其它部分尽量不改。\n";
            JTextArea ta = new JTextArea(text, 12, 60);
            ta.setEditable(false);
            ta.setLineWrap(true);
            ta.setWrapStyleWord(true);
            ta.setCaretPosition(0);
            JScrollPane sp = new JScrollPane(ta);
            JOptionPane.showMessageDialog(frame, sp, "使用说明", JOptionPane.INFORMATION_MESSAGE);
        });

        btnInterruptExecution.addActionListener(e -> {
            long epoch = uiEpoch.incrementAndGet();
            try {
                for (java.util.Map.Entry<String, Thread> ent : runningThreadByModel.entrySet()) {
                    Thread t = ent.getValue();
                    if (t != null && t.isAlive()) {
                        try { t.interrupt(); } catch (Exception ignored) {}
                    }
                }
            } catch (Exception ignored) {}
            try {
                for (java.util.Map.Entry<String, java.util.Map<Integer, Boolean>> ent : stepRunningByModel.entrySet()) {
                    java.util.Map<Integer, Boolean> map = ent.getValue();
                    if (map == null) continue;
                    try { map.clear(); } catch (Exception ignored) {}
                }
            } catch (Exception ignored) {}
            try {
                for (String modelName : latestCodeByModel.keySet()) {
                    executionSummaryByModel.put(modelName, "已中断执行（epoch=" + epoch + "）");
                }
            } catch (Exception ignored) {}
            SwingUtilities.invokeLater(() -> {
                unlockCodeTabs.run();
                setActionButtonsEnabled(btnPlan, btnGetCode, btnRefinePlan, btnRefine, btnStepExecute, btnExecute, btnClearAll, true);
                if (mainHorizontalCollapsed.get()) expandLeftPanel.run();
                btnToggleLeftPanel.setEnabled(true);
                refreshPlanCodePanel.run();
            });
            setStage.accept("READY_EXECUTE");
            uiLogger.accept("[INTERRUPT] 已发送中断信号，尝试停止当前执行任务");
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
            try { latestCodeByModel.clear(); } catch (Exception ignored) {}
            try { checkedPlanStepsByModel.clear(); } catch (Exception ignored) {}
            try { stepStatusByModel.clear(); } catch (Exception ignored) {}
            try { stepRunningByModel.clear(); } catch (Exception ignored) {}
            try { stepErrorByModel.clear(); } catch (Exception ignored) {}
            try { executionSummaryByModel.clear(); } catch (Exception ignored) {}
            try { stepCursorByModel.clear(); } catch (Exception ignored) {}
            try { SwingUtilities.invokeLater(refreshPlanCodePanel); } catch (Exception ignored) {}
            try { uiEpoch.incrementAndGet(); } catch (Exception ignored) {}
            
            try {
                sessionsByModel.clear();
            } catch (Exception ignored) {}
            try { hasExecuted.set(false); } catch (Exception ignored) {}
            try { forceNewPageOnExecute.set(false); } catch (Exception ignored) {}
            try {
                PlayWrightUtil.Connection conn = connectionRef.get();
                if (conn != null && conn.browser != null) {
                    Page keep = null;
                    try {
                        if (conn.browser.contexts().isEmpty()) {
                            keep = conn.browser.newPage();
                        } else {
                            com.microsoft.playwright.BrowserContext ctx = conn.browser.contexts().get(0);
                            keep = ctx.newPage();
                        }
                    } catch (Exception ignored) {}
                    if (keep != null) {
                        try { keep.navigate("about:blank"); } catch (Exception ignored) {}
                        try { keep.bringToFront(); } catch (Exception ignored) {}
                        try { rootPageRef.set(keep); } catch (Exception ignored) {}
                        try {
                            for (com.microsoft.playwright.BrowserContext ctx : conn.browser.contexts()) {
                                if (ctx == null) continue;
                                for (Page p : ctx.pages()) {
                                    if (p == null) continue;
                                    if (p == keep) continue;
                                    boolean closed;
                                    try { closed = p.isClosed(); } catch (Exception e2) { closed = true; }
                                    if (closed) continue;
                                    try { p.close(); } catch (Exception ignored2) {}
                                }
                            }
                        } catch (Exception ignored) {}
                    }
                }
            } catch (Exception ignored) {}
            
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
                        codeTabs.addTab(model, new JPanel(new BorderLayout()));
                    }
                    if (codeTabs.getTabCount() > 0 && codeTabs.getSelectedIndex() < 0) {
                        codeTabs.setSelectedIndex(0);
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
                            boolean useVisualSupplement = chkUseVisualSupplement.isSelected();
                            java.util.concurrent.atomic.AtomicReference<String> visualDescriptionRef = new java.util.concurrent.atomic.AtomicReference<>(null);
                            Object visualDescriptionLock = new Object();
                            traceVisual("PLAN_REFINE_THREAD_START",
                                    "epochOk=" + (uiEpoch.get() == planEpoch) +
                                            ", models=" + refineModels.size() +
                                            ", useVisualSupplement=" + useVisualSupplement +
                                            ", entryInputLen=" + (entryInput == null ? 0 : entryInput.length()));
                            java.util.concurrent.ExecutorService ex2 = java.util.concurrent.Executors.newFixedThreadPool(refineModels.size());
                            java.util.List<java.util.concurrent.Future<?>> fs2 = new java.util.ArrayList<>();
                            for (String modelName : refineModels) {
                                fs2.add(ex2.submit(() -> {
                                    try {
                                        if (uiEpoch.get() != planEpoch) return;
                                        ModelSession session = sessionsByModel.computeIfAbsent(modelName, k -> new ModelSession());
                                        traceVisual("PLAN_REFINE_MODEL_START",
                                                "model=" + modelName +
                                                        ", useVisualSupplement=" + useVisualSupplement +
                                                        ", visualRefNull=" + (visualDescriptionRef.get() == null));
                                        uiLogger.accept("阶段开始: model=" + modelName + ", action=PLAN_REFINE");
                                        uiLogger.accept("PLAN_REFINE Debug: model=" + modelName + ", entryInput='" + entryInput + "'");
                                        String visualDescriptionFinal = null;
                                        if (useVisualSupplement) {
                                            String v = visualDescriptionRef.get();
                                            if (v == null) {
                                                synchronized (visualDescriptionLock) {
                                                    v = visualDescriptionRef.get();
                                                    if (v == null) {
                                                        try {
                                                            traceVisual("PLAN_REFINE_VISUAL_BEGIN", "model=" + modelName);
                                                            ensureLiveRootPage(rootPageRef, connectionRef, forceNewPageOnExecute, hasExecuted, uiLogger);
                                                            refreshRootPageRefIfNeeded(rootPageRef, connectionRef, uiLogger, "PLAN_REFINE 视觉补充截图前刷新页面");
                                                            Page pageForVisual = rootPageRef.get();
                                                            String targetUrlForVisual = PlanRoutingSupport.extractFirstUrlFromText(entryInput);
                                                            if (targetUrlForVisual != null && !targetUrlForVisual.trim().isEmpty()) {
                                                                traceVisual("PLAN_REFINE_VISUAL_TARGET", "targetUrl=" + PlanRoutingSupport.stripUrlQuery(targetUrlForVisual.trim()));
                                                                PlanRoutingSupport.ensureRootPageAtUrl(pageForVisual, targetUrlForVisual.trim(), uiLogger);
                                                            }
                                                            traceVisual("PLAN_REFINE_VISUAL_PAGE",
                                                                    "model=" + modelName +
                                                                            ", pageNull=" + (pageForVisual == null) +
                                                                            ", pageUrl=" + safePageUrl(pageForVisual));
                                                            clearVisualCacheForPage(pageForVisual, uiLogger);
                                                            if (uiLogger != null) uiLogger.accept("视觉补充(PLAN_REFINE): 开始截图");
                                                            v = buildPageVisualDescription(pageForVisual, uiLogger);
                                                            int len = v == null ? 0 : v.length();
                                                            uiLogger.accept("视觉补充(PLAN_REFINE): 已生成页面描述（len=" + len + "）");
                                                            traceVisual("PLAN_REFINE_VISUAL_DONE", "model=" + modelName + ", descLen=" + len);
                                                        } catch (Exception ex) {
                                                            uiLogger.accept("视觉补充(PLAN_REFINE)失败: " + ex.getMessage());
                                                            traceVisual("PLAN_REFINE_VISUAL_ERROR",
                                                                    "model=" + modelName + ", err=" + ex.getClass().getName() + ": " + (ex.getMessage() == null ? "" : ex.getMessage()));
                                                            v = "";
                                                        }
                                                        if (v == null) v = "";
                                                        visualDescriptionRef.set(v);
                                                    }
                                                }
                                            }
                                            visualDescriptionFinal = v;
                                        }
                                        String currentUrlForRefine = safePageUrl(rootPageRef.get());
                                        String payload = buildPlanRefinePayload(currentUrlForRefine, currentPrompt, entryInput, useVisualSupplement ? visualDescriptionFinal : null);
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
                                        session.userPrompt = currentPrompt;
                                        session.planText = parsed.planText;
                                        session.steps = parsed.steps;
                                        session.planConfirmed = parsed.confirmed;
                                        session.lastArtifactType = "PLAN";
                                        session.htmlPrepared = false;
                                        session.htmlCaptureMode = null;
                                        session.htmlA11yInterestingOnly = false;
                                        session.stepSnapshots.clear();
                                        SwingUtilities.invokeLater(() -> {
                                            int idx = codeTabs.indexOfTab(modelName);
                                            if (idx < 0) {
                                                codeTabs.addTab(modelName, new JPanel(new BorderLayout()));
                                                idx = codeTabs.indexOfTab(modelName);
                                            }
                                            if (idx >= 0) codeTabs.setSelectedIndex(idx);
                                            stepStatusByModel.remove(modelName);
                                            stepRunningByModel.remove(modelName);
                                            stepErrorByModel.remove(modelName);
                                            executionSummaryByModel.remove(modelName);
                                            stepCursorByModel.remove(modelName);
                                            checkedPlanStepsByModel.remove(modelName);
                                            refreshPlanCodePanel.run();
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
                                        showPlanReadyDialog(frame, () -> {
                                            if (btnGetCode != null) btnGetCode.doClick();
                                        });
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
            SwingUtilities.invokeLater(refreshPlanCodePanel);
            
            uiLogger.accept("=== UI: 点击生成计划 | models=" + selectedModels.size() + " ===");

            for (String model : selectedModels) {
                codeTabs.addTab(model, new JPanel(new BorderLayout()));
            }
            if (codeTabs.getTabCount() > 0) codeTabs.setSelectedIndex(0);

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
                                session.htmlCaptureMode = null;
                                session.htmlA11yInterestingOnly = false;
                                session.stepSnapshots.clear();

                                SwingUtilities.invokeLater(() -> {
                                    int idx = codeTabs.indexOfTab(modelName);
                                    if (idx < 0) {
                                        codeTabs.addTab(modelName, new JPanel(new BorderLayout()));
                                        idx = codeTabs.indexOfTab(modelName);
                                    }
                                    stepStatusByModel.remove(modelName);
                                    stepRunningByModel.remove(modelName);
                                    stepErrorByModel.remove(modelName);
                                    executionSummaryByModel.remove(modelName);
                                    stepCursorByModel.remove(modelName);
                                    checkedPlanStepsByModel.remove(modelName);
                                    refreshPlanCodePanel.run();
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
                                        refreshPlanCodePanel.run();
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
                                    showPlanReadyDialog(frame, () -> {
                                        if (btnGetCode != null) btnGetCode.doClick();
                                    });
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
                    boolean useVisualSupplement = chkUseVisualSupplement.isSelected();
                    java.util.concurrent.atomic.AtomicReference<String> visualDescriptionRef = new java.util.concurrent.atomic.AtomicReference<>(null);
                    Object visualDescriptionLock = new Object();
                    java.util.List<String> refineModels = new java.util.ArrayList<>(needList);
                    traceVisual("PLAN_REFINE_THREAD_START",
                            "epochOk=" + (uiEpoch.get() == planEpoch) +
                                    ", models=" + refineModels.size() +
                                    ", useVisualSupplement=" + useVisualSupplement +
                                    ", currentUrlForRefine=" + (currentUrlForRefine == null ? "" : currentUrlForRefine) +
                                    ", entryInputLen=" + (entryInput == null ? 0 : entryInput.length()));
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
                                        traceVisual("PLAN_REFINE_MODEL_START",
                                                "model=" + modelName +
                                                        ", useVisualSupplement=" + useVisualSupplement +
                                                        ", visualRefNull=" + (visualDescriptionRef.get() == null));
                                        uiLogger.accept("阶段开始: model=" + modelName + ", action=PLAN_REFINE");
                                        uiLogger.accept("PLAN_REFINE Debug: model=" + modelName + ", entryInput='" + entryInput + "'");
                                        String visualDescriptionFinal = null;
                                        if (useVisualSupplement) {
                                            String v = visualDescriptionRef.get();
                                            if (v == null) {
                                                synchronized (visualDescriptionLock) {
                                                    v = visualDescriptionRef.get();
                                                    if (v == null) {
                                                        try {
                                                            traceVisual("PLAN_REFINE_VISUAL_BEGIN", "model=" + modelName);
                                                            ensureLiveRootPage(rootPageRef, connectionRef, forceNewPageOnExecute, hasExecuted, uiLogger);
                                                            refreshRootPageRefIfNeeded(rootPageRef, connectionRef, uiLogger, "PLAN_REFINE 视觉补充截图前刷新页面");
                                                            Page pageForVisual = rootPageRef.get();
                                                            String targetUrlForVisual = PlanRoutingSupport.extractFirstUrlFromText(entryInput);
                                                            if (targetUrlForVisual != null && !targetUrlForVisual.trim().isEmpty()) {
                                                                traceVisual("PLAN_REFINE_VISUAL_TARGET", "targetUrl=" + PlanRoutingSupport.stripUrlQuery(targetUrlForVisual.trim()));
                                                                PlanRoutingSupport.ensureRootPageAtUrl(pageForVisual, targetUrlForVisual.trim(), uiLogger);
                                                            }
                                                            traceVisual("PLAN_REFINE_VISUAL_PAGE",
                                                                    "model=" + modelName +
                                                                            ", pageNull=" + (pageForVisual == null) +
                                                                            ", pageUrl=" + safePageUrl(pageForVisual));
                                                            clearVisualCacheForPage(pageForVisual, uiLogger);
                                                            if (uiLogger != null) uiLogger.accept("视觉补充(PLAN_REFINE): 开始截图");
                                                            v = buildPageVisualDescription(pageForVisual, uiLogger);
                                                            int len = v == null ? 0 : v.length();
                                                            uiLogger.accept("视觉补充(PLAN_REFINE): 已生成页面描述（len=" + len + "）");
                                                            traceVisual("PLAN_REFINE_VISUAL_DONE", "model=" + modelName + ", descLen=" + len);
                                                        } catch (Exception ex) {
                                                            uiLogger.accept("视觉补充(PLAN_REFINE)失败: " + ex.getMessage());
                                                            traceVisual("PLAN_REFINE_VISUAL_ERROR",
                                                                    "model=" + modelName + ", err=" + ex.getClass().getName() + ": " + (ex.getMessage() == null ? "" : ex.getMessage()));
                                                            v = "";
                                                        }
                                                        if (v == null) v = "";
                                                        visualDescriptionRef.set(v);
                                                    }
                                                }
                                            }
                                            visualDescriptionFinal = v;
                                        }
                                        String currentUrlForRefine2 = safePageUrl(rootPageRef.get());
                                        String payload = buildPlanRefinePayload(currentUrlForRefine2, currentPrompt, entryInput, useVisualSupplement ? visualDescriptionFinal : null);
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
                                        session.userPrompt = currentPrompt;
                                        session.planText = parsed.planText;
                                        session.steps = parsed.steps;
                                        session.planConfirmed = parsed.confirmed;
                                        session.lastArtifactType = "PLAN";
                                        session.htmlPrepared = false;
                                        session.htmlCaptureMode = null;
                                        session.htmlA11yInterestingOnly = false;
                                        session.stepSnapshots.clear();
                                        SwingUtilities.invokeLater(() -> {
                                            int idx = codeTabs.indexOfTab(modelName);
                                            if (idx < 0) {
                                                codeTabs.addTab(modelName, new JPanel(new BorderLayout()));
                                            }
                                            refreshPlanCodePanel.run();
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
                                        showPlanReadyDialog(frame, () -> {
                                            if (btnGetCode != null) btnGetCode.doClick();
                                        });
                                    }
                                });
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
            Object[] options = new Object[]{"继续生成", "取消"};
            int choice = JOptionPane.showOptionDialog(
                    frame,
                    tip.toString(),
                    "生成代码检查",
                    JOptionPane.YES_NO_OPTION,
                    JOptionPane.INFORMATION_MESSAGE,
                    null,
                    options,
                    options[0]
            );
            if (choice != 0) {
                return;
            }

            if (readyModels.isEmpty()) {
                return;
            }

            setStage.accept("CODEGEN");
            setActionButtonsEnabled(btnPlan, btnGetCode, btnRefinePlan, btnRefine, btnStepExecute, btnExecute, btnClearAll, false);
            outputArea.setText("");
            hasExecuted.set(false);
            codeTabs.removeAll();
            SwingUtilities.invokeLater(refreshPlanCodePanel);

            uiLogger.accept("=== UI: 点击生成代码 | selectedModels=" + selectedModels.size() + ", readyModels=" + readyModels.size() + " ===");

            for (String model : selectedModels) {
                codeTabs.addTab(model, new JPanel(new BorderLayout()));
            }
            if (codeTabs.getTabCount() > 0) codeTabs.setSelectedIndex(0);

            boolean useVisualSupplement = chkUseVisualSupplement.isSelected();

            new Thread(() -> {
                try {
                    int total = selectedModels.size();
                    int llmThreads = Math.max(1, Math.min(readyModels.size(), selectedModels.size()));
                    
                    java.util.concurrent.ExecutorService htmlExecutor = java.util.concurrent.Executors.newSingleThreadExecutor();
                    java.util.concurrent.ExecutorService llmExecutor = java.util.concurrent.Executors.newFixedThreadPool(llmThreads);
                    java.util.List<java.util.concurrent.Future<?>> futures = new java.util.ArrayList<>();
                    java.util.concurrent.atomic.AtomicReference<String> visualDescriptionRef = new java.util.concurrent.atomic.AtomicReference<>(null);
                    Object visualDescriptionLock = new Object();

                    try {
                        for (int i = 0; i < total; i++) {
                            String modelName = selectedModels.get(i);
                            int order = i + 1;

                            futures.add(llmExecutor.submit(() -> {
                                ModelSession session = sessionsByModel.get(modelName);
                                if (!readyModels.contains(modelName)) {
                                    return;
                                }

                                try {
                                    if (session.steps == null || session.steps.isEmpty()) {
                                        uiLogger.accept(modelName + ": 计划缺少步骤，无法采集 HTML。请重新生成计划。");
                                        return;
                                    }

                                    HtmlCaptureMode modeForSession = chkUseA11yTree.isSelected() ? HtmlCaptureMode.ARIA_SNAPSHOT : HtmlCaptureMode.RAW_HTML;
                                    boolean a11yInterestingOnlyForSession = chkA11yInterestingOnly.isSelected();
                                    boolean needPrepare = !session.htmlPrepared
                                            || session.htmlCaptureMode != modeForSession
                                            || session.htmlA11yInterestingOnly != a11yInterestingOnlyForSession;
                                    if (needPrepare) {
                                        java.util.concurrent.Future<?> htmlFuture = htmlExecutor.submit(() -> {
                                            uiLogger.accept(modelName + ": 开始按计划采集 HTML（Step 数: " + session.steps.size() + "）...");
                                            refreshRootPageRefIfNeeded(rootPageRef, connectionRef, uiLogger, "采集 HTML 前刷新页面");
                                            java.util.List<HtmlSnapshot> snaps = prepareStepHtmls(rootPageRef.get(), session.steps, uiLogger, modeForSession, a11yInterestingOnlyForSession);
                                            java.util.Map<Integer, HtmlSnapshot> map = new java.util.HashMap<>();
                                            for (HtmlSnapshot s : snaps) map.put(s.stepIndex, s);
                                            session.stepSnapshots = map;
                                            session.htmlPrepared = true;
                                            session.htmlCaptureMode = modeForSession;
                                            session.htmlA11yInterestingOnly = a11yInterestingOnlyForSession;
                                            uiLogger.accept(modelName + ": HTML 采集完成（snapshots=" + session.stepSnapshots.size() + "）");
                                            return null;
                                        });
                                        try { htmlFuture.get(); } catch (Exception ignored) {}
                                    }

                                    java.util.List<HtmlSnapshot> snaps = new java.util.ArrayList<>(session.stepSnapshots.values());
                                    snaps.sort(java.util.Comparator.comparingInt(a -> a.stepIndex));
                                    
                                    refreshRootPageRefIfNeeded(rootPageRef, connectionRef, uiLogger, "生成 Payload 前刷新页面");
                                    String visualDescriptionFinal = null;
                                    if (useVisualSupplement) {
                                        String v = visualDescriptionRef.get();
                                        if (v == null) {
                                            synchronized (visualDescriptionLock) {
                                                v = visualDescriptionRef.get();
                                                if (v == null) {
                                                    try {
                                                        ensureLiveRootPage(rootPageRef, connectionRef, forceNewPageOnExecute, hasExecuted, uiLogger);
                                                        refreshRootPageRefIfNeeded(rootPageRef, connectionRef, uiLogger, "视觉补充截图前刷新页面");
                                                        v = buildPageVisualDescription(rootPageRef.get(), uiLogger);
                                                        int len = v == null ? 0 : v.length();
                                                        uiLogger.accept("视觉补充(CODEGEN): 已生成页面描述（len=" + len + "）");
                                                    } catch (Exception ex) {
                                                        uiLogger.accept("视觉补充(CODEGEN)失败: " + ex.getMessage());
                                                        v = "";
                                                    }
                                                    if (v == null) v = "";
                                                    visualDescriptionRef.set(v);
                                                }
                                            }
                                        }
                                        visualDescriptionFinal = v;
                                    }
                                    String payload = buildCodegenPayload(rootPageRef.get(), session.planText, snaps, useVisualSupplement ? visualDescriptionFinal : null);
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

                                    generatedCode = repairStepMarkersIfNeeded(
                                            modelName,
                                            currentPrompt,
                                            session,
                                            rootPageRef.get(),
                                            snaps,
                                            useVisualSupplement,
                                            visualDescriptionFinal,
                                            generatedCode,
                                            uiLogger
                                    );

                                    String finalCode = generatedCode == null ? "" : generatedCode;
                                    AutoWebAgentUtils.saveDebugCodeVariant(finalCode, modelName, "gen", uiLogger);
                                    session.lastArtifactType = "CODE";

                                    SwingUtilities.invokeLater(() -> {
                                        int idx = codeTabs.indexOfTab(modelName);
                                        if (idx < 0) {
                                            codeTabs.addTab(modelName, new JPanel(new BorderLayout()));
                                            idx = codeTabs.indexOfTab(modelName);
                                        }
                                        if (idx >= 0) codeTabs.setSelectedIndex(idx);
                                        latestCodeByModel.put(modelName, finalCode);
                                        stepStatusByModel.remove(modelName);
                                        stepCursorByModel.remove(modelName);
                                        refreshPlanCodePanel.run();
                                    });
                                } catch (Exception ex) {
                                    try {
                                        uiLogger.accept("CODEGEN 失败: model=" + modelName + ", err=" + ex.getMessage());
                                        saveDebugArtifact(newDebugTimestamp(), modelName, "CODEGEN", "exception", stackTraceToString(ex), uiLogger);
                                    } catch (Exception ignored) {}
                                    SwingUtilities.invokeLater(() -> {
                                        refreshPlanCodePanel.run();
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
            String previousCode = latestCodeByModel.getOrDefault(modelName, "");

            String currentPrompt = promptArea.getText();
            String refineHint = refineArea.getText();
            String focusHint = buildRefineFocusHintFromSelection(planCodeTable, planCodeTableModel);
            String refineHintForModel;
            if (focusHint == null || focusHint.trim().isEmpty()) {
                refineHintForModel = refineHint;
            } else if (refineHint == null || refineHint.trim().isEmpty()) {
                refineHintForModel = focusHint;
            } else {
                refineHintForModel = refineHint + "\n\n" + focusHint;
            }
            String execOutput = outputArea.getText();

            if (previousCode == null || previousCode.trim().isEmpty()) {
                JOptionPane.showMessageDialog(frame, "当前没有可用于修正的代码。", "提示", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            if (refineHintForModel == null || refineHintForModel.trim().isEmpty()) {
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
                    boolean useVisualSupplement = chkUseVisualSupplement.isSelected();
                    String visualDescriptionForRefine = "";
                    if (useVisualSupplement) {
                        try {
                            visualDescriptionForRefine = readCachedPageVisualDescription(rootPageRef.get(), uiLogger);
                        } catch (Exception ex) {
                            uiLogger.accept("视觉补充(REFINE_CODE)失败: " + ex.getMessage());
                            visualDescriptionForRefine = "";
                        }
                    }
                    final String visualDescriptionForRefineFinal = visualDescriptionForRefine;
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

                    if (session.planConfirmed) {
                        boolean a11yInterestingOnly2 = chkA11yInterestingOnly.isSelected();
                        boolean needPrepare = !session.htmlPrepared
                                || session.htmlCaptureMode != mode
                                || session.htmlA11yInterestingOnly != a11yInterestingOnly2;
                        if (needPrepare) {
                            java.util.List<HtmlSnapshot> snaps = prepareStepHtmls(rootPageRef.get(), session.steps, uiLogger, mode, a11yInterestingOnly2);
                        java.util.Map<Integer, HtmlSnapshot> map = new java.util.HashMap<>();
                        for (HtmlSnapshot s : snaps) map.put(s.stepIndex, s);
                        session.stepSnapshots = map;
                        session.htmlPrepared = true;
                            session.htmlCaptureMode = mode;
                            session.htmlA11yInterestingOnly = a11yInterestingOnly2;
                        }
                    }

                    java.util.List<HtmlSnapshot> stepSnaps = new java.util.ArrayList<>(session.stepSnapshots.values());
                    stepSnaps.sort(java.util.Comparator.comparingInt(a -> a.stepIndex));
                    String payload = buildRefinePayload(rootPageRef.get(), session.planText, stepSnaps, freshCleanedHtml, currentPrompt, refineHintForModel, useVisualSupplement ? visualDescriptionForRefineFinal : null);
                    uiLogger.accept("阶段中: model=" + modelName + ", action=REFINE_CODE, payloadMode=" + extractModeFromPayload(payload) + ", snapshots=" + stepSnaps.size());
                    String promptForRefine = currentPrompt;
                    try {
                        if (session.userPrompt != null && !session.userPrompt.equals(currentPrompt)) {
                            promptForRefine = "原用户任务:\n" + session.userPrompt + "\n\n当前用户任务:\n" + currentPrompt;
                        }
                    } catch (Exception ignored) {}
                    String refinedCode = generateRefinedGroovyScript(
                            promptForRefine, payload, previousCode, execOutput, refineHintForModel, uiLogger, modelName
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
                        latestCodeByModel.put(modelName, finalRefinedCode);
                                        stepStatusByModel.remove(modelName);
                                        stepErrorByModel.remove(modelName);
                                        executionSummaryByModel.remove(modelName);
                        stepCursorByModel.remove(modelName);
                        refreshPlanCodePanel.run();
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

        btnStepExecute.addActionListener(e -> {
            int selectedIndex = codeTabs.getSelectedIndex();
            if (selectedIndex < 0) {
                JOptionPane.showMessageDialog(frame, "请先选择一个包含代码的标签页。", "提示", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            String modelName = codeTabs.getTitleAt(selectedIndex);
            String code = latestCodeByModel.getOrDefault(modelName, "");
            if (code == null || code.trim().isEmpty()) {
                JOptionPane.showMessageDialog(frame, "当前没有可执行的代码。", "提示", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            ModelSession session = sessionsByModel.get(modelName);
            java.util.List<PlanStep> steps = getStepsForStepExecution(session, code);
            if (steps.isEmpty()) {
                JOptionPane.showMessageDialog(frame, "未找到可分步执行的步骤。", "提示", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            java.util.Set<Integer> checkedSteps = checkedPlanStepsByModel.getOrDefault(modelName, java.util.Collections.emptySet());
            if (checkedSteps == null || checkedSteps.isEmpty()) {
                JOptionPane.showMessageDialog(frame, "请在右边选择一个或者多个步骤，然后点击执行", "提示", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            stepStatusByModel.remove(modelName);
            stepRunningByModel.remove(modelName);
            stepErrorByModel.remove(modelName);
            executionSummaryByModel.remove(modelName);
            stepCursorByModel.put(modelName, 0);
            outputArea.setText("");
            SwingUtilities.invokeLater(refreshPlanCodePanel);
            java.util.List<PlanStep> selectedSteps = new java.util.ArrayList<>();
            for (PlanStep step : steps) {
                if (step != null && checkedSteps.contains(step.index)) {
                    selectedSteps.add(step);
                }
            }
            if (selectedSteps.isEmpty()) {
                JOptionPane.showMessageDialog(frame, "选中的步骤在当前代码中未找到。", "提示", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            boolean hasMarkersForStepRun = hasExplicitStepMarkers(code);
            if (!hasMarkersForStepRun) {
                int confirm = JOptionPane.showConfirmDialog(
                        frame,
                        "当前代码未按 // Step N 分段，无法只执行选中步骤。\n将改为整段脚本一次性执行。\n\n是否继续？",
                        "执行确认",
                        JOptionPane.YES_NO_OPTION,
                        JOptionPane.WARNING_MESSAGE
                );
                if (confirm != JOptionPane.YES_OPTION) {
                    return;
                }
            }
            boolean singleSelected = selectedSteps.size() == 1;
            lastStepExecSingleByModel.put(modelName, singleSelected);
            java.util.Map<Integer, Boolean> statusMap = stepStatusByModel.computeIfAbsent(modelName, k -> new java.util.HashMap<>());
            java.util.Map<Integer, Boolean> runningMap = stepRunningByModel.computeIfAbsent(modelName, k -> new java.util.HashMap<>());
            java.util.Map<Integer, String> errorMap = stepErrorByModel.computeIfAbsent(modelName, k -> new java.util.HashMap<>());
            stepCursorByModel.put(modelName, 0);
            SwingUtilities.invokeLater(refreshPlanCodePanel);
            setActionButtonsEnabled(btnPlan, btnGetCode, btnRefinePlan, btnRefine, btnStepExecute, btnExecute, btnClearAll, false);
            lockCodeTabs.run();
            collapseLeftPanel.run();
            uiLogger.accept("=== 分步执行开始: 已选步骤 " + selectedSteps.size() + " ===");
            setStage.accept("EXECUTING");
            final long execEpoch = uiEpoch.incrementAndGet();
            Thread execThread = new Thread(() -> {
                try {
                    if (uiEpoch.get() != execEpoch) return;
                    String currentPrompt = promptArea.getText();
                    String entryUrl = chooseExecutionEntryUrl(session, currentPrompt);
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
                    boolean hasMarkers = hasExplicitStepMarkers(code);
                    if (!hasMarkers) {
                        uiLogger.accept("检测到代码缺少 Step 分段标记，将按整段脚本一次性执行。");
                        for (PlanStep step : selectedSteps) {
                            if (step == null) continue;
                            runningMap.put(step.index, true);
                        }
                        SwingUtilities.invokeLater(refreshPlanCodePanel);
                        try {
                            ContextWrapper bestContext = waitAndFindContext(liveRootPage, uiLogger);
                            Object executionTarget = bestContext == null ? liveRootPage : bestContext.context;
                            executeWithGroovy(code, executionTarget, uiLogger);
                            hasExecuted.set(true);
                            for (PlanStep step : selectedSteps) {
                                if (step == null) continue;
                                statusMap.put(step.index, true);
                                errorMap.remove(step.index);
                            }
                            stepCursorByModel.put(modelName, selectedSteps.size());
                            uiLogger.accept("=== 整段执行完成（无 Step 分段） ===");
                        } catch (Exception ex) {
                            String reason = ex.getMessage();
                            if (reason == null || reason.trim().isEmpty()) reason = ex.toString();
                            for (PlanStep step : selectedSteps) {
                                if (step == null) continue;
                                statusMap.put(step.index, false);
                                errorMap.put(step.index, reason);
                            }
                            uiLogger.accept("=== 整段执行失败（无 Step 分段）: " + reason + " ===");
                        } finally {
                            for (PlanStep step : selectedSteps) {
                                if (step == null) continue;
                                runningMap.remove(step.index);
                            }
                            executionSummaryByModel.put(modelName, buildExecutionSummary(statusMap, errorMap));
                            SwingUtilities.invokeLater(refreshPlanCodePanel);
                        }
                        return;
                    }
                    for (int i = 0; i < selectedSteps.size(); i++) {
                        if (uiEpoch.get() != execEpoch || Thread.currentThread().isInterrupted()) return;
                        PlanStep step = selectedSteps.get(i);
                        if (step == null) continue;
                        int stepIndex = step.index;
                        String stepCode = buildStepExecutionCode(code, stepIndex);
                        if (stepCode == null || stepCode.trim().isEmpty()) {
                            statusMap.put(stepIndex, true);
                            errorMap.remove(stepIndex);
                            stepCursorByModel.put(modelName, i + 1);
                            executionSummaryByModel.put(modelName, buildExecutionSummary(statusMap, errorMap));
                            SwingUtilities.invokeLater(refreshPlanCodePanel);
                            uiLogger.accept("=== 分步执行跳过: Step " + stepIndex + " | 无可执行代码，视为成功 ===");
                            continue;
                        }
                        uiLogger.accept("=== 分步执行开始: Step " + stepIndex + " ===");
                        runningMap.put(stepIndex, true);
                        SwingUtilities.invokeLater(refreshPlanCodePanel);
                        try {
                            ContextWrapper bestContext = waitAndFindContext(liveRootPage, uiLogger);
                            Object executionTarget = bestContext == null ? liveRootPage : bestContext.context;
                            executeWithGroovy(stepCode, executionTarget, uiLogger);
                            hasExecuted.set(true);
                            statusMap.put(stepIndex, true);
                            errorMap.remove(stepIndex);
                            stepCursorByModel.put(modelName, i + 1);
                            uiLogger.accept("=== 分步执行完成: Step " + stepIndex + " ===");
                        } catch (Exception stepEx) {
                            statusMap.put(stepIndex, false);
                            String reason = stepEx.getMessage();
                            if (reason == null || reason.trim().isEmpty()) reason = stepEx.toString();
                            errorMap.put(stepIndex, reason);
                            uiLogger.accept("=== 分步执行失败: Step " + stepIndex + " | " + reason + " ===");
                        } finally {
                            runningMap.remove(stepIndex);
                        }
                        executionSummaryByModel.put(modelName, buildExecutionSummary(statusMap, errorMap));
                        SwingUtilities.invokeLater(refreshPlanCodePanel);
                    }
                } catch (Exception ex) {
                    if (uiEpoch.get() == execEpoch) uiLogger.accept("=== 执行失败: " + ex.getMessage() + " ===");
                } finally {
                    runningThreadByModel.remove(modelName);
                    if (uiEpoch.get() == execEpoch) {
                        SwingUtilities.invokeLater(() -> {
                            unlockCodeTabs.run();
                            setActionButtonsEnabled(btnPlan, btnGetCode, btnRefinePlan, btnRefine, btnStepExecute, btnExecute, btnClearAll, true);
                            if (mainHorizontalCollapsed.get()) expandLeftPanel.run();
                            btnToggleLeftPanel.setEnabled(true);
                        });
                        setStage.accept("READY_EXECUTE");
                    }
                    checkedPlanStepsByModel.remove(modelName);
                    SwingUtilities.invokeLater(refreshPlanCodePanel);
                }
            });
            runningThreadByModel.put(modelName, execThread);
            execThread.start();
            return;
        });

        btnExecute.addActionListener(e -> {
            int selectedIndex = codeTabs.getSelectedIndex();
            if (selectedIndex < 0) {
                JOptionPane.showMessageDialog(frame, "请先选择一个包含代码的标签页。", "提示", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            String modelName = codeTabs.getTitleAt(selectedIndex);
            String code = latestCodeByModel.getOrDefault(modelName, "");

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

            ModelSession session = sessionsByModel.get(modelName);
            java.util.List<PlanStep> steps = getStepsForStepExecution(session, code);
            if (steps.isEmpty()) {
                JOptionPane.showMessageDialog(frame, "未找到可分步执行的步骤。", "提示", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            stepStatusByModel.remove(modelName);
            stepRunningByModel.remove(modelName);
            stepErrorByModel.remove(modelName);
            executionSummaryByModel.remove(modelName);
            java.util.Map<Integer, Boolean> statusMap = stepStatusByModel.computeIfAbsent(modelName, k -> new java.util.HashMap<>());
            java.util.Map<Integer, Boolean> runningMap = stepRunningByModel.computeIfAbsent(modelName, k -> new java.util.HashMap<>());
            java.util.Map<Integer, String> errorMap = stepErrorByModel.computeIfAbsent(modelName, k -> new java.util.HashMap<>());
            stepCursorByModel.put(modelName, 0);
            executionSummaryByModel.put(modelName, "执行中：准备中（0/" + steps.size() + "）");
            SwingUtilities.invokeLater(refreshPlanCodePanel);

            setActionButtonsEnabled(btnPlan, btnGetCode, btnRefinePlan, btnRefine, btnStepExecute, btnExecute, btnClearAll, false);
            lockCodeTabs.run();
            collapseLeftPanel.run();
            outputArea.setText(""); 
            uiLogger.accept("=== 开始执行代码 ===");
            setStage.accept("EXECUTING");
            
            final long execEpoch = uiEpoch.incrementAndGet();
            Thread execThread = new Thread(() -> {
                try {
                    if (uiEpoch.get() != execEpoch) return;
                    String currentPrompt = promptArea.getText();
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
                    boolean hasMarkers = hasExplicitStepMarkers(code);
                    if (!hasMarkers) {
                        uiLogger.accept("检测到代码缺少 Step 分段标记，将按整段脚本一次性执行。");
                        SwingUtilities.invokeLater(refreshPlanCodePanel);
                        try {
                            for (PlanStep step : steps) {
                                if (step == null) continue;
                                runningMap.put(step.index, true);
                            }
                            String progressText = "执行中：整段执行（无 Step 分段）";
                            executionSummaryByModel.put(modelName, progressText);
                            SwingUtilities.invokeLater(refreshPlanCodePanel);

                            ContextWrapper bestContext = waitAndFindContext(liveRootPage, uiLogger);
                            Object executionTarget = bestContext == null ? liveRootPage : bestContext.context;
                            executeWithGroovy(code, executionTarget, uiLogger);
                            hasExecuted.set(true);
                            for (PlanStep step : steps) {
                                if (step == null) continue;
                                statusMap.put(step.index, true);
                                errorMap.remove(step.index);
                            }
                            stepCursorByModel.put(modelName, steps.size());
                            uiLogger.accept("=== 整段执行完成（无 Step 分段） ===");
                        } catch (Exception ex) {
                            String reason = ex.getMessage();
                            if (reason == null || reason.trim().isEmpty()) reason = ex.toString();
                            for (PlanStep step : steps) {
                                if (step == null) continue;
                                statusMap.put(step.index, false);
                                errorMap.put(step.index, reason);
                            }
                            uiLogger.accept("=== 整段执行失败（无 Step 分段）: " + reason + " ===");
                        } finally {
                            for (PlanStep step : steps) {
                                if (step == null) continue;
                                runningMap.remove(step.index);
                            }
                            executionSummaryByModel.put(modelName, buildExecutionSummary(statusMap, errorMap));
                            SwingUtilities.invokeLater(refreshPlanCodePanel);
                        }
                        return;
                    }
                    for (int i = 0; i < steps.size(); i++) {
                        if (uiEpoch.get() != execEpoch || Thread.currentThread().isInterrupted()) return;
                        PlanStep step = steps.get(i);
                        if (step == null) continue;
                        int stepIndex = step.index;
                        String stepCode = buildStepExecutionCode(code, stepIndex);
                        if (stepCode == null || stepCode.trim().isEmpty()) {
                            statusMap.put(stepIndex, true);
                            errorMap.remove(stepIndex);
                            stepCursorByModel.put(modelName, i + 1);
                            executionSummaryByModel.put(modelName, buildExecutionSummary(statusMap, errorMap));
                            uiLogger.accept("=== 分步执行跳过: Step " + stepIndex + " | 无可执行代码，视为成功 ===");
                            SwingUtilities.invokeLater(refreshPlanCodePanel);
                            continue;
                        }
                        uiLogger.accept("=== 分步执行开始: Step " + stepIndex + " ===");
                        try {
                            runningMap.put(stepIndex, true);
                            String partialSummary = buildExecutionSummary(statusMap, errorMap);
                            String progressText = "执行中：步骤" + stepIndex + "（" + (i + 1) + "/" + steps.size() + "）";
                            if (partialSummary != null && !partialSummary.trim().isEmpty()) {
                                progressText = progressText + "\n\n" + partialSummary;
                            }
                            executionSummaryByModel.put(modelName, progressText);
                            SwingUtilities.invokeLater(refreshPlanCodePanel);
                            ContextWrapper bestContext = waitAndFindContext(liveRootPage, uiLogger);
                            Object executionTarget = bestContext == null ? liveRootPage : bestContext.context;
                            executeWithGroovy(stepCode, executionTarget, uiLogger);
                            hasExecuted.set(true);
                            statusMap.put(stepIndex, true);
                            errorMap.remove(stepIndex);
                            stepCursorByModel.put(modelName, i + 1);
                            uiLogger.accept("=== 分步执行完成: Step " + stepIndex + " ===");
                        } catch (Exception stepEx) {
                            statusMap.put(stepIndex, false);
                            String reason = stepEx.getMessage();
                            if (reason == null || reason.trim().isEmpty()) reason = stepEx.toString();
                            errorMap.put(stepIndex, reason);
                            uiLogger.accept("=== 分步执行失败: Step " + stepIndex + " | " + reason + " ===");
                            SwingUtilities.invokeLater(refreshPlanCodePanel);
                            continue;
                        } finally {
                            runningMap.remove(stepIndex);
                        }
                        executionSummaryByModel.put(modelName, buildExecutionSummary(statusMap, errorMap));
                        SwingUtilities.invokeLater(refreshPlanCodePanel);
                    }
                    executionSummaryByModel.put(modelName, buildExecutionSummary(statusMap, errorMap));
                } catch (Exception ex) {
                    if (uiEpoch.get() == execEpoch) {
                        executionSummaryByModel.put(modelName, "执行失败：" + (ex.getMessage() == null ? "未知原因" : ex.getMessage()));
                        SwingUtilities.invokeLater(refreshPlanCodePanel);
                        uiLogger.accept("=== 执行失败: " + ex.getMessage() + " ===");
                    }
                } finally {
                    runningThreadByModel.remove(modelName);
                    if (uiEpoch.get() == execEpoch) {
                        SwingUtilities.invokeLater(() -> {
                            unlockCodeTabs.run();
                            setActionButtonsEnabled(btnPlan, btnGetCode, btnRefinePlan, btnRefine, btnStepExecute, btnExecute, btnClearAll, true);
                            if (mainHorizontalCollapsed.get()) expandLeftPanel.run();
                            btnToggleLeftPanel.setEnabled(true);
                        });
                        setStage.accept("READY_EXECUTE");
                        uiLogger.accept("=== 执行完成 ===");
                    }
                }
            });
            runningThreadByModel.put(modelName, execThread);
            execThread.start();
        });

        Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
        int width = (int) (screenSize.width * (5.0 / 6.0));
        int height = (int) (screenSize.height * (5.0 / 6.0));
        frame.setSize(width, height);
        frame.setLocation((screenSize.width - width) / 2, (screenSize.height - height) / 2);
        frame.setVisible(true);
        frame.addComponentListener(new ComponentAdapter() {
            public void componentResized(ComponentEvent e) {
                if (!mainHorizontalCollapsed.get()) {
                    mainHorizontalSplit.setDividerLocation(0.5);
                }
            }
        });
        SwingUtilities.invokeLater(() -> {
            if (!mainHorizontalCollapsed.get()) {
                mainHorizontalSplit.setDividerLocation(0.5);
            }
        });
    }

    /**
     * 获取用于分步执行的步骤列表（优先使用会话缓存，必要时从计划文本解析）
     *
     * @param session 模型会话
     * @param code 当前代码/计划文本
     * @return 排序后的步骤列表
     */
    private static java.util.List<PlanStep> getStepsForStepExecution(ModelSession session, String code) {
        java.util.List<PlanStep> steps = session == null ? null : session.steps;
        if (steps == null || steps.isEmpty()) {
            PlanParseResult parsed = parsePlanFromText(code);
            steps = parsed == null ? null : parsed.steps;
        }
        if (steps == null || steps.isEmpty()) return java.util.Collections.emptyList();
        java.util.List<PlanStep> sorted = new java.util.ArrayList<>(steps);
        sorted.sort(java.util.Comparator.comparingInt(a -> a == null ? Integer.MAX_VALUE : a.index));
        return sorted;
    }

    /**
     * 汇总步骤执行结果，生成展示区的执行摘要文本
     */
    private static String buildExecutionSummary(java.util.Map<Integer, Boolean> statusMap, java.util.Map<Integer, String> errorMap) {
        if (statusMap == null || statusMap.isEmpty()) return "";
        int success = 0;
        int fail = 0;
        java.util.List<Integer> successSteps = new java.util.ArrayList<>();
        java.util.List<Integer> failSteps = new java.util.ArrayList<>();
        for (java.util.Map.Entry<Integer, Boolean> entry : statusMap.entrySet()) {
            if (Boolean.TRUE.equals(entry.getValue())) {
                success++;
                if (entry.getKey() != null) successSteps.add(entry.getKey());
            } else {
                fail++;
                if (entry.getKey() != null) failSteps.add(entry.getKey());
            }
        }
        java.util.Collections.sort(successSteps);
        java.util.Collections.sort(failSteps);
        StringBuilder sb = new StringBuilder();
        sb.append("执行成功").append(success).append("步，执行失败").append(fail).append("步");
        for (Integer stepIndex : successSteps) {
            sb.append("\n【执行成功：步骤").append(stepIndex).append("】");
        }
        for (Integer stepIndex : failSteps) {
            String reason = errorMap == null ? null : errorMap.get(stepIndex);
            if (reason == null || reason.trim().isEmpty()) reason = "未知原因";
            sb.append("\n【执行失败：步骤").append(stepIndex).append("，原因：").append(reason).append("】");
        }
        return sb.toString();
    }

    /**
     * 从完整代码中抽取指定步骤对应的可执行片段
     */
    private static String buildStepExecutionCode(String code, int stepIndex) {
        if (code == null || code.trim().isEmpty()) return "";
        String src = stripPlanBlock(code);
        // 支持 //、/* */、*、# 等多种 Step 标记前缀
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("(?mi)^\\s*(?:/\\*+\\s*)?(?:\\*+\\s*)?(?://\\s*)?(?:#+\\s*)?(?:[-–—*>•]+\\s*)?(?:(?:Step|步骤)\\s*[:：#\\-]?\\s*(\\d+)|第\\s*(\\d+)\\s*(?:步|步骤)|Part\\s*[:：#\\-]?\\s*(\\d+)).*$");
        java.util.regex.Matcher m = p.matcher(src);
        java.util.List<Integer> starts = new java.util.ArrayList<>();
        java.util.List<Integer> nums = new java.util.ArrayList<>();
        while (m.find()) {
            starts.add(m.start());
            try {
                String token = null;
                try { token = m.group(1); } catch (Exception ignored) {}
                if (token == null || token.trim().isEmpty()) {
                    try { token = m.group(2); } catch (Exception ignored) {}
                }
                if (token == null || token.trim().isEmpty()) {
                    try { token = m.group(3); } catch (Exception ignored) {}
                }
                Integer n = parseUnicodeInt(token);
                nums.add(n == null ? (nums.size() + 1) : n);
            } catch (Exception ignored) {
                nums.add(nums.size() + 1);
            }
        }
        if (starts.isEmpty()) return "";
        starts.add(src.length());
        int targetStart = -1;
        int targetEnd = -1;
        for (int i = 0; i < nums.size(); i++) {
            if (nums.get(i) == stepIndex) {
                targetStart = starts.get(i);
                targetEnd = starts.get(i + 1);
                break;
            }
        }
        if (targetStart < 0 || targetEnd < 0) return "";
        String prelude = src.substring(0, starts.get(0));
        String block = src.substring(targetStart, Math.min(targetEnd, src.length()));
        String blockNonComment = block;
        try {
            blockNonComment = blockNonComment.replaceAll("(?s)/\\*.*?\\*/", " ");
            blockNonComment = blockNonComment.replaceAll("(?m)^\\s*//.*$", " ");
        } catch (Exception ignored) {}
        if (blockNonComment == null || blockNonComment.trim().isEmpty()) return "";
        if (!prelude.isEmpty() && !prelude.endsWith("\n")) prelude = prelude + "\n";
        return prelude + block;
    }

    private static boolean hasExplicitStepMarkers(String code) {
        if (code == null || code.trim().isEmpty()) return false;
        String src = stripPlanBlock(code);
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("(?mi)^\\s*(?:/\\*+\\s*)?(?:\\*+\\s*)?(?://\\s*)?(?:#+\\s*)?(?:[-–—*>•]+\\s*)?(?:(?:Step|步骤)\\s*[:：#\\-]?\\s*(\\d+)|第\\s*(\\d+)\\s*(?:步|步骤)|Part\\s*[:：#\\-]?\\s*(\\d+)).*$");
        return p.matcher(src).find();
    }

    private static String repairStepMarkersIfNeeded(
            String modelName,
            String currentPrompt,
            ModelSession session,
            Page currentPage,
            java.util.List<HtmlSnapshot> snaps,
            boolean useVisualSupplement,
            String visualDescriptionFinal,
            String generatedCode,
            java.util.function.Consumer<String> uiLogger
    ) {
        if (generatedCode == null || generatedCode.trim().isEmpty()) return generatedCode;
        if (hasExplicitStepMarkers(generatedCode)) return generatedCode;
        if (session == null || session.steps == null || session.steps.size() <= 1) return generatedCode;
        boolean looksLikePlanOnly = generatedCode.contains("PLAN_START") && !generatedCode.contains("web.");
        if (looksLikePlanOnly) return generatedCode;

        String refineHintForModel =
                "仅修复输出格式，不要改变执行逻辑。\n" +
                "要求：在每个步骤对应的可执行代码前，加入单行注释标记：// Step N（N=1..步骤数），并且顺序与计划一致。\n" +
                "输出必须是完整、可直接执行的 Groovy 脚本（包含原有 PLAN 块注释），不要输出 Markdown，不要省略任何步骤。";

        try {
            uiLogger.accept("CODEGEN 输出缺少 Step 标记，开始自动修复格式: model=" + modelName + ", steps=" + session.steps.size());
            String payload = buildRefinePayload(
                    currentPage,
                    session.planText,
                    snaps,
                    "",
                    currentPrompt,
                    refineHintForModel,
                    useVisualSupplement ? visualDescriptionFinal : null
            );
            String repaired = generateRefinedGroovyScript(
                    currentPrompt,
                    payload,
                    generatedCode,
                    "",
                    refineHintForModel,
                    uiLogger,
                    modelName
            );
            String normalizedRepaired = normalizeGeneratedGroovy(repaired);
            if (normalizedRepaired != null && !normalizedRepaired.equals(repaired)) {
                java.util.List<String> normalizeErrors = GroovyLinter.check(normalizedRepaired);
                boolean hasSyntaxIssue = normalizeErrors.stream().anyMatch(e2 -> e2.startsWith("Syntax Error") || e2.startsWith("Parse Error"));
                if (!hasSyntaxIssue) {
                    repaired = normalizedRepaired;
                }
            }
            if (repaired != null && hasExplicitStepMarkers(repaired)) {
                AutoWebAgentUtils.saveDebugCodeVariant(repaired, modelName, "gen_repair", uiLogger);
                uiLogger.accept("CODEGEN 自动修复完成: model=" + modelName);
                return repaired;
            }
            uiLogger.accept("CODEGEN 自动修复未生效: model=" + modelName);
        } catch (Exception ex) {
            try { uiLogger.accept("CODEGEN 自动修复失败: model=" + modelName + ", err=" + ex.getMessage()); } catch (Exception ignored) {}
        }
        return generatedCode;
    }

    /**
     * 构建 Plan 列表展示文本（按步骤顺序）
     */
    private static java.util.List<String> buildPlanStepRows(ModelSession session, String code) {
        java.util.List<PlanStep> steps = session == null ? null : session.steps;
        if (steps == null || steps.isEmpty()) {
            PlanParseResult parsed = parsePlanFromText(code);
            steps = parsed == null ? null : parsed.steps;
        }
        if (steps == null || steps.isEmpty()) return java.util.Collections.emptyList();
        java.util.List<PlanStep> stepsSorted = new java.util.ArrayList<>(steps);
        stepsSorted.sort(java.util.Comparator.comparingInt(a -> a == null ? Integer.MAX_VALUE : a.index));
        java.util.List<String> rows = new java.util.ArrayList<>();
        for (PlanStep step : stepsSorted) {
            if (step == null) continue;
            String desc = step.description == null ? "" : step.description.trim();
            String url = step.targetUrl == null ? "" : step.targetUrl.trim();
            String entry = step.entryAction == null ? "" : step.entryAction.trim();
            String status = step.status == null ? "" : step.status.trim();
            StringBuilder line = new StringBuilder();
            line.append("Step ").append(step.index).append(": ");
            if (!desc.isEmpty()) line.append(desc);
            if (!url.isEmpty()) line.append(" | URL=").append(url);
            if (!entry.isEmpty()) line.append(" | Entry=").append(entry);
            if (!status.isEmpty()) line.append(" | Status=").append(status);
            rows.add(line.toString());
        }
        return rows;
    }

    /**
     * 构建 Code 列表展示文本（按步骤顺序）
     */
    private static java.util.List<String> buildCodeStepRows(String code, ModelSession session) {
        if (code == null || code.trim().isEmpty()) return java.util.Collections.emptyList();
        boolean looksLikePlanOnly = code.contains("PLAN_START") && !code.contains("web.");
        if (looksLikePlanOnly) return java.util.Collections.emptyList();
        java.util.List<PlanStep> steps = session == null ? null : session.steps;
        if (steps == null || steps.isEmpty()) {
            PlanParseResult parsed = parsePlanFromText(code);
            steps = parsed == null ? null : parsed.steps;
        }
        java.util.Map<Integer, String> mapped = extractCodeByStep(stripPlanBlock(code));
        if (steps == null || steps.isEmpty()) {
            if (mapped.isEmpty()) return java.util.Collections.emptyList();
            java.util.List<Integer> order = new java.util.ArrayList<>(mapped.keySet());
            order.sort(Integer::compareTo);
            java.util.List<String> rows = new java.util.ArrayList<>();
            for (Integer idx : order) {
                if (idx == null) continue;
                String line = mapped.get(idx);
                if (line == null) line = "";
                if (line.trim().isEmpty()) line = "（未生成可执行代码）";
                rows.add("Step " + idx + ": " + line);
            }
            return rows;
        }
        java.util.List<PlanStep> sorted = new java.util.ArrayList<>(steps);
        sorted.sort(java.util.Comparator.comparingInt(a -> a == null ? Integer.MAX_VALUE : a.index));
        java.util.List<String> rows = new java.util.ArrayList<>();
        for (PlanStep step : sorted) {
            if (step == null) continue;
            String line = mapped.get(step.index);
            if (line == null) line = "";
            if (line.trim().isEmpty()) line = "（未生成可执行代码）";
            rows.add("Step " + step.index + ": " + line);
        }
        return rows;
    }

    /**
     * 解析代码中的 Step 标记并按步骤编号归档
     */
    private static java.util.Map<Integer, String> extractCodeByStep(String code) {
        java.util.Map<Integer, String> res = new java.util.HashMap<>();
        if (code == null || code.trim().isEmpty()) return res;
        String src = stripPlanBlock(code);
        // 支持 //、/* */、*、# 等多种 Step 标记前缀
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("(?mi)^\\s*(?:/\\*+\\s*)?(?:\\*+\\s*)?(?://\\s*)?(?:#+\\s*)?(?:[-–—*>•]+\\s*)?(?:(?:Step|步骤)\\s*[:：#\\-]?\\s*(\\d+)|第\\s*(\\d+)\\s*(?:步|步骤)|Part\\s*[:：#\\-]?\\s*(\\d+)).*$");
        java.util.regex.Matcher m = p.matcher(src);
        java.util.List<Integer> starts = new java.util.ArrayList<>();
        java.util.List<Integer> nums = new java.util.ArrayList<>();
        while (m.find()) {
            starts.add(m.start());
            try {
                String token = null;
                try { token = m.group(1); } catch (Exception ignored) {}
                if (token == null || token.trim().isEmpty()) {
                    try { token = m.group(2); } catch (Exception ignored) {}
                }
                if (token == null || token.trim().isEmpty()) {
                    try { token = m.group(3); } catch (Exception ignored) {}
                }
                Integer n = parseUnicodeInt(token);
                nums.add(n == null ? (nums.size() + 1) : n);
            } catch (Exception ignored) {
                nums.add(nums.size() + 1);
            }
        }
        if (starts.isEmpty()) {
            String body = src == null ? "" : src.trim();
            if (!body.isEmpty()) {
                boolean looksExecutable = false;
                try {
                    looksExecutable = body.contains("web.")
                            || body.matches("(?s).*\\b(import|def|class)\\b.*")
                            || body.matches("(?s).*\\b(if|for|while|try)\\b\\s*\\(.*");
                } catch (Exception ignored) {
                    looksExecutable = body.contains("web.");
                }
                if (looksExecutable) {
                    String cleaned = stripCodeCommentsForDisplay(body);
                    res.put(1, cleaned == null ? "" : cleaned.trim());
                }
            }
            return res;
        }
        starts.add(src.length());
        for (int i = 0; i < nums.size(); i++) {
            int start = starts.get(i);
            int end = starts.get(i + 1);
            String block = src.substring(start, Math.min(end, src.length()));
            String cleaned = stripCodeCommentsForDisplay(block);
            res.put(nums.get(i), cleaned == null ? "" : cleaned.trim());
        }
        return res;
    }

    /**
     * 移除 PLAN_START~PLAN_END 计划块，保留可执行脚本部分
     */
    private static String stripPlanBlock(String code) {
        if (code == null) return "";
        int ps = code.indexOf("PLAN_START");
        int pe = code.indexOf("PLAN_END");
        if (ps >= 0 && pe > ps) {
            int start = code.lastIndexOf("/*", ps);
            int end = code.indexOf("*/", pe);
            if (start >= 0 && end > pe) {
                return code.substring(0, start) + code.substring(end + 2);
            }
            // PLAN 块未包裹在块注释内时，直接截断移除
            int after = pe + "PLAN_END".length();
            return code.substring(0, ps) + code.substring(Math.min(after, code.length()));
        }
        return code;
    }

    private static String loadLatestDebugCodeVariant(String modelName) {
        if (modelName == null || modelName.trim().isEmpty()) return "";
        try {
            java.nio.file.Path debugDir = java.nio.file.Paths.get(System.getProperty("user.dir"), "autoweb", "debug");
            if (!java.nio.file.Files.exists(debugDir) || !java.nio.file.Files.isDirectory(debugDir)) return "";
            String safeModel = modelName.trim().replaceAll("[^A-Za-z0-9_\\-]", "_");
            if (safeModel.isEmpty()) safeModel = "UNKNOWN";

            String prefix = "debug_code_" + safeModel + "_";
            String suffix = ".groovy";
            String bestTs = null;
            String bestTag = null;
            java.nio.file.Path bestPath = null;
            java.util.List<String> preferredTags = java.util.Arrays.asList("refine", "gen");

            try (java.util.stream.Stream<java.nio.file.Path> s = java.nio.file.Files.list(debugDir)) {
                java.util.List<java.nio.file.Path> files = s
                        .filter(p -> p != null && java.nio.file.Files.isRegularFile(p))
                        .filter(p -> {
                            String name = p.getFileName() == null ? "" : p.getFileName().toString();
                            return name.startsWith(prefix) && name.endsWith(suffix);
                        })
                        .toList();

                for (java.nio.file.Path p : files) {
                    String name = p.getFileName() == null ? "" : p.getFileName().toString();
                    String core = name.substring(prefix.length(), name.length() - suffix.length());
                    if (core.length() <= 20) continue;
                    int tsSplit = core.length() - 20;
                    if (tsSplit < 0 || core.charAt(tsSplit) != '_') continue;
                    String tag = core.substring(0, tsSplit);
                    String ts = core.substring(tsSplit + 1);
                    if (ts.length() != 19) continue;
                    boolean tsOk = ts.matches("\\d{8}_\\d{6}_\\d{3}");
                    if (!tsOk) continue;

                    boolean tagOk = preferredTags.contains(tag);
                    if (!tagOk) continue;

                    if (bestTs == null || ts.compareTo(bestTs) > 0) {
                        bestTs = ts;
                        bestTag = tag;
                        bestPath = p;
                    } else if (bestTs != null && ts.equals(bestTs) && bestTag != null && bestPath != null) {
                        int curRank = preferredTags.indexOf(tag);
                        int bestRank = preferredTags.indexOf(bestTag);
                        if (curRank >= 0 && bestRank >= 0 && curRank < bestRank) {
                            bestTag = tag;
                            bestPath = p;
                        }
                    }
                }
            }

            if (bestPath == null) {
                try (java.util.stream.Stream<java.nio.file.Path> s = java.nio.file.Files.list(debugDir)) {
                    java.util.List<java.nio.file.Path> files = s
                            .filter(p -> p != null && java.nio.file.Files.isRegularFile(p))
                            .filter(p -> {
                                String name = p.getFileName() == null ? "" : p.getFileName().toString();
                                return name.startsWith(prefix) && name.endsWith(suffix);
                            })
                            .toList();
                    for (java.nio.file.Path p : files) {
                        String name = p.getFileName() == null ? "" : p.getFileName().toString();
                        String core = name.substring(prefix.length(), name.length() - suffix.length());
                        if (core.length() <= 20) continue;
                        int tsSplit = core.length() - 20;
                        if (tsSplit < 0 || core.charAt(tsSplit) != '_') continue;
                        String ts = core.substring(tsSplit + 1);
                        if (ts.length() != 19) continue;
                        boolean tsOk = ts.matches("\\d{8}_\\d{6}_\\d{3}");
                        if (!tsOk) continue;
                        if (bestTs == null || ts.compareTo(bestTs) > 0) {
                            bestTs = ts;
                            bestPath = p;
                        }
                    }
                }
            }

            if (bestPath == null) return "";
            String out = java.nio.file.Files.readString(bestPath, java.nio.charset.StandardCharsets.UTF_8);
            return out == null ? "" : out;
        } catch (Exception ignored) {
            return "";
        }
    }

    /**
     * 清理代码注释，便于在 Code 列简要展示
     */
    private static String stripCodeCommentsForDisplay(String code) {
        if (code == null || code.trim().isEmpty()) return "";
        String cleaned = code.replaceAll("(?s)/\\*.*?\\*/", " ");
        cleaned = cleaned.replaceAll("(?m)^\\s*//.*$", " ");
        cleaned = collapseBlankLines(cleaned);
        return cleaned;
    }

    /**
     * 折叠连续空行，避免展示区域过度拉长
     *
     * @param text 原始文本
     * @return 折叠后的文本
     */
    private static String collapseBlankLines(String text) {
        if (text == null || text.isEmpty()) return "";
        return text.replaceAll("(?m)(\\n\\s*\\n)+", "\n\n");
    }

    private static String buildRefineFocusHintFromSelection(JTable table, javax.swing.table.DefaultTableModel model) {
        if (table == null || model == null) return "";
        int[] selected = table.getSelectedRows();
        if (selected == null || selected.length == 0) return "";
        Object orderObj = table.getClientProperty("stepOrder");
        java.util.List<Integer> order = orderObj instanceof java.util.List ? (java.util.List<Integer>) orderObj : null;

        java.util.List<Integer> rowIdx = new java.util.ArrayList<>();
        for (int r : selected) {
            try {
                rowIdx.add(table.convertRowIndexToModel(r));
            } catch (Exception ignored) {}
        }
        rowIdx.sort(Integer::compareTo);

        StringBuilder sb = new StringBuilder();
        sb.append("【重点修正范围】\n");
        sb.append("仅修正以下 Step 的 Plan/Code，其他 Step 不要改动，除非为保持整体一致性必须做最小调整。\n");
        for (Integer r : rowIdx) {
            if (r == null || r < 0 || r >= model.getRowCount()) continue;
            Integer stepIndex = null;
            if (order != null && r < order.size()) {
                try { stepIndex = order.get(r); } catch (Exception ignored) {}
            }
            String plan = "";
            String code = "";
            try { plan = String.valueOf(model.getValueAt(r, 1)); } catch (Exception ignored) {}
            try { code = String.valueOf(model.getValueAt(r, 2)); } catch (Exception ignored) {}
            if (stepIndex == null) {
                Integer p = parseStepIndex(plan);
                if (p != null) stepIndex = p;
            }
            if (stepIndex == null) {
                Integer c = parseStepIndex(code);
                if (c != null) stepIndex = c;
            }
            sb.append("\n");
            sb.append("Step ").append(stepIndex == null ? "?" : stepIndex).append(":\n");
            if (plan != null) sb.append("PLAN: ").append(plan.trim()).append("\n");
            if (code != null) sb.append("CODE: ").append(code.trim()).append("\n");
        }
        return sb.toString().trim();
    }

    /**
     * 根据 Plan/Code 行与勾选状态刷新表格数据
     *
     * @param table 表格组件
     * @param model 表格模型
     * @param planRows 计划行
     * @param codeRows 代码行
     * @param checkedSteps 已勾选的步骤集合
     * @param statusMap 步骤执行状态
     */
    private static void updatePlanCodeTableRows(
            JTable table,
            javax.swing.table.DefaultTableModel model,
            java.util.List<String> planRows,
            java.util.List<String> codeRows,
            java.util.Set<Integer> checkedSteps,
            java.util.Map<Integer, Boolean> statusMap,
            java.util.Map<Integer, Boolean> runningMap
    ) {
        if (model == null) return;
        model.setRowCount(0);
        java.util.Map<Integer, String> planByStep = new java.util.HashMap<>();
        java.util.Map<Integer, String> codeByStep = new java.util.HashMap<>();
        java.util.Set<Integer> stepSet = new java.util.TreeSet<>();
        if (planRows != null) {
            for (String row : planRows) {
                Integer stepIndex = parseStepIndex(row);
                if (stepIndex != null) {
                    planByStep.put(stepIndex, row);
                    stepSet.add(stepIndex);
                }
            }
        }
        if (codeRows != null) {
            for (String row : codeRows) {
                Integer stepIndex = parseStepIndex(row);
                if (stepIndex != null) {
                    codeByStep.put(stepIndex, row);
                    stepSet.add(stepIndex);
                }
            }
        }
        java.util.List<Integer> order = new java.util.ArrayList<>();
        for (Integer stepIndex : stepSet) {
            String plan = planByStep.getOrDefault(stepIndex, "");
            String code = codeByStep.getOrDefault(stepIndex, "");
            boolean checked = stepIndex != null && checkedSteps != null && checkedSteps.contains(stepIndex);
            Boolean status = stepIndex == null || statusMap == null ? null : statusMap.get(stepIndex);
            boolean running = stepIndex != null && runningMap != null && Boolean.TRUE.equals(runningMap.get(stepIndex));
            model.addRow(new Object[]{new StepCellData(checked, status, running), plan, code});
            order.add(stepIndex);
        }
        if (table != null) {
            table.putClientProperty("stepOrder", order);
            adjustPlanCodeRowHeights(table, 1, 2);
        }
    }

    /**
     * 按内容高度自适应 Plan/Code 两列的行高
     */
    private static void adjustPlanCodeRowHeights(JTable table, int planCol, int codeCol) {
        if (table == null) return;
        int pCol = Math.min(planCol, table.getColumnCount() - 1);
        int cCol = Math.min(codeCol, table.getColumnCount() - 1);
        int pWidth = table.getColumnModel().getColumn(pCol).getWidth();
        int cWidth = table.getColumnModel().getColumn(cCol).getWidth();
        if (pWidth <= 0 && table.getParent() != null) pWidth = table.getParent().getWidth() / 3;
        if (cWidth <= 0 && table.getParent() != null) cWidth = table.getParent().getWidth() * 2 / 3;
        if (pWidth <= 0) pWidth = 200;
        if (cWidth <= 0) cWidth = 400;
        for (int r = 0; r < table.getRowCount(); r++) {
            java.awt.Component pComp = table.prepareRenderer(table.getCellRenderer(r, pCol), r, pCol);
            java.awt.Component cComp = table.prepareRenderer(table.getCellRenderer(r, cCol), r, cCol);
            if (pComp instanceof JTextArea) {
                ((JTextArea) pComp).setSize(new Dimension(pWidth, Integer.MAX_VALUE));
            } else {
                pComp.setSize(new Dimension(pWidth, pComp.getPreferredSize().height));
            }
            if (cComp instanceof JTextArea) {
                ((JTextArea) cComp).setSize(new Dimension(cWidth, Integer.MAX_VALUE));
            } else {
                cComp.setSize(new Dimension(cWidth, cComp.getPreferredSize().height));
            }
            int h = Math.max(pComp.getPreferredSize().height, cComp.getPreferredSize().height) + table.getRowMargin();
            int target = Math.max(22, h);
            table.setRowHeight(r, target);
        }
    }

    /**
     * 按视口宽度重新分配 Plan/Code 列宽，确保 Code 列填满展示区
     */
    private static void adjustPlanCodeColumnWidths(JTable table) {
        if (table == null) return;
        if (table.getColumnCount() < 3) return;
        int total = table.getWidth();
        java.awt.Container parent = table.getParent();
        if (parent instanceof javax.swing.JViewport) {
            total = ((javax.swing.JViewport) parent).getExtentSize().width;
        } else if (total <= 0 && parent != null) {
            total = parent.getWidth();
        }
        if (total <= 0) total = 600;
        int fixed = table.getColumnModel().getColumn(0).getWidth();
        if (fixed <= 0) fixed = table.getColumnModel().getColumn(0).getPreferredWidth();
        int remaining = Math.max(240, total - fixed);
        int plan = Math.max(120, remaining / 3);
        int code = Math.max(200, remaining - plan);
        table.getColumnModel().getColumn(1).setPreferredWidth(plan);
        table.getColumnModel().getColumn(1).setMinWidth(120);
        table.getColumnModel().getColumn(1).setWidth(plan);
        table.getColumnModel().getColumn(2).setPreferredWidth(code);
        table.getColumnModel().getColumn(2).setMinWidth(200);
        table.getColumnModel().getColumn(2).setWidth(code);
        int totalWidth = fixed + plan + code;
        if (totalWidth < total) totalWidth = total;
        Dimension pref = table.getPreferredSize();
        table.setPreferredSize(new Dimension(totalWidth, pref.height));
        table.revalidate();
    }

    /**
     * 从行文本中解析 Step 编号
     *
     * @param row 行文本
     * @return 步骤编号或 null
     */
    private static Integer parseStepIndex(String row) {
        if (row == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?i)(?:Step|步骤)\\s*(\\d+)").matcher(row);
        if (!m.find()) return null;
        return parseUnicodeInt(m.group(1));
    }
    
    private static Integer parseUnicodeInt(String token) {
        if (token == null) return null;
        String s = token.trim();
        if (s.isEmpty()) return null;
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!Character.isDigit(c)) return null;
            int d = Character.getNumericValue(c);
            if (d < 0 || d > 9) return null;
            n = n * 10 + d;
        }
        return n;
    }

    private static String toVerticalHtml(String text) {
        if (text == null) return "";
        String t = text.trim();
        if (t.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("<html>");
        int added = 0;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c == '\n' || c == '\r') continue;
            if (added > 0) sb.append("<br>");
            if (c == ' ') sb.append("&nbsp;");
            else sb.append(c);
            added++;
        }
        sb.append("</html>");
        return sb.toString();
    }

    private static void applyGentleScrollSpeed(JScrollPane scrollPane) {
        if (scrollPane == null) return;
        JScrollBar v = scrollPane.getVerticalScrollBar();
        if (v != null) {
            v.setUnitIncrement(12);
            v.setBlockIncrement(96);
        }
        JScrollBar h = scrollPane.getHorizontalScrollBar();
        if (h != null) {
            h.setUnitIncrement(12);
            h.setBlockIncrement(96);
        }
    }


    /**
     * 应用 Plan&Code 表头样式
     *
     * @param table 表格组件
     */
    private static void applyTableHeaderStyle(JTable table) {
        if (table == null) return;
        javax.swing.table.JTableHeader header = table.getTableHeader();
        if (header == null) return;
        Color headerBg = new Color(220, 235, 255);
        Color headerFg = new Color(30, 30, 30);
        Color headerLine = new Color(120, 120, 120);
        header.setBackground(headerBg);
        header.setForeground(headerFg);
        header.setFont(header.getFont().deriveFont(Font.BOLD));
        Dimension pref = header.getPreferredSize();
        header.setPreferredSize(new Dimension(pref.width, 28));
        javax.swing.table.DefaultTableCellRenderer renderer = new javax.swing.table.DefaultTableCellRenderer();
        renderer.setHorizontalAlignment(SwingConstants.CENTER);
        renderer.setVerticalAlignment(SwingConstants.CENTER);
        renderer.setOpaque(true);
        renderer.setBackground(headerBg);
        renderer.setForeground(headerFg);
        renderer.setBorder(BorderFactory.createMatteBorder(0, 0, 2, 1, headerLine));
        header.setDefaultRenderer(renderer);
    }

    private static class StepCellData {
        boolean checked;
        Boolean status;
        boolean running;

        StepCellData(boolean checked, Boolean status, boolean running) {
            this.checked = checked;
            this.status = status;
            this.running = running;
        }
    }

    private static class MultiLineTableCellRenderer extends JTextArea implements javax.swing.table.TableCellRenderer {
        MultiLineTableCellRenderer() {
            setLineWrap(true);
            setWrapStyleWord(true);
            setOpaque(true);
        }

        public java.awt.Component getTableCellRendererComponent(
                JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column
        ) {
            setText(value == null ? "" : value.toString());
            if (isSelected) {
                setBackground(table.getSelectionBackground());
                setForeground(table.getSelectionForeground());
            } else {
                setBackground(table.getBackground());
                setForeground(table.getForeground());
            }
            setFont(table.getFont());
            return this;
        }
    }

    private static class CodeStepCellRenderer extends JTextArea implements javax.swing.table.TableCellRenderer {
        CodeStepCellRenderer() {
            setLineWrap(true);
            setWrapStyleWord(true);
            setOpaque(true);
        }

        public java.awt.Component getTableCellRendererComponent(
                JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column
        ) {
            setText(value == null ? "" : value.toString());
            if (isSelected) {
                setBackground(table.getSelectionBackground());
                setForeground(table.getSelectionForeground());
            } else {
                setBackground(new Color(221, 235, 255));
                setForeground(table.getForeground());
            }
            setFont(table.getFont().deriveFont(Font.BOLD));
            return this;
        }
    }

    private static class StepSelectStatusRenderer extends JPanel implements javax.swing.table.TableCellRenderer {
        private static final Icon OK_ICON = new StatusMarkIcon(true);
        private static final Icon FAIL_ICON = new StatusMarkIcon(false);
        private final JCheckBox checkBox = new JCheckBox();
        private final JLabel statusLabel = new JLabel();

        StepSelectStatusRenderer() {
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            setOpaque(true);
            checkBox.setOpaque(false);
            statusLabel.setOpaque(false);
            checkBox.setAlignmentX(Component.CENTER_ALIGNMENT);
            statusLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
            add(checkBox);
            add(statusLabel);
        }

        public java.awt.Component getTableCellRendererComponent(
                JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column
        ) {
            if (isSelected) {
                setBackground(table.getSelectionBackground());
                setForeground(table.getSelectionForeground());
            } else {
                setBackground(table.getBackground());
                setForeground(table.getForeground());
            }
            StepCellData data = value instanceof StepCellData ? (StepCellData) value : null;
            boolean checked = data != null && data.checked;
            checkBox.setSelected(checked);
            Boolean status = data == null ? null : data.status;
            boolean running = data != null && data.running;
            if (running) {
                statusLabel.setIcon(null);
                statusLabel.setText("执行中");
            } else if (status == null) {
                statusLabel.setIcon(null);
                statusLabel.setText("");
            } else if (status) {
                statusLabel.setIcon(OK_ICON);
                statusLabel.setText("成功");
            } else {
                statusLabel.setIcon(FAIL_ICON);
                statusLabel.setText("失败");
            }
            return this;
        }
    }

    private static class StepSelectStatusEditor extends javax.swing.AbstractCellEditor implements javax.swing.table.TableCellEditor {
        private static final Icon OK_ICON = new StatusMarkIcon(true);
        private static final Icon FAIL_ICON = new StatusMarkIcon(false);
        private final JPanel panel = new JPanel();
        private final JCheckBox checkBox = new JCheckBox();
        private final JLabel statusLabel = new JLabel();
        private StepCellData data;

        StepSelectStatusEditor() {
            panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
            panel.setOpaque(true);
            checkBox.setOpaque(false);
            statusLabel.setOpaque(false);
            checkBox.setAlignmentX(Component.CENTER_ALIGNMENT);
            statusLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
            panel.add(checkBox);
            panel.add(statusLabel);
            checkBox.addActionListener(e -> stopCellEditing());
        }

        public java.awt.Component getTableCellEditorComponent(JTable table, Object value, boolean isSelected, int row, int column) {
            data = value instanceof StepCellData ? (StepCellData) value : new StepCellData(false, null, false);
            checkBox.setSelected(data.checked);
            Boolean status = data.status;
            boolean running = data.running;
            if (running) {
                statusLabel.setIcon(null);
                statusLabel.setText("执行中");
            } else if (status == null) {
                statusLabel.setIcon(null);
                statusLabel.setText("");
            } else if (status) {
                statusLabel.setIcon(OK_ICON);
                statusLabel.setText("成功");
            } else {
                statusLabel.setIcon(FAIL_ICON);
                statusLabel.setText("失败");
            }
            panel.setBackground(isSelected ? table.getSelectionBackground() : table.getBackground());
            panel.setForeground(isSelected ? table.getSelectionForeground() : table.getForeground());
            return panel;
        }

        public Object getCellEditorValue() {
            if (data == null) data = new StepCellData(false, null, false);
            data.checked = checkBox.isSelected();
            return data;
        }
    }


    private static class StatusMarkIcon implements Icon {
        private final boolean ok;
        private final int size;

        StatusMarkIcon(boolean ok) {
            this.ok = ok;
            this.size = 12;
        }

        public int getIconWidth() {
            return size;
        }

        public int getIconHeight() {
            return size;
        }

        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setStroke(new BasicStroke(2.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                if (ok) {
                    g2.setColor(new Color(16, 128, 64));
                    int x1 = x + 2;
                    int y1 = y + size / 2;
                    int x2 = x + size / 2 - 1;
                    int y2 = y + size - 3;
                    int x3 = x + size - 2;
                    int y3 = y + 2;
                    g2.drawLine(x1, y1, x2, y2);
                    g2.drawLine(x2, y2, x3, y3);
                } else {
                    g2.setColor(new Color(180, 48, 48));
                    int x1 = x + 2;
                    int y1 = y + 2;
                    int x2 = x + size - 2;
                    int y2 = y + size - 2;
                    g2.drawLine(x1, y1, x2, y2);
                    g2.drawLine(x1, y2, x2, y1);
                }
            } finally {
                g2.dispose();
            }
        }
    }

    /**
     * 统一控制按钮可用性，避免并发操作
     *
     * @param btnPlan 生成计划按钮
     * @param btnGetCode 生成代码按钮
     * @param btnRefinePlan 修正计划按钮
     * @param btnRefine 修正代码按钮
     * @param btnStepExecute 分步执行按钮
     * @param btnExecute 执行按钮
     * @param btnClearAll 清空按钮
     * @param enabled 是否启用
     */
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

    /**
     * 从连接中选取最近可用的页面
     *
     * @param connection Playwright 连接
     * @return 最近的非空页面或最后一个可用页面
     */
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

    /**
     * 检测 rootPageRef 是否需要切换到最新活跃页面
     *
     * @param rootPageRef 页面引用
     * @param connectionRef 连接引用
     * @param uiLogger 可选日志输出
     * @param stage 日志阶段标识
     * @return 刷新后的页面引用
     */
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

    /**
     * 确保执行前存在可用页面与连接，必要时重连并创建新页面
     *
     * @param rootPageRef 页面引用
     * @param connectionRef 连接引用
     * @param forceNewPageOnExecute 强制新建页面标记
     * @param hasExecuted 是否已执行标记
     * @param uiLogger 可选日志输出
     * @return 可用页面
     */
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

    private static double asDouble(Object v, double defaultValue) {
        if (v == null) return defaultValue;
        if (v instanceof Number) return ((Number) v).doubleValue();
        try {
            return Double.parseDouble(String.valueOf(v));
        } catch (Exception ignored) {
            return defaultValue;
        }
    }

    private static String sha256Hex(String s) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] b = md.digest((s == null ? "" : s).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte x : b) sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf((s == null ? "" : s).hashCode());
        }
    }

    private static final Object VISUAL_TRACE_LOCK = new Object();

    private static void traceVisual(String stage, String msg) {
        try {
            String line = java.time.LocalDateTime.now() +
                    " | " + (stage == null ? "" : stage) +
                    " | " + (msg == null ? "" : msg) +
                    " | thread=" + Thread.currentThread().getName();
            synchronized (VISUAL_TRACE_LOCK) {
                java.nio.file.Path dir = java.nio.file.Paths.get(System.getProperty("user.dir"), "autoweb", "debug");
                java.nio.file.Files.createDirectories(dir);
                java.nio.file.Path file = dir.resolve("visual_trace.log");
                java.nio.file.Files.writeString(
                        file,
                        line + "\n",
                        java.nio.charset.StandardCharsets.UTF_8,
                        java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.WRITE,
                        java.nio.file.StandardOpenOption.APPEND
                );
            }
        } catch (Exception ignored) {
        }
    }

    private static void clearVisualCacheForPage(Page page, java.util.function.Consumer<String> uiLogger) {
        if (page == null) return;
        try {
            java.nio.file.Path cacheDir = java.nio.file.Paths.get(System.getProperty("user.dir"), "autoweb", "cache");
            java.nio.file.Files.createDirectories(cacheDir);
            String urlKey = safePageUrl(page);
            String baseUrlKey = PlanRoutingSupport.stripUrlQuery(urlKey == null ? "" : urlKey.trim());
            if (baseUrlKey == null || baseUrlKey.trim().isEmpty()) return;
            traceVisual("VISUAL_CACHE_CLEAR_BEGIN", "baseUrlKey=" + baseUrlKey);
            String descKey = sha256Hex("VISUAL_DESC_V4|" + baseUrlKey);
            try {
                java.nio.file.Files.deleteIfExists(cacheDir.resolve("visual_desc_" + descKey + ".txt"));
            } catch (Exception ignored) {}
            String shotKey = sha256Hex("VISUAL_V2|" + baseUrlKey);
            for (int i = 1; i <= 5; i++) {
                try {
                    java.nio.file.Files.deleteIfExists(cacheDir.resolve("visual_" + shotKey + "_" + i + ".png"));
                } catch (Exception ignored) {}
            }
            if (uiLogger != null) uiLogger.accept("视觉补充: 已清理缓存并强制重新截图");
            traceVisual("VISUAL_CACHE_CLEAR_DONE", "baseUrlKey=" + baseUrlKey);
        } catch (Exception ignored) {
        }
    }

    private static java.util.Map<String, Object> readBestScrollerState(Page page) {
        if (page == null) return java.util.Collections.emptyMap();
        try {
            Object stateObj = page.evaluate("() => {\n" +
                    "  const el = window.__autowebBestScroller;\n" +
                    "  if (el && el !== window) {\n" +
                    "    return { mode: 'el', tag: (el.tagName || ''), y: (el.scrollTop || 0), ih: (el.clientHeight || 0), sh: (el.scrollHeight || 0) };\n" +
                    "  }\n" +
                    "  const de = document.scrollingElement || document.documentElement;\n" +
                    "  return { mode: 'win', tag: '', y: (window.scrollY || 0), ih: (window.innerHeight || 0), sh: (de ? (de.scrollHeight || 0) : 0) };\n" +
                    "}");
            if (stateObj instanceof java.util.Map<?, ?> m) {
                java.util.Map<String, Object> out = new java.util.HashMap<>();
                for (java.util.Map.Entry<?, ?> e : m.entrySet()) {
                    if (e == null) continue;
                    Object k = e.getKey();
                    if (k == null) continue;
                    out.put(String.valueOf(k), e.getValue());
                }
                return out;
            }
            return java.util.Collections.emptyMap();
        } catch (Exception ignored) {
            return java.util.Collections.emptyMap();
        }
    }

    private static java.util.List<java.io.File> captureMultiScreenScreenshots(Page page, java.util.function.Consumer<String> uiLogger) {
        if (page == null) return java.util.Collections.emptyList();
        synchronized (PLAYWRIGHT_LOCK) {
            try {
                java.nio.file.Path cacheDir = java.nio.file.Paths.get(System.getProperty("user.dir"), "autoweb", "cache");
                java.nio.file.Files.createDirectories(cacheDir);
                String urlKey = safePageUrl(page);
                String baseUrlKey = PlanRoutingSupport.stripUrlQuery(urlKey == null ? "" : urlKey.trim());
                traceVisual("VISUAL_SCREENSHOT_START", "baseUrlKey=" + baseUrlKey + ", urlKey=" + (urlKey == null ? "" : urlKey));
                String cacheKey = sha256Hex("VISUAL_V2|" + baseUrlKey);
                int maxScreens = 2;
                java.nio.file.Path p1 = cacheDir.resolve("visual_" + cacheKey + "_1.png");

                long now = System.currentTimeMillis();
                long ttlMillis = 1200_000L;
                boolean reuse = false;
                try {
                    if (java.nio.file.Files.exists(p1)) {
                        long age = now - java.nio.file.Files.getLastModifiedTime(p1).toMillis();
                        reuse = age >= 0 && age <= ttlMillis;
                    }
                } catch (Exception ignored) {}
                if (reuse) {
                    traceVisual("VISUAL_SCREENSHOT_REUSE", "baseUrlKey=" + baseUrlKey);
                    java.util.List<java.io.File> out = new java.util.ArrayList<>();
                    for (int i = 1; i <= maxScreens; i++) {
                        java.nio.file.Path pi = cacheDir.resolve("visual_" + cacheKey + "_" + i + ".png");
                        try {
                            if (!java.nio.file.Files.exists(pi)) continue;
                            long age = now - java.nio.file.Files.getLastModifiedTime(pi).toMillis();
                            if (age >= 0 && age <= ttlMillis) out.add(pi.toFile());
                        } catch (Exception ignored) {
                        }
                    }
                    if (uiLogger != null) uiLogger.accept("视觉补充: 复用本地截图缓存（images=" + out.size() + "）");
                    return out;
                }

                Object initObj = null;
                try {
                    initObj = page.evaluate("() => {\n" +
                            "  const winH = window.innerHeight || 0;\n" +
                            "  const nodes = document.querySelectorAll('body *');\n" +
                            "  const limit = Math.min(nodes.length, 6000);\n" +
                            "  let best = null;\n" +
                            "  let bestScore = 0;\n" +
                            "  for (let i = 0; i < limit; i++) {\n" +
                            "    const el = nodes[i];\n" +
                            "    if (!el) continue;\n" +
                            "    let st;\n" +
                            "    try { st = window.getComputedStyle(el); } catch (e) { st = null; }\n" +
                            "    const oy = st ? (st.overflowY || '') : '';\n" +
                            "    if (oy !== 'auto' && oy !== 'scroll' && oy !== 'overlay') continue;\n" +
                            "    const ch = el.clientHeight || 0;\n" +
                            "    const sh = el.scrollHeight || 0;\n" +
                            "    const cw = el.clientWidth || 0;\n" +
                            "    if (sh <= ch + 40) continue;\n" +
                            "    if (ch < Math.max(200, Math.floor(winH * 0.5))) continue;\n" +
                            "    const score = (sh - ch) * cw;\n" +
                            "    if (score > bestScore) {\n" +
                            "      bestScore = score;\n" +
                            "      best = el;\n" +
                            "    }\n" +
                            "  }\n" +
                            "  window.__autowebBestScroller = best || window;\n" +
                            "  return { ok: true, nodes: nodes.length, limit, hasEl: !!best, tag: best ? (best.tagName || '') : '', score: bestScore };\n" +
                            "}");
                } catch (Exception ignored) {
                    initObj = null;
                }
                if (initObj != null) traceVisual("VISUAL_SCROLLER_PICK", String.valueOf(initObj));
                if (uiLogger != null && initObj instanceof java.util.Map<?, ?> m) {
                    Object hasEl = m.get("hasEl");
                    if (Boolean.TRUE.equals(hasEl)) {
                        Object tag = m.get("tag");
                        uiLogger.accept("视觉补充: 使用滚动容器 " + (tag == null ? "" : String.valueOf(tag)));
                    }
                }

                double originY = 0;
                try {
                    java.util.Map<String, Object> s0 = readBestScrollerState(page);
                    originY = asDouble(s0.get("y"), 0);
                    traceVisual("VISUAL_SCROLL_ORIGIN",
                            "mode=" + String.valueOf(s0.getOrDefault("mode", "")) +
                                    ", tag=" + String.valueOf(s0.getOrDefault("tag", "")) +
                                    ", y=" + originY +
                                    ", ih=" + asDouble(s0.get("ih"), 0) +
                                    ", sh=" + asDouble(s0.get("sh"), 0));
                } catch (Exception ignored) {
                    originY = 0;
                }

                java.util.List<java.io.File> out = new java.util.ArrayList<>();
                for (int i = 1; i <= maxScreens; i++) {
                    java.nio.file.Path pi = cacheDir.resolve("visual_" + cacheKey + "_" + i + ".png");
                    page.screenshot(new com.microsoft.playwright.Page.ScreenshotOptions().setPath(pi));
                    if (uiLogger != null) uiLogger.accept("视觉补充: 已截图 " + i + " -> " + pi.toAbsolutePath());
                    traceVisual("VISUAL_SCREENSHOT_SAVED", "index=" + i + ", path=" + pi.toAbsolutePath());
                    out.add(pi.toFile());

                    double y = 0;
                    double ih = 0;
                    double sh = 0;
                    String mode = "";
                    String tag = "";
                    try {
                        java.util.Map<String, Object> s1 = readBestScrollerState(page);
                        mode = String.valueOf(s1.getOrDefault("mode", ""));
                        tag = String.valueOf(s1.getOrDefault("tag", ""));
                        y = asDouble(s1.get("y"), 0);
                        ih = asDouble(s1.get("ih"), 0);
                        sh = asDouble(s1.get("sh"), 0);
                    } catch (Exception ignored) {
                    }
                    traceVisual("VISUAL_SCROLL_STATE",
                            "index=" + i +
                                    ", mode=" + mode +
                                    ", tag=" + tag +
                                    ", y=" + y +
                                    ", ih=" + ih +
                                    ", sh=" + sh);

                    boolean hasMore = sh > ih + 8 && (y + ih + 8) < sh;
                    if (!hasMore) break;

                    double targetY = Math.min(y + Math.max(1, ih * 0.9), Math.max(0, sh - ih));
                    try {
                        traceVisual("VISUAL_SCROLL_ATTEMPT",
                                "index=" + i +
                                        ", mode=" + mode +
                                        ", tag=" + tag +
                                        ", fromY=" + y +
                                        ", targetY=" + targetY +
                                        ", ih=" + ih +
                                        ", sh=" + sh);
                        page.evaluate("yy => {\n" +
                                "  const el = window.__autowebBestScroller;\n" +
                                "  if (el && el !== window) {\n" +
                                "    el.scrollTop = yy;\n" +
                                "    return;\n" +
                                "  }\n" +
                                "  window.scrollTo(0, yy);\n" +
                                "}", targetY);
                        page.waitForTimeout(350);
                        java.util.Map<String, Object> s2 = readBestScrollerState(page);
                        double afterY = asDouble(s2.get("y"), y);
                        traceVisual("VISUAL_SCROLL_AFTER",
                                "index=" + i +
                                        ", afterY=" + afterY +
                                        ", expectedY=" + targetY +
                                        ", delta=" + (afterY - y) +
                                        ", mode=" + String.valueOf(s2.getOrDefault("mode", mode)) +
                                        ", tag=" + String.valueOf(s2.getOrDefault("tag", tag)));
                        if (Math.abs(afterY - y) < 2) {
                            try {
                                int dy = (int) Math.max(200, ih * 0.9);
                                double mx = 0;
                                double my = 0;
                                try {
                                    Object ptObj = page.evaluate("() => {\n" +
                                            "  const el = window.__autowebBestScroller;\n" +
                                            "  if (el && el !== window) {\n" +
                                            "    const r = el.getBoundingClientRect();\n" +
                                            "    const x = (r.left || 0) + (r.width || 0) / 2;\n" +
                                            "    const y = (r.top || 0) + Math.max(1, Math.min((r.height || 0) - 1, (r.height || 0) / 2));\n" +
                                            "    return { x, y };\n" +
                                            "  }\n" +
                                            "  const x = (window.innerWidth || 0) / 2;\n" +
                                            "  const y = (window.innerHeight || 0) / 2;\n" +
                                            "  return { x, y };\n" +
                                            "}");
                                    if (ptObj instanceof java.util.Map<?, ?> mpt) {
                                        mx = asDouble(mpt.get("x"), 0);
                                        my = asDouble(mpt.get("y"), 0);
                                    }
                                } catch (Exception ignored) {
                                    mx = 0;
                                    my = 0;
                                }
                                traceVisual("VISUAL_SCROLL_WHEEL_ATTEMPT",
                                        "index=" + i +
                                                ", dy=" + dy +
                                                ", mouseX=" + mx +
                                                ", mouseY=" + my +
                                                ", fromY=" + y +
                                                ", mode=" + mode +
                                                ", tag=" + tag);
                                if (mx > 0 && my > 0) {
                                    page.mouse().move(mx, my);
                                    page.waitForTimeout(60);
                                }
                                page.mouse().wheel(0, dy);
                                page.waitForTimeout(350);
                                java.util.Map<String, Object> s3 = readBestScrollerState(page);
                                double wheelAfterY = asDouble(s3.get("y"), afterY);
                                traceVisual("VISUAL_SCROLL_WHEEL_AFTER",
                                        "index=" + i +
                                                ", afterY=" + wheelAfterY +
                                                ", delta=" + (wheelAfterY - y) +
                                                ", mode=" + String.valueOf(s3.getOrDefault("mode", mode)) +
                                                ", tag=" + String.valueOf(s3.getOrDefault("tag", tag)));
                                if (uiLogger != null && Math.abs(wheelAfterY - y) < 2) {
                                    uiLogger.accept("视觉补充: 翻页滚动未生效（index=" + i + ", mode=" + mode + ", tag=" + tag + "）");
                                }
                            } catch (Exception ignored) {
                            }
                        }
                    } catch (Exception ignored) {
                    }
                }

                try {
                    traceVisual("VISUAL_SCROLL_RESTORE", "originY=" + originY);
                    page.evaluate("yy => {\n" +
                            "  const el = window.__autowebBestScroller;\n" +
                            "  if (el && el !== window) {\n" +
                            "    el.scrollTop = yy;\n" +
                            "    return;\n" +
                            "  }\n" +
                            "  window.scrollTo(0, yy);\n" +
                            "}", originY);
                    page.waitForTimeout(150);
                    java.util.Map<String, Object> sr = readBestScrollerState(page);
                    traceVisual("VISUAL_SCROLL_RESTORE_DONE",
                            "afterY=" + asDouble(sr.get("y"), originY) +
                                    ", mode=" + String.valueOf(sr.getOrDefault("mode", "")) +
                                    ", tag=" + String.valueOf(sr.getOrDefault("tag", "")));
                } catch (Exception ignored) {}

                return out;
            } catch (Exception ex) {
                if (uiLogger != null) uiLogger.accept("视觉补充: 截图失败: " + ex.getMessage());
                traceVisual("VISUAL_SCREENSHOT_ERROR", ex.getClass().getName() + ": " + (ex.getMessage() == null ? "" : ex.getMessage()));
                return java.util.Collections.emptyList();
            }
        }
    }

    static String buildPageVisualDescription(Page page, java.util.function.Consumer<String> uiLogger) {
        if (page == null) return "";
        String urlKey = safePageUrl(page);
        String baseUrlKey = PlanRoutingSupport.stripUrlQuery(urlKey == null ? "" : urlKey.trim());
        traceVisual("VISUAL_DESC_START", "baseUrlKey=" + baseUrlKey + ", urlKey=" + (urlKey == null ? "" : urlKey));
        String cacheKey = sha256Hex("VISUAL_DESC_V4|" + baseUrlKey);
        java.nio.file.Path cacheDir = java.nio.file.Paths.get(System.getProperty("user.dir"), "autoweb", "cache");
        java.nio.file.Path descPath = cacheDir.resolve("visual_desc_" + cacheKey + ".txt");
        long now = System.currentTimeMillis();
        long ttlMillis = 120_000L;
        try {
            if (java.nio.file.Files.exists(descPath)) {
                long age = now - java.nio.file.Files.getLastModifiedTime(descPath).toMillis();
                if (age >= 0 && age <= ttlMillis) {
                    String cached = java.nio.file.Files.readString(descPath, java.nio.charset.StandardCharsets.UTF_8);
                    if (cached != null && !cached.trim().isEmpty()) {
                        if (uiLogger != null) uiLogger.accept("视觉补充: 复用本地描述缓存");
                        traceVisual("VISUAL_DESC_REUSE", "descPath=" + descPath.toAbsolutePath());
                        return cached.trim();
                    }
                }
            }
        } catch (Exception ignored) {}

        java.util.List<java.io.File> images = captureMultiScreenScreenshots(page, uiLogger);
        traceVisual("VISUAL_DESC_IMAGES", "count=" + (images == null ? 0 : images.size()));
        if (images == null || images.isEmpty()) return "";
        String prompt = AppConfig.getInstance().getAutowebVisualPrompt();
        try {
            java.util.List<String> srcs = new java.util.ArrayList<>();
            for (java.io.File f : images) {
                if (f == null) continue;
                srcs.add(f.getAbsolutePath());
            }
            String r = LLMUtil.analyzeImageWithAliyun(srcs, prompt);
            String out = r == null ? "" : r.trim();
            traceVisual("VISUAL_DESC_LLM_DONE", "len=" + out.length());
            if (!out.isEmpty()) {
                try {
                    java.nio.file.Files.createDirectories(cacheDir);
                    java.nio.file.Files.writeString(descPath, out, java.nio.charset.StandardCharsets.UTF_8);
                    traceVisual("VISUAL_DESC_SAVED", "descPath=" + descPath.toAbsolutePath());
                } catch (Exception ignored) {}
            }
            return out;
        } catch (Exception ex) {
            if (uiLogger != null) uiLogger.accept("视觉补充: 图片分析失败: " + ex.getMessage());
            traceVisual("VISUAL_DESC_ERROR", ex.getClass().getName() + ": " + (ex.getMessage() == null ? "" : ex.getMessage()));
            return "";
        }
    }

    static String readCachedPageVisualDescription(Page page, java.util.function.Consumer<String> uiLogger) {
        if (page == null) return "";
        String urlKey = safePageUrl(page);
        String baseUrlKey = PlanRoutingSupport.stripUrlQuery(urlKey == null ? "" : urlKey.trim());
        String cacheKey = sha256Hex("VISUAL_DESC_V4|" + baseUrlKey);
        java.nio.file.Path cacheDir = java.nio.file.Paths.get(System.getProperty("user.dir"), "autoweb", "cache");
        java.nio.file.Path descPath = cacheDir.resolve("visual_desc_" + cacheKey + ".txt");
        try {
            if (!java.nio.file.Files.exists(descPath)) return "";
            String cached = java.nio.file.Files.readString(descPath, java.nio.charset.StandardCharsets.UTF_8);
            if (cached == null || cached.trim().isEmpty()) return "";
            if (uiLogger != null) uiLogger.accept("视觉补充(REFINE_CODE): 复用本地描述缓存");
            return cached.trim();
        } catch (Exception ignored) {
            return "";
        }
    }

    /**
     * 弹出多行输入对话框
     *
     * @param parent 父组件
     * @param title 弹窗标题
     * @param message 提示文本
     * @return 用户输入或 null
     */
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
    
    /**
     * 在非 EDT 线程中阻塞获取多行输入
     *
     * @param parent 父组件
     * @param title 弹窗标题
     * @param message 提示文本
     * @return 用户输入或 null
     */
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

    /**
     * 判断指定模型列表的计划是否已满足生成代码条件
     *
     * @param models 模型列表
     * @param sessionsByModel 模型会话状态
     * @param currentPrompt 当前用户提示
     * @return true 表示全部准备就绪
     */
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

    /**
     * 弹窗提示计划已就绪，并引导生成代码
     *
     * @param frame 父窗口
     * @param onGenerateCode 确认后执行的回调
     */
    private static void showPlanReadyDialog(JFrame frame, Runnable onGenerateCode) {
        if (frame == null) return;
        Runnable show = () -> {
            String msg = "计划已生成，是否继续生成代码？";
            JDialog dialog = new JDialog(frame, "提示", true);
            JPanel panel = new JPanel(new BorderLayout(12, 12));
            panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

            JTextArea ta = new JTextArea(msg, 4, 40);
            ta.setEditable(false);
            ta.setLineWrap(true);
            ta.setWrapStyleWord(true);
            ta.setOpaque(false);
            ta.setBorder(null);

            JButton btnGenerate = new JButton("生成代码");
            btnGenerate.addActionListener(ev -> {
                dialog.dispose();
                if (onGenerateCode != null) onGenerateCode.run();
            });
            JButton btnCancel = new JButton("取消");
            btnCancel.addActionListener(ev -> dialog.dispose());
            JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
            btnPanel.add(btnCancel);
            btnPanel.add(btnGenerate);

            panel.add(ta, BorderLayout.CENTER);
            panel.add(btnPanel, BorderLayout.SOUTH);
            dialog.setContentPane(panel);
            dialog.getRootPane().setDefaultButton(btnGenerate);
            dialog.pack();
            dialog.setLocationRelativeTo(frame);
            btnGenerate.requestFocusInWindow();
            dialog.setVisible(true);
        };
        if (SwingUtilities.isEventDispatchThread()) {
            show.run();
        } else {
            SwingUtilities.invokeLater(show);
        }
    }
}
