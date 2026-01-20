package com.qiyi.tools.agent;

import com.alibaba.fastjson2.JSONObject;
import com.qiyi.tools.Tool;
import com.qiyi.tools.ToolContext;
import com.qiyi.util.DingTalkUtil;

public class ShutdownAgentTool implements Tool {
    @Override
    public String getName() {
        return "shutdown_agent";
    }

    @Override
    public String getDescription() {
        return "关闭钉钉机器人服务并退出 DingTalkAgent。Parameters: none.";
    }

    protected void stopRobotMsgCallbackConsumer() throws Exception {
        DingTalkUtil.stopRobotMsgCallbackConsumer();
    }

    @Override
    public String execute(JSONObject params, ToolContext context) {
        try {
            context.sendText("已收到关闭指令，正在关闭钉钉机器人服务...");
        } catch (Exception e) {
            e.printStackTrace();
        }
        try {
            stopRobotMsgCallbackConsumer();
        } catch (Exception e) {
            e.printStackTrace();
        }
        try {
            Thread.sleep(1000);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        exit(0);
        return "已关闭钉钉机器人服务并退出 DingTalkAgent。";
    }

    protected void exit(int status) {
        System.exit(status);
    }
}
