package com.qiyi.tools.dingtalk;

import com.alibaba.fastjson2.JSONObject;
import com.qiyi.service.dingtalk.DingTalkDepartment;
import com.qiyi.service.dingtalk.DingTalkUser;
import com.qiyi.service.dingtalk.DingTalkService;
import com.qiyi.component.ComponentId;
import com.qiyi.tools.Tool;
import com.qiyi.tools.ToolContext;
import com.qiyi.tools.ToolMessenger;
import com.qiyi.util.AppLog;

import java.util.ArrayList;
import java.util.List;

/**
 * 钉钉用户搜索工具：按姓名关键词检索并返回用户 UID。
 *
 * <p>该工具仅用于“查 UID/用户ID”的场景；发送消息优先用 send_message 由工具内部解析收件人。</p>
 */
@Tool.Info(
        name = "SearchDingTalkUserTool",
        description = "通过用户名的模糊搜索来查询钉钉用户的Uid，输入参数为 name (关键词)",
        requiredComponents = {ComponentId.DINGTALK}
)
public class SearchDingTalkUserTool implements Tool {
    private static final DingTalkService DING_TALK_SERVICE = DingTalkService.fromAppConfig();

    @Override
    public void enrichPlannedTask(String userText, JSONObject plannedTask) {
        if (plannedTask == null) return;
        JSONObject params = plannedTask.getJSONObject("parameters");
        if (params == null) {
            params = new JSONObject();
            plannedTask.put("parameters", params);
        }
        String name = params.getString("name");
        if (name == null || name.trim().isEmpty()) {
            String extracted = tryExtractUidLookupName(userText);
            if (extracted != null) {
                params.put("name", extracted);
            }
        }
    }

    @Override
    public String execute(JSONObject params, ToolContext context, ToolMessenger messenger) {
        String name = params.getString("name");
        if (name == null || name.trim().isEmpty()) {
            return "请输入要搜索的用户名关键词 (name)";
        }
        
        try {
            // 获取所有部门和用户，使用缓存
            List<DingTalkDepartment> allDepartments = getAllDepartments();
            List<String> results = new ArrayList<>();
            int matchCount = 0;

            for (DingTalkDepartment dept : allDepartments) {
                if (dept.getUserList() != null) {
                    for (DingTalkUser user : dept.getUserList()) {
                        if (user.getName() != null && user.getName().contains(name)) {
                            results.add(String.format("姓名: %s, UserId: %s, 部门: %s", user.getName(), user.getUserid(), dept.getName()));
                            matchCount++;
                        }
                    }
                }
            }
            
            if (results.isEmpty()) {
                String msg = "未找到包含 '" + name + "' 的用户。";
                if (messenger != null) messenger.sendText(msg);
                return msg;
            } else {
                StringBuilder sb = new StringBuilder();
                sb.append("找到 ").append(matchCount).append(" 位用户:\n");
                for (String res : results) {
                    sb.append(res).append("\n");
                }
                String output = sb.toString();
                if (messenger != null) messenger.sendText(output);
                return output;
            }
            
        } catch (Exception e) {
            AppLog.error(e);
            return "搜索用户失败: " + e.getMessage();
        }
    }

    protected List<DingTalkDepartment> getAllDepartments() throws Exception {
        return DING_TALK_SERVICE.getAllDepartments(true, true);
    }

    private static String tryExtractUidLookupName(String text) {
        if (text == null) return null;
        String v = text.trim();
        if (v.isEmpty()) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("查一下\\s*([^，。,\\s]{1,16})\\s*的\\s*(?:uid|Uid|userId|UserId|用户id|用户ID)")
                .matcher(v);
        if (m.find()) {
            String name = m.group(1);
            if (name == null) return null;
            String n = name.trim();
            return n.isEmpty() ? null : n;
        }
        return null;
    }
}
