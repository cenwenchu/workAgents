package com.qiyi.tools.dingtalk;

import com.alibaba.fastjson2.JSONObject;
import com.qiyi.service.dingtalk.DingTalkDepartment;
import com.qiyi.service.dingtalk.DingTalkUser;
import com.qiyi.service.dingtalk.DingTalkService;
import com.qiyi.component.ComponentId;
import com.qiyi.tools.Tool;
import com.qiyi.tools.ToolMessenger;
import com.qiyi.util.AppLog;

import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;
import java.util.Collection;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import com.qiyi.tools.ToolContext;
import java.util.Collections;

/**
 * 钉钉日程创建工具。
 *
 * <p>核心流程：解析入参 → 解析参会人（姓名/uid）→ 调用 DingTalkService 创建日程 → 通知发起人/被 @ 的人。</p>
 */
@Tool.Info(
        name = "create_event",
        description = "Create a calendar event. Parameters: summary (string, mandatory), startTime (string, mandatory, yyyy-MM-dd HH:mm:ss), endTime (string, mandatory, yyyy-MM-dd HH:mm:ss), attendees (string/List, mandatory, names/userIds), description (string, optional), location (string, optional).",
        requiredComponents = {ComponentId.DINGTALK}
)
public class CreateEventTool implements Tool {
    private static final DingTalkService DING_TALK_SERVICE = DingTalkService.fromAppConfig();

    protected List<DingTalkDepartment> getAllDepartments() throws Exception {
        return DING_TALK_SERVICE.getAllDepartments(true, true);
    }
    
    protected String createCalendarEvent(String unionId, String summary, String description, String startTime, String endTime, String location, List<String> attendeeUserIds) throws Exception {
        return DING_TALK_SERVICE.createCalendarEvent(unionId, summary, description, startTime, endTime, attendeeUserIds, location);
    }

    protected String getUnionIdByUserId(String userId) throws Exception {
        return DING_TALK_SERVICE.getUnionIdByUserId(userId);
    }
    
    protected void sendTextMessageToEmployees(List<String> userIds, String content) throws Exception {
        DING_TALK_SERVICE.sendTextMessageToEmployees(userIds, content);
    }

    @Override
    public String execute(JSONObject params, ToolContext context, ToolMessenger messenger) {
        String senderId = context != null ? context.getUserId() : null;
        List<String> mentionedUserIds = messenger != null ? messenger.getMentionedUserIds() : Collections.emptyList();

        String summary = params.getString("summary");
        String startTimeStr = params.getString("startTime");
        String endTimeStr = params.getString("endTime");
        String description = params.getString("description");
        String location = params.getString("location");
        Object attendeesObj = params.get("attendees");

        List<String> notifyUsers = new ArrayList<>();
        if (senderId != null) notifyUsers.add(senderId);
        if (mentionedUserIds != null) notifyUsers.addAll(mentionedUserIds);

        // 1. Resolve Attendees
        List<String> attendeeUserIds = new ArrayList<>();
        List<String> notFoundNames = new ArrayList<>();

        if (attendeesObj != null) {
            List<String> inputNames = new ArrayList<>();
            if (attendeesObj instanceof com.alibaba.fastjson2.JSONArray) {
                for (Object o : (com.alibaba.fastjson2.JSONArray) attendeesObj) {
                    if (o != null) inputNames.add(String.valueOf(o));
                }
            } else if (attendeesObj instanceof Collection) {
                for (Object o : (Collection<?>) attendeesObj) {
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
                    List<DingTalkDepartment> departments = getAllDepartments();
                    Map<String, String> userMap = new HashMap<>();
                    Map<String, List<String>> deptMap = new HashMap<>();
                    
                    for (DingTalkDepartment dept : departments) {
                        if (dept.getName() != null) {
                            List<String> userIdsInDept = new ArrayList<>();
                            if (dept.getUserList() != null) {
                                for (DingTalkUser user : dept.getUserList()) {
                                    userMap.put(user.getName(), user.getUserid());
                                    userIdsInDept.add(user.getUserid());
                                }
                            }
                            // Store department name -> list of user IDs
                            deptMap.put(dept.getName(), userIdsInDept);
                        }
                    }
                    
                    for (String name : inputNames) {
                        if (userMap.containsKey(name)) {
                            String uid = userMap.get(name);
                            if (!attendeeUserIds.contains(uid)) {
                                attendeeUserIds.add(uid);
                            }
                        } else if (deptMap.containsKey(name)) {
                            // If name matches a department, add all users in that department
                            List<String> deptUsers = deptMap.get(name);
                            if (deptUsers != null) {
                                for (String uid : deptUsers) {
                                    if (!attendeeUserIds.contains(uid)) {
                                        attendeeUserIds.add(uid);
                                    }
                                }
                            }
                        } else if (userMap.containsValue(name)) { // Check if input is already an ID
                             if (!attendeeUserIds.contains(name)) {
                                attendeeUserIds.add(name);
                             }
                        } else {
                            notFoundNames.add(name);
                        }
                    }
                } catch (Exception e) {
                    AppLog.error(e);
                }
            }
        }
        
        if (mentionedUserIds != null) {
            for (String uid : mentionedUserIds) {
                if (!attendeeUserIds.contains(uid)) attendeeUserIds.add(uid);
            }
        }

        if (!notFoundNames.isEmpty()) {
            sendTextSafe(messenger, notifyUsers, "创建日程警告: 未找到以下参与人: " + String.join("，", notFoundNames) + "。");
        }

        if (attendeeUserIds.isEmpty()) {
            sendTextSafe(messenger, notifyUsers, "创建日程失败: 未指定有效的参与人。");
            return "Error: No attendees";
        }

        // 2. Validate Time
        if (startTimeStr == null || endTimeStr == null) {
            sendTextSafe(messenger, notifyUsers, "创建日程失败: 开始时间或结束时间缺失。");
            return "Error: Missing time";
        }

        // 3. Convert Time to ISO 8601 (yyyy-MM-dd'T'HH:mm:ss+08:00)
        String isoStartTime;
        String isoEndTime;
        try {
            DateTimeFormatter inputFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
            LocalDateTime localStart = LocalDateTime.parse(startTimeStr, inputFormatter);
            LocalDateTime localEnd = LocalDateTime.parse(endTimeStr, inputFormatter);
            
            ZonedDateTime zonedStart = localStart.atZone(ZoneId.of("Asia/Shanghai"));
            ZonedDateTime zonedEnd = localEnd.atZone(ZoneId.of("Asia/Shanghai"));
            
            isoStartTime = zonedStart.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
            isoEndTime = zonedEnd.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        } catch (Exception e) {
            sendTextSafe(messenger, notifyUsers, "创建日程失败: 时间格式错误 (" + e.getMessage() + ")");
            return "Error: Time format";
        }

        // 4. Create Event
        try {
            // Using senderId as the user to create event for
            String unionId = getUnionIdByUserId(senderId);
            if (unionId == null) {
                 throw new RuntimeException("无法获取操作人的 UnionId，无法创建日程。UserId: " + senderId);
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
                    AppLog.error(e);
                }
            }
            
            if (!failedConversionUsers.isEmpty()) {
                sendTextSafe(messenger, notifyUsers, "警告: 无法获取以下用户的 UnionId，将被忽略: " + String.join(", ", failedConversionUsers));
            }

            if (attendeeUnionIds.isEmpty() && !attendeeUserIds.isEmpty()) {
                 throw new RuntimeException("没有有效的参与人 (UnionId 获取失败)");
            }
            
            String eventId = createCalendarEvent(unionId, summary, description, isoStartTime, isoEndTime, location, attendeeUnionIds);
            String successMsg = "日程创建成功！标题: " + summary + "，时间: " + startTimeStr + " - " + endTimeStr + "，参与人数: " + attendeeUnionIds.size();
            sendTextSafe(messenger, notifyUsers, successMsg);

            AppLog.info("Event ID: " + eventId);
            return successMsg + "，参与人: " + String.join(",", attendeeUserIds) + "，EventID: " + eventId;
        } catch (Exception e) {
            AppLog.error(e);
            sendTextSafe(messenger, notifyUsers, "创建日程失败: " + e.getMessage());
            return "Error: " + e.getMessage();
        }
    }

    private void sendTextSafe(ToolMessenger messenger, List<String> notifyUsers, String content) {
        if (messenger != null) {
            try {
                messenger.sendText(content);
                return;
            } catch (Exception ignored) {
            }
        }
        if (notifyUsers == null || notifyUsers.isEmpty()) return;
        try {
            sendTextMessageToEmployees(notifyUsers, content);
        } catch (Exception ignored) {
        }
    }
}
