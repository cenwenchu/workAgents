package com.qiyi.service.dingtalk;

import com.qiyi.config.AppConfig;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public class DingTalkConfig {
    private final String robotToken;
    private final String robotSecret;
    private final String robotClientId;
    private final String robotClientSecret;
    private final String robotCode;
    private final long agentId;
    private final List<String> podcastAdminUsers;

    public DingTalkConfig(
            String robotToken,
            String robotSecret,
            String robotClientId,
            String robotClientSecret,
            String robotCode,
            long agentId,
            List<String> podcastAdminUsers
    ) {
        this.robotToken = robotToken;
        this.robotSecret = robotSecret;
        this.robotClientId = robotClientId;
        this.robotClientSecret = robotClientSecret;
        this.robotCode = robotCode;
        this.agentId = agentId;
        this.podcastAdminUsers = podcastAdminUsers != null ? podcastAdminUsers : Collections.emptyList();
    }

    public static DingTalkConfig fromAppConfig() {
        AppConfig config = AppConfig.getInstance();

        String agentIdStr = config.getProperty(AppConfig.KEY_DINGTALK_AGENT_ID);
        long agentId = 0L;
        if (agentIdStr != null && !agentIdStr.isEmpty()) {
            try {
                agentId = Long.parseLong(agentIdStr);
            } catch (NumberFormatException ignored) {
            }
        }

        List<String> adminUsers;
        String users = config.getProperty(AppConfig.KEY_ADMIN_USERS);
        if (users != null && !users.isEmpty()) {
            adminUsers = Arrays.stream(users.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toList());
        } else {
            adminUsers = Collections.emptyList();
        }

        return new DingTalkConfig(
                config.getProperty(AppConfig.KEY_DINGTALK_TOKEN),
                config.getProperty(AppConfig.KEY_DINGTALK_SECRET),
                config.getProperty(AppConfig.KEY_DINGTALK_CLIENT_ID),
                config.getProperty(AppConfig.KEY_DINGTALK_CLIENT_SECRET),
                config.getProperty(AppConfig.KEY_DINGTALK_CODE),
                agentId,
                adminUsers
        );
    }

    public String getRobotToken() {
        return robotToken;
    }

    public String getRobotSecret() {
        return robotSecret;
    }

    public String getRobotClientId() {
        return robotClientId;
    }

    public String getRobotClientSecret() {
        return robotClientSecret;
    }

    public String getRobotCode() {
        return robotCode;
    }

    public long getAgentId() {
        return agentId;
    }

    public List<String> getPodcastAdminUsers() {
        return podcastAdminUsers;
    }

    public boolean hasRobotClientCredentials() {
        return !isBlank(robotClientId) && !isBlank(robotClientSecret);
    }

    public boolean hasRobotCode() {
        return !isBlank(robotCode);
    }

    public boolean hasAgentId() {
        return agentId > 0;
    }

    public void requireRobotClientCredentials() {
        if (hasRobotClientCredentials()) return;
        throw new IllegalStateException("DingTalk config missing: dingtalk.robot.client.id / dingtalk.robot.client.secret");
    }

    public void requireRobotCode() {
        if (hasRobotCode()) return;
        throw new IllegalStateException("DingTalk config missing: dingtalk.robot.code");
    }

    public void requireAgentId() {
        if (hasAgentId()) return;
        throw new IllegalStateException("DingTalk config missing: dingtalk.agent.id");
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
