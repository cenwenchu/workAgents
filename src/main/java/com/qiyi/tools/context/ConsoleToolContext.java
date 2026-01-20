package com.qiyi.tools.context;

import com.qiyi.tools.ToolContext;
import java.util.Collections;
import java.util.List;

public class ConsoleToolContext implements ToolContext {
    @Override
    public String getSenderId() {
        return "console_user";
    }

    @Override
    public List<String> getAtUserIds() {
        return Collections.emptyList();
    }

    @Override
    public void sendText(String content) {
        System.out.println("[ConsoleToolContext] Text: " + content);
    }

    @Override
    public void sendMarkdown(String title, String content) {
        System.out.println("[ConsoleToolContext] Markdown Title: " + title);
        System.out.println("[ConsoleToolContext] Content: \n" + content);
    }

    @Override
    public void sendImage(String imageUrl) {
        System.out.println("[ConsoleToolContext] Image: " + imageUrl);
    }

    @Override
    public void sendImage(java.io.File imageFile) {
        System.out.println("[ConsoleToolContext] Local Image: " + imageFile.getAbsolutePath());
    }

    @Override
    public ToolContext withAtUserIds(List<String> atUserIds) {
        // Console context ignores atUserIds
        return this;
    }
}
