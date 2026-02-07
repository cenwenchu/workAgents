package com.qiyi.agent;

import com.qiyi.tools.TaskProcessor;
import com.qiyi.tools.ToolContext;
import com.qiyi.tools.ToolMessenger;
import com.qiyi.tools.context.ConsoleToolContext;
import com.qiyi.util.AppLog;

import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.Scanner;

/**
 * 命令行工具测试代理
 * 用于在不启动 DingTalkAgent 的情况下，直接测试各个 Tool 的功能。
 */
public class ConsoleAgent extends AbstractAgent {
    private final ToolContext context;

    public static void main(String[] args) {
        new ConsoleAgent().start();
    }

    public ConsoleAgent() {
        this.context = new ConsoleToolContext();
    }

    @Override
    protected void doStart() {
        com.qiyi.tools.ToolManager.init();
        AppLog.info("环境初始化完成。");

        Scanner scanner = new Scanner(System.in);
        AppLog.info("==========================================");
        AppLog.info("欢迎使用 ConsoleAgent");
        AppLog.info("直接输入自然语言指令进行聊天，输入 exit/quit 退出");
        AppLog.info("==========================================");

        while (isRunning()) {
            AppLog.info("ConsoleAgent> ");
            if (!scanner.hasNextLine()) break;
            String line = scanner.nextLine();
            if (line == null) continue;
            line = line.trim();
            if (line.isEmpty()) continue;
            if ("exit".equalsIgnoreCase(line) || "quit".equalsIgnoreCase(line)) {
                AppLog.info("再见！");
                stop();
                break;
            }
            try {
                chat(line);
            } catch (Exception e) {
                AppLog.error("执行出错: " + e.getMessage());
                AppLog.error(e);
            }
        }
        scanner.close();
    }

    @Override
    public String chat(String userInput) {
        if (userInput == null) return "";
        String input = userInput.trim();
        if (input.isEmpty()) return "";
        CapturingMessenger messenger = new CapturingMessenger();
        TaskProcessor.process(input, context, messenger);
        return messenger.getCaptured();
    }

    private static final class CapturingMessenger implements ToolMessenger {
        private final StringBuilder captured = new StringBuilder();
        private List<String> mentionedUserIds = Collections.emptyList();

        @Override
        public List<String> getMentionedUserIds() {
            return mentionedUserIds;
        }

        @Override
        public ToolMessenger withMentionedUserIds(List<String> mentionedUserIds) {
            this.mentionedUserIds = mentionedUserIds == null ? Collections.emptyList() : mentionedUserIds;
            return this;
        }

        @Override
        public void sendText(String content) {
            if (content != null) {
                captured.append(content).append("\n");
            }
            AppLog.info("[ConsoleAgent] Text: " + content);
        }

        @Override
        public void sendMarkdown(String title, String content) {
            if (title != null) {
                captured.append(title).append("\n");
            }
            if (content != null) {
                captured.append(content).append("\n");
            }
            AppLog.info("[ConsoleAgent] Markdown Title: " + title);
            AppLog.info("[ConsoleAgent] Content: \n" + content);
        }

        @Override
        public void sendImage(String imageUrl) {
            if (imageUrl != null) {
                captured.append(imageUrl).append("\n");
            }
            AppLog.info("[ConsoleAgent] Image: " + imageUrl);
        }

        @Override
        public void sendImage(File imageFile) {
            String path = imageFile == null ? null : imageFile.getAbsolutePath();
            if (path != null) {
                captured.append(path).append("\n");
            }
            AppLog.info("[ConsoleAgent] Local Image: " + path);
        }

        public String getCaptured() {
            return captured.toString().trim();
        }
    }
}
