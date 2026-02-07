package com.qiyi.component.impl;

import com.qiyi.component.AgentComponent;
import com.qiyi.component.ComponentId;
import com.qiyi.component.ComponentStatus;
import com.qiyi.service.dingtalk.DingTalkConfig;
import com.qiyi.service.dingtalk.DingTalkService;
import com.qiyi.util.DingTalkUtil;

public final class DingTalkComponent implements AgentComponent {
    private static final ComponentId ID = ComponentId.DINGTALK;

    @Override
    public ComponentId id() {
        return ID;
    }

    @Override
    public String description() {
        return "DingTalk runtime (robot callbacks + API access)";
    }

    @Override
    public boolean isConfigured() {
        try {
            DingTalkConfig cfg = DingTalkConfig.fromAppConfig();
            cfg.requireRobotClientCredentials();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public String configurationHint() {
        return "Missing DingTalk robot config: dingtalk.robot.client.id / dingtalk.robot.client.secret";
    }

    @Override
    public void start() {
        DingTalkService.fromAppConfig().startRobotMsgCallbackConsumer();
    }

    @Override
    public void stop() {
        DingTalkService.fromAppConfig().stopRobotMsgCallbackConsumer();
    }

    @Override
    public ComponentStatus status() {
        boolean running = DingTalkUtil.isRobotMsgCallbackConsumerRunning();
        if (running) {
            return ComponentStatus.running(ID.id(), "running");
        }
        return ComponentStatus.stopped(ID.id(), "stopped");
    }
}
