package com.qiyi.tools;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.qiyi.util.LLMUtil;
import com.qiyi.tools.context.ConsoleToolContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.qiyi.tools.agent.ListCapabilitiesTool;
import com.qiyi.tools.agent.ShutdownAgentTool;
import com.qiyi.tools.android.TaobaoAppiumTool;
import com.qiyi.tools.dingtalk.CreateEventTool;
import com.qiyi.tools.dingtalk.SearchDingTalkUserTool;
import com.qiyi.tools.dingtalk.SendMessageTool;
import com.qiyi.tools.erp.ErpAfterSaleTool;
import com.qiyi.tools.erp.QueryErpOrderTool;
import com.qiyi.tools.futu.*;
import com.qiyi.tools.podcast.DownloadPodcastTool;
import com.qiyi.tools.wechat.PublishWechatTool;

/**
 * 工具管理类
 * <p>
 * 负责工具的注册、管理以及智能解析执行。
 * </p>
 */
public class ToolManager {

    private static final Map<String, String> PARAM_NAME_MAPPING = new HashMap<>();

    static {
        // 参数名中文映射
        PARAM_NAME_MAPPING.put("maxProcessCount", "单次最大处理数量");
        PARAM_NAME_MAPPING.put("maxTryTimes", "最大重试次数");
        PARAM_NAME_MAPPING.put("maxDuplicatePages", "最大重复页数");
        PARAM_NAME_MAPPING.put("downloadMaxProcessCount", "下载后最大处理数量");
        PARAM_NAME_MAPPING.put("threadPoolSize", "线程池大小");
        PARAM_NAME_MAPPING.put("isDraft", "是否存为草稿");
        PARAM_NAME_MAPPING.put("atUserIds", "@用户列表");
        PARAM_NAME_MAPPING.put("userIds", "接收人用户ID列表");
        PARAM_NAME_MAPPING.put("names", "接收人姓名列表");
        PARAM_NAME_MAPPING.put("content", "消息内容");
        PARAM_NAME_MAPPING.put("summary", "日程标题");
        PARAM_NAME_MAPPING.put("startTime", "开始时间");
        PARAM_NAME_MAPPING.put("endTime", "结束时间");
        PARAM_NAME_MAPPING.put("description", "日程描述");
        PARAM_NAME_MAPPING.put("location", "地点");
        PARAM_NAME_MAPPING.put("attendees", "参与人");
        PARAM_NAME_MAPPING.put("departments", "部门列表");
        PARAM_NAME_MAPPING.put("orderId", "订单号");
        PARAM_NAME_MAPPING.put("name", "用户姓名关键词");
    }

    /**
     * 初始化工具注册
     */
    public static void init() {
        registerTools();
    }

    public static void registerTools() {
        // 避免重复注册
        if (!ToolRegistry.getAll().isEmpty()) {
            return;
        }

        ToolRegistry.register(new DownloadPodcastTool());
        ToolRegistry.register(new PublishWechatTool());
        ToolRegistry.register(new SendMessageTool());
        ToolRegistry.register(new SearchDingTalkUserTool());
        ToolRegistry.register(new CreateEventTool());
        ToolRegistry.register(new ShutdownAgentTool());
        ToolRegistry.register(new QueryErpOrderTool());
        ToolRegistry.register(new ErpAfterSaleTool());
        ToolRegistry.register(new GetStockQuoteTool());
        ToolRegistry.register(new GetMarketSnapshotTool());
        ToolRegistry.register(new GetCurKlineTool());
        ToolRegistry.register(new GetUserSecurityGroupTool());
        ToolRegistry.register(new GetUserSecurityTool());
        ToolRegistry.register(new GetGroupStockQuotesTool());
        ToolRegistry.register(new TaobaoAppiumTool());
        ToolRegistry.register(new ListCapabilitiesTool());
        
        // 异步初始化 ListCapabilitiesTool 的缓存，避免首次调用时延迟
        new Thread(() -> {
            try {
                System.out.println("Initializing capabilities cache...");
                new ListCapabilitiesTool().execute(null, new ConsoleToolContext());
                System.out.println("Capabilities cache initialized.");
            } catch (Exception e) {
                System.err.println("Failed to initialize capabilities cache: " + e.getMessage());
            }
        }).start();
    }

    /**
     * 分析用户意图并执行相应工具
     *
     * @param text    用户输入文本
     * @param context 工具执行上下文
     */
    public static void analyzeAndExecute(String text, ToolContext context) {
        // Step 1: Tool Selection
        StringBuilder selectionPrompt = new StringBuilder();
        selectionPrompt.append("You are an intent classifier. Analyze the user's input and select the tools that might be needed.\n");
        selectionPrompt.append("The available tools are:\n");
        for (Tool tool : ToolRegistry.getAll()) {
            selectionPrompt.append("- Tool: ").append(tool.getName()).append("\n");
            String desc = tool.getDescription();
            int idx = desc.indexOf("Parameters:");
            if (idx > 0) {
                 desc = desc.substring(0, idx).trim();
            }
            selectionPrompt.append("  Description: ").append(desc).append("\n");
        }
        selectionPrompt.append("\nUser Input: \"").append(text).append("\"\n");
        selectionPrompt.append("\nReturn JSON only. Format: { \"selected_tools\": [\"tool_name1\"] } or { \"selected_tools\": [] } if no tool matches.");
        selectionPrompt.append("\nIf the user asks about the agent's capabilities (e.g., '你能做什么', '工具能力', 'capabilities'), select the 'list_capabilities' tool.");

        List<String> validSelectedTools = new ArrayList<>();
        try {
            String selectionResponse = LLMUtil.chatWithDeepSeek(selectionPrompt.toString());
            if (selectionResponse != null && !selectionResponse.trim().isEmpty()) {
                selectionResponse = selectionResponse.replace("```json", "").replace("```", "").trim();
                JSONObject selectionJson = JSON.parseObject(selectionResponse);
                if (selectionJson.containsKey("selected_tools")) {
                    List<String> selectedTools = selectionJson.getJSONArray("selected_tools").toJavaList(String.class);
                    for (String t : selectedTools) {
                        if (ToolRegistry.contains(t)) {
                            validSelectedTools.add(t);
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("Tool selection failed: " + e.getMessage());
        }

        // Step 2: Execution Plan
        StringBuilder sb = new StringBuilder();
        sb.append("You are an intent classifier. Analyze the user's input and map it to a sequence of tools to be executed.\n");
        sb.append("Current Date and Time: ").append(java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))).append("\n");
        sb.append("Note: If the user provides relative time (e.g., 'tomorrow', 'next week'), calculate the exact date based on the Current Date. For 'create_event', startTime and endTime MUST be in 'yyyy-MM-dd HH:mm:ss' format.\n");
        sb.append("IMPORTANT: You can chain multiple tools. If the output of one tool is required as input for the next tool (e.g., use the result of a query as the message content), use the placeholder '{{PREV_RESULT}}' as the parameter value. This placeholder will be replaced by the actual result of the previous tool execution.\n");
        
        if (!validSelectedTools.isEmpty()) {
            sb.append("The tools available (selected from previous step) are:\n");
            for (String toolName : validSelectedTools) {
                Tool tool = ToolRegistry.get(toolName);
                sb.append("- Tool: ").append(tool.getName()).append("\n");
                sb.append("  Description: ").append(tool.getDescription()).append("\n");
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
            String jsonStr = LLMUtil.chatWithDeepSeek(sb.toString());
            // Clean up markdown if present
            jsonStr = jsonStr.replaceAll("```json", "").replaceAll("```", "").trim();
            
            JSONObject result = JSON.parseObject(jsonStr);
            String globalReply = result.getString("reply");
            JSONArray tasks = result.getJSONArray("tasks");

            if (tasks == null || tasks.isEmpty()) {
                String reply = (globalReply != null && !globalReply.isEmpty())
                        ? globalReply
                        : "抱歉，我不理解您的指令或当前不具备该能力。";
                reply = reply + "\n\n如果需要了解当前Agent支持哪些工作，请直接问我：你能做什么";
                if (context != null) {
                    context.sendText(reply);
                }
                return;
            }

            StringBuilder notification = new StringBuilder();
            if (globalReply != null) notification.append(globalReply);

            List<JSONObject> validTasks = new ArrayList<>();

            for (int i = 0; i < tasks.size(); i++) {
                JSONObject task = tasks.getJSONObject(i);
                String toolName = task.getString("tool");
                String confidence = task.getString("confidence");
                JSONObject params = task.getJSONObject("parameters");
                String missingInfo = task.getString("missing_info");
                if ("null".equalsIgnoreCase(missingInfo)) missingInfo = null;

                if (toolName != null && ToolRegistry.contains(toolName) && ("high".equalsIgnoreCase(confidence) || "medium".equalsIgnoreCase(confidence))) {
                    Map<String, String> defaults = extractDefaultParamsFromDescription(ToolRegistry.get(toolName).getDescription());
                    List<String> defaultEntries = new ArrayList<>();
                    if (defaults != null && !defaults.isEmpty()) {
                        for (Map.Entry<String, String> e : defaults.entrySet()) {
                            String k = e.getKey();
                            if (params == null || !params.containsKey(k)) {
                                String zhName = PARAM_NAME_MAPPING.getOrDefault(k, k);
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

            if (notification.length() > 0 && context != null) {
                context.sendText(notification.toString());
            }

            // Execute valid tasks with result chaining
            boolean hasSendMessageTool = false;
            for (JSONObject task : validTasks) {
                if ("send_message".equals(task.getString("tool"))) {
                    hasSendMessageTool = true;
                    break;
                }
            }

            String previousResult = null;
            for (JSONObject task : validTasks) {
                String toolName = task.getString("tool");
                JSONObject params = task.getJSONObject("parameters");
                
                // Parameter substitution
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

                // Execute and capture result
                ToolContext executionContext = context;
                if (hasSendMessageTool && !"send_message".equals(toolName) && context != null) {
                    // 如果任务链中有明确的 send_message 工具，则中间步骤不应向用户发送默认通知
                    executionContext = context.withAtUserIds(new ArrayList<>());
                }
                
                String executionResult = ToolRegistry.get(toolName).execute(params, executionContext);
                previousResult = executionResult;
            }

        } catch (Exception e) {
            e.printStackTrace();
             try {
                 if (context != null) {
                    context.sendText("指令解析失败: " + e.getMessage());
                 }
             } catch (Exception ex) {
                 ex.printStackTrace();
             }
         }
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
                // 忽略无参数占位（如：none）
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
