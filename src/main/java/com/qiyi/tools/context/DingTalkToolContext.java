package com.qiyi.tools.context;

import com.qiyi.tools.ToolContext;
import com.qiyi.util.DingTalkUtil;
import java.util.ArrayList;
import java.util.List;

public class DingTalkToolContext implements ToolContext {
    private final String senderId;
    private final List<String> atUserIds;
    private final List<String> notifyUsers;

    public DingTalkToolContext(String senderId, List<String> atUserIds) {
        this.senderId = senderId;
        this.atUserIds = atUserIds;
        this.notifyUsers = new ArrayList<>();
        if (senderId != null) {
            this.notifyUsers.add(senderId);
        }
        if (atUserIds != null) {
            this.notifyUsers.addAll(atUserIds);
        }
    }

    @Override
    public String getSenderId() {
        return senderId;
    }

    @Override
    public List<String> getAtUserIds() {
        return atUserIds;
    }

    @Override
    public void sendText(String content) {
        try {
            DingTalkUtil.sendTextMessageToEmployees(notifyUsers, content);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void sendMarkdown(String title, String content) {
        try {
            DingTalkUtil.sendMarkdownMessageToEmployees(notifyUsers, title, content);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void sendImage(String imageUrl) {
        try {
            DingTalkUtil.sendImageMessageToEmployees(notifyUsers, imageUrl);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void sendImage(java.io.File imageFile) {
        try {
            // Check if agent is configured (implied by DingTalkUtil usage, but let's be safe)
            if (DingTalkUtil.AGENT_ID != 0) {
                 String mediaId = DingTalkUtil.uploadMedia(DingTalkUtil.ROBOT_CLIENT_ID, DingTalkUtil.ROBOT_CLIENT_SECRET, imageFile);
                 DingTalkUtil.sendImageMessageToEmployees(notifyUsers, mediaId);
            } else {
                 // Fallback if not configured properly or generic usage
                 System.err.println("DingTalk Agent ID not configured, cannot upload image: " + imageFile.getAbsolutePath());
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public ToolContext withAtUserIds(List<String> atUserIds) {
        return new DingTalkToolContext(this.senderId, atUserIds);
    }
}
