package com.qiyi.tools.agent;

import com.alibaba.fastjson2.JSONObject;
import com.qiyi.tools.Tool;
import com.qiyi.tools.ToolRegistry;
import com.qiyi.util.DingTalkUtil;
import com.qiyi.util.LLMUtil;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ListCapabilitiesTool implements Tool {

    private static String cachedCapabilities = null;

    @Override
    public String getName() {
        return "list_capabilities";
    }

    @Override
    public String getDescription() {
        return "List all available tools and their capabilities. Use this tool when the user asks what the agent can do or asks for help.";
    }

    @Override
    public String execute(JSONObject params, String senderId, List<String> atUserIds) {
        String result = getCapabilities();
        
        // Send message to user if senderId is present (not pre-warming)
        if (senderId != null && !senderId.isEmpty()) {
            List<String> users = new ArrayList<>();
            users.add(senderId);
            try {
                System.out.println("ListCapabilitiesTool: Sending capabilities to " + senderId);
                // Use Markdown for better formatting
                boolean sent = DingTalkUtil.sendMarkdownMessageToEmployees(users, "Agent 能力列表", result);
                if (!sent) {
                     System.err.println("ListCapabilitiesTool: Markdown send returned false, trying text");
                     DingTalkUtil.sendTextMessageToEmployees(users, result);
                }
            } catch (Exception e) {
                System.err.println("ListCapabilitiesTool: Failed to send message: " + e.getMessage());
                e.printStackTrace();
                // Fallback to text
                try {
                    DingTalkUtil.sendTextMessageToEmployees(users, result);
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
            }
        } else {
             System.out.println("ListCapabilitiesTool: No senderId provided, skipping message send.");
        }
        
        return result;
    }

    private String getCapabilities() {
        if (cachedCapabilities != null && !cachedCapabilities.isEmpty()) {
            return cachedCapabilities;
        }

        Collection<Tool> tools = ToolRegistry.getAll();
        Map<String, List<Tool>> toolsByCategory = new HashMap<>();

        for (Tool tool : tools) {
            // Skip listing itself
            if (tool.getName().equals(this.getName())) continue;

            String packageName = tool.getClass().getPackage().getName();
            String category = getCategoryFromPackage(packageName);
            
            toolsByCategory.computeIfAbsent(category, k -> new ArrayList<>()).add(tool);
        }

        StringBuilder toolListBuilder = new StringBuilder();
        for (Map.Entry<String, List<Tool>> entry : toolsByCategory.entrySet()) {
            toolListBuilder.append("Category: ").append(entry.getKey()).append("\n");
            for (Tool tool : entry.getValue()) {
                toolListBuilder.append("  - Tool Name: ").append(tool.getName()).append("\n");
                toolListBuilder.append("    Description: ").append(tool.getDescription()).append("\n");
            }
            toolListBuilder.append("\n");
        }

        String prompt = "我需要你根据以下工具列表，生成一段面向最终用户的中文能力介绍。\n" +
                "请严格遵守以下格式要求：\n" +
                "1. 都用中文回答。\n" +
                "2. 介绍每个工具的使用场景（Functionality/Scenario）。\n" +
                "3. 简单描述必选参数（Required Parameters）。\n" +
                "4. 语言通俗易懂，适合非技术用户。\n" +
                "5. **必须保留并优化分类结构**。请将工具按照提供的 Category 进行归类展示，将 Category 翻译为通俗易懂的中文标题（例如 'android' -> '📱 安卓设备控制', 'futu' -> '📈 股市行情', 'dingtalk' -> '💬 钉钉办公' 等）。\n" +
                "6. 格式示例：\n" +
                "   ### 📈 股市行情\n" +
                "   1. **查询股价**：...\n" +
                "   2. **获取K线**：...\n" +
                "\n" +
                "   ### 📱 安卓设备控制\n" +
                "   1. **打开应用**：...\n" +
                "\n" +
                "以下是原始工具列表：\n" +
                toolListBuilder.toString();

        try {
            String response = LLMUtil.chatWithDeepSeek(prompt);
            if (response != null && !response.trim().isEmpty()) {
                cachedCapabilities = "我目前具备以下能力：\n\n" + response;
                return cachedCapabilities;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        // Fallback to basic list if LLM fails
        return generateFallbackList(toolsByCategory);
    }

    private String getCategoryFromPackage(String packageName) {
        // Assume package format com.qiyi.tools.xxx
        // We want 'xxx'
        String prefix = "com.qiyi.tools.";
        if (packageName.startsWith(prefix)) {
            String sub = packageName.substring(prefix.length());
            int dotIndex = sub.indexOf('.');
            if (dotIndex > 0) {
                return sub.substring(0, dotIndex);
            } else {
                return sub;
            }
        }
        return "General";
    }

    private String generateFallbackList(Map<String, List<Tool>> toolsByCategory) {
        StringBuilder sb = new StringBuilder();
        sb.append("我目前具备以下能力（自动生成）：\n\n");
        
        for (Map.Entry<String, List<Tool>> entry : toolsByCategory.entrySet()) {
            sb.append("## Category: ").append(entry.getKey()).append("\n");
            for (Tool tool : entry.getValue()) {
                sb.append("### ").append(tool.getName()).append("\n");
                sb.append(tool.getDescription()).append("\n\n");
            }
        }
        return sb.toString();
    }
}
