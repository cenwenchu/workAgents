package com.qiyi.tools;

import java.util.List;

/**
 * 工具执行上下文，封装了环境相关的信息和操作（如消息发送）
 */
public interface ToolContext {
    /**
     * 获取发起工具调用的用户ID
     */
    String getSenderId();

    /**
     * 获取被@的用户ID列表
     */
    List<String> getAtUserIds();

    /**
     * 发送文本消息（自动发送给上下文中的相关用户）
     */
    void sendText(String content);

    /**
     * 发送Markdown消息
     */
    void sendMarkdown(String title, String content);

    /**
     * 发送图片消息
     */
    void sendImage(String imageUrl);

    /**
     * 发送本地图片消息
     */
    void sendImage(java.io.File imageFile);

    /**
     * 创建一个具有不同@用户列表的新上下文副本
     */
    ToolContext withAtUserIds(List<String> atUserIds);
}
