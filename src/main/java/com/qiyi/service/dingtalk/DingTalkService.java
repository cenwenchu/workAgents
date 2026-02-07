package com.qiyi.service.dingtalk;

import com.qiyi.util.DingTalkUtil;

import java.io.File;
import java.util.List;

/**
 * 钉钉能力门面（Facade）。
 *
 * <p>职责：</p>
 * <ul>
 *     <li>统一从 {@link DingTalkConfig} 读取鉴权配置，并在调用前做必需项校验</li>
 *     <li>封装对 {@link DingTalkUtil} 的调用，避免上层工具/Agent 直接拼接 clientId/secret/robotCode</li>
 *     <li>提供机器人消息回调消费的启动/停止入口</li>
 * </ul>
 */
public class DingTalkService {
    private final DingTalkConfig config;

    public DingTalkService(DingTalkConfig config) {
        this.config = config;
    }

    public static DingTalkService fromAppConfig() {
        return new DingTalkService(DingTalkConfig.fromAppConfig());
    }

    public DingTalkConfig getConfig() {
        return config;
    }

    public void startRobotMsgCallbackConsumer() {
        config.requireRobotClientCredentials();
        DingTalkUtil.startRobotMsgCallbackConsumer(config.getRobotClientId(), config.getRobotClientSecret());
    }

    public void stopRobotMsgCallbackConsumer() {
        DingTalkUtil.stopRobotMsgCallbackConsumer();
    }

    public List<DingTalkDepartment> getAllDepartments(boolean isNeedUserList, boolean needCache) throws Exception {
        config.requireRobotClientCredentials();
        return DingTalkUtil.getAllDepartments(config.getRobotClientId(), config.getRobotClientSecret(), isNeedUserList, needCache);
    }

    public String getUnionIdByUserId(String userId) throws Exception {
        config.requireRobotClientCredentials();
        return DingTalkUtil.getUnionIdByUserId(config.getRobotClientId(), config.getRobotClientSecret(), userId);
    }

    public String createCalendarEvent(String userId, String summary, String description, String startTime, String endTime, List<String> attendeeUnionIds, String location) throws Exception {
        config.requireRobotClientCredentials();
        return DingTalkUtil.createCalendarEvent(config.getRobotClientId(), config.getRobotClientSecret(), userId, summary, description, startTime, endTime, attendeeUnionIds, location);
    }

    public boolean sendTextMessageToEmployees(List<String> userIds, String content) throws Exception {
        config.requireRobotClientCredentials();
        config.requireRobotCode();
        return DingTalkUtil.sendTextMessageToEmployees(config.getRobotClientId(), config.getRobotClientSecret(), config.getRobotCode(), userIds, content);
    }

    public boolean sendMarkdownMessageToEmployees(List<String> userIds, String title, String markdownText) throws Exception {
        config.requireRobotClientCredentials();
        config.requireRobotCode();
        return DingTalkUtil.sendMarkdownMessageToEmployees(config.getRobotClientId(), config.getRobotClientSecret(), config.getRobotCode(), userIds, title, markdownText);
    }

    public boolean sendImageMessageToEmployees(List<String> userIds, String photoUrl) throws Exception {
        config.requireRobotClientCredentials();
        config.requireRobotCode();
        return DingTalkUtil.sendImageMessageToEmployees(config.getRobotClientId(), config.getRobotClientSecret(), config.getRobotCode(), userIds, photoUrl);
    }

    public boolean sendLinkMessageToEmployees(List<String> userIds, String title, String text, String messageUrl, String picUrl) throws Exception {
        config.requireRobotClientCredentials();
        config.requireRobotCode();
        return DingTalkUtil.sendLinkMessageToEmployees(config.getRobotClientId(), config.getRobotClientSecret(), config.getRobotCode(), userIds, title, text, messageUrl, picUrl);
    }

    public String uploadMedia(File file) throws Exception {
        config.requireRobotClientCredentials();
        return DingTalkUtil.uploadMedia(config.getRobotClientId(), config.getRobotClientSecret(), file);
    }

    public void sendWorkNotificationImage(List<String> userIds, String mediaId) throws Exception {
        config.requireRobotClientCredentials();
        config.requireAgentId();
        DingTalkUtil.sendWorkNotificationImage(config.getRobotClientId(), config.getRobotClientSecret(), config.getAgentId(), userIds, mediaId);
    }

    public void sendImageFileToEmployees(List<String> userIds, File file) throws Exception {
        String mediaId = uploadMedia(file);
        sendWorkNotificationImage(userIds, mediaId);
    }

    public void sendAsyncWorkTextMessage(List<String> userIds, String content) {
        config.requireRobotClientCredentials();
        config.requireAgentId();
        DingTalkUtil.sendAsyncWorkTextMessage(config.getRobotClientId(), config.getRobotClientSecret(), config.getAgentId(), userIds, content);
    }

    public void sendAsyncWorkImageMessage(List<String> userIds, String mediaId) {
        config.requireRobotClientCredentials();
        config.requireAgentId();
        DingTalkUtil.sendAsyncWorkImageMessage(config.getRobotClientId(), config.getRobotClientSecret(), config.getAgentId(), userIds, mediaId);
    }
}
