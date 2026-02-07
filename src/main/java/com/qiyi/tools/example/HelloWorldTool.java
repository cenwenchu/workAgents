package com.qiyi.tools.example;

import com.alibaba.fastjson2.JSONObject;
import com.qiyi.tools.Tool;
import com.qiyi.tools.ToolContext;
import com.qiyi.tools.ToolMessenger;

/**
 * HelloWorld 工具
 * <p>
 * 这是一个示范性的入门工具，旨在帮助开发者快速了解如何创建自定义工具。
 * 它展示了参数接收、消息反馈以及如何提示开发者使用更高级的系统能力。
 * </p>
 */
public class HelloWorldTool implements Tool {

    @Override
    public String getName() {
        return "hello_world";
    }

    @Override
    public String getDescription() {
        return "新手入门工具：Hello World！演示参数接收、消息发送及大模型调用代码示例。参数：name (您的名字，可选)";
    }

    @Override
    public String execute(JSONObject params, ToolContext context, ToolMessenger messenger) {
        // 1. 获取参数
        // 假设用户输入： hello_world name="WorkAgents"
        String name = null;
        if (params != null) {
            name = params.getString("name");
        }
        
        if (name == null || name.isEmpty()) {
            name = "开发者";
        }

        // 2. 发送简单文本消息
        // messenger.sendText() 会根据运行环境（控制台或钉钉）自动路由消息
        // 这是最基础的交互方式
        String welcomeMsg = "Hello, " + name + "! 欢迎来到 WorkAgents 世界。";
        if (messenger != null) messenger.sendText(welcomeMsg);

        // 3. 演示如何进行更复杂的业务逻辑
        // 这里通过发送一段教学文本，指导开发者如何利用系统能力
        StringBuilder guide = new StringBuilder();
        guide.append("这是一个教学示例，您可以在 `com.qiyi.tools.example.HelloWorldTool.java` 中修改代码来实现更强大的业务：\n\n");
        
        guide.append("1. **获取用户输入**:\n");
        guide.append("   `String value = params.getString(\"key\");`\n");
        guide.append("   在 ToolManager 中注册参数中文名，可以让 LLM 更准确地提取参数。\n\n");
        
        guide.append("2. **发送多种消息**:\n");
        guide.append("   `messenger.sendText(\"文本\");`\n");
        guide.append("   `messenger.sendImage(\"图片URL\");`\n");
        guide.append("   // 钉钉环境下还支持 Markdown, Link 等丰富格式\n\n");
        
        guide.append("3. **调用大模型 (LLM)**:\n");
        guide.append("   WorkAgents 封装了 LLMUtil，您可以轻松接入 DeepSeek 或 Gemini：\n");
        guide.append("   ```java\n");
        guide.append("   List<Message> msgs = new ArrayList<>();\n");
        guide.append("   msgs.add(UserMessage.builder().addText(\"你的提示词\").build());\n");
        guide.append("   String reply = LLMUtil.chatWithDeepSeek(msgs, false);\n");
        guide.append("   messenger.sendText(\"AI回复: \" + reply);\n");
        guide.append("   ```\n\n");

        guide.append("4. **集成业务系统**:\n");
        guide.append("   您可以引入自己的 Service 或 Util 类（如 DatabaseUtil, ErpService），在 execute 方法中调用它们，实现真正的自动化业务闭环。\n");
        
        guide.append("\n现在，尝试修改这个文件，打印一句属于您自己的问候语吧！");

        if (messenger != null) messenger.sendText(guide.toString());

        return "执行完成: " + welcomeMsg;
    }
}
