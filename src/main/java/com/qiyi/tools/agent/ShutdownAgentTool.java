package com.qiyi.tools.agent;

import com.alibaba.fastjson2.JSONObject;
import com.qiyi.service.dingtalk.DingTalkService;
import com.qiyi.tools.Tool;
import com.qiyi.tools.ToolContext;
import com.qiyi.tools.ToolMessenger;
import com.qiyi.util.AppLog;

public class ShutdownAgentTool implements Tool {
    private static final DingTalkService DING_TALK_SERVICE = DingTalkService.fromAppConfig();

    @Override
    public String getName() {
        return "shutdown_agent";
    }

    @Override
    public String getDescription() {
        return "关闭钉钉机器人服务并退出 DingTalkAgent。Parameters: none.";
    }

    protected void stopRobotMsgCallbackConsumer() throws Exception {
        DING_TALK_SERVICE.stopRobotMsgCallbackConsumer();
    }

    @Override
    public String execute(JSONObject params, ToolContext context, ToolMessenger messenger) {
        try {
            if (messenger != null) messenger.sendText("已收到关闭指令，正在关闭钉钉机器人服务...");
        } catch (Exception e) {
            AppLog.error(e);
        }
        try {
            stopRobotMsgCallbackConsumer();
        } catch (Exception e) {
            AppLog.error(e);
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
