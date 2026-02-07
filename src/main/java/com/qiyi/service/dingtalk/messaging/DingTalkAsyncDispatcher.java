package com.qiyi.service.dingtalk.messaging;

import com.qiyi.util.DingTalkUtil;
import com.qiyi.util.AppLog;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

public class DingTalkAsyncDispatcher {
    private enum MsgType {
        TEXT, IMAGE, LINK, WORK_IMAGE, WORK_TEXT
    }

    private static class DingTalkMessageTask {
        private final List<String> userIds;
        private final MsgType type;
        private final String content;
        private final String title;
        private final String messageUrl;
        private final String picUrl;
        private final String appKey;
        private final String appSecret;
        private final long agentId;

        private DingTalkMessageTask(List<String> userIds, MsgType type, String content) {
            this(userIds, type, content, null, null, null, null, null, 0);
        }

        private DingTalkMessageTask(List<String> userIds, String title, String text, String messageUrl, String picUrl) {
            this(userIds, MsgType.LINK, text, title, messageUrl, picUrl, null, null, 0);
        }

        private DingTalkMessageTask(List<String> userIds, MsgType type, String content, String appKey, String appSecret, long agentId) {
            this(userIds, type, content, null, null, null, appKey, appSecret, agentId);
        }

        private DingTalkMessageTask(
            List<String> userIds,
            MsgType type,
            String content,
            String title,
            String messageUrl,
            String picUrl,
            String appKey,
            String appSecret,
            long agentId
        ) {
            this.userIds = userIds;
            this.type = type;
            this.content = content;
            this.title = title;
            this.messageUrl = messageUrl;
            this.picUrl = picUrl;
            this.appKey = appKey;
            this.appSecret = appSecret;
            this.agentId = agentId;
        }
    }

    private static final DingTalkAsyncDispatcher INSTANCE = new DingTalkAsyncDispatcher();

    private final BlockingQueue<DingTalkMessageTask> queue = new LinkedBlockingQueue<>();
    private final AtomicBoolean started = new AtomicBoolean(false);

    public static DingTalkAsyncDispatcher getInstance() {
        return INSTANCE;
    }

    public void enqueueText(List<String> userIds, String content) {
        if (userIds == null || userIds.isEmpty()) return;
        ensureStarted();
        queue.offer(new DingTalkMessageTask(userIds, MsgType.TEXT, content));
    }

    public void enqueueImage(List<String> userIds, String photoUrl) {
        if (userIds == null || userIds.isEmpty()) return;
        ensureStarted();
        queue.offer(new DingTalkMessageTask(userIds, MsgType.IMAGE, photoUrl));
    }

    public void enqueueLink(List<String> userIds, String title, String text, String messageUrl, String picUrl) {
        if (userIds == null || userIds.isEmpty()) return;
        ensureStarted();
        queue.offer(new DingTalkMessageTask(userIds, title, text, messageUrl, picUrl));
    }

    public void enqueueWorkImage(List<String> userIds, String mediaId) {
        if (userIds == null || userIds.isEmpty()) return;
        ensureStarted();
        queue.offer(new DingTalkMessageTask(userIds, MsgType.WORK_IMAGE, mediaId));
    }

    public void enqueueWorkText(List<String> userIds, String content) {
        if (userIds == null || userIds.isEmpty()) return;
        ensureStarted();
        queue.offer(new DingTalkMessageTask(userIds, MsgType.WORK_TEXT, content));
    }

    public void enqueueWorkImage(String appKey, String appSecret, long agentId, List<String> userIds, String mediaId) {
        if (userIds == null || userIds.isEmpty()) return;
        ensureStarted();
        queue.offer(new DingTalkMessageTask(userIds, MsgType.WORK_IMAGE, mediaId, appKey, appSecret, agentId));
    }

    public void enqueueWorkText(String appKey, String appSecret, long agentId, List<String> userIds, String content) {
        if (userIds == null || userIds.isEmpty()) return;
        ensureStarted();
        queue.offer(new DingTalkMessageTask(userIds, MsgType.WORK_TEXT, content, appKey, appSecret, agentId));
    }

    private void ensureStarted() {
        if (!started.compareAndSet(false, true)) return;

        Thread worker = new Thread(() -> {
            AppLog.info("DingTalk-Async-Msg-Processor started.");
            while (true) {
                try {
                    DingTalkMessageTask task = queue.take();
                    process(task);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    AppLog.info("DingTalk-Async-Msg-Processor interrupted.");
                    started.set(false);
                    break;
                } catch (Throwable e) {
                    AppLog.error("Error processing DingTalk message task: " + e.getMessage());
                    AppLog.error(e);
                }
            }
        });
        worker.setDaemon(true);
        worker.setName("DingTalk-Async-Msg-Processor");
        worker.start();
    }

    private void process(DingTalkMessageTask task) {
        try {
            if (task.userIds == null || task.userIds.isEmpty()) return;

            if (task.type == MsgType.TEXT) {
                DingTalkUtil.sendTextMessageToEmployees(task.userIds, task.content);
            } else if (task.type == MsgType.IMAGE) {
                DingTalkUtil.sendImageMessageToEmployees(task.userIds, task.content);
            } else if (task.type == MsgType.LINK) {
                DingTalkUtil.sendLinkMessageToEmployees(task.userIds, task.title, task.content, task.messageUrl, task.picUrl);
            } else if (task.type == MsgType.WORK_IMAGE) {
                if (task.agentId > 0 && task.appKey != null && task.appSecret != null) {
                    DingTalkUtil.sendWorkNotificationImage(task.appKey, task.appSecret, task.agentId, task.userIds, task.content);
                } else {
                    DingTalkUtil.sendWorkNotificationImage(task.userIds, task.content);
                }
            } else if (task.type == MsgType.WORK_TEXT) {
                if (task.agentId > 0 && task.appKey != null && task.appSecret != null) {
                    DingTalkUtil.sendWorkNotificationText(task.appKey, task.appSecret, task.agentId, task.userIds, task.content);
                } else {
                    DingTalkUtil.sendWorkNotificationText(task.userIds, task.content);
                }
            }

            Thread.sleep(200);
        } catch (Exception e) {
            AppLog.error("Failed to send async message: " + e.getMessage());
            AppLog.error(e);
        }
    }
}

