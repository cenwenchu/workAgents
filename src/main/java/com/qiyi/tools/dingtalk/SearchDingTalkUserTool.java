package com.qiyi.tools.dingtalk;

import com.alibaba.fastjson2.JSONObject;
import com.qiyi.dingtalk.DingTalkDepartment;
import com.qiyi.dingtalk.DingTalkUser;
import com.qiyi.tools.Tool;
import com.qiyi.tools.ToolContext;
import com.qiyi.util.DingTalkUtil;

import java.util.ArrayList;
import java.util.List;

public class SearchDingTalkUserTool implements Tool {

    @Override
    public String getName() {
        return "SearchDingTalkUserTool";
    }

    @Override
    public String getDescription() {
        return "通过用户名的模糊搜索来查询钉钉用户的Uid，输入参数为 name (关键词)";
    }

    @Override
    public String execute(JSONObject params, ToolContext context) {
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
                context.sendText(msg);
                return msg;
            } else {
                StringBuilder sb = new StringBuilder();
                sb.append("找到 ").append(matchCount).append(" 位用户:\n");
                for (String res : results) {
                    sb.append(res).append("\n");
                }
                String output = sb.toString();
                context.sendText(output);
                return output;
            }
            
        } catch (Exception e) {
            e.printStackTrace();
            return "搜索用户失败: " + e.getMessage();
        }
    }

    protected List<DingTalkDepartment> getAllDepartments() throws Exception {
        return DingTalkUtil.getAllDepartments(true, true);
    }
}
