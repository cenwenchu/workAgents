package com.qiyi.tools.context;

import com.qiyi.tools.ToolContext;
import com.qiyi.tools.ToolMessenger;
import com.qiyi.service.dingtalk.DingTalkService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import com.qiyi.util.AppLog;

/**
 * 钉钉入口的工具上下文实现。
 *
 * <p>特点：</p>
 * <ul>
 *     <li>同时实现 {@link ToolContext} 与 {@link ToolMessenger}：在钉钉场景下，执行上下文与消息回传通道天然绑定</li>
 *     <li>默认仅回传给指令发起人（sender）：避免将“规划回复/过程日志”推送给被通知目标用户</li>
 * </ul>
 */
public class DingTalkToolContext implements ToolContext, ToolMessenger {
    private static final DingTalkService DING_TALK_SERVICE = DingTalkService.fromAppConfig();

    private final String senderId;
    private final List<String> mentionedUserIds;
    private final List<String> notifyUsers;
    private final String enterpriseId;

    public DingTalkToolContext(String senderId, List<String> mentionedUserIds) {
        this(senderId, null, mentionedUserIds);
    }

    public DingTalkToolContext(String senderId, String enterpriseId, List<String> mentionedUserIds) {
        this.senderId = senderId;
        this.enterpriseId = enterpriseId;
        this.mentionedUserIds = mentionedUserIds != null ? mentionedUserIds : Collections.emptyList();
        this.notifyUsers = new ArrayList<>();
        if (senderId != null) {
            this.notifyUsers.add(senderId);
        }
    }

    @Override
    public String getUserId() {
        return senderId;
    }

    @Override
    public String getEnterpriseId() {
        return enterpriseId;
    }

    @Override
    public List<String> getMentionedUserIds() {
        return mentionedUserIds;
    }

    @Override
    public void sendText(String content) {
        try {
            DING_TALK_SERVICE.sendTextMessageToEmployees(notifyUsers, content);
        } catch (Exception e) {
            AppLog.error("[dingtalk] sendText failed, notifyUsers=" + notifyUsers, e);
        }
    }

    @Override
    public void sendMarkdown(String title, String content) {
        try {
            DING_TALK_SERVICE.sendMarkdownMessageToEmployees(notifyUsers, title, content);
        } catch (Exception e) {
            AppLog.error("[dingtalk] sendMarkdown failed, notifyUsers=" + notifyUsers, e);
        }
    }

    @Override
    public void sendImage(String imageUrl) {
        try {
            DING_TALK_SERVICE.sendImageMessageToEmployees(notifyUsers, imageUrl);
        } catch (Exception e) {
            AppLog.error("[dingtalk] sendImage failed, notifyUsers=" + notifyUsers, e);
        }
    }

    @Override
    public void sendImage(java.io.File imageFile) {
        try {
            DING_TALK_SERVICE.sendImageFileToEmployees(notifyUsers, imageFile);
        } catch (Exception e) {
            AppLog.error("[dingtalk] sendImageFile failed, notifyUsers=" + notifyUsers, e);
        }
    }

    @Override
    public ToolMessenger withMentionedUserIds(List<String> mentionedUserIds) {
        return new DingTalkToolContext(this.senderId, this.enterpriseId, mentionedUserIds);
    }
}
