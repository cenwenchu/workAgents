package com.qiyi.tools.example;

import com.alibaba.fastjson2.JSONObject;
import com.qiyi.tools.Tool;
import com.qiyi.tools.ToolContext;
import com.qiyi.tools.ToolMessenger;

/**
 * 示例工具：Hello World。
 *
 * <p>该类用于演示最简 Tool 开发方式：仅实现 {@link Tool#execute(JSONObject, ToolContext, ToolMessenger)}，
 * 并通过 {@link Tool.Info} 提供 name/description 等元信息，从而可被系统自动发现与注册。</p>
 */
@Tool.Info(
        name = "hello_world",
        description = "新手入门工具：Hello World！演示参数接收、消息发送及大模型调用代码示例。参数：name (您的名字，可选)",
        businessDomain = "com.qiyi.tools.example"
)
public class HelloWorldTool implements Tool {

    /**
     * 生成欢迎语文本（不依赖上下文，便于单元测试与复用）。
     */
    public String hello(String name) {
        String n = name;
        if (n == null || n.isEmpty()) {
            n = "开发者";
        }
        return "Hello, " + n + "! 欢迎来到 WorkAgents 世界。";
    }

    /**
     * 执行示例：读取可选参数 name，生成欢迎语，并通过 messenger 回传。
     */
    @Override
    public String execute(JSONObject params, ToolContext context, ToolMessenger messenger) {
        String name = params == null ? null : params.getString("name");
        String n = name;
        if (n == null || n.isEmpty()) {
            n = "开发者";
        }

        String welcomeMsg = hello(n);

        if (messenger != null) {
            messenger.sendText(welcomeMsg);
        }
        
        return "执行完成: " + welcomeMsg;
    }
}
