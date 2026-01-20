package com.qiyi.tools.dingtalk;

import com.alibaba.fastjson2.JSONObject;
import com.qiyi.util.DingTalkUtil;
import com.qiyi.dingtalk.DingTalkDepartment;
import com.qiyi.dingtalk.DingTalkUser;
import com.qiyi.tools.Tool;
import com.qiyi.tools.ToolContext;

import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;
import java.util.Collection;
import java.util.Collections;

public class SendMessageTool implements Tool {
    @Override
    public String getName() {
        return "send_message";
    }

    @Override
    public String getDescription() {
        return "Send direct DingTalk text message to specific users. Parameters: content (string, mandatory). Choose ONE of: departments (string/List, names or IDs) OR names (string/List). If both provided, departments take precedence.";
    }

    protected List<DingTalkDepartment> getAllDepartments() throws Exception {
        return DingTalkUtil.getAllDepartments(true, true);
    }

    protected void sendTextMessageToEmployees(List<String> userIds, String content) throws Exception {
        DingTalkUtil.sendTextMessageToEmployees(userIds, content);
    }

    @Override
    public String execute(JSONObject params, ToolContext context) {
        String senderId = context.getSenderId();
        List<String> atUserIds = context.getAtUserIds();
        
        // Use a context that only notifies the sender to match original behavior
        ToolContext senderContext = context.withAtUserIds(Collections.emptyList());

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
                } else if (depObj instanceof Collection) {
                    for (Object o : (Collection<?>) depObj) {
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
                        List<DingTalkDepartment> departments = getAllDepartments();
                        Map<String, DingTalkDepartment> deptById = new HashMap<>();
                        Map<String, DingTalkDepartment> deptByName = new HashMap<>();
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
                } else if (ids instanceof Collection) {
                    for (Object o : (Collection<?>) ids) {
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
                } else if (namesObj instanceof Collection) {
                    for (Object o : (Collection<?>) namesObj) {
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
                        List<DingTalkDepartment> departments = getAllDepartments();
                        Map<String, String> userMap = new HashMap<>();
                        for (DingTalkDepartment dept : departments) {
                            if (dept.getUserList() != null) {
                                for (DingTalkUser u : dept.getUserList()) {
                                    userMap.put(u.getName(), u.getUserid());
                                }
                            }
                        }
                        for (String name : nameList) {
                            String uid = userMap.get(name);
                            if (uid != null) {
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
        
        // List<String> notifyUsers = new ArrayList<>();
        // if (senderId != null) notifyUsers.add(senderId);
        
        if (content == null || content.trim().isEmpty()) {
            try {
                senderContext.sendText("未提供消息内容，未执行发送。");
            } catch (Exception e) {
                e.printStackTrace();
            }
            return "Error: Empty content";
        }

        if (!notFoundDepartments.isEmpty()) {
            try {
                senderContext.sendText("未找到以下部门: " + String.join("，", notFoundDepartments) + "。请确认部门名称或ID是否正确。");
            } catch (Exception e) {
                e.printStackTrace();
            }
            if (recipients.isEmpty()) {
                return "Error: Dept not found";
            }
        }

        if (!notFoundNames.isEmpty()) {
            try {
                senderContext.sendText("未找到以下用户: " + String.join("，", notFoundNames) + "。请确认姓名是否正确。");
            } catch (Exception e) {
                e.printStackTrace();
            }
            if (recipients.isEmpty()) {
                return "Error: Name not found";
            }
        }

        if (recipients.isEmpty()) {
            try {
                senderContext.sendText("未指定有效的接收人（部门或用户），未执行发送。");
            } catch (Exception e) {
                e.printStackTrace();
            }
            return "Error: No recipients";
        }
        
        try {
            String senderName = null;
            try {
                List<DingTalkDepartment> departments = getAllDepartments();
                Map<String, String> idNameMap = new HashMap<>();
                for (DingTalkDepartment dept : departments) {
                    if (dept.getUserList() != null) {
                        for (DingTalkUser user : dept.getUserList()) {
                            idNameMap.put(user.getUserid(), user.getName());
                        }
                    }
                }
                if (senderId != null) {
                    senderName = idNameMap.get(senderId);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            String finalContent = (senderName != null && !senderName.trim().isEmpty())
                    ? ("【消息发起人：" + senderName + "】" + content)
                    : ("【消息发起人：" + (senderId != null ? senderId : "未知") + "】" + content);
            sendTextMessageToEmployees(recipients, finalContent);
            senderContext.sendText("已向 " + recipients.size() + " 位用户发送消息");
            return "Message Sent to " + recipients.size() + " users";
        } catch (Exception e) {
            e.printStackTrace();
            try {
                senderContext.sendText("发送消息失败: " + e.getMessage());
            } catch (Exception ex) {
                ex.printStackTrace();
            }
            return "Error: " + e.getMessage();
        }
    }
}
