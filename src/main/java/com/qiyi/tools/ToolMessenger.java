package com.qiyi.tools;

import java.io.File;
import java.util.List;

/**
 * 工具消息输出能力（与渠道无关的“回传通道”）。
 *
 * <p>实现类通常由 Agent 根据入口环境提供，例如：</p>
 * <ul>
 *     <li>钉钉入口：发送钉钉消息/Markdown/图片</li>
 *     <li>控制台入口：打印到标准输出或日志</li>
 * </ul>
 */
public interface ToolMessenger {
    List<String> getMentionedUserIds();

    ToolMessenger withMentionedUserIds(List<String> mentionedUserIds);

    void sendText(String content);

    void sendMarkdown(String title, String content);

    void sendImage(String imageUrl);

    void sendImage(File imageFile);
}
