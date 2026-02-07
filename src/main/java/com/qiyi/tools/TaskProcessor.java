package com.qiyi.tools;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.qiyi.component.AgentComponent;
import com.qiyi.component.ComponentId;
import com.qiyi.component.ComponentManager;
import com.qiyi.component.ComponentState;
import com.qiyi.component.ComponentStatus;
import com.qiyi.skills.SkillPrompts;
import com.qiyi.util.LLMUtil;
import com.qiyi.util.AppLog;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 将用户输入转换为可执行任务，并按顺序调用 Tool。
 *
 * 主要职责：
 * <ul>
 *     <li>当未配置远程 LLM Key 时，支持“直连模式”：toolName key=value ...</li>
 *     <li>当已配置 LLM Key 时，先做工具筛选（减少上下文），再生成可执行任务列表</li>
 *     <li>执行前校验工具依赖组件（requiredComponents）是否已启动</li>
 *     <li>输出关键执行日志：调用了哪些工具、耗时、失败原因</li>
 * </ul>
 */
public class TaskProcessor {

    public static void process(String text, ToolContext context, ToolMessenger messenger) {
        long processBegin = System.nanoTime();
        AppLog.info("[task] process begin, text=" + safeOneLine(text));
        if (!LLMUtil.hasAnyRemoteChatKeyConfigured() && tryExecuteDirectCommand(text, context, messenger)) {
            AppLog.info("No remote LLM key configured. Tool executed in direct mode without LLM.");
            return;
        }

        // Step 1: 先用全量工具 Schema 做一次“工具筛选”，减少后续规划 prompt 体积
        StringBuilder selectionPrompt = new StringBuilder();
        selectionPrompt.append(SkillPrompts.base());
        selectionPrompt.append("You are an intent classifier. Analyze the user's input and select the tools that might be needed.\n");
        selectionPrompt.append("The available tools are:\n");
        for (Tool tool : ToolManager.getAll()) {
            selectionPrompt.append("- Schema: ").append(ToolManager.toToolSchema(tool).toJSONString()).append("\n");
        }
        selectionPrompt.append("\nUser Input: \"").append(text).append("\"\n");
        selectionPrompt.append("\nReturn JSON only. Format: { \"selected_tools\": [\"tool_name1\"] } or { \"selected_tools\": [] } if no tool matches.");
        selectionPrompt.append("\nIf the user asks about the agent's capabilities (e.g., '你能做什么', '工具能力', 'capabilities'), select the 'list_capabilities' tool.");
        selectionPrompt.append("\nDo NOT select 'SearchDingTalkUserTool' unless the user explicitly asks for uid/userId/用户ID/Uid/查询uid。For sending notifications like '告诉/通知/发给某人', select 'send_message' directly (it can resolve names); do not use SearchDingTalkUserTool for that.");

        List<String> validSelectedTools = new ArrayList<>();
        try {
            String selectionResponse = LLMUtil.chat(selectionPrompt.toString());
            if (selectionResponse != null && !selectionResponse.trim().isEmpty()) {
                selectionResponse = selectionResponse.replace("```json", "").replace("```", "").trim();
                JSONObject selectionJson = JSON.parseObject(selectionResponse);
                if (selectionJson.containsKey("selected_tools")) {
                    List<String> selectedTools = selectionJson.getJSONArray("selected_tools").toJavaList(String.class);
                    for (String t : selectedTools) {
                        if (ToolManager.contains(t)) {
                            validSelectedTools.add(t);
                        }
                    }
                }
            }
        } catch (Exception e) {
            AppLog.error("[task] tool selection failed", e);
        }
        AppLog.info("[task] selectedTools=" + validSelectedTools);

        // Step 2: 生成“可执行任务计划”（reply + tasks[]），并按需注入对应业务域 skills prompt
        StringBuilder sb = new StringBuilder();
        sb.append(SkillPrompts.base());
        sb.append("You are an intent classifier. Analyze the user's input and map it to a sequence of tools to be executed.\n");
        sb.append("Current Date and Time: ").append(java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append("\n");
        sb.append("Note: If the user provides relative time (e.g., 'tomorrow', 'next week'), calculate the exact date based on the Current Date. For 'create_event', startTime and endTime MUST be in 'yyyy-MM-dd HH:mm:ss' format.\n");
        sb.append("IMPORTANT: You can chain multiple tools. If the output of one tool is required as input for the next tool (e.g., use the result of a query as the message content), use the placeholder '{{PREV_RESULT}}' as the parameter value. This placeholder will be replaced by the actual result of the previous tool execution.\n");
        sb.append("RULES: 1) Do NOT use SearchDingTalkUserTool unless the user asks for uid/userId. 2) For '把结果告诉/发给/通知 某人', use send_message with names/departments and content='{{PREV_RESULT}}'. 3) For ERP order queries like '查一下订单1063063', map the number to parameter orderId. 4) For self-selected stock group queries, use get_group_stock_quotes and extract groupName from phrases like 'XX分组/自选股XX/我的XX里面的股票'.\n");

        if (!validSelectedTools.isEmpty()) {
            java.util.Set<String> domains = new java.util.LinkedHashSet<>();
            for (String toolName : validSelectedTools) {
                Tool tool = ToolManager.get(toolName);
                if (tool != null) domains.add(tool.getBusinessDomain());
            }
            sb.append(SkillPrompts.forDomains(domains));
            sb.append("The tools available (selected from previous step) are:\n");
            for (String toolName : validSelectedTools) {
                Tool tool = ToolManager.get(toolName);
                if (tool == null) continue;
                sb.append("- Schema: ").append(ToolManager.toToolSchema(tool).toJSONString()).append("\n");
            }
        } else {
            sb.append("No specific tools were matched, but please provide a helpful reply.\n");
        }

        sb.append("\nUser Input: \"").append(text).append("\"\n");
        sb.append("\nReturn JSON only (no markdown, no ```json wrapper). The JSON must follow this structure:\n");
        sb.append("IMPORTANT: Use the EXACT parameter names as defined in the tool description. Do not use aliases or invent new parameter names (e.g. use 'maxProcessCount' NOT 'count' or 'limit').\n");
        sb.append("Note: For tasks involving sending notifications or messages (e.g., '通知', '发消息', '发送给'), the text immediately following these keywords is typically the recipient (user name or department name). Please infer the recipient based on this context.\n");
        sb.append("IMPORTANT: Extraction Policy: Values should generally be extracted from the user input. However, use common sense and basic semantic analysis to identify entities correctly (e.g., do not split names like '其二' into separate characters if they likely represent a single entity). You may normalize values if necessary (e.g. 'tomorrow' -> actual date), but do not invent unrelated values.\n");
        sb.append("{\n");
        if (!validSelectedTools.isEmpty()) {
            sb.append("  \"reply\": \"A polite reply in Chinese summarizing the plan. Do NOT ask for user confirmation or if they want to proceed. State that you are starting the tasks immediately.\",\n");
        } else {
            sb.append("  \"reply\": \"A polite reply in Chinese. If the user input is a greeting or chat, respond naturally. If the user is asking for a task that cannot be performed by the available tools (since none were selected), politely explain that you do not have that capability.\",\n");
        }
        sb.append("  \"tasks\": [\n");
        if (!validSelectedTools.isEmpty()) {
            sb.append("    {\n");
            sb.append("      \"tool\": \"tool_name\" (or null if no match found),\n");
            sb.append("      \"confidence\": \"high\" | \"medium\" | \"low\",\n");
            sb.append("      \"parameters\": {\n");
            sb.append("        \"paramName\": value\n");
            sb.append("      },\n");
            sb.append("      \"missing_info\": \"Description of missing MANDATORY information ONLY. If a parameter is optional or has a default value, do NOT list it here. Return empty string if all mandatory info is present.\"\n");
            sb.append("    }\n");
        }
        sb.append("  ]\n");
        sb.append("}");

        try {
            String jsonStr = LLMUtil.chat(sb.toString());
            jsonStr = jsonStr.replaceAll("```json", "").replaceAll("```", "").trim();

            JSONObject result = JSON.parseObject(jsonStr);
            String globalReply = result.getString("reply");
            JSONArray tasks = result.getJSONArray("tasks");

            if (tasks == null || tasks.isEmpty()) {
                String reply = (globalReply != null && !globalReply.isEmpty())
                        ? globalReply
                        : "抱歉，我不理解您的指令或当前不具备该能力。";
                reply = reply + "\n\n如果需要了解当前Agent支持哪些工作，请直接问我：你能做什么";
                sendTextSafe(messenger, reply);
                return;
            }

            // Step 3: 对 LLM 规划的 tasks 做轻量补参（由各 Tool 自己实现 enrichPlannedTask）
            TaskPlanEnricher.enrich(text, tasks);

            StringBuilder notification = new StringBuilder();
            if (globalReply != null) notification.append(globalReply);

            List<JSONObject> validTasks = new ArrayList<>();

            // Step 4: 过滤掉低置信度/缺必填参数的任务；同时把默认参数信息回传给用户（可读性提示）
            for (int i = 0; i < tasks.size(); i++) {
                JSONObject task = tasks.getJSONObject(i);
                String toolName = task.getString("tool");
                String confidence = task.getString("confidence");
                JSONObject params = task.getJSONObject("parameters");
                String missingInfo = task.getString("missing_info");
                if ("null".equalsIgnoreCase(missingInfo)) missingInfo = null;

                if (toolName != null && ToolManager.contains(toolName) && ("high".equalsIgnoreCase(confidence) || "medium".equalsIgnoreCase(confidence))) {
                    Tool tool = ToolManager.get(toolName);
                    if (tool == null) continue;
                    Map<String, String> defaults = extractDefaultParamsFromDescription(tool.getDescription());
                    List<String> defaultEntries = new ArrayList<>();
                    if (defaults != null && !defaults.isEmpty()) {
                        for (Map.Entry<String, String> e : defaults.entrySet()) {
                            String k = e.getKey();
                            if (params == null || !params.containsKey(k)) {
                                String zhName = ToolManager.getParamDisplayName(tool, k);
                                defaultEntries.add(zhName + "=" + e.getValue());
                            }
                        }
                    }

                    if (missingInfo != null && !missingInfo.isEmpty()) {
                        notification.append("\n[任务：").append(toolName).append("] 缺少必选参数：").append(missingInfo);
                        if (!defaultEntries.isEmpty()) {
                            notification.append("。可选参数默认值：").append(String.join("，", defaultEntries));
                        }
                    } else {
                        validTasks.add(task);
                        if (!defaultEntries.isEmpty()) {
                            notification.append("\n[任务：").append(toolName).append("] 将使用默认参数：").append(String.join("，", defaultEntries));
                        }
                    }
                }
            }

            if (notification.length() > 0) {
                sendTextSafe(messenger, notification.toString());
            }

            boolean hasSendMessageTool = false;
            for (JSONObject task : validTasks) {
                if ("send_message".equals(task.getString("tool"))) {
                    hasSendMessageTool = true;
                    break;
                }
            }

            // Step 5: 顺序执行任务链
            // - 支持 {{PREV_RESULT}} 的链式参数传递
            // - send_message 默认可将上一工具的结果作为 content（若未显式传 content）
            // - 若任务链里存在 send_message，则其它工具执行时默认不沿用 @ 人员（避免“执行工具的过程消息”被 @）
            String previousResult = null;
            for (JSONObject task : validTasks) {
                String toolName = task.getString("tool");
                JSONObject params = task.getJSONObject("parameters");

                if ("send_message".equals(toolName) && previousResult != null) {
                    String content = params == null ? null : params.getString("content");
                    if (content == null || content.trim().isEmpty()) {
                        if (params == null) {
                            params = new JSONObject();
                            task.put("parameters", params);
                        }
                        params.put("content", previousResult);
                    }
                }

                if (previousResult != null && params != null) {
                    for (String key : params.keySet()) {
                        Object val = params.get(key);
                        if (val instanceof String) {
                            String strVal = (String) val;
                            if (strVal.contains("{{PREV_RESULT}}")) {
                                params.put(key, strVal.replace("{{PREV_RESULT}}", previousResult));
                            }
                        }
                    }
                }

                ToolContext executionContext = context;
                ToolMessenger executionMessenger = messenger;
                if (hasSendMessageTool && !"send_message".equals(toolName) && messenger != null) {
                    executionMessenger = messenger.withMentionedUserIds(new ArrayList<>());
                }

                Tool tool = ToolManager.get(toolName);
                if (tool == null) continue;
                if (!ensureComponentsReady(tool, messenger)) {
                    previousResult = "SKIPPED: missing required components for tool " + toolName;
                    continue;
                }
                long toolBegin = System.nanoTime();
                AppLog.info("[tool] execute begin, name=" + toolName + ", requiredComponents=" + requiredComponentIds(tool) + ", params=" + summarizeParams(params));
                try {
                    String executionResult = tool.execute(params, executionContext, executionMessenger);
                    long costMs = (System.nanoTime() - toolBegin) / 1_000_000;
                    AppLog.info("[tool] execute done, name=" + toolName + ", costMs=" + costMs);
                    previousResult = executionResult;
                } catch (Exception e) {
                    long costMs = (System.nanoTime() - toolBegin) / 1_000_000;
                    AppLog.error("[tool] execute failed, name=" + toolName + ", costMs=" + costMs, e);
                    sendTextSafe(messenger, "[任务失败] " + toolName + ": " + e.getMessage());
                    previousResult = "ERROR: " + e.getMessage();
                }
            }

        } catch (Exception e) {
            AppLog.error(e);
            try {
                sendTextSafe(messenger, "指令解析失败: " + e.getMessage());
            } catch (Exception ex) {
                AppLog.error(ex);
            }
        } finally {
            long costMs = (System.nanoTime() - processBegin) / 1_000_000;
            AppLog.info("[task] process done, costMs=" + costMs);
        }
    }

    static boolean tryExecuteDirectCommand(String text, ToolContext context, ToolMessenger messenger) {
        if (text == null || text.trim().isEmpty()) return false;

        // 直连模式：当未配置远程 LLM Key 时，支持 toolName key=value ... 的快速执行
        String[] parts = text.trim().split("\\s+", 2);
        String toolName = parts[0];

        if (!ToolManager.contains(toolName)) {
            return false;
        }

        String args = (parts.length > 1) ? parts[1].trim() : "";
        JSONObject params = new JSONObject();
        boolean hasExplicitParams = false;

        if (!args.isEmpty()) {
            java.util.regex.Pattern p = java.util.regex.Pattern.compile("(\\w+)=(?:\"([^\"]*)\"|([^\\s]+))");
            java.util.regex.Matcher m = p.matcher(args);
            while (m.find()) {
                hasExplicitParams = true;
                String key = m.group(1);
                String value = m.group(2) != null ? m.group(2) : m.group(3);
                params.put(key, value);
            }

            if (!hasExplicitParams) {
                return false;
            }
        }

        try {
            long begin = System.nanoTime();
            AppLog.info("[tool] direct execute begin, name=" + toolName + ", params=" + summarizeParams(params));
            Tool tool = ToolManager.get(toolName);
            if (tool == null) return false;
            if (!ensureComponentsReady(tool, messenger)) {
                return true;
            }
            tool.execute(params, context, messenger);
            long costMs = (System.nanoTime() - begin) / 1_000_000;
            AppLog.info("[tool] direct execute done, name=" + toolName + ", costMs=" + costMs);
            return true;
        } catch (Exception e) {
            AppLog.error("[tool] direct execute failed, name=" + toolName, e);
            try {
                sendTextSafe(messenger, "Tool execution failed: " + e.getMessage());
            } catch (Exception ignored) {}
            return true;
        }
    }

    private static void sendTextSafe(ToolMessenger messenger, String content) {
        if (messenger == null) return;
        try {
            messenger.sendText(content);
        } catch (Exception ignored) {
        }
    }

    private static boolean ensureComponentsReady(Tool tool, ToolMessenger messenger) {
        if (tool == null) return true;
        List<ComponentId> required = tool.requiredComponents();
        if (required == null || required.isEmpty()) return true;

        // 组件依赖校验：requiredComponents 未 RUNNING 时尝试自动启动（已配置场景），否则提示用户 start_component
        ComponentManager mgr = ComponentManager.getInstance();
        for (ComponentId id : required) {
            if (id == null) continue;
            AgentComponent c = mgr.get(id);
            if (c == null) {
                AppLog.warn("[component] missing for tool=" + tool.getName() + ", component=" + id.id());
                sendTextSafe(messenger, "Tool requires component '" + id.id() + "' but it is not registered. Use list_components to view available components.");
                return false;
            }
            ComponentStatus st = c.status();
            ComponentState state = st == null ? null : st.getState();
            if (state == ComponentState.RUNNING) {
                continue;
            }

            if (c.isConfigured()) {
                AppLog.info("[component] auto start requested, tool=" + tool.getName() + ", component=" + id.id() + ", currentState=" + (state == null ? "UNKNOWN" : state.name()));
                try {
                    ComponentStatus startSt = mgr.start(id);
                    ComponentState newState = startSt == null ? null : startSt.getState();
                    if (newState == ComponentState.RUNNING) {
                        sendTextSafe(messenger, "组件已自动启动: " + id.id());
                        continue;
                    }
                    AppLog.warn("[component] auto start failed, tool=" + tool.getName() + ", component=" + id.id() + ", state=" + (newState == null ? "UNKNOWN" : newState.name()));
                } catch (Exception e) {
                    AppLog.error("[component] auto start failed, tool=" + tool.getName() + ", component=" + id.id(), e);
                }
            } else {
                AppLog.warn("[component] not configured for tool=" + tool.getName() + ", component=" + id.id());
            }

            String msg = "Tool requires component '" + id.id() + "' to be running. " +
                    "Current=" + (state == null ? "UNKNOWN" : state.name()) +
                    ". Start it with: start_component id=" + id.id();
            sendTextSafe(messenger, msg);
            return false;
        }
        return true;
    }

    private static String requiredComponentIds(Tool tool) {
        if (tool == null) return "[]";
        List<ComponentId> required = tool.requiredComponents();
        if (required == null || required.isEmpty()) return "[]";
        java.util.List<String> ids = new java.util.ArrayList<>();
        for (ComponentId id : required) {
            if (id != null) ids.add(id.id());
        }
        return ids.toString();
    }

    private static String summarizeParams(JSONObject params) {
        if (params == null || params.isEmpty()) return "{}";
        java.util.List<String> keys = new java.util.ArrayList<>(params.keySet());
        java.util.Collections.sort(keys);
        return "{keys=" + keys + "}";
    }

    private static String safeOneLine(String s) {
        if (s == null) return "null";
        String v = s.replace("\n", "\\n").replace("\r", "\\r").trim();
        if (v.length() > 500) return v.substring(0, 500) + "...";
        return v;
    }

    private static Map<String, String> extractDefaultParamsFromDescription(String description) {
        Map<String, String> map = new HashMap<>();
        if (description == null) return map;
        int idx = description.indexOf("Parameters:");
        if (idx < 0) return map;
        String part = description.substring(idx + "Parameters:".length()).trim();
        String[] segments = part.split(",");
        for (String seg : segments) {
            String s = seg.trim();
            int lp = s.indexOf('(');
            int rp = s.lastIndexOf(')');
            if (lp > 0 && rp > lp) {
                String name = s.substring(0, lp).trim();
                if ("none".equalsIgnoreCase(name)) {
                    continue;
                }
                String inside = s.substring(lp + 1, rp);
                int dIdx = inside.toLowerCase().indexOf("default");
                if (dIdx >= 0) {
                    String dv = inside.substring(dIdx + "default".length()).trim();
                    if (dv.startsWith(" ")) dv = dv.substring(1);
                    if (dv.startsWith(":")) dv = dv.substring(1).trim();
                    int commaIdx = dv.indexOf(',');
                    if (commaIdx >= 0) {
                        dv = dv.substring(0, commaIdx).trim();
                    }
                    map.put(name, dv);
                }
            }
        }
        return map;
    }

}
