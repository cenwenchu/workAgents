package com.qiyi.util;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.dingtalk.api.DefaultDingTalkClient;
import com.dingtalk.api.DingTalkClient;
import com.dingtalk.api.request.OapiRobotSendRequest;
import com.dingtalk.api.request.OapiUserListsimpleRequest;
import com.dingtalk.api.request.OapiV2DepartmentListsubRequest;
import com.dingtalk.api.request.OapiV2UserGetRequest;
import com.dingtalk.api.response.OapiRobotSendResponse;
import com.dingtalk.api.response.OapiUserListsimpleResponse;
import com.dingtalk.api.response.OapiV2DepartmentListsubResponse;
import com.dingtalk.api.response.OapiV2UserGetResponse;
import com.dingtalk.open.app.api.OpenDingTalkClient;
import com.dingtalk.open.app.api.OpenDingTalkStreamClientBuilder;
import com.dingtalk.open.app.api.callback.OpenDingTalkCallbackListener;
import com.dingtalk.open.app.api.security.AuthClientCredential;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qiyi.dingtalk.DingTalkDepartment;
import com.qiyi.dingtalk.DingTalkUser;
import com.qiyi.podcast.tools.PodCastPostToWechat;
import com.taobao.api.FileItem;
import com.aliyun.dingtalkoauth2_1_0.models.GetAccessTokenResponse;
import com.aliyun.dingtalkrobot_1_0.models.*;
import com.aliyun.dingtalkcalendar_1_0.models.*;
import com.aliyun.tea.TeaException;

import org.apache.commons.codec.binary.Base64;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.aliyun.teaopenapi.models.Config;
import com.aliyun.teautil.models.RuntimeOptions;
import com.qiyi.config.AppConfig;
import java.nio.file.StandardCopyOption;

//机器人方面的配置，可以参考这里：最好是企业机器人 https://open-dev.dingtalk.com/fe/app?hash=%23%2Fcorp%2Fapp#/corp/app
//接口方面的说明可以参考这里：https://open.dingtalk.com/document/development/development-basic-concepts

public class DingTalkUtil {

    // 机器人配置信息（从配置文件加载）
    private static String ROBOT_TOKEN = "";
    private static String ROBOT_SECRET = "";

    public static String ROBOT_CLIENT_ID = "";
    public static String ROBOT_CLIENT_SECRET = "";

    private static String ROBOT_CODE = "";
    private static Long AGENT_ID = 0L; // AgentId 用于发送工作通知（如本地图片）
    private static String PODCAST_PUBLISH_DIR = "";
    public static List<String> PODCAST_ADMIN_USERS = new ArrayList<>();

    private static final java.util.Map<String, ToolHandler> tools = new java.util.HashMap<>();
    private static final java.util.Map<String, String> PARAM_NAME_MAPPING = new java.util.HashMap<>();

    static {
        // 参数名中文映射
        PARAM_NAME_MAPPING.put("maxProcessCount", "单次最大处理数量");
        PARAM_NAME_MAPPING.put("maxTryTimes", "最大重试次数");
        PARAM_NAME_MAPPING.put("maxDuplicatePages", "最大重复页数");
        PARAM_NAME_MAPPING.put("downloadMaxProcessCount", "下载后最大处理数量");
        PARAM_NAME_MAPPING.put("threadPoolSize", "线程池大小");
        PARAM_NAME_MAPPING.put("isDraft", "是否存为草稿");
        PARAM_NAME_MAPPING.put("atUserIds", "@用户列表");
        PARAM_NAME_MAPPING.put("userIds", "接收人用户ID列表");
        PARAM_NAME_MAPPING.put("names", "接收人姓名列表");
        PARAM_NAME_MAPPING.put("content", "消息内容");
        PARAM_NAME_MAPPING.put("summary", "日程标题");
        PARAM_NAME_MAPPING.put("startTime", "开始时间");
        PARAM_NAME_MAPPING.put("endTime", "结束时间");
        PARAM_NAME_MAPPING.put("description", "日程描述");
        PARAM_NAME_MAPPING.put("location", "地点");
        PARAM_NAME_MAPPING.put("attendees", "参与人");

        initClientConfig();PARAM_NAME_MAPPING.put("departments", "部门列表");

        initClientConfig();
        registerTools();
    }

    interface ToolHandler {
        String getName();
        String getDescription();
        void execute(JSONObject params, String senderId, List<String> atUserIds);
    }

    private static void registerTools() {
        tools.put("download_podcast", new ToolHandler() {
            @Override
            public String getName() { return "download_podcast"; }
            @Override
            public String getDescription() {
                return "Download podcasts from Podwise. Parameters: maxProcessCount (int, default 50), maxTryTimes (int, default 15), maxDuplicatePages (int, default 10), downloadMaxProcessCount (int, default 0), threadPoolSize (int, default 15).";
            }
            @Override
            public void execute(JSONObject params, String senderId, List<String> atUserIds) {
                DingTalkUtil.handleDownloadPodCastTask(senderId, atUserIds, params);
            }
        });

        tools.put("publish_wechat", new ToolHandler() {
            @Override
            public String getName() { return "publish_wechat"; }
            @Override
            public String getDescription() {
                return "Publish podcasts to WeChat Official Account. Parameters: isDraft (boolean, default false), atUserIds (List<String>).";
            }
            @Override
            public void execute(JSONObject params, String senderId, List<String> atUserIds) {
                DingTalkUtil.handlePublishTask(senderId, atUserIds, params);
            }
        });

        tools.put("send_message", new ToolHandler() {
            @Override
            public String getName() { return "send_message"; }
            @Override
            public String getDescription() {
                return "Send direct DingTalk text message to specific users. Parameters: content (string, mandatory). Choose ONE of: departments (string/List, names or IDs) OR names (string/List). If both provided, departments take precedence.";
            }
            @Override
            public void execute(JSONObject params, String senderId, List<String> atUserIds) {
                DingTalkUtil.handleSendMessageTask(senderId, atUserIds, params);
            }
        });

        tools.put("create_event", new ToolHandler() {
            @Override
            public String getName() { return "create_event"; }
            @Override
            public String getDescription() {
                return "Create a calendar event. Parameters: summary (string, mandatory), startTime (string, mandatory, yyyy-MM-dd HH:mm:ss), endTime (string, mandatory, yyyy-MM-dd HH:mm:ss), attendees (string/List, mandatory, names/userIds), description (string, optional), location (string, optional).";
            }
            @Override
            public void execute(JSONObject params, String senderId, List<String> atUserIds) {
                DingTalkUtil.handleCreateEventTask(senderId, atUserIds, params);
            }
        });
    }

    private static void analyzeAndExecute(String text, String senderId, List<String> atUserIds) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are an intent classifier. Analyze the user's input and map it to a sequence of tools to be executed.\n");
        sb.append("The tools available are:\n");
        for (ToolHandler tool : tools.values()) {
            sb.append("- Tool: ").append(tool.getName()).append("\n");
            sb.append("  Description: ").append(tool.getDescription()).append("\n");
        }
        sb.append("\nUser Input: \"").append(text).append("\"\n");
        sb.append("\nReturn JSON only (no markdown, no ```json wrapper). The JSON must follow this structure:\n");
        sb.append("{\n");
        sb.append("  \"reply\": \"A polite reply in Chinese summarizing the plan. Do NOT ask for user confirmation or if they want to proceed. State that you are starting the tasks immediately.\",\n");
        sb.append("  \"tasks\": [\n");
        sb.append("    {\n");
        sb.append("      \"tool\": \"tool_name\" (or null if no match found),\n");
        sb.append("      \"confidence\": \"high\" | \"medium\" | \"low\",\n");
        sb.append("      \"parameters\": {\n");
        sb.append("        \"paramName\": value\n");
        sb.append("      },\n");
        sb.append("      \"missing_info\": \"Description of missing MANDATORY information ONLY. If a parameter is optional or has a default value, do NOT list it here. Return empty string if all mandatory info is present.\"\n");
        sb.append("    }\n");
        sb.append("  ]\n");
        sb.append("}");

        try {
            String jsonStr = PodCastUtil.chatWithDeepSeek(sb.toString());
            // Clean up markdown if present
            jsonStr = jsonStr.replaceAll("```json", "").replaceAll("```", "").trim();
            
            JSONObject result = JSON.parseObject(jsonStr);
            String globalReply = result.getString("reply");
            com.alibaba.fastjson2.JSONArray tasks = result.getJSONArray("tasks");

            List<String> notifyUsers = new ArrayList<>();
            if (senderId != null) notifyUsers.add(senderId);

            if (tasks == null || tasks.isEmpty()) {
                if (globalReply != null && !globalReply.isEmpty()) {
                    sendTextMessageToEmployees(notifyUsers, globalReply);
                } else {
                    sendTextMessageToEmployees(notifyUsers, "抱歉，我不理解您的指令或当前不具备该能力。");
                }
                return;
            }

            StringBuilder notification = new StringBuilder();
            if (globalReply != null) notification.append(globalReply);

            List<JSONObject> validTasks = new ArrayList<>();
            boolean hasMissingInfo = false;

            for (int i = 0; i < tasks.size(); i++) {
                JSONObject task = tasks.getJSONObject(i);
                String toolName = task.getString("tool");
                String confidence = task.getString("confidence");
                JSONObject params = task.getJSONObject("parameters");
                String missingInfo = task.getString("missing_info");
                if ("null".equalsIgnoreCase(missingInfo)) missingInfo = null;

                if (toolName != null && tools.containsKey(toolName) && ("high".equalsIgnoreCase(confidence) || "medium".equalsIgnoreCase(confidence))) {
                    java.util.Map<String, String> defaults = extractDefaultParamsFromDescription(tools.get(toolName).getDescription());
                    List<String> defaultEntries = new ArrayList<>();
                    if (defaults != null && !defaults.isEmpty()) {
                        for (java.util.Map.Entry<String, String> e : defaults.entrySet()) {
                            String k = e.getKey();
                            if (params == null || !params.containsKey(k)) {
                                String zhName = PARAM_NAME_MAPPING.getOrDefault(k, k);
                                defaultEntries.add(zhName + "=" + e.getValue());
                            }
                        }
                    }

                    if (missingInfo != null && !missingInfo.isEmpty()) {
                        hasMissingInfo = true;
                        notification.append("\n[任务：").append(toolName).append("] 缺少必选参数：").append(missingInfo);
                        if (!defaultEntries.isEmpty()) {
                            notification.append("。可选参数默认值：").append(String.join("，", defaultEntries));
                        }
                    } else {
                        validTasks.add(task);
                        if (!defaultEntries.isEmpty()) {
                            notification.append("\n[任务：").append(toolName).append("] 将使用默认参数：").append(String.join("，", defaultEntries));
                        }
                    }
                }
            }

            if (notification.length() > 0) {
                sendTextMessageToEmployees(notifyUsers, notification.toString());
            }

            // 如果有缺失信息，暂时不执行任何任务，或者只执行有效的？
            // 考虑到多任务可能有依赖（虽然用户说没交互，但通常逻辑上有），如果有一个缺失，可能整个流程都需要确认。
            // 但用户特别指出“两个工具之间可以没有交互”，所以我们执行有效的任务。
            for (JSONObject task : validTasks) {
                String toolName = task.getString("tool");
                JSONObject params = task.getJSONObject("parameters");
                tools.get(toolName).execute(params, senderId, atUserIds);
            }

        } catch (Exception e) {
            e.printStackTrace();
             List<String> notifyUsers = new ArrayList<>();
             if (senderId != null) notifyUsers.add(senderId);
             try {
                sendTextMessageToEmployees(notifyUsers, "指令解析失败: " + e.getMessage());
             } catch (Exception ex) {
                 ex.printStackTrace();
             }
        }
    }
    private static java.util.Map<String, String> extractDefaultParamsFromDescription(String description) {
        java.util.Map<String, String> map = new java.util.HashMap<>();
        if (description == null) return map;
        int idx = description.indexOf("Parameters:");
        if (idx < 0) return map;
        String part = description.substring(idx + "Parameters:".length()).trim();
        String[] segments = part.split(",");
        for (String seg : segments) {
            String s = seg.trim();
            int lp = s.indexOf('(');
            int rp = s.lastIndexOf(')');
            if (lp > 0 && rp > lp) {
                String name = s.substring(0, lp).trim();
                String inside = s.substring(lp + 1, rp);
                int dIdx = inside.toLowerCase().indexOf("default");
                if (dIdx >= 0) {
                    String dv = inside.substring(dIdx + "default".length()).trim();
                    if (dv.startsWith(" ")) dv = dv.substring(1);
                    if (dv.startsWith(":")) dv = dv.substring(1).trim();
                    int commaIdx = dv.indexOf(',');
                    if (commaIdx >= 0) {
                        dv = dv.substring(0, commaIdx).trim();
                    }
                    map.put(name, dv);
                }
            }
        }
        return map;
    }

    public static void initClientConfig() {
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
            ROBOT_SECRET = props.getProperty("dingtalk.robot.secret");
            ROBOT_CLIENT_ID = props.getProperty("dingtalk.robot.client.id");
            ROBOT_CLIENT_SECRET = props.getProperty("dingtalk.robot.client.secret");
            ROBOT_CODE = props.getProperty("dingtalk.robot.code");
            if (props.containsKey("dingtalk.agent.id")) {
                try {
                    AGENT_ID = Long.parseLong(props.getProperty("dingtalk.agent.id"));
                } catch (NumberFormatException e) {
                    System.err.println("Invalid dingtalk.agent.id format");
                }
            }
        }
        
        if (props.containsKey("podcast.publish.dir")) {
            PODCAST_PUBLISH_DIR = props.getProperty("podcast.publish.dir");
        }
        
        if (props.containsKey("podcast.admin.users")) {
            String users = props.getProperty("podcast.admin.users");
            if (users != null && !users.isEmpty()) {
                PODCAST_ADMIN_USERS = Arrays.asList(users.split(","));
            }
        }
    }


    // 任务锁，确保同一时间只有一个发布任务执行，避免争抢浏览器资源
    private static final java.util.concurrent.locks.ReentrantLock PUBLISH_LOCK = new java.util.concurrent.locks.ReentrantLock();
    // 任务锁，确保同一时间只有一个下载任务执行
    private static final java.util.concurrent.locks.ReentrantLock DOWNLOAD_LOCK = new java.util.concurrent.locks.ReentrantLock();
    private static final com.qiyi.agent.PodwiseAgent podwiseAgent = new com.qiyi.agent.PodwiseAgent();

    private static volatile OpenDingTalkClient streamClient;
    
    /**
     * 从 summary 目录读取未发布的文件到 publish 目录
     */
    private static void stageFilesForPublishing(List<String> notifyUsers) {
        String summaryDirStr = AppConfig.getInstance().getPodcastSummaryDir();
        String publishDirStr = AppConfig.getInstance().getPodcastPublishDir();
        String publishedDirStr = AppConfig.getInstance().getPodcastPublishedDir();
        int batchSize = AppConfig.getInstance().getPodcastPublishBatchSize();

        if (summaryDirStr == null || publishDirStr == null || publishedDirStr == null) {
            System.err.println("目录配置不完整，无法执行文件准备");
            return;
        }

        java.nio.file.Path summaryDir = java.nio.file.Paths.get(summaryDirStr);
        java.nio.file.Path publishDir = java.nio.file.Paths.get(publishDirStr);
        java.nio.file.Path publishedDir = java.nio.file.Paths.get(publishedDirStr);

        if (!java.nio.file.Files.exists(summaryDir)) {
            System.err.println("Summary 目录不存在: " + summaryDirStr);
            return;
        }

        try {
            if (!java.nio.file.Files.exists(publishDir)) {
                java.nio.file.Files.createDirectories(publishDir);
            }
            if (!java.nio.file.Files.exists(publishedDir)) {
                java.nio.file.Files.createDirectories(publishedDir);
            }

            // 获取已发布的文件名集合
            java.util.Set<String> publishedFiles = java.util.stream.Stream.of(publishedDir.toFile().list())
                    .collect(java.util.stream.Collectors.toSet());
            
            // 获取 publish 目录下的文件集合 (避免重复拷贝)
            java.util.Set<String> existingPublishFiles = java.util.stream.Stream.of(publishDir.toFile().list())
                    .collect(java.util.stream.Collectors.toSet());
            
            // 计算当前发布目录下实际待发布的文件数量（过滤掉隐藏文件）
            long currentPendingCount = existingPublishFiles.stream()
                    .filter(name -> !name.startsWith("."))
                    .count();
            
            if (currentPendingCount >= batchSize) {
                System.out.println("发布目录已有 " + currentPendingCount + " 个文件，达到或超过批次大小 " + batchSize + "，不再补充新文件");
                // sendTextMessageToEmployees(notifyUsers, "发布目录已有 " + currentPendingCount + " 个文件，暂不补充新文件");
                return;
            }
            
            int needed = batchSize - (int)currentPendingCount;

            // 查找未发布的文件
            List<java.nio.file.Path> candidates = java.nio.file.Files.walk(summaryDir)
                    .filter(p -> java.nio.file.Files.isRegularFile(p) && !p.getFileName().toString().startsWith("."))
                    .filter(p -> p.toString().endsWith("_summary.txt")) // 假设只处理摘要文件
                    .filter(p -> !publishedFiles.contains(p.getFileName().toString()))
                    .filter(p -> !existingPublishFiles.contains(p.getFileName().toString()))
                    .sorted((p1, p2) -> {
                        try {
                            return java.nio.file.Files.getLastModifiedTime(p2).compareTo(java.nio.file.Files.getLastModifiedTime(p1));
                        } catch (java.io.IOException e) {
                            return 0;
                        }
                    })
                    .limit(needed)
                    .collect(java.util.stream.Collectors.toList());

            if (candidates.isEmpty()) {
                System.out.println("没有需要发布的新文件");
                // sendTextMessageToEmployees(notifyUsers, "没有需要发布的新文件");
                return;
            }

            int count = 0;
            for (java.nio.file.Path src : candidates) {
                java.nio.file.Path dest = publishDir.resolve(src.getFileName());
                java.nio.file.Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING);
                count++;
            }
            
            try {
                sendTextMessageToEmployees(notifyUsers, "已准备 " + count + " 个新文件到发布目录");
            } catch (Exception e) {
                e.printStackTrace();
            }

        } catch (Exception e) {
            e.printStackTrace();
            try {
                sendTextMessageToEmployees(notifyUsers, "准备发布文件时出错: " + e.getMessage());
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        }
    }

    //启动监听机器人消息回调本地线程，搭配RobotMsgCallbackConsumer来使用
    //注意：这里是阻塞线程，需要在单独的线程中调用
    public static synchronized void startRobotMsgCallbackConsumer() {
        startRobotMsgCallbackConsumer(ROBOT_CLIENT_ID, ROBOT_CLIENT_SECRET);
    }

    public static synchronized void startRobotMsgCallbackConsumer(String clientId,String clientSecret) {
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
                        .credential(new AuthClientCredential(clientId, clientSecret))
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

    private static void handleDownloadPodCastTask(String operatorId, List<String> atUserIds, JSONObject params) {
            int maxProcessCount = params != null && params.containsKey("maxProcessCount") ? params.getIntValue("maxProcessCount") : 50;
            int maxTryTimes = params != null && params.containsKey("maxTryTimes") ? params.getIntValue("maxTryTimes") : 15;
            int maxDuplicatePages = params != null && params.containsKey("maxDuplicatePages") ? params.getIntValue("maxDuplicatePages") : 5;
            int downloadMaxProcessCount = params != null && params.containsKey("downloadMaxProcessCount") ? params.getIntValue("downloadMaxProcessCount") : 0;
            int threadPoolSize = params != null && params.containsKey("threadPoolSize") ? params.getIntValue("threadPoolSize") : 15;

            List<String> notifyUsers = new ArrayList<>();
            if (operatorId != null) notifyUsers.add(operatorId);
            if (atUserIds != null && !atUserIds.isEmpty()) {
                notifyUsers.addAll(atUserIds);
            }

            if (!DOWNLOAD_LOCK.tryLock()) {
                try {
                    sendTextMessageToEmployees(notifyUsers, "当前已有下载任务正在执行，请稍后再试。");
                } catch (Exception e) {
                    e.printStackTrace();
                }
                return;
            }

            try {
                sendTextMessageToEmployees(notifyUsers, "开始执行下载任务...");
                int count = podwiseAgent.run(maxProcessCount, maxTryTimes, maxDuplicatePages, downloadMaxProcessCount, threadPoolSize);
                sendTextMessageToEmployees(notifyUsers, "下载任务执行完毕，共下载更新了 " + count + " 条播客。");
            } catch (Exception e) {
                e.printStackTrace();
                try {
                    sendTextMessageToEmployees(notifyUsers, "下载任务执行异常: " + e.getMessage());
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
            } finally {
                DOWNLOAD_LOCK.unlock();
            }
        }




        private static void handlePublishTask(String operatorId,List<String> atUserIds, JSONObject params) {
             boolean isDraft = params != null && params.containsKey("isDraft") ? params.getBooleanValue("isDraft") : false;
             List<String> notifyUsers = new ArrayList<>();
             if (operatorId != null) notifyUsers.add(operatorId);
             if (atUserIds != null && !atUserIds.isEmpty()) {
                 notifyUsers.addAll(atUserIds);
             }

             // 如果没有操作者ID（不太可能），或者想通知所有人，可以调整这里
             // 如果没有配置管理员，且 operatorId 为空，可能需要默认通知列表

             // 尝试获取锁，如果获取失败说明有任务正在执行
             if (!PUBLISH_LOCK.tryLock()) {
                 try {
                     sendTextMessageToEmployees(notifyUsers, "当前已有发布任务正在执行，请稍后再试。");
                 } catch (Exception e) {
                     e.printStackTrace();
                 }
                 return;
             }

             try {
                 if (PODCAST_PUBLISH_DIR == null || PODCAST_PUBLISH_DIR.isEmpty()) {
                     sendTextMessageToEmployees(notifyUsers, "发布目录未配置，请检查 podcast.cfg");
                     return;
                 }
                 
                 sendTextMessageToEmployees(notifyUsers, "开始执行发布任务...");

                 // 1. 准备发布文件
                 stageFilesForPublishing(notifyUsers);
                 
                 PlayWrightUtil.Connection connection = PlayWrightUtil.connectAndAutomate();
                 if (connection == null){
                      sendTextMessageToEmployees(notifyUsers, "无法连接到浏览器，任务终止");
                      return;
                 }
                 
                 try {
                     // 2. 检查微信登录状态
                     if (!checkWechatLogin(connection, notifyUsers)) {
                         return;
                     }
                     
                     // 3. 执行发布文件处理
                     processPublishFiles(connection, notifyUsers, isDraft);
                     
                 } finally {
                     PlayWrightUtil.disconnectBrowser(connection.playwright, connection.browser);
                 }
                 
                 sendTextMessageToEmployees(notifyUsers, "所有发布任务执行完毕");
                 
             } catch (Exception e) {
                 e.printStackTrace();
                 try {
                     sendTextMessageToEmployees(notifyUsers, "发布任务执行异常: " + e.getMessage());
        } catch (Exception ex) {
            ex.printStackTrace();
        }
    } finally {
       PUBLISH_LOCK.unlock();
    }
}

        private static void handleSendMessageTask(String operatorId, List<String> atUserIds, JSONObject params) {
            String content = params != null ? params.getString("content") : null;
            List<String> recipients = new ArrayList<>();
            List<String> notFoundNames = new ArrayList<>();
            List<String> notFoundDepartments = new ArrayList<>();
            boolean usedDepartments = false;
            
            if (params != null) {
                // 1) 优先：按部门选择（若提供则部门优先于人）
                if (params.containsKey("departments")) {
                    List<String> deptKeys = new ArrayList<>();
                    Object depObj = params.get("departments");
                    if (depObj instanceof com.alibaba.fastjson2.JSONArray) {
                        for (Object o : (com.alibaba.fastjson2.JSONArray) depObj) {
                            if (o != null) deptKeys.add(String.valueOf(o));
                        }
                    } else if (depObj instanceof java.util.Collection) {
                        for (Object o : (java.util.Collection<?>) depObj) {
                            if (o != null) deptKeys.add(String.valueOf(o));
                        }
                    } else if (depObj instanceof String) {
                        String s = (String) depObj;
                        if (s != null && !s.trim().isEmpty()) {
                            String[] parts = s.split("[,，\\s]+");
                            for (String p : parts) {
                                if (!p.trim().isEmpty()) deptKeys.add(p.trim());
                            }
                        }
                    }
                    if (!deptKeys.isEmpty()) {
                        try {
                            List<DingTalkDepartment> departments = getAllDepartments(true, true);
                            java.util.Map<String, DingTalkDepartment> deptById = new java.util.HashMap<>();
                            java.util.Map<String, DingTalkDepartment> deptByName = new java.util.HashMap<>();
                            for (DingTalkDepartment d : departments) {
                                deptById.put(d.getDeptId(), d);
                                deptByName.put(d.getName(), d);
                            }
                            List<String> deptRecipients = new ArrayList<>();
                            for (String key : deptKeys) {
                                DingTalkDepartment dept = deptById.get(key);
                                if (dept == null) {
                                    dept = deptByName.get(key);
                                }
                                if (dept != null && dept.getUserList() != null) {
                                    for (DingTalkUser u : dept.getUserList()) {
                                        String uid = u.getUserid();
                                        if (uid != null && !uid.isEmpty() && !deptRecipients.contains(uid)) {
                                            deptRecipients.add(uid);
                                        }
                                    }
                                } else {
                                    notFoundDepartments.add(key);
                                }
                            }
                            if (!deptRecipients.isEmpty()) {
                                recipients.clear();
                                recipients.addAll(deptRecipients);
                                usedDepartments = true;
                            }
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                }

                // 2) 非部门路径：按用户ID或姓名选择
                if (params.containsKey("userIds")) {
                    Object ids = params.get("userIds");
                    if (ids instanceof com.alibaba.fastjson2.JSONArray) {
                        for (Object o : (com.alibaba.fastjson2.JSONArray) ids) {
                            if (o != null) recipients.add(String.valueOf(o));
                        }
                    } else if (ids instanceof java.util.Collection) {
                        for (Object o : (java.util.Collection<?>) ids) {
                            if (o != null) recipients.add(String.valueOf(o));
                        }
                    } else if (ids instanceof String) {
                        String s = (String) ids;
                        if (s != null && !s.trim().isEmpty()) {
                            String[] parts = s.split("[,，\\s]+");
                            for (String p : parts) {
                                if (!p.trim().isEmpty()) recipients.add(p.trim());
                            }
                        }
                    }
                }
                
                if (!usedDepartments && params.containsKey("names")) {
                    List<String> nameList = new ArrayList<>();
                    Object namesObj = params.get("names");
                    if (namesObj instanceof com.alibaba.fastjson2.JSONArray) {
                        for (Object o : (com.alibaba.fastjson2.JSONArray) namesObj) {
                            if (o != null) nameList.add(String.valueOf(o));
                        }
                    } else if (namesObj instanceof java.util.Collection) {
                        for (Object o : (java.util.Collection<?>) namesObj) {
                            if (o != null) nameList.add(String.valueOf(o));
                        }
                    } else if (namesObj instanceof String) {
                        String s = (String) namesObj;
                        if (s != null && !s.trim().isEmpty()) {
                            String[] parts = s.split("[,，\\s]+");
                            for (String p : parts) {
                                if (!p.trim().isEmpty()) nameList.add(p.trim());
                            }
                        }
                    }

                    if (!nameList.isEmpty()) {
                        try {
                            List<DingTalkDepartment> departments = getAllDepartments(true, true);
                            java.util.Map<String, String> userMap = new java.util.HashMap<>();
                            for (DingTalkDepartment dept : departments) {
                                if (dept.getUserList() != null) {
                                    for (DingTalkUser user : dept.getUserList()) {
                                        userMap.put(user.getName(), user.getUserid());
                                    }
                                }
                            }
                            
                            for (String name : nameList) {
                                if (userMap.containsKey(name)) {
                                    String uid = userMap.get(name);
                                    if (!recipients.contains(uid)) {
                                        recipients.add(uid);
                                    }
                                } else {
                                    notFoundNames.add(name);
                                }
                            }
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                }
            }

            if (!usedDepartments && atUserIds != null && !atUserIds.isEmpty()) {
                for (String uid : atUserIds) {
                    if (!recipients.contains(uid)) {
                        recipients.add(uid);
                    }
                }
            }
            
            List<String> notifyUsers = new ArrayList<>();
            if (operatorId != null) notifyUsers.add(operatorId);
            
            if (content == null || content.trim().isEmpty()) {
                try {
                    sendTextMessageToEmployees(notifyUsers, "未提供消息内容，未执行发送。");
                } catch (Exception e) {
                    e.printStackTrace();
                }
                return;
            }

            if (!notFoundDepartments.isEmpty()) {
                try {
                    sendTextMessageToEmployees(notifyUsers, "未找到以下部门: " + String.join("，", notFoundDepartments) + "。请确认部门名称或ID是否正确。");
                } catch (Exception e) {
                    e.printStackTrace();
                }
                if (recipients.isEmpty()) {
                    return;
                }
            }

            if (!notFoundNames.isEmpty()) {
                try {
                    sendTextMessageToEmployees(notifyUsers, "未找到以下用户: " + String.join("，", notFoundNames) + "。请确认姓名是否正确。");
                } catch (Exception e) {
                    e.printStackTrace();
                }
                if (recipients.isEmpty()) {
                    return;
                }
            }

            if (recipients.isEmpty()) {
                try {
                    sendTextMessageToEmployees(notifyUsers, "未指定有效的接收人（部门或用户），未执行发送。");
                } catch (Exception e) {
                    e.printStackTrace();
                }
                return;
            }
            
            try {
                String senderName = null;
                try {
                    List<DingTalkDepartment> departments = getAllDepartments(true, true);
                    java.util.Map<String, String> idNameMap = new java.util.HashMap<>();
                    for (DingTalkDepartment dept : departments) {
                        if (dept.getUserList() != null) {
                            for (DingTalkUser user : dept.getUserList()) {
                                idNameMap.put(user.getUserid(), user.getName());
                            }
                        }
                    }
                    if (operatorId != null) {
                        senderName = idNameMap.get(operatorId);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
                String finalContent = (senderName != null && !senderName.trim().isEmpty())
                        ? ("【消息发起人：" + senderName + "】" + content)
                        : ("【消息发起人：" + (operatorId != null ? operatorId : "未知") + "】" + content);
                sendTextMessageToEmployees(recipients, finalContent);
                sendTextMessageToEmployees(notifyUsers, "已向 " + recipients.size() + " 位用户发送消息");
            } catch (Exception e) {
                e.printStackTrace();
                try {
                    sendTextMessageToEmployees(notifyUsers, "发送消息失败: " + e.getMessage());
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
            }
        }
    private static void handleCreateEventTask(String operatorId, List<String> atUserIds, JSONObject params) {
        String summary = params.getString("summary");
        String startTimeStr = params.getString("startTime");
        String endTimeStr = params.getString("endTime");
        String description = params.getString("description");
        String location = params.getString("location");
        Object attendeesObj = params.get("attendees");

        List<String> notifyUsers = new ArrayList<>();
        if (operatorId != null) notifyUsers.add(operatorId);

        // 1. Resolve Attendees
        List<String> attendeeUserIds = new ArrayList<>();
        List<String> notFoundNames = new ArrayList<>();

        if (attendeesObj != null) {
            List<String> inputNames = new ArrayList<>();
            if (attendeesObj instanceof com.alibaba.fastjson2.JSONArray) {
                for (Object o : (com.alibaba.fastjson2.JSONArray) attendeesObj) {
                    if (o != null) inputNames.add(String.valueOf(o));
                }
            } else if (attendeesObj instanceof java.util.Collection) {
                for (Object o : (java.util.Collection<?>) attendeesObj) {
                    if (o != null) inputNames.add(String.valueOf(o));
                }
            } else if (attendeesObj instanceof String) {
                String s = (String) attendeesObj;
                if (!s.trim().isEmpty()) {
                    String[] parts = s.split("[,，\\s]+");
                    for (String p : parts) {
                        if (!p.trim().isEmpty()) inputNames.add(p.trim());
                    }
                }
            }

            if (!inputNames.isEmpty()) {
                 try {
                    // Try to resolve names to IDs
                    List<DingTalkDepartment> departments = getAllDepartments(true, true);
                    java.util.Map<String, String> userMap = new java.util.HashMap<>();
                    for (DingTalkDepartment dept : departments) {
                        if (dept.getUserList() != null) {
                            for (DingTalkUser user : dept.getUserList()) {
                                userMap.put(user.getName(), user.getUserid());
                            }
                        }
                    }
                    
                    for (String name : inputNames) {
                        if (userMap.containsKey(name)) {
                            String uid = userMap.get(name);
                            if (!attendeeUserIds.contains(uid)) {
                                attendeeUserIds.add(uid);
                            }
                        } else if (userMap.containsValue(name)) { // Check if input is already an ID (less likely for name field but possible)
                             if (!attendeeUserIds.contains(name)) {
                                attendeeUserIds.add(name);
                             }
                        } else {
                            notFoundNames.add(name);
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
        
        // Add atUserIds if any
        if (atUserIds != null) {
             for(String uid : atUserIds) {
                 if(!attendeeUserIds.contains(uid)) attendeeUserIds.add(uid);
             }
        }

        if (!notFoundNames.isEmpty()) {
             try {
                sendTextMessageToEmployees(notifyUsers, "创建日程警告: 未找到以下参与人: " + String.join("，", notFoundNames) + "。");
             } catch (Exception e) {
                e.printStackTrace();
            }
        }

        if (attendeeUserIds.isEmpty()) {
            try {
                sendTextMessageToEmployees(notifyUsers, "创建日程失败: 未指定有效的参与人。");
            } catch (Exception e) {
                e.printStackTrace();
            }
            return;
        }

        // 2. Validate Time
        if (startTimeStr == null || endTimeStr == null) {
             try {
                sendTextMessageToEmployees(notifyUsers, "创建日程失败: 开始时间或结束时间缺失。");
            } catch (Exception e) {
                e.printStackTrace();
            }
            return;
        }

        // 3. Convert Time to ISO 8601 (yyyy-MM-dd'T'HH:mm:ss+08:00)
        String isoStartTime;
        String isoEndTime;
        try {
            java.time.format.DateTimeFormatter inputFormatter = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
            java.time.LocalDateTime localStart = java.time.LocalDateTime.parse(startTimeStr, inputFormatter);
            java.time.LocalDateTime localEnd = java.time.LocalDateTime.parse(endTimeStr, inputFormatter);
            
            java.time.ZonedDateTime zonedStart = localStart.atZone(java.time.ZoneId.of("Asia/Shanghai"));
            java.time.ZonedDateTime zonedEnd = localEnd.atZone(java.time.ZoneId.of("Asia/Shanghai"));
            
            isoStartTime = zonedStart.format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME);
            isoEndTime = zonedEnd.format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        } catch (Exception e) {
             // Fallback or rethrow, but better to let the user know input was bad if it fails here
             // However, for safety if simple replace was working before (but getting 400), we stick to new logic
             throw new RuntimeException("时间格式解析错误，请确保使用 yyyy-MM-dd HH:mm:ss 格式。Input: " + startTimeStr + " / " + endTimeStr);
        }

        // 4. Create Event
        try {
            // Using operatorId as the user to create event for
            String unionId = getUnionIdByUserId(operatorId);
            if (unionId == null) {
                 throw new RuntimeException("无法获取操作人的 UnionId，无法创建日程。UserId: " + operatorId);
            }
            
            // Convert attendeeUserIds to UnionIds
            List<String> attendeeUnionIds = new ArrayList<>();
            List<String> failedConversionUsers = new ArrayList<>();
            for (String uid : attendeeUserIds) {
                try {
                    String uUnionId = getUnionIdByUserId(uid);
                    if (uUnionId != null) {
                        attendeeUnionIds.add(uUnionId);
                    } else {
                        failedConversionUsers.add(uid);
                    }
                } catch (Exception e) {
                    failedConversionUsers.add(uid);
                    e.printStackTrace();
                }
            }
            
            if (!failedConversionUsers.isEmpty()) {
                 sendTextMessageToEmployees(notifyUsers, "警告: 无法获取以下用户的 UnionId，将被忽略: " + String.join(", ", failedConversionUsers));
            }

            if (attendeeUnionIds.isEmpty() && !attendeeUserIds.isEmpty()) {
                 throw new RuntimeException("没有有效的参与人 (UnionId 获取失败)");
            }
            
            String eventId = createCalendarEvent(unionId, summary, description, isoStartTime, isoEndTime, attendeeUnionIds, location);
            sendTextMessageToEmployees(notifyUsers, "日程创建成功！标题: " + summary + "，时间: " + startTimeStr + " - " + endTimeStr + "，参与人数: " + attendeeUnionIds.size());
        } catch (Exception e) {
            e.printStackTrace();
            try {
                sendTextMessageToEmployees(notifyUsers, "创建日程失败: " + e.getMessage());
            } catch (Exception ex) {
                ex.printStackTrace();
            }
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

    private static boolean checkWechatLogin(PlayWrightUtil.Connection connection, List<String> notifyUsers) {
        try {
            com.microsoft.playwright.BrowserContext context = connection.browser.contexts().isEmpty() ? 
                    connection.browser.newContext() : connection.browser.contexts().get(0);
            com.microsoft.playwright.Page checkPage = context.newPage();
            // 设置大视口，确保截图清晰
            checkPage.setViewportSize(1920, 1080);
            try {
                checkPage.navigate(PodCastPostToWechat.WECHAT_LOGIN_URL);
                checkPage.waitForLoadState(com.microsoft.playwright.options.LoadState.NETWORKIDLE);
                if (!PodCastUtil.isWechatLoggedIn(checkPage)) {
                    sendTextMessageToEmployees(notifyUsers, "检测到微信公众号未登录，请及时在服务器浏览器完成扫码登录，任务将继续执行等待。");
                    
                    // 尝试截图并发送 (需配置 dingtalk.agent.id)
                    if (AGENT_ID != 0) {
                        try {
                            // 增加等待时间，确保二维码完全加载
                            checkPage.waitForTimeout(3000); 
                            
                            String fileName = "login_screenshot_" + System.currentTimeMillis() + ".png";
                            java.nio.file.Path screenshotPath = java.nio.file.Paths.get(PODCAST_PUBLISH_DIR, fileName);
                            
                            // 尝试定位登录框截图，清晰度更高
                            com.microsoft.playwright.Locator loginFrame = checkPage.locator(".login_frame");
                            if (loginFrame.isVisible()) {
                                loginFrame.screenshot(new com.microsoft.playwright.Locator.ScreenshotOptions().setPath(screenshotPath));
                            } else {
                                checkPage.screenshot(new com.microsoft.playwright.Page.ScreenshotOptions().setPath(screenshotPath));
                            }
                            
                            String mediaId = uploadMedia(ROBOT_CLIENT_ID, ROBOT_CLIENT_SECRET, screenshotPath.toFile());

                            sendAsyncWorkTextMessage(notifyUsers, "微信公众号未登录，请扫描下方二维码进行登录： --" + System.currentTimeMillis());
                            sendAsyncWorkImageMessage(notifyUsers, mediaId);
                            
                            // 上传成功后删除本地文件
                            screenshotPath.toFile().delete();
                        } catch (Exception e) {
                            System.err.println("Failed to capture and send screenshot: " + e.getMessage());
                            e.printStackTrace();
                        }
                    }
                    
                    // 阻塞等待用户扫码登录
                    long maxWaitTime = 5 * 60 * 1000; // 5分钟超时
                    long startTime = System.currentTimeMillis();
                    boolean isLogged = false;
                    
                    while (!isLogged) {
                        if (System.currentTimeMillis() - startTime > maxWaitTime) {
                            sendTextMessageToEmployees(notifyUsers, "微信登录超时，任务终止。");
                            return false;
                        }
                        
                        try {
                            Thread.sleep(3000);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            sendTextMessageToEmployees(notifyUsers, "等待登录被中断，任务终止。");
                            return false;
                        }
                        isLogged = PodCastUtil.isWechatLoggedIn(checkPage);
                    }
                    
                    sendTextMessageToEmployees(notifyUsers, "微信登录成功，继续执行任务。");
                }
                return true;
            } finally {
                if (!checkPage.isClosed()) {
                    checkPage.close();
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    private static void processPublishFiles(PlayWrightUtil.Connection connection, List<String> notifyUsers, boolean isDraft) {
        try {
             PodCastPostToWechat task = new PodCastPostToWechat(connection.browser);
             
             java.nio.file.Path publishDirPath = java.nio.file.Paths.get(PODCAST_PUBLISH_DIR);
             if (!java.nio.file.Files.exists(publishDirPath) || !java.nio.file.Files.isDirectory(publishDirPath)) {
                 sendTextMessageToEmployees(notifyUsers, "发布目录不存在或无效: " + PODCAST_PUBLISH_DIR);
                 return;
             }

             java.util.List<String> podcastFilePaths = java.nio.file.Files.walk(publishDirPath)
                        .filter(p -> java.nio.file.Files.isRegularFile(p) && !p.getFileName().toString().startsWith(".")) // 忽略隐藏文件
                        .map(p -> p.toString())
                        .collect(java.util.stream.Collectors.toList());
             
             if (podcastFilePaths.isEmpty()) {
                 sendTextMessageToEmployees(notifyUsers, "目录中没有找到待发布的文件: " + PODCAST_PUBLISH_DIR);
                 return;
             }
             
             for (String podcastFilePath : podcastFilePaths) {
                  try {
                      String result = task.publishPodcastToWechat(podcastFilePath, isDraft);
                      if (result.startsWith(PodCastPostToWechat.SUCCESS_MSG)){
                          sendTextMessageToEmployees(notifyUsers, "文件 " + new java.io.File(podcastFilePath).getName() + "， " + result);

                          // 移动文件到已发布目录
                          try {
                              String publishedDirStr = AppConfig.getInstance().getPodcastPublishedDir();
                              if (publishedDirStr != null) {
                                  java.nio.file.Path publishedDir = java.nio.file.Paths.get(publishedDirStr);
                                  if (!java.nio.file.Files.exists(publishedDir)) {
                                      java.nio.file.Files.createDirectories(publishedDir);
                                  }
                                  java.nio.file.Path srcFile = java.nio.file.Paths.get(podcastFilePath);
                                  java.nio.file.Path destFile = publishedDir.resolve(srcFile.getFileName());
                                  java.nio.file.Files.move(srcFile, destFile, StandardCopyOption.REPLACE_EXISTING);
                                  System.out.println("Moved published file to: " + destFile);
                              }
                          } catch (Exception moveEx) {
                               System.err.println("Failed to move published file: " + moveEx.getMessage());
                               moveEx.printStackTrace();
                               sendTextMessageToEmployees(notifyUsers, "文件已发布但移动到已发布目录失败: " + moveEx.getMessage());
                          }
                      }
                      else
                          sendTextMessageToEmployees(notifyUsers, "文件 " + new java.io.File(podcastFilePath).getName() + "，发布失败: " + result);
                      
                  } catch (Exception e) {
                      sendTextMessageToEmployees(notifyUsers, "文件 " + new java.io.File(podcastFilePath).getName() + " 发布失败: " + e.getMessage());
                      e.printStackTrace();
                  }
             }
         }
         catch (Exception e) {
             e.printStackTrace();
             try {
                sendTextMessageToEmployees(notifyUsers, "处理发布文件时出错: " + e.getMessage());
             } catch (Exception ex) {
                 ex.printStackTrace();
             }
         }
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
            
            String response = PodCastUtil.chatWithDeepSeek(messages, false);
            
            // 4. 解析结果并匹配 UserID
            if (response != null && !response.trim().isEmpty()) {
                // 去除可能存在的 Markdown 代码块标记
                response = response.replace("```", "").trim();
                
                String[] names = response.split("[,，]"); // 支持中英文逗号
                for (String name : names) {
                    String trimmedName = name.trim();
                    if (trimmedName.isEmpty()) continue;
                    
                    // 尝试精确匹配
                    if (userMap.containsKey(trimmedName)) {
                        atUserIds.add(userMap.get(trimmedName));
                        System.out.println("DeepSeek 提取的姓名 '" + trimmedName + "' 匹配到用户ID: " + userMap.get(trimmedName));
                    } else {
                         System.out.println("DeepSeek 提取的姓名 '" + trimmedName + "' 未在用户列表中找到");
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

    public static List<DingTalkDepartment> getAllDepartments(String appKey, String appSecret, boolean isNeedUserList, boolean needCache) throws Exception {
        String cacheKey = "dingtalk_departments_v2_" + appKey + "_" + isNeedUserList;
        
        if (needCache) {
            // 尝试从缓存获取
            String cachedData = dingTalkCache.get(cacheKey);
            if (cachedData != null && !cachedData.isEmpty()) {
                try {
                    ObjectMapper mapper = new ObjectMapper();
                    return mapper.readValue(cachedData, new com.fasterxml.jackson.core.type.TypeReference<List<DingTalkDepartment>>(){});
                } catch (Exception e) {
                    // 如果反序列化失败，忽略缓存错误，继续执行重新获取逻辑
                }
            }
        }

        List<DingTalkDepartment> allDepartments = new ArrayList<>();

        allDepartments.add(new DingTalkDepartment("1","根部门","0"));

        // 递归获取所有部门，从根部门 ID 1 开始
        traverseDepartments(appKey, appSecret, null, allDepartments);

        if (isNeedUserList)
        {
            // 为每个部门添加用户列表
            for (DingTalkDepartment dept : allDepartments) {
                dept.setUserList(getAllUsersInDepartment(appKey, appSecret, dept.getDeptId()));
            }
        }
        
        if (needCache) {
            // 存入缓存
            try {
                ObjectMapper mapper = new ObjectMapper();
                String jsonStr = mapper.writeValueAsString(allDepartments);
                dingTalkCache.put(cacheKey, jsonStr, 30*60);
            } catch (Exception e) {
                System.err.println("Failed to serialize departments for cache: " + e.getMessage());
            }
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
        List<DingTalkDepartment> allDepartments = getAllDepartments(ROBOT_CLIENT_ID, ROBOT_CLIENT_SECRET, true, true);
        
        for (DingTalkDepartment dept : allDepartments) {

            if (dept.getUserList() != null && !dept.getUserList().isEmpty())
            {
                for (DingTalkUser user : dept.getUserList()) {
                    if (user.getName().equals(name)) {
                        return user;
                    }
                }
            }
        }
        return null;
    }

    /**
     * 1. 发送【文本消息】
     * 模板Key: sampleText
     */
    public static boolean sendTextMessageToEmployees(List<String> userIds, String content) throws Exception {
        return sendTextMessageToEmployees(ROBOT_CLIENT_ID, ROBOT_CLIENT_SECRET, ROBOT_CODE, userIds, content);
    }

    public static boolean sendTextMessageToEmployees(String appKey, String appSecret, String robotCode, List<String> userIds, String content) throws Exception {
        String msgKey = "sampleText";
        // 构造JSON: {"content": "具体的文本内容"}
        String msgParam = String.format("{\"content\": \"%s\"}", escapeJson(content));
        return sendBatchMessageToEmployees(appKey, appSecret, robotCode, userIds, msgKey, msgParam);
    }

    /**
     * 2. 发送【图片消息】
     * 模板Key: sampleImageMsg
     * 注意: photoURL 必须是公网可访问的图片链接
     */
    public static boolean sendImageMessageToEmployees(List<String> userIds, String photoUrl) throws Exception {
        return sendImageMessageToEmployees(ROBOT_CLIENT_ID, ROBOT_CLIENT_SECRET, ROBOT_CODE, userIds, photoUrl);
    }

    public static boolean sendImageMessageToEmployees(String appKey, String appSecret, String robotCode, List<String> userIds, String photoUrl) throws Exception {
        String msgKey = "sampleImageMsg";
        // 构造JSON: {"photoURL": "https://..."}
        String msgParam = String.format("{\"photoURL\": \"%s\"}", photoUrl);
        return sendBatchMessageToEmployees(appKey, appSecret, robotCode, userIds, msgKey, msgParam);
    }

    /**
     * 3. 发送【Markdown消息】
     * 模板Key: sampleMarkdown
     */
    public static boolean sendMarkdownMessageToEmployees(List<String> userIds, String title, String markdownText) throws Exception {
        return sendMarkdownMessageToEmployees(ROBOT_CLIENT_ID, ROBOT_CLIENT_SECRET, ROBOT_CODE, userIds, title, markdownText);
    }

    public static boolean sendMarkdownMessageToEmployees(String appKey, String appSecret, String robotCode, List<String> userIds, String title, String markdownText) throws Exception {
        String msgKey = "sampleMarkdown";
        // 构造JSON: {"title": "标题", "text": "markdown内容"}
        // 注意：实际生产中请使用 JSON 库 (如 Gson/Jackson) 来避免拼接字符串时的转义问题
        String msgParam = String.format("{\"title\": \"%s\", \"text\": \"%s\"}", escapeJson(title), escapeJson(markdownText));
        return sendBatchMessageToEmployees(appKey, appSecret, robotCode, userIds, msgKey, msgParam);
    }

    /**
     * 4. 发送【链接消息】
     * 模板Key: sampleLink
     */
    public static boolean sendLinkMessageToEmployees(List<String> userIds, String title, String text, String messageUrl, String picUrl) throws Exception {
        return sendLinkMessageToEmployees(ROBOT_CLIENT_ID, ROBOT_CLIENT_SECRET, ROBOT_CODE, userIds, title, text, messageUrl, picUrl);
    }

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
     * 5. 发送【ActionCard 卡片消息】
     * 模板Key: sampleActionCard
     */
    public static boolean sendActionCardMessageToEmployees(List<String> userIds, String title, String text, String singleTitle, String singleUrl) throws Exception {
        return sendActionCardMessageToEmployees(ROBOT_CLIENT_ID, ROBOT_CLIENT_SECRET, ROBOT_CODE, userIds, title, text, singleTitle, singleUrl);
    }

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
     * 文档: https://open.dingtalk.com/document/dingstart/types-of-messages-sent-by-robots
     *
     * @param appKey    应用AppKey
     * @param appSecret 应用AppSecret
     * @param robotCode 机器人编码 (通常在开发者后台可以找到)
     * @param userIds   接收人的UserId列表
     * @param msgKey    消息模板Key (如 sampleText, sampleMarkdown, sampleImageMsg 等)
     * @param msgParam  消息模板参数 (JSON字符串)
     */
    public static boolean sendBatchMessageToEmployees (String appKey, String appSecret, String robotCode, List<String> userIds, String msgKey, String msgParam) throws Exception {
        boolean result = false;
        
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
    public static boolean sendTextMessageToGroup(String content, List<String> atUserIds, boolean isAtAll) {
        OapiRobotSendRequest request = new OapiRobotSendRequest();
        request.setMsgtype("text");
        
        OapiRobotSendRequest.Text text = new OapiRobotSendRequest.Text();
        text.setContent(content);
        request.setText(text);

        return sendRobotMessageToGroup(request, atUserIds, isAtAll);
    }

    /**
     * 发送链接消息 (Link) - 支持封面图
     */
    public static boolean sendLinkMessageToGroup(String title, String text, String messageUrl, String picUrl) {
        OapiRobotSendRequest request = new OapiRobotSendRequest();
        request.setMsgtype("link");
        
        OapiRobotSendRequest.Link link = new OapiRobotSendRequest.Link();
        link.setTitle(title);
        link.setText(text);
        link.setMessageUrl(messageUrl);
        link.setPicUrl(picUrl); // 这里的图片链接
        request.setLink(link);

        return sendRobotMessageToGroup(request, null, false); // Link类型不支持@
    }

    /**
     * 发送 Markdown 消息 - 推荐用于发送带图详情
     * 图片语法: ![alt](url)
     */
    public static boolean sendMarkdownMessageToGroup(String title, String markdownText, List<String> atUserIds, boolean isAtAll) {
        OapiRobotSendRequest request = new OapiRobotSendRequest();
        request.setMsgtype("markdown");
        
        OapiRobotSendRequest.Markdown markdown = new OapiRobotSendRequest.Markdown();
        markdown.setTitle(title); // 首屏会话透出的展示内容
        markdown.setText(markdownText);
        request.setMarkdown(markdown);

        return sendRobotMessageToGroup(request, atUserIds, isAtAll);
    }

    /**
     * 发送 ActionCard (整体跳转)
     */
    public static boolean sendActionCardMessageToGroup(String title, String markdownText, String btnTitle, String btnUrl) {
        OapiRobotSendRequest request = new OapiRobotSendRequest();
        request.setMsgtype("actionCard");
        
        OapiRobotSendRequest.Actioncard actionCard = new OapiRobotSendRequest.Actioncard();
        actionCard.setTitle(title);
        actionCard.setText(markdownText);
        actionCard.setBtnOrientation("0"); // 0-按钮竖直排列，1-横向排列
        actionCard.setSingleTitle(btnTitle);
        actionCard.setSingleURL(btnUrl);
        request.setActionCard(actionCard);

        return sendRobotMessageToGroup(request, null, false);
    }

    /**
     * 核心发送逻辑：签名、设置@对象、执行发送
     */
    private static boolean sendRobotMessageToGroup(OapiRobotSendRequest request, List<String> atUserIds, boolean isAtAll) {
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
