package com.qiyi.service.dingtalk.stream;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.dingtalk.open.app.api.callback.OpenDingTalkCallbackListener;
import com.qiyi.service.dingtalk.DingTalkDepartment;
import com.qiyi.service.dingtalk.DingTalkUser;
import com.qiyi.tools.TaskProcessor;
import com.qiyi.tools.context.DingTalkToolContext;
import com.qiyi.util.DingTalkUtil;
import com.qiyi.util.AppLog;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 钉钉 Stream 回调入口：接收消息并转交给 TaskProcessor。
 *
 * <p>该类只做“消息接入 + 上下文构建 + 异步分发”，具体工具选择与执行由 {@link TaskProcessor} 负责。</p>
 */
public class RobotMsgCallbackConsumer implements OpenDingTalkCallbackListener<JSONObject, JSONObject> {
    @Override
    public JSONObject execute(JSONObject request) {
        try {
            JSONObject text = request.getJSONObject("text");
            String senderStaffId = request.getString("senderStaffId");

            if (text != null) {
                String msg = text.getString("content").trim();

                List<String> atUserIds = parseAtUserIds(msg);

                AppLog.info("[dingtalk] message received, senderStaffId=" + senderStaffId + ", atCount=" + (atUserIds == null ? 0 : atUserIds.size()) + ", content=" + safeOneLine(msg));
                java.util.concurrent.CompletableFuture.runAsync(() -> {
                    DingTalkToolContext ctx = new DingTalkToolContext(senderStaffId, atUserIds);
                    TaskProcessor.process(msg, ctx, ctx);
                });
            } else {
                AppLog.warn("[dingtalk] message received but missing text payload, senderStaffId=" + senderStaffId + ", raw=" + safeOneLine(JSON.toJSONString(request)));
            }
        } catch (Exception e) {
            AppLog.error("[dingtalk] receive message error", e);
        }
        return new JSONObject();
    }

    private static List<String> parseAtUserIds(String msg) {
        List<String> atUserIds = new ArrayList<>();
        try {
            if (msg == null || msg.isEmpty() || !msg.contains("@")) {
                return atUserIds;
            }

            List<DingTalkDepartment> departments = DingTalkUtil.getAllDepartments(true, true);
            List<DingTalkUser> allUsers = new ArrayList<>();
            for (DingTalkDepartment dept : departments) {
                if (dept.getUserList() != null) {
                    allUsers.addAll(dept.getUserList());
                }
            }

            Map<String, String> userMap = new HashMap<>();
            for (DingTalkUser user : allUsers) {
                userMap.put(user.getName(), user.getUserid());
            }

            if (userMap.isEmpty()) {
                return atUserIds;
            }

            Pattern p = Pattern.compile("@([\\p{IsHan}A-Za-z0-9._-]{1,32})");
            Matcher m = p.matcher(msg);
            while (m.find()) {
                String raw = m.group(1);
                if (raw == null) continue;
                String name = raw.trim();
                if (name.isEmpty()) continue;

                String exact = userMap.get(name);
                if (exact != null) {
                    if (!atUserIds.contains(exact)) atUserIds.add(exact);
                    continue;
                }

                Map<String, String> partialMatches = new HashMap<>();
                for (Map.Entry<String, String> entry : userMap.entrySet()) {
                    String fullName = entry.getKey();
                    if (fullName != null && fullName.contains(name)) {
                        partialMatches.put(entry.getValue(), fullName);
                    }
                }

                if (partialMatches.size() == 1) {
                    String userId = partialMatches.keySet().iterator().next();
                    if (!atUserIds.contains(userId)) atUserIds.add(userId);
                } else if (partialMatches.size() > 1) {
                    AppLog.info("[dingtalk] @name ambiguous, name=" + name + ", matches=" + partialMatches.values());
                } else {
                    AppLog.info("[dingtalk] @name not found, name=" + name);
                }
            }
        } catch (Exception e) {
            AppLog.error("[dingtalk] parse @ users failed", e);
        }
        return atUserIds;
    }

    private static String safeOneLine(String s) {
        if (s == null) return "null";
        String v = s.replace("\n", "\\n").replace("\r", "\\r").trim();
        if (v.length() > 500) return v.substring(0, 500) + "...";
        return v;
    }
}
