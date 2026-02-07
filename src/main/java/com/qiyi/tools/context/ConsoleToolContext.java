package com.qiyi.tools.context;

import com.qiyi.tools.ToolContext;
import com.qiyi.tools.ToolMessenger;
import java.util.Collections;
import java.util.List;
import com.qiyi.util.AppLog;

/**
 * 控制台入口的工具上下文实现。
 *
 * <p>特点：</p>
 * <ul>
 *     <li>同时实现 {@link ToolContext} 与 {@link ToolMessenger}，便于在本地调试时复用同一套工具链路</li>
 *     <li>所有输出写入日志（不走外部消息通道）</li>
 * </ul>
 */
public class ConsoleToolContext implements ToolContext, ToolMessenger {
    @Override
    public String getUserId() {
        return "console_user";
    }

    @Override
    public String getEnterpriseId() {
        return "console";
    }

    @Override
    public void sendText(String content) {
        AppLog.info("[ConsoleToolContext] Text: " + content);
    }

    @Override
    public void sendMarkdown(String title, String content) {
        AppLog.info("[ConsoleToolContext] Markdown Title: " + title);
        AppLog.info("[ConsoleToolContext] Content: \n" + content);
    }

    @Override
    public void sendImage(String imageUrl) {
        AppLog.info("[ConsoleToolContext] Image: " + imageUrl);
    }

    @Override
    public void sendImage(java.io.File imageFile) {
        AppLog.info("[ConsoleToolContext] Local Image: " + imageFile.getAbsolutePath());
    }

    @Override
    public List<String> getMentionedUserIds() {
        return Collections.emptyList();
    }

    @Override
    public ToolMessenger withMentionedUserIds(List<String> mentionedUserIds) {
        return this;
    }
}
