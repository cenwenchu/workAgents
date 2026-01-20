package com.qiyi.util;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.dingtalk.api.DefaultDingTalkClient;
import com.dingtalk.api.DingTalkClient;
import com.dingtalk.api.request.OapiRobotSendRequest;
import com.dingtalk.api.request.OapiUserListsimpleRequest;
import com.dingtalk.api.request.OapiV2DepartmentListsubRequest;
import com.dingtalk.api.response.OapiRobotSendResponse;
import com.dingtalk.api.response.OapiUserListsimpleResponse;
import com.dingtalk.api.response.OapiV2DepartmentListsubResponse;
import com.dingtalk.open.app.api.OpenDingTalkClient;
import com.dingtalk.open.app.api.OpenDingTalkStreamClientBuilder;
import com.dingtalk.open.app.api.callback.OpenDingTalkCallbackListener;
import com.dingtalk.open.app.api.security.AuthClientCredential;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.codec.binary.Base64;
import com.qiyi.dingtalk.DingTalkDepartment;
import com.qiyi.dingtalk.DingTalkUser;
import com.taobao.api.FileItem;
import com.aliyun.dingtalkoauth2_1_0.models.GetAccessTokenResponse;
import com.aliyun.dingtalkrobot_1_0.models.*;
import com.aliyun.dingtalkcalendar_1_0.models.*;
import com.aliyun.tea.TeaException;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.aliyun.teaopenapi.models.Config;
import com.aliyun.teautil.models.RuntimeOptions;
import com.qiyi.tools.ToolManager;

/**
 * 钉钉工具类
 * <p>
 * 提供钉钉机器人消息发送、用户管理、部门管理等功能。
 * 支持发送文本、图片、Markdown、链接、ActionCard等类型的消息。
 * </p>
 * 
 * 机器人配置说明：
 * <ul>
 * <li>企业内部机器人：需要配置 AppKey, AppSecret, RobotCode</li>
 * <li>自定义机器人：需要配置 Webhook Token, Secret</li>
 * </ul>
 */
public class DingTalkUtil {

    // 机器人配置信息（从配置文件加载）
    private static String ROBOT_TOKEN = "";
    private static String ROBOT_SECRET = "";

    public static String ROBOT_CLIENT_ID = "";
    public static String ROBOT_CLIENT_SECRET = "";

    private static String ROBOT_CODE = "";
    public static Long AGENT_ID = 0L; // AgentId 用于发送工作通知（如本地图片）
    public static List<String> PODCAST_ADMIN_USERS = new ArrayList<>();

    static {
        initClientConfig();
    }

    /**
     * 显示调用关闭函数
     * @deprecated 请使用 ToolManager.analyzeAndExecute
     */
    @Deprecated
    private static void analyzeAndExecute(String text, String senderId, List<String> atUserIds) {
         ToolManager.analyzeAndExecute(text, new com.qiyi.tools.context.DingTalkToolContext(senderId, atUserIds));
    }
    
    public static synchronized void initClientConfig() {
        java.util.Properties props = new java.util.Properties();
        try (java.io.InputStream input = DingTalkUtil.class.getClassLoader().getResourceAsStream("podcast.cfg")) {
            if (input == null) {
                System.out.println("配置文件 podcast.cfg 未找到");
            } else {
                props.load(input);
            }
        } catch (java.io.IOException ex) {
            System.out.println("加载配置文件失败: " + ex.getMessage());
            ex.printStackTrace();
        }

        if (props.containsKey("dingtalk.robot.token")) {
            ROBOT_TOKEN = props.getProperty("dingtalk.robot.token");
        }
        if (props.containsKey("dingtalk.robot.secret")) {
            ROBOT_SECRET = props.getProperty("dingtalk.robot.secret");
        }
        if (props.containsKey("dingtalk.robot.client.id")) {
            ROBOT_CLIENT_ID = props.getProperty("dingtalk.robot.client.id");
        }
        if (props.containsKey("dingtalk.robot.client.secret")) {
            ROBOT_CLIENT_SECRET = props.getProperty("dingtalk.robot.client.secret");
        }
        if (props.containsKey("dingtalk.robot.code")) {
            ROBOT_CODE = props.getProperty("dingtalk.robot.code");
        }
        
        if (props.containsKey("dingtalk.agent.id")) {
            try {
                AGENT_ID = Long.parseLong(props.getProperty("dingtalk.agent.id"));
            } catch (NumberFormatException e) {
                System.err.println("Invalid dingtalk.agent.id format");
            }
        }
        
        if (props.containsKey("podcast.admin.users")) {
            String users = props.getProperty("podcast.admin.users");
            if (users != null && !users.isEmpty()) {
                PODCAST_ADMIN_USERS = Arrays.asList(users.split(","));
            }
        }
    }



    private static volatile OpenDingTalkClient streamClient;
    


    //启动监听机器人消息回调本地线程，搭配RobotMsgCallbackConsumer来使用
    //注意：这里是阻塞线程，需要在单独的线程中调用
    public static synchronized void startRobotMsgCallbackConsumer() {
        startRobotMsgCallbackConsumer(ROBOT_CLIENT_ID, ROBOT_CLIENT_SECRET);
    }

    public static synchronized void startRobotMsgCallbackConsumer(String clientId,String clientSecret) {
        if ((clientId == null || clientId.isEmpty()) || (clientSecret == null || clientSecret.isEmpty())) {
             initClientConfig();
             if (clientId == null || clientId.isEmpty()) clientId = ROBOT_CLIENT_ID;
             if (clientSecret == null || clientSecret.isEmpty()) clientSecret = ROBOT_CLIENT_SECRET;
        }

        final String finalClientId = clientId;
        final String finalClientSecret = clientSecret;

        if (streamClient != null) {
            System.out.println("Robot callback consumer is already running.");
            return;
        }

        Thread thread = new Thread(() -> {
            try {
                // 在线程内部创建并赋值，但在外部通过 synchronized 保证不会多次触发创建线程
                // 注意：这里存在一个小的时间窗口（线程启动但 streamClient 尚未赋值），
                // 但由于 start 是 synchronized 的，且通常只调用一次，风险可控。
                OpenDingTalkClient client = OpenDingTalkStreamClientBuilder
                        .custom()
                        .credential(new AuthClientCredential(finalClientId, finalClientSecret))
                        .registerCallbackListener("/v1.0/im/bot/messages/get", new RobotMsgCallbackConsumer())
                        .build();

                System.out.println("DingTalk Stream Client starting...");
                client.start();
                streamClient = client;
            } catch (Exception e) {
                System.err.println("DingTalk Stream Client Error: " + e.getMessage());
                e.printStackTrace();
                // 如果启动失败，重置 streamClient 以便允许重试
                streamClient = null; 
            }
        });
        
        // 设置为非守护线程，确保主线程结束后该线程继续运行 (resident)
        thread.setDaemon(false); 
        thread.setName("DingTalk-Robot-Consumer-Thread");
        thread.start();
    }

    // 显示调用关闭函数
    public static synchronized void stopRobotMsgCallbackConsumer() {
        if (streamClient != null) {
            try {
                streamClient.stop();
                System.out.println("DingTalk Stream Client stopped.");
            } catch (Exception e) {
                e.printStackTrace();
            }
            streamClient = null;
        }
    }

    //接收企业内部机器人消息，在群里如果有人 @机器人，就会收到消息
     public static class RobotMsgCallbackConsumer implements OpenDingTalkCallbackListener<JSONObject, JSONObject> {
    /*
        * @param request
        * @return
        */
        @Override
        public JSONObject execute(JSONObject request) {
            System.out.println(JSON.toJSONString(request));
            try {
                JSONObject text = request.getJSONObject("text");
                String senderStaffId = request.getString("senderStaffId");

                if (text != null) {
                    //机器人接收消息内容
                    String msg = text.getString("content").trim();
                    // String openConversationId = request.getString("conversationId");

                    // 解析 @ 人列表
                    List<String> atUserIds = parseAtUserIds(msg);

                    // 使用 LLM 分析并执行意图
                    java.util.concurrent.CompletableFuture.runAsync(() -> {
                        analyzeAndExecute(msg, senderStaffId, atUserIds);
                    });
                }
            } catch (Exception e) {
                System.out.println("receive group message by robot error:" +e.getMessage());
                e.printStackTrace();
            }
            return new JSONObject();
        }
    }

    public static String getUnionIdByUserId(String userId) throws Exception {
        DingTalkClient client = new DefaultDingTalkClient("https://oapi.dingtalk.com/topapi/v2/user/get");
        com.dingtalk.api.request.OapiV2UserGetRequest req = new com.dingtalk.api.request.OapiV2UserGetRequest();
        req.setUserid(userId);
        req.setLanguage("zh_CN");
        com.dingtalk.api.response.OapiV2UserGetResponse rsp = client.execute(req, getDingTalkRobotAccessToken(ROBOT_CLIENT_ID, ROBOT_CLIENT_SECRET));
        if (rsp.getResult() != null) {
            return rsp.getResult().getUnionid();
        }
        return null;
    }

    public static com.aliyun.dingtalkcalendar_1_0.Client createCalendarClient() throws Exception {
        com.aliyun.teaopenapi.models.Config config = new com.aliyun.teaopenapi.models.Config();
        config.protocol = "https";
        config.regionId = "central";
        return new com.aliyun.dingtalkcalendar_1_0.Client(config);
    }

    public static String createCalendarEvent(String userId, String summary, String description, String startTime, String endTime, List<String> attendeeUnionIds, String location) throws Exception {
        com.aliyun.dingtalkcalendar_1_0.Client client = createCalendarClient();
        com.aliyun.dingtalkcalendar_1_0.models.CreateEventHeaders headers = new com.aliyun.dingtalkcalendar_1_0.models.CreateEventHeaders();
        headers.xAcsDingtalkAccessToken = getDingTalkRobotAccessToken(ROBOT_CLIENT_ID, ROBOT_CLIENT_SECRET);

        com.aliyun.dingtalkcalendar_1_0.models.CreateEventRequest request = new com.aliyun.dingtalkcalendar_1_0.models.CreateEventRequest()
            .setSummary(summary)
            .setDescription(description)
            .setStart(new com.aliyun.dingtalkcalendar_1_0.models.CreateEventRequest.CreateEventRequestStart().setDateTime(startTime).setTimeZone("Asia/Shanghai"))
            .setEnd(new com.aliyun.dingtalkcalendar_1_0.models.CreateEventRequest.CreateEventRequestEnd().setDateTime(endTime).setTimeZone("Asia/Shanghai"));
            
        if (location != null && !location.isEmpty()) {
            request.setLocation(new com.aliyun.dingtalkcalendar_1_0.models.CreateEventRequest.CreateEventRequestLocation().setDisplayName(location));
        }
            
        List<com.aliyun.dingtalkcalendar_1_0.models.CreateEventRequest.CreateEventRequestAttendees> attendees = new ArrayList<>();
        for (String uid : attendeeUnionIds) {
            attendees.add(new com.aliyun.dingtalkcalendar_1_0.models.CreateEventRequest.CreateEventRequestAttendees().setId(uid));
        }
        request.setAttendees(attendees);

        // createEventWithOptions(userId, calendarId, request, headers, runtime)
        CreateEventResponse response = client.createEventWithOptions(userId, "primary", request, headers, new com.aliyun.teautil.models.RuntimeOptions());
        if (response.getBody() != null) {
            return response.getBody().getId();
        }
        return null;
    }



    /**
     * 解析消息中的 @ 人员列表
     * 通过 DeepSeek 分析消息内容，结合企业通讯录，识别出需要通知的用户 ID
     */
    private static List<String> parseAtUserIds(String msg) {
        List<String> atUserIds = new ArrayList<>();
        try {
            // 1. 获取全量用户列表 (带缓存)
            List<DingTalkDepartment> departments = getAllDepartments(true, true);
            List<DingTalkUser> allUsers = new ArrayList<>();
            for (DingTalkDepartment dept : departments) {
                if (dept.getUserList() != null) {
                    allUsers.addAll(dept.getUserList());
                }
            }
            
            // 去重
            java.util.Map<String, String> userMap = new java.util.HashMap<>();
            for (DingTalkUser user : allUsers) {
                userMap.put(user.getName(), user.getUserid());
            }
            
            if (userMap.isEmpty()) {
                return atUserIds;
            }

            // 2. 构造提示词给 DeepSeek
            // 优化：不再发送全量用户列表，仅让 AI 提取消息中的人名，后续再本地匹配
            String prompt = String.format(
                "请分析以下消息内容，提取出其中提到的人员姓名（可能是中文名或英文名）。只返回姓名列表，用逗号分隔。不要包含任何解释性文字。如果没有提到特定人员，返回空字符串。消息内容：'%s'",
                msg
            );
            
            // 3. 调用 DeepSeek
            List<io.github.pigmesh.ai.deepseek.core.chat.Message> messages = new ArrayList<>();
            messages.add(io.github.pigmesh.ai.deepseek.core.chat.UserMessage.builder().addText(prompt).build());
            
            String response = LLMUtil.chatWithDeepSeek(messages, false);
            
            // 4. 解析结果并匹配 UserID
            if (response != null && !response.trim().isEmpty()) {
                // 去除可能存在的 Markdown 代码块标记
                response = response.replace("```", "").trim();
                
                String[] names = response.split("[,，]"); // 支持中英文逗号
                for (String name : names) {
                    String trimmedName = name.trim();
                    if (trimmedName.isEmpty()) continue;
                    
                    // 1. 尝试精确匹配
                    if (userMap.containsKey(trimmedName)) {
                        atUserIds.add(userMap.get(trimmedName));
                        System.out.println("DeepSeek 提取的姓名 '" + trimmedName + "' 精确匹配到用户ID: " + userMap.get(trimmedName));
                    } else {
                        // 2. 尝试模糊匹配 (包含关系)
                        // 寻找所有包含 trimmedName 的名字
                        java.util.Map<String, String> partialMatches = new java.util.HashMap<>();
                        for (java.util.Map.Entry<String, String> entry : userMap.entrySet()) {
                             if (entry.getKey().contains(trimmedName)) {
                                 partialMatches.put(entry.getValue(), entry.getKey()); // ID -> Name
                             }
                        }

                        if (partialMatches.size() == 1) {
                            String userId = partialMatches.keySet().iterator().next();
                            String matchedName = partialMatches.get(userId);
                            atUserIds.add(userId);
                            System.out.println("DeepSeek 提取的姓名 '" + trimmedName + "' 模糊匹配到唯一用户: " + matchedName + " (" + userId + ")");
                        } else if (partialMatches.size() > 1) {
                             System.out.println("DeepSeek 提取的姓名 '" + trimmedName + "' 模糊匹配到多个用户，存在歧义: " + partialMatches.values());
                        } else {
                             System.out.println("DeepSeek 提取的姓名 '" + trimmedName + "' 未在用户列表中找到");
                        }
                    }
                }
            }

        } catch (Exception e) {
            System.err.println("解析 @ 人员失败: " + e.getMessage());
            e.printStackTrace();
        }
        return atUserIds;
    }

    // =========================================================================
    // 异步消息发送队列支持
    // =========================================================================

    public enum MsgType {
        TEXT, IMAGE, LINK, WORK_IMAGE, WORK_TEXT
    }

    private static class DingTalkMessageTask {
        List<String> userIds;
        MsgType type;
        String content; // text content or image url or mediaId
        String title; // for link message
        String messageUrl; // for link message
        String picUrl; // for link message

        public DingTalkMessageTask(List<String> userIds, MsgType type, String content) {
            this.userIds = userIds;
            this.type = type;
            this.content = content;
        }

        // For Link Message (Text + Image)
        public DingTalkMessageTask(List<String> userIds, String title, String text, String messageUrl, String picUrl) {
            this.userIds = userIds;
            this.type = MsgType.LINK;
            this.title = title;
            this.content = text;
            this.messageUrl = messageUrl;
            this.picUrl = picUrl;
        }
    }

    private static final java.util.concurrent.BlockingQueue<DingTalkMessageTask> messageQueue = new java.util.concurrent.LinkedBlockingQueue<>();
    
    static {
        Thread messageProcessorThread = new Thread(() -> {
            System.out.println("DingTalk-Async-Msg-Processor started.");
            while (true) {
                try {
                    DingTalkMessageTask task = messageQueue.take(); // 阻塞获取
                    // System.out.println("Processing async dingtalk task: " + task.type);
                    processMessageTask(task);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    System.out.println("DingTalk-Async-Msg-Processor interrupted.");
                    break;
                } catch (Throwable e) {
                    System.err.println("Error processing DingTalk message task: " + e.getMessage());
                    e.printStackTrace();
                }
            }
        });
        messageProcessorThread.setDaemon(true);
        messageProcessorThread.setName("DingTalk-Async-Msg-Processor");
        messageProcessorThread.start();
    }

    private static void processMessageTask(DingTalkMessageTask task) {
        try {
            if (task.userIds == null || task.userIds.isEmpty()) return;

            if (task.type == MsgType.TEXT) {
                sendTextMessageToEmployees(task.userIds, task.content);
            } else if (task.type == MsgType.IMAGE) {
                sendImageMessageToEmployees(task.userIds, task.content);
            } else if (task.type == MsgType.LINK) {
                sendLinkMessageToEmployees(task.userIds, task.title, task.content, task.messageUrl, task.picUrl);
            } else if (task.type == MsgType.WORK_IMAGE) {
                sendWorkNotificationImage(task.userIds, task.content); // content is mediaId
            } else if (task.type == MsgType.WORK_TEXT) {
                sendWorkNotificationText(task.userIds, task.content);
            }
            
            // 简单的限流，避免触发钉钉频率限制
            Thread.sleep(200); 
        } catch (Exception e) {
            System.err.println("Failed to send async message: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 异步发送文本消息
     * 将消息放入队列，由后台线程逐条发送
     */
    public static void sendAsyncTextMessage(List<String> userIds, String content) {
        if (userIds == null || userIds.isEmpty()) return;
        messageQueue.offer(new DingTalkMessageTask(userIds, MsgType.TEXT, content));
    }

    /**
     * 异步发送图片消息 (基于公网URL)
     * 将消息放入队列，由后台线程逐条发送
     */
    public static void sendAsyncImageMessage(List<String> userIds, String photoUrl) {
        if (userIds == null || userIds.isEmpty()) return;
        messageQueue.offer(new DingTalkMessageTask(userIds, MsgType.IMAGE, photoUrl));
    }

    /**
     * 异步发送工作通知图片消息 (基于 MediaId)
     * 将消息放入队列，由后台线程逐条发送
     */
    public static void sendAsyncWorkImageMessage(List<String> userIds, String mediaId) {
        if (userIds == null || userIds.isEmpty()) return;
        messageQueue.offer(new DingTalkMessageTask(userIds, MsgType.WORK_IMAGE, mediaId));
    }

    /**
     * 异步发送工作通知文本消息
     * 将消息放入队列，由后台线程逐条发送
     */
    public static void sendAsyncWorkTextMessage(List<String> userIds, String content) {
        if (userIds == null || userIds.isEmpty()) return;
        messageQueue.offer(new DingTalkMessageTask(userIds, MsgType.WORK_TEXT, content));
    }

    /**
     * 异步发送图文链接消息 (支持文本+图片)
     * 将消息放入队列，由后台线程逐条发送
     * @param userIds 接收人列表
     * @param title 消息标题
     * @param text 消息摘要文本
     * @param messageUrl 点击消息跳转的URL
     * @param picUrl 图片URL
     */
    public static void sendAsyncLinkMessage(List<String> userIds, String title, String text, String messageUrl, String picUrl) {
        if (userIds == null || userIds.isEmpty()) return;
        messageQueue.offer(new DingTalkMessageTask(userIds, title, text, messageUrl, picUrl));
    }

    public static String uploadMedia(String appKey, String appSecret, java.io.File file) throws Exception {
        com.dingtalk.api.DefaultDingTalkClient client = new com.dingtalk.api.DefaultDingTalkClient("https://oapi.dingtalk.com/media/upload");
        com.dingtalk.api.request.OapiMediaUploadRequest req = new com.dingtalk.api.request.OapiMediaUploadRequest();
        req.setType("image");
        req.setMedia(new FileItem(file));
        String accessToken = getDingTalkRobotAccessToken(appKey, appSecret);
        com.dingtalk.api.response.OapiMediaUploadResponse rsp = client.execute(req, accessToken);
        if (rsp.getErrcode() == 0) {
            return rsp.getMediaId();
        }
        throw new RuntimeException("Media upload failed: " + rsp.getErrmsg());
    }

    public static void sendWorkNotificationImage(List<String> userIds, String mediaId) throws Exception {
        if (AGENT_ID == 0) {
            System.out.println("Agent ID not configured, cannot send work notification image.");
            return;
        }

        // 2. 发送图片
        com.dingtalk.api.DefaultDingTalkClient client = new com.dingtalk.api.DefaultDingTalkClient("https://oapi.dingtalk.com/topapi/message/corpconversation/asyncsend_v2");
        com.dingtalk.api.request.OapiMessageCorpconversationAsyncsendV2Request req = new com.dingtalk.api.request.OapiMessageCorpconversationAsyncsendV2Request();
        req.setAgentId(AGENT_ID);
        req.setUseridList(String.join(",", userIds));
        
        com.dingtalk.api.request.OapiMessageCorpconversationAsyncsendV2Request.Msg msg = new com.dingtalk.api.request.OapiMessageCorpconversationAsyncsendV2Request.Msg();
        msg.setMsgtype("image");
        msg.setImage(new com.dingtalk.api.request.OapiMessageCorpconversationAsyncsendV2Request.Image());
        msg.getImage().setMediaId(mediaId);
        req.setMsg(msg);
        
        String accessToken = getDingTalkRobotAccessToken(ROBOT_CLIENT_ID, ROBOT_CLIENT_SECRET);
        com.dingtalk.api.response.OapiMessageCorpconversationAsyncsendV2Response rsp = client.execute(req, accessToken);
        if (rsp.getErrcode() != 0) {
             throw new RuntimeException("Send work notification image failed: " + rsp.getErrmsg());
        }
    }

    //注意，如果文本内容曾经发过，钉钉会有机制来保障不重发，因此注意不要始终发相同的内容
    private static void sendWorkNotificationText(List<String> userIds, String content) throws Exception {
         if (AGENT_ID == 0) return;
         com.dingtalk.api.DefaultDingTalkClient client = new com.dingtalk.api.DefaultDingTalkClient("https://oapi.dingtalk.com/topapi/message/corpconversation/asyncsend_v2");
         com.dingtalk.api.request.OapiMessageCorpconversationAsyncsendV2Request req = new com.dingtalk.api.request.OapiMessageCorpconversationAsyncsendV2Request();
         req.setAgentId(AGENT_ID);
         req.setUseridList(String.join(",", userIds));
         
         com.dingtalk.api.request.OapiMessageCorpconversationAsyncsendV2Request.Msg msg = new com.dingtalk.api.request.OapiMessageCorpconversationAsyncsendV2Request.Msg();
         msg.setMsgtype("text");
         msg.setText(new com.dingtalk.api.request.OapiMessageCorpconversationAsyncsendV2Request.Text());
         msg.getText().setContent(content);
         req.setMsg(msg);
         
         String accessToken = getDingTalkRobotAccessToken(ROBOT_CLIENT_ID, ROBOT_CLIENT_SECRET);
         com.dingtalk.api.response.OapiMessageCorpconversationAsyncsendV2Response rsp = client.execute(req, accessToken);
         
         if (rsp.getErrcode() != 0) {
             System.err.println("Send work notification text failed: " + rsp.getErrmsg() + ", code: " + rsp.getErrcode());
             throw new RuntimeException("Send work notification text failed: " + rsp.getErrmsg());
         } else {
             System.out.println("Send work notification text success. TaskId: " + rsp.getTaskId());
         }
    }

    public static com.aliyun.dingtalkrobot_1_0.Client createClient() throws Exception {
        Config config = new Config();
        config.protocol = "https";
        config.regionId = "central";
        return new com.aliyun.dingtalkrobot_1_0.Client(config);
    }

    public static com.aliyun.dingtalkoauth2_1_0.Client createOAuthClient() throws Exception {
        com.aliyun.teaopenapi.models.Config config = new com.aliyun.teaopenapi.models.Config();
        config.protocol = "https";
        config.regionId = "central";
        return new com.aliyun.dingtalkoauth2_1_0.Client(config);
    }

    // =========================================================================
    // 缓存支持
    // =========================================================================

    public interface DingTalkCache {
        String get(String key);
        void put(String key, String value, long expireSeconds);
    }

    public static class LocalDingTalkCache implements DingTalkCache {
        private static final java.util.Map<String, String> cache = new java.util.concurrent.ConcurrentHashMap<>();
        private static final java.util.Map<String, Long> expireMap = new java.util.concurrent.ConcurrentHashMap<>();

        @Override
        public String get(String key) {
            Long expireTime = expireMap.get(key);
            if (expireTime != null && expireTime > System.currentTimeMillis()) {
                return cache.get(key);
            } else if (expireTime != null) {
                // Expired, clean up
                cache.remove(key);
                expireMap.remove(key);
            }
            return null;
        }

        @Override
        public void put(String key, String value, long expireSeconds) {
            cache.put(key, value);
            expireMap.put(key, System.currentTimeMillis() + expireSeconds * 1000);
        }
    }

    // 默认使用本地缓存，未来可替换为 Redis 等集中式缓存实现
    private static DingTalkCache dingTalkCache = new LocalDingTalkCache();

    public static void setDingTalkCache(DingTalkCache cache) {
        dingTalkCache = cache;
    }

    public static String getDingTalkRobotAccessToken(String appKey,String appSecret) throws Exception
    {
        // 尝试从缓存获取
        String cacheKey = "dingtalk_access_token_" + appKey;
        String cachedToken = dingTalkCache.get(cacheKey);
        if (cachedToken != null) {
            // System.out.println("Get accessToken from cache: " + cachedToken);
            return cachedToken;
        }

        GetAccessTokenResponse accessTokenResponse = null;
        String accessToken = null;
        
        com.aliyun.dingtalkoauth2_1_0.Client client = DingTalkUtil.createOAuthClient();
        com.aliyun.dingtalkoauth2_1_0.models.GetAccessTokenRequest getAccessTokenRequest = new com.aliyun.dingtalkoauth2_1_0.models.GetAccessTokenRequest()
                .setAppKey(appKey)
                .setAppSecret(appSecret);
        try {
            accessTokenResponse = client.getAccessToken(getAccessTokenRequest);
        } catch (TeaException err) {
            if (!com.aliyun.teautil.Common.empty(err.code) && !com.aliyun.teautil.Common.empty(err.message)) {
                // err 中含有 code 和 message 属性，可帮助开发定位问题
            }

        } catch (Exception _err) {
            TeaException err = new TeaException(_err.getMessage(), _err);
            if (!com.aliyun.teautil.Common.empty(err.code) && !com.aliyun.teautil.Common.empty(err.message)) {
                // err 中含有 code 和 message 属性，可帮助开发定位问题
            }

        } 
        if (accessTokenResponse != null) {
            accessToken = accessTokenResponse.getBody().getAccessToken();
            System.out.println("accessTokenResponse: " + accessToken);
            
            // 获取成功后放入缓存，设置过期时间为 60 分钟 (3600 秒)
            if (accessToken != null && !accessToken.isEmpty()) {
                dingTalkCache.put(cacheKey, accessToken, 3600);
            }
        }

        return accessToken;
    }

    // =========================================================================
    // 下面是针对常用消息类型的封装方法
    // =========================================================================

    /**
     * 简单的辅助方法：处理JSON特殊字符转义 (简单实现，建议使用 Jackson/Gson 替代)
     */
    private static String escapeJson(String text) {
        if (text == null) return "";
        return text.replace("\\", "\\\\")
                   .replace("\"", "\\\"")
                   .replace("\n", "\\n")
                   .replace("\r", "\\r");
    }

    /**
     * 分页获取钉钉API数据
     * @param appKey 应用Key
     * @param appSecret 应用密钥
     * @param departmentId 部门ID
     * @param pageSize 每页大小
     * @param dataExtractor 数据提取函数
     * @return 所有数据列表
     */
    public static <T> List<T> getAllDataByPagination(
            String appKey, 
            String appSecret, 
            String departmentId,
            int pageSize,
            Function<Long, List<T>> dataExtractor) throws Exception {
        
        List<T> allData = new ArrayList<>();
        long cursor = 0;
        int page = 1;
        boolean hasMore = true;
        
        while (hasMore) {
            try {
                List<T> pageData = dataExtractor.apply(cursor);
                
                if (pageData != null && !pageData.isEmpty()) {
                    allData.addAll(pageData);
                    System.out.printf("第 %d 页获取 %d 条数据，累计 %d 条%n", 
                        page, pageData.size(), allData.size());
                    
                    // 如果返回的数据小于请求的页大小，说明没有更多数据了
                    if (pageData.size() < pageSize) {
                        hasMore = false;
                    } else {
                        cursor += pageSize;
                        page++;
                    }
                } else {
                    hasMore = false;
                }
                
                // 避免频繁请求
                Thread.sleep(200);
                
            } catch (Exception e) {
                System.err.println("分页获取数据失败: " + e.getMessage());
                throw e;
            }
        }
        
        return allData;
    }
    
    /**
     * 获取部门下所有用户的通用方法
     */
    public static List<DingTalkUser> getAllUsersInDepartment(String appKey, String appSecret, String departmentId) throws Exception {

        String accessToken = getDingTalkRobotAccessToken(appKey, appSecret);
        if (accessToken == null || accessToken.isEmpty()) {
            System.out.println("Failed to get access token");
            return Collections.emptyList();
        }
        
        return getAllDataByPagination(appKey, appSecret, departmentId, 30, cursor -> {
            try {
                DingTalkClient client = new DefaultDingTalkClient("https://oapi.dingtalk.com/topapi/user/listsimple");
                OapiUserListsimpleRequest req = new OapiUserListsimpleRequest();
                req.setDeptId(Long.parseLong(departmentId));
                req.setCursor(cursor);
                req.setSize(30L);
                req.setOrderField("modify_desc");
                req.setContainAccessLimit(false);
                req.setLanguage("zh_CN");
                
                OapiUserListsimpleResponse rsp = client.execute(req, accessToken);
                if (rsp.isSuccess() && rsp.getResult() != null) {
                    return rsp.getResult().getList().stream()
                            .map(user -> new DingTalkUser(user.getName(), user.getUserid()))
                            .collect(Collectors.toList());
                }
            } catch (Exception e) {
                throw new RuntimeException("获取用户数据失败", e);
            }
            return Collections.emptyList();
        });
    }


    public static List<DingTalkDepartment> getDingTalkDepartmentList(String appKey, String appSecret,String departmentId) throws Exception {

        List<DingTalkDepartment> departmentList = new ArrayList<DingTalkDepartment>();

        String accessToken = getDingTalkRobotAccessToken(appKey, appSecret);
        if (accessToken == null || accessToken.isEmpty()) {
            System.out.println("Failed to get access token");
            return departmentList;
        }

        DingTalkClient client = new DefaultDingTalkClient("https://oapi.dingtalk.com/topapi/v2/department/listsub");
        OapiV2DepartmentListsubRequest req = new OapiV2DepartmentListsubRequest();

        if (departmentId!= null)
        {
            req.setDeptId(Long.parseLong(departmentId));
        }

        OapiV2DepartmentListsubResponse rsp = client.execute(req, accessToken);


        if (rsp != null && rsp.getBody() != null) {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(rsp.getBody().toString());
            
            // 检查错误码
            if (root.get("errcode").asInt() != 0) {
                throw new RuntimeException("API返回错误: " + root.get("errmsg").asText());
            }
            
            // 解析result数组
            JsonNode resultArray = root.get("result");
            
            for (JsonNode node : resultArray) {
                DingTalkDepartment dept = new DingTalkDepartment();
                dept.setDeptId(node.get("dept_id").asText());
                dept.setName(node.get("name").asText());
                dept.setParentId(node.get("parent_id").asText());
                departmentList.add(dept);
            }
        }

        return  departmentList;
    }


    /**
     * 获取公司所有部门列表（从根部门 ID 1 开始递归遍历），支持缓存指定时间
     * @param cacheSeconds 缓存时间（秒），如果 <= 0 则不使用缓存
     */
    public static List<DingTalkDepartment> getAllDepartments(boolean isNeedUserList, boolean needCache) throws Exception {
        return getAllDepartments(ROBOT_CLIENT_ID, ROBOT_CLIENT_SECRET, isNeedUserList, needCache);
    }

    // 刷新锁 Map
    private static final java.util.Map<String, java.util.concurrent.atomic.AtomicBoolean> REFRESH_LOCKS = new java.util.concurrent.ConcurrentHashMap<>();

    public static List<DingTalkDepartment> getAllDepartments(String appKey, String appSecret, boolean isNeedUserList, boolean needCache) throws Exception {
        String cacheKey = "dingtalk_departments_v2_" + appKey + "_" + isNeedUserList;
        // 文件名加入 appKey 以区分不同应用，防止缓存冲突
        String safeAppKey = appKey != null ? appKey.replaceAll("[^a-zA-Z0-9.-]", "_") : "default";
        java.io.File cacheFile = new java.io.File(System.getProperty("java.io.tmpdir"), "dingtalk_contacts_cache_" + safeAppKey + "_" + isNeedUserList + ".json");
        
        // 1. 优先尝试文件缓存 (Stale-While-Revalidate 模式)
        if (needCache && cacheFile.exists()) {
            try {
                // 读取文件
                ObjectMapper mapper = new ObjectMapper();
                List<DingTalkDepartment> cachedData = mapper.readValue(cacheFile, new com.fasterxml.jackson.core.type.TypeReference<List<DingTalkDepartment>>(){});
                System.out.println("Loaded DingTalk departments from local file cache: " + cacheFile.getAbsolutePath());
                
                // 异步刷新逻辑：如果使用了缓存，且文件超过1天（24小时）未更新，则触发异步刷新
                if (System.currentTimeMillis() - cacheFile.lastModified() > 24 * 3600 * 1000) {
                    REFRESH_LOCKS.putIfAbsent(cacheKey, new java.util.concurrent.atomic.AtomicBoolean(false));
                    java.util.concurrent.atomic.AtomicBoolean isRefreshing = REFRESH_LOCKS.get(cacheKey);
                    
                    if (isRefreshing.compareAndSet(false, true)) {
                        new Thread(() -> {
                            try {
                                System.out.println("Local cache expired (>1 day), starting asynchronous refresh...");
                                fetchAndCacheDepartments(appKey, appSecret, isNeedUserList, cacheKey, cacheFile);
                                System.out.println("Asynchronous refresh of DingTalk departments completed.");
                            } catch (Exception e) {
                                System.err.println("Async refresh failed: " + e.getMessage());
                            } finally {
                                isRefreshing.set(false);
                            }
                        }).start();
                    }
                } else {
                    System.out.println("Local cache is fresh (<1 day), skipping refresh.");
                }
                
                return cachedData;
            } catch (Exception e) {
                System.err.println("Failed to load local file cache, falling back to sync load: " + e.getMessage());
            }
        }

        // 2. 尝试从内存缓存获取
        if (needCache) {
            String cachedDataStr = dingTalkCache.get(cacheKey);
            if (cachedDataStr != null && !cachedDataStr.isEmpty()) {
                try {
                    ObjectMapper mapper = new ObjectMapper();
                    return mapper.readValue(cachedDataStr, new com.fasterxml.jackson.core.type.TypeReference<List<DingTalkDepartment>>(){});
                } catch (Exception e) {
                    // Ignore memory cache error
                }
            }
        }

        // 3. 同步加载（无缓存或读取失败）
        return fetchAndCacheDepartments(appKey, appSecret, isNeedUserList, cacheKey, cacheFile);
    }

    private static List<DingTalkDepartment> fetchAndCacheDepartments(String appKey, String appSecret, boolean isNeedUserList, String cacheKey, java.io.File cacheFile) throws Exception {
        List<DingTalkDepartment> allDepartments = new ArrayList<>();
        allDepartments.add(new DingTalkDepartment("1","根部门","0"));

        // 递归获取所有部门，从根部门 ID 1 开始
        traverseDepartments(appKey, appSecret, null, allDepartments);

        if (isNeedUserList) {
            // 为每个部门添加用户列表
            for (DingTalkDepartment dept : allDepartments) {
                dept.setUserList(getAllUsersInDepartment(appKey, appSecret, dept.getDeptId()));
            }
        }
        
        // 更新缓存
        ObjectMapper mapper = new ObjectMapper();
        // 存入内存缓存
        try {
            String jsonStr = mapper.writeValueAsString(allDepartments);
            dingTalkCache.put(cacheKey, jsonStr, 30*60);
        } catch (Exception e) {
            System.err.println("Failed to update memory cache: " + e.getMessage());
        }
        
        // 存入文件缓存
        try {
            // 确保覆盖历史文件
            if (cacheFile.exists()) {
                cacheFile.delete();
            }
            mapper.writeValue(cacheFile, allDepartments);
            System.out.println("Saved DingTalk departments to local file cache: " + cacheFile.getAbsolutePath());
        } catch (Exception e) {
            System.err.println("Failed to update file cache: " + e.getMessage());
        }
        
        return allDepartments;
    }

    private static void traverseDepartments(String appKey, String appSecret, String parentId, List<DingTalkDepartment> result) throws Exception {
        // 获取当前部门的子部门列表
        List<DingTalkDepartment> children = getDingTalkDepartmentList(appKey, appSecret, parentId);
        
        if (children != null && !children.isEmpty()) {
            result.addAll(children);
            // 对每个子部门，继续递归获取其子部门
            for (DingTalkDepartment child : children) {
                traverseDepartments(appKey, appSecret, child.getDeptId(), result);
            }
        }
    }

    public static DingTalkUser findUserFromDepartmentByName(String name) throws Exception {
        if (name == null) return null;
        name = name.trim();
        // System.out.println("DEBUG: findUserFromDepartmentByName searching for: '" + name + "'");

        List<DingTalkDepartment> allDepartments = getAllDepartments(ROBOT_CLIENT_ID, ROBOT_CLIENT_SECRET, true, true);
        
        // 用于存储模糊匹配的结果（使用Map去重，因为一个用户可能在多个部门）
        java.util.Map<String, DingTalkUser> partialMatches = new java.util.HashMap<>();

        for (DingTalkDepartment dept : allDepartments) {

            if (dept.getUserList() != null && !dept.getUserList().isEmpty())
            {
                for (DingTalkUser user : dept.getUserList()) {
                    // 1. 精确匹配：直接返回
                    if (user.getName().equals(name)) {
                        return user;
                    }
                    // 2. 模糊匹配：收集备选
                    if (user.getName().contains(name)) {
                        partialMatches.put(user.getUserid(), user);
                    }
                }
            }
        }
        
        // 3. 如果精确匹配未找到，且模糊匹配只有一个结果，则返回该结果
        if (partialMatches.size() == 1) {
            DingTalkUser user = partialMatches.values().iterator().next();
            System.out.println("Exact match not found for '" + name + "', but found unique partial match: " + user.getName());
            return user;
        }
        
        if (partialMatches.size() > 1) {
             System.out.println("Exact match not found for '" + name + "', and found multiple partial matches (" + partialMatches.size() + "), ambiguous. Matches: " 
                 + partialMatches.values().stream().map(DingTalkUser::getName).collect(Collectors.joining(", ")));
        } else if (partialMatches.isEmpty()) {
             System.out.println("No match found for '" + name + "' in any department.");
        }

        return null;
    }

    /**
     * 发送文本消息（批量发送给指定员工）
     * <p>
     * 使用默认配置的机器人发送文本消息。
     * </p>
     * @param userIds 接收消息的员工UserID列表
     * @param content 消息内容
     * @return 发送结果，成功返回true，否则抛出异常
     * @throws Exception 发送过程中的异常
     */
    public static boolean sendTextMessageToEmployees(List<String> userIds, String content) throws Exception {
        return sendTextMessageToEmployees(ROBOT_CLIENT_ID, ROBOT_CLIENT_SECRET, ROBOT_CODE, userIds, content);
    }

    /**
     * 发送文本消息（指定机器人配置）
     * @param appKey 机器人的AppKey
     * @param appSecret 机器人的AppSecret
     * @param robotCode 机器人的RobotCode
     * @param userIds 接收消息的员工UserID列表
     * @param content 消息内容
     * @return 发送结果，成功返回true
     * @throws Exception 发送过程中的异常
     */
    public static boolean sendTextMessageToEmployees(String appKey, String appSecret, String robotCode, List<String> userIds, String content) throws Exception {
        String msgKey = "sampleText";
        // 构造JSON: {"content": "具体的文本内容"}
        String msgParam = String.format("{\"content\": \"%s\"}", escapeJson(content));
        return sendBatchMessageToEmployees(appKey, appSecret, robotCode, userIds, msgKey, msgParam);
    }

    /**
     * 发送图片消息
     * <p>
     * 注意: photoURL 必须是公网可访问的图片链接。
     * </p>
     * @param userIds 接收消息的员工UserID列表
     * @param photoUrl 图片的公网URL
     * @return 发送结果
     * @throws Exception 发送过程中的异常
     */
    public static boolean sendImageMessageToEmployees(List<String> userIds, String photoUrl) throws Exception {
        return sendImageMessageToEmployees(ROBOT_CLIENT_ID, ROBOT_CLIENT_SECRET, ROBOT_CODE, userIds, photoUrl);
    }

    /**
     * 发送图片消息（指定机器人配置）
     * @param appKey 机器人的AppKey
     * @param appSecret 机器人的AppSecret
     * @param robotCode 机器人的RobotCode
     * @param userIds 接收消息的员工UserID列表
     * @param photoUrl 图片的公网URL
     * @return 发送结果
     * @throws Exception 发送过程中的异常
     */
    public static boolean sendImageMessageToEmployees(String appKey, String appSecret, String robotCode, List<String> userIds, String photoUrl) throws Exception {
        String msgKey = "sampleImageMsg";
        // 构造JSON: {"photoURL": "https://..."}
        String msgParam = String.format("{\"photoURL\": \"%s\"}", photoUrl);
        return sendBatchMessageToEmployees(appKey, appSecret, robotCode, userIds, msgKey, msgParam);
    }

    /**
     * 发送Markdown消息
     * @param userIds 接收消息的员工UserID列表
     * @param title 消息标题
     * @param markdownText Markdown格式的内容
     * @return 发送结果
     * @throws Exception 发送过程中的异常
     */
    public static boolean sendMarkdownMessageToEmployees(List<String> userIds, String title, String markdownText) throws Exception {
        return sendMarkdownMessageToEmployees(ROBOT_CLIENT_ID, ROBOT_CLIENT_SECRET, ROBOT_CODE, userIds, title, markdownText);
    }

    /**
     * 发送Markdown消息（指定机器人配置）
     * @param appKey 机器人的AppKey
     * @param appSecret 机器人的AppSecret
     * @param robotCode 机器人的RobotCode
     * @param userIds 接收消息的员工UserID列表
     * @param title 消息标题
     * @param markdownText Markdown格式的内容
     * @return 发送结果
     * @throws Exception 发送过程中的异常
     */
    public static boolean sendMarkdownMessageToEmployees(String appKey, String appSecret, String robotCode, List<String> userIds, String title, String markdownText) throws Exception {
        String msgKey = "sampleMarkdown";
        // 构造JSON: {"title": "标题", "text": "markdown内容"}
        // 注意：实际生产中请使用 JSON 库 (如 Gson/Jackson) 来避免拼接字符串时的转义问题
        String msgParam = String.format("{\"title\": \"%s\", \"text\": \"%s\"}", escapeJson(title), escapeJson(markdownText));
        return sendBatchMessageToEmployees(appKey, appSecret, robotCode, userIds, msgKey, msgParam);
    }

    /**
     * 发送链接消息
     * @param userIds 接收消息的员工UserID列表
     * @param title 消息标题
     * @param text 消息描述
     * @param messageUrl 点击消息跳转的URL
     * @param picUrl 图片URL
     * @return 发送结果
     * @throws Exception 发送过程中的异常
     */
    public static boolean sendLinkMessageToEmployees(List<String> userIds, String title, String text, String messageUrl, String picUrl) throws Exception {
        return sendLinkMessageToEmployees(ROBOT_CLIENT_ID, ROBOT_CLIENT_SECRET, ROBOT_CODE, userIds, title, text, messageUrl, picUrl);
    }

    /**
     * 发送链接消息（指定机器人配置）
     * @param appKey 机器人的AppKey
     * @param appSecret 机器人的AppSecret
     * @param robotCode 机器人的RobotCode
     * @param userIds 接收消息的员工UserID列表
     * @param title 消息标题
     * @param text 消息描述
     * @param messageUrl 点击消息跳转的URL
     * @param picUrl 图片URL
     * @return 发送结果
     * @throws Exception 发送过程中的异常
     */
    public static boolean sendLinkMessageToEmployees(String appKey, String appSecret, String robotCode, List<String> userIds, 
                                   String title, String text, String messageUrl, String picUrl) throws Exception {
        String msgKey = "sampleLink";
        // 构造JSON
        String msgParam = String.format(
            "{\"title\": \"%s\", \"text\": \"%s\", \"messageUrl\": \"%s\", \"picUrl\": \"%s\"}",
            escapeJson(title), escapeJson(text), messageUrl, picUrl
        );
        return sendBatchMessageToEmployees(appKey, appSecret, robotCode, userIds, msgKey, msgParam);
    }
    
    /**
     * 发送ActionCard卡片消息
     * @param userIds 接收消息的员工UserID列表
     * @param title 消息标题
     * @param text 消息内容（支持Markdown）
     * @param singleTitle 按钮标题
     * @param singleUrl 按钮点击跳转URL
     * @return 发送结果
     * @throws Exception 发送过程中的异常
     */
    public static boolean sendActionCardMessageToEmployees(List<String> userIds, String title, String text, String singleTitle, String singleUrl) throws Exception {
        return sendActionCardMessageToEmployees(ROBOT_CLIENT_ID, ROBOT_CLIENT_SECRET, ROBOT_CODE, userIds, title, text, singleTitle, singleUrl);
    }

    /**
     * 发送ActionCard卡片消息（指定机器人配置）
     * @param appKey 机器人的AppKey
     * @param appSecret 机器人的AppSecret
     * @param robotCode 机器人的RobotCode
     * @param userIds 接收消息的员工UserID列表
     * @param title 消息标题
     * @param text 消息内容（支持Markdown）
     * @param singleTitle 按钮标题
     * @param singleUrl 按钮点击跳转URL
     * @return 发送结果
     * @throws Exception 发送过程中的异常
     */
    public static boolean sendActionCardMessageToEmployees(String appKey, String appSecret, String robotCode, List<String> userIds, 
                                         String title, String text, String singleTitle, String singleUrl) throws Exception {
        String msgKey = "sampleActionCard";
        // 构造JSON: 支持 markdown 格式的 text
        String msgParam = String.format(
            "{\"title\": \"%s\", \"text\": \"%s\", \"singleTitle\": \"%s\", \"singleURL\": \"%s\"}",
            escapeJson(title), escapeJson(text), escapeJson(singleTitle), singleUrl
        );
        return sendBatchMessageToEmployees(appKey, appSecret, robotCode, userIds, msgKey, msgParam);
    }


    /**
     * 【核心通用方法】发送单聊消息
     * <p>
     * 官方文档: <a href="https://open.dingtalk.com/document/dingstart/types-of-messages-sent-by-robots">机器人发送消息类型</a>
     * </p>
     *
     * @param appKey    应用AppKey
     * @param appSecret 应用AppSecret
     * @param robotCode 机器人编码 (通常在开发者后台可以找到)
     * @param userIds   接收人的UserId列表
     * @param msgKey    消息模板Key (如 sampleText, sampleMarkdown, sampleImageMsg 等)
     * @param msgParam  消息模板参数 (JSON字符串)
     * @return 发送结果，成功true，失败false
     * @throws Exception 发送异常
     */
    public static boolean sendBatchMessageToEmployees (String appKey, String appSecret, String robotCode, List<String> userIds, String msgKey, String msgParam) throws Exception {
        boolean result = false;

        if (userIds.size() == 0)
            return true;
        
        // 假设 DingTalkUtil.createClient() 已经实现了 client 初始化
        com.aliyun.dingtalkrobot_1_0.Client client = DingTalkUtil.createClient();
        
        BatchSendOTOHeaders batchSendOTOHeaders = new BatchSendOTOHeaders();
        // 假设 getDingTalkRobotAccessToken 是你已有的获取 token 方法
        batchSendOTOHeaders.xAcsDingtalkAccessToken = getDingTalkRobotAccessToken(appKey, appSecret);

        BatchSendOTORequest batchSendOTORequest = new BatchSendOTORequest()
                .setRobotCode(robotCode)
                .setUserIds(userIds)
                .setMsgKey(msgKey)      // 动态设置模板Key
                .setMsgParam(msgParam); // 动态设置参数

        try {
            client.batchSendOTOWithOptions(batchSendOTORequest, batchSendOTOHeaders, new RuntimeOptions());
            result = true;
            System.out.println("发送成功: " + msgKey);
        } catch (TeaException err) {
            System.err.println("发送失败 Code: " + err.code);
            System.err.println("发送失败 Message: " + err.message);
        } catch (Exception _err) {
            TeaException err = new TeaException(_err.getMessage(), _err);
            System.err.println("系统异常: " + err.message);
        }

        return result;
    }

    /**
     * 发送文本消息 (Text)
     */
    public static boolean sendTextMessageToGroup_UserDefineRobotVersion(String content, List<String> atUserIds, boolean isAtAll) {
        OapiRobotSendRequest request = new OapiRobotSendRequest();
        request.setMsgtype("text");
        
        OapiRobotSendRequest.Text text = new OapiRobotSendRequest.Text();
        text.setContent(content);
        request.setText(text);

        return sendUserDefineRobotMessageToGroup(request, atUserIds, isAtAll);
    }

    /**
     * 发送链接消息 (Link) - 支持封面图
     */
    public static boolean sendLinkMessageToGroup_UserDefineRobotVersion(String title, String text, String messageUrl, String picUrl) {
        OapiRobotSendRequest request = new OapiRobotSendRequest();
        request.setMsgtype("link");
        
        OapiRobotSendRequest.Link link = new OapiRobotSendRequest.Link();
        link.setTitle(title);
        link.setText(text);
        link.setMessageUrl(messageUrl);
        link.setPicUrl(picUrl); // 这里的图片链接
        request.setLink(link);

        return sendUserDefineRobotMessageToGroup(request, null, false); // Link类型不支持@
    }

    /**
     * 发送 Markdown 消息 - 推荐用于发送带图详情
     * 图片语法: ![alt](url)
     */
    public static boolean sendMarkdownMessageToGroup_UserDefineRobotVersion(String title, String markdownText, List<String> atUserIds, boolean isAtAll) {
        OapiRobotSendRequest request = new OapiRobotSendRequest();
        request.setMsgtype("markdown");
        
        OapiRobotSendRequest.Markdown markdown = new OapiRobotSendRequest.Markdown();
        markdown.setTitle(title); // 首屏会话透出的展示内容
        markdown.setText(markdownText);
        request.setMarkdown(markdown);

        return sendUserDefineRobotMessageToGroup(request, atUserIds, isAtAll);
    }

    /**
     * 发送 ActionCard (整体跳转)
     */
    public static boolean sendActionCardMessageToGroup_UserDefineRobotVersion(String title, String markdownText, String btnTitle, String btnUrl) {
        OapiRobotSendRequest request = new OapiRobotSendRequest();
        request.setMsgtype("actionCard");
        
        OapiRobotSendRequest.Actioncard actionCard = new OapiRobotSendRequest.Actioncard();
        actionCard.setTitle(title);
        actionCard.setText(markdownText);
        actionCard.setBtnOrientation("0"); // 0-按钮竖直排列，1-横向排列
        actionCard.setSingleTitle(btnTitle);
        actionCard.setSingleURL(btnUrl);
        request.setActionCard(actionCard);

        return sendUserDefineRobotMessageToGroup(request, null, false);
    }

    /**
     * 适用于自定义的机器人，通过webhook直接调用
     * 核心发送逻辑：签名、设置@对象、执行发送
     */
    private static boolean sendUserDefineRobotMessageToGroup(OapiRobotSendRequest request, List<String> atUserIds, boolean isAtAll) {
        try {
            Long timestamp = System.currentTimeMillis();
            String stringToSign = timestamp + "\n" + ROBOT_SECRET;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(ROBOT_SECRET.getBytes("UTF-8"), "HmacSHA256"));
            byte[] signData = mac.doFinal(stringToSign.getBytes("UTF-8"));
            String sign = URLEncoder.encode(new String(Base64.encodeBase64(signData)), "UTF-8");

            String serverUrl = "https://oapi.dingtalk.com/robot/send?sign=" + sign + "&timestamp=" + timestamp;
            DingTalkClient client = new DefaultDingTalkClient(serverUrl);

            // 处理 @ 逻辑
            if (atUserIds != null || isAtAll) {
                OapiRobotSendRequest.At at = new OapiRobotSendRequest.At();
                if (atUserIds != null && !atUserIds.isEmpty()) {
                    at.setAtUserIds(atUserIds);
                }
                at.setIsAtAll(isAtAll);
                request.setAt(at);
            }

            OapiRobotSendResponse rsp = client.execute(request, ROBOT_TOKEN);
            System.out.println("Result: " + rsp.getBody());
            return rsp.isSuccess();

        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

}
