package com.qiyi.agent;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.qiyi.tools.Tool;
import com.qiyi.tools.ToolRegistry;

import java.util.Scanner;

/**
 * 命令行工具测试代理
 * 用于在不启动 DingTalkAgent 的情况下，直接测试各个 Tool 的功能。
 */
public class ConsoleAgent {

    public static void main(String[] args) {
        // 初始化工具管理器
        com.qiyi.tools.ToolManager.init();
        // 确保 DingTalkUtil 配置也加载（如果工具依赖它）
        // 但为了剥离，我们尽量减少显式依赖，除非必要
        try {
             // 某些工具可能仍依赖 DingTalkUtil 的静态配置（如 SendMessageTool）
             Class.forName("com.qiyi.util.DingTalkUtil");
        } catch (ClassNotFoundException ignored) {}
        
        System.out.println("环境初始化完成。");

        Scanner scanner = new Scanner(System.in);
        System.out.println("==========================================");
        System.out.println("欢迎使用 ConsoleAgent 工具测试控制台");
        System.out.println("此工具允许您直接调用已注册的 Tool 进行测试，无需通过钉钉交互。");
        System.out.println("输入 'help' 查看可用命令");
        System.out.println("==========================================");

        while (true) {
            System.out.print("ConsoleAgent> ");
            if (!scanner.hasNextLine()) break;
            String line = scanner.nextLine().trim();
            if (line.isEmpty()) continue;

            String[] parts = line.split("\\s+", 2);
            String command = parts[0].toLowerCase();
            String argsStr = parts.length > 1 ? parts[1] : "";

            try {
                switch (command) {
                    case "exit":
                    case "quit":
                        System.out.println("再见！");
                        // System.exit(0);
                        return;
                    case "help":
                        printHelp();
                        break;
                    case "list":
                        listTools();
                        break;
                    case "exec":
                        executeTool(argsStr);
                        break;
                    case "chat":
                        if (parts.length > 1) {
                            com.qiyi.tools.ToolManager.analyzeAndExecute(parts[1], new com.qiyi.tools.context.ConsoleToolContext());
                        } else {
                            System.out.println("请输入聊天内容。");
                        }
                        break;
                    default:
                        // 直接作为自然语言处理
                        com.qiyi.tools.ToolManager.analyzeAndExecute(line, new com.qiyi.tools.context.ConsoleToolContext());
                }
            } catch (Exception e) {
                System.err.println("执行出错: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    private static void printHelp() {
        System.out.println("可用命令:");
        System.out.println("  list                       列出所有已注册的工具");
        System.out.println("  exec <tool_name> [json]    执行指定工具");
        System.out.println("                             示例: exec list_capabilities");
        System.out.println("                             示例: exec search_keyword {\"keyword\":\"iPhone\"}");
        System.out.println("  chat <text>                (可选) 模拟用户聊天/指令");
        System.out.println("  <text>                     直接输入文本进行对话或指令");
        System.out.println("                             示例: 帮我查一下茅台股价");
        System.out.println("  exit / quit                退出程序");
    }

    private static void listTools() {
        System.out.println("已注册工具列表 (" + ToolRegistry.getAll().size() + "):");
        for (Tool tool : ToolRegistry.getAll()) {
            System.out.printf("- %-25s : %s%n", tool.getName(), tool.getDescription());
        }
    }

    private static void executeTool(String argsStr) {
        String[] parts = argsStr.split("\\s+", 2);
        if (parts.length < 1 || parts[0].isEmpty()) {
            System.out.println("参数错误。用法: exec <tool_name> [json_params]");
            return;
        }
        String toolName = parts[0];
        String jsonParams = parts.length > 1 ? parts[1] : "{}";

        if (!ToolRegistry.contains(toolName)) {
            System.out.println("错误: 找不到工具 '" + toolName + "'");
            return;
        }

        Tool tool = ToolRegistry.get(toolName);
        try {
            JSONObject params = JSON.parseObject(jsonParams);
            System.out.println(">> 正在执行工具: " + toolName);
            System.out.println(">> 参数: " + params.toJSONString());
            
            long startTime = System.currentTimeMillis();
            // 模拟 senderId 和 atUserIds
            String result = tool.execute(params, new com.qiyi.tools.context.ConsoleToolContext());
            long endTime = System.currentTimeMillis();
            
            System.out.println(">> 执行结果 (耗时 " + (endTime - startTime) + "ms):");
            System.out.println(result);
        } catch (Exception e) {
            System.out.println("参数解析失败或执行异常: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
