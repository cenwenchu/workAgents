package com.qiyi.agent;

import java.util.Arrays;
import java.util.List;
import com.qiyi.util.AppLog;

import com.qiyi.service.dingtalk.DingTalkDepartment;
import com.qiyi.service.dingtalk.DingTalkUser;
import com.qiyi.service.dingtalk.DingTalkConfig;
import com.qiyi.service.dingtalk.DingTalkService;
import com.qiyi.tools.TaskProcessor;
import com.qiyi.tools.ToolContext;
import com.qiyi.tools.ToolMessenger;
import com.qiyi.tools.context.ConsoleToolContext;

/**
 * 钉钉入口 Agent：
 * <ul>
 *     <li>启动 DingTalkService（机器人回调消费）</li>
 *     <li>通过 RobotMsgCallbackConsumer 接收消息并交给 TaskProcessor 规划与执行</li>
 * </ul>
 *
 * <p>运行所需的钉钉与管理员配置由 {@link com.qiyi.config.AppConfig} 从 {@code agent.cfg} 读取。</p>
 */
public class DingTalkAgent extends AbstractAgent {
    private final ToolContext chatContext;
    private DingTalkService dingTalkService;

    public DingTalkAgent() {
        this.chatContext = new ConsoleToolContext();
    }

    public static void main(String[] args) throws Exception {
        new DingTalkAgent().start();
    }

    /**
     * 启动钉钉入口：初始化 Tool/组件、启动钉钉回调消费并进入进程保活循环。
     */
    @Override
    protected void doStart() {
        com.qiyi.tools.ToolManager.init();
        this.dingTalkService = DingTalkService.fromAppConfig();
        DingTalkConfig dingTalkConfig = this.dingTalkService.getConfig();
        this.dingTalkService.startRobotMsgCallbackConsumer();
        AppLog.info("[agent] DingTalk robot callback consumer started");

        List<DingTalkDepartment> allDepartments = null;
        try {
            allDepartments = this.dingTalkService.getAllDepartments(true, true);
            int deptCount = allDepartments == null ? 0 : allDepartments.size();
            int userCount = 0;
            if (allDepartments != null) {
                for (DingTalkDepartment dept : allDepartments) {
                    if (dept != null && dept.getUserList() != null) {
                        userCount += dept.getUserList().size();
                    }
                }
            }
            AppLog.info("[agent] DingTalk org loaded, deptCount=" + deptCount + ", userCount=" + userCount);
        } catch (Exception e) {
            AppLog.error(e);
        }

        if (dingTalkConfig.getPodcastAdminUsers() != null && !dingTalkConfig.getPodcastAdminUsers().isEmpty()) {
            for (String adminUserId : dingTalkConfig.getPodcastAdminUsers()) {
                DingTalkUser targetUser = null;
                if (allDepartments != null) {
                    for (DingTalkDepartment dept : allDepartments) {
                        if (dept.getUserList() != null) {
                            for (DingTalkUser u : dept.getUserList()) {
                                if (u.getUserid().equals(adminUserId)) {
                                    targetUser = u;
                                    break;
                                }
                            }
                        }
                        if (targetUser != null) break;
                    }
                }

                if (targetUser != null) {
                    AppLog.info("[agent] podcast admin resolved: " + targetUser.getName() + " (" + targetUser.getUserid() + ")");
                } else {
                    AppLog.warn("[agent] podcast admin userId configured but not found in org cache: " + adminUserId);
                }

                try {
                    this.dingTalkService.sendTextMessageToEmployees(Arrays.asList(adminUserId), "新的一天，看看我帮啥忙～");
                } catch (Exception e) {
                    AppLog.error(e);
                }
            }
        } else {
            AppLog.warn("未配置 podcast.admin.users，请在 agent.cfg 中配置");
        }

        AppLog.info("\n机器人监听已启动。在控制台输入 'exit' 并回车以停止程序...");
        java.util.Scanner scanner = new java.util.Scanner(System.in);
        while (isRunning()) {
            if (scanner.hasNextLine()) {
                String input = scanner.nextLine();
                if ("exit".equalsIgnoreCase(input.trim())) {
                    stop();
                    AppLog.info("程序已退出。");
                    break;
                }
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        scanner.close();
    }

    @Override
    protected void doStop() {
        if (this.dingTalkService != null) {
            try {
                this.dingTalkService.stopRobotMsgCallbackConsumer();
            } catch (Exception e) {
                AppLog.error(e);
            }
        }
    }

    @Override
    public String chat(String userInput) {
        if (userInput == null) return "";
        String input = userInput.trim();
        if (input.isEmpty()) return "";
        CapturingMessenger messenger = new CapturingMessenger();
        TaskProcessor.process(input, chatContext, messenger);
        return messenger.getCaptured();
    }

    private static final class CapturingMessenger implements ToolMessenger {
        private final StringBuilder captured = new StringBuilder();
        private List<String> mentionedUserIds = java.util.Collections.emptyList();

        @Override
        public List<String> getMentionedUserIds() {
            return mentionedUserIds;
        }

        @Override
        public ToolMessenger withMentionedUserIds(List<String> mentionedUserIds) {
            this.mentionedUserIds = mentionedUserIds == null ? java.util.Collections.emptyList() : mentionedUserIds;
            return this;
        }

        @Override
        public void sendText(String content) {
            if (content != null) {
                captured.append(content).append("\n");
            }
            AppLog.info("[DingTalkAgent] Text: " + content);
        }

        @Override
        public void sendMarkdown(String title, String content) {
            if (title != null) {
                captured.append(title).append("\n");
            }
            if (content != null) {
                captured.append(content).append("\n");
            }
            AppLog.info("[DingTalkAgent] Markdown Title: " + title);
            AppLog.info("[DingTalkAgent] Content: \n" + content);
        }

        @Override
        public void sendImage(String imageUrl) {
            if (imageUrl != null) {
                captured.append(imageUrl).append("\n");
            }
            AppLog.info("[DingTalkAgent] Image: " + imageUrl);
        }

        @Override
        public void sendImage(java.io.File imageFile) {
            String path = imageFile == null ? null : imageFile.getAbsolutePath();
            if (path != null) {
                captured.append(path).append("\n");
            }
            AppLog.info("[DingTalkAgent] Local Image: " + path);
        }

        public String getCaptured() {
            return captured.toString().trim();
        }
    }
    
}
