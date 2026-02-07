package com.qiyi.tools;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

/**
 * 任务计划补全器：把“计划阶段补参”下沉到各 Tool 内聚实现。
 *
 * <p>这里只负责分发：根据 task.tool 找到对应 Tool，并调用 tool.enrichPlannedTask(userText, task)。</p>
 */
public final class TaskPlanEnricher {
    private TaskPlanEnricher() {}

    /**
     * 遍历 LLM 规划的 tasks，并让各工具自行补全其入参。
     */
    public static void enrich(String userText, JSONArray tasks) {
        if (tasks == null || tasks.isEmpty()) return;
        for (int i = 0; i < tasks.size(); i++) {
            JSONObject task = tasks.getJSONObject(i);
            if (task == null) continue;
            String toolName = task.getString("tool");
            if (toolName == null || toolName.trim().isEmpty()) continue;
            Tool tool = ToolManager.get(toolName);
            if (tool == null) continue;
            tool.enrichPlannedTask(userText, task);
        }
    }
}
