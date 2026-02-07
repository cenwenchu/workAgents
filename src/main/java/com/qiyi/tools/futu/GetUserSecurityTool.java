package com.qiyi.tools.futu;

import com.alibaba.fastjson2.JSONObject;
import com.qiyi.component.ComponentId;
import com.qiyi.service.futu.FutuOpenD;
import com.qiyi.tools.Tool;
import com.qiyi.tools.ToolContext;
import com.qiyi.tools.ToolMessenger;
import com.futu.openapi.pb.QotGetUserSecurity;
import com.futu.openapi.pb.QotCommon;
import com.qiyi.util.AppLog;

import java.util.List;
import java.util.ArrayList;

/**
 * 获取指定自选股分组下的股票列表（富途）。
 *
 * <p>规划补参：在 groupName 缺失或 key 被污染时，尝试从 userText 中抽取并写回 parameters.groupName。</p>
 */
public class GetUserSecurityTool implements Tool {
    @Override
    public String getName() {
        return "get_user_security";
    }

    @Override
    public String getDescription() {
        return "功能：获取指定分组下的自选股列表。参数：groupName（字符串，必填，分组名称）。返回：股票代码、名称等信息列表。";
    }

    @Override
    public void enrichPlannedTask(String userText, JSONObject plannedTask) {
        if (plannedTask == null) return;
        JSONObject params = plannedTask.getJSONObject("parameters");
        if (params == null) {
            params = new JSONObject();
            plannedTask.put("parameters", params);
        }

        if (!params.containsKey("groupName")) {
            String moved = tryMoveStringParam(params, "groupName");
            if (moved != null && !moved.trim().isEmpty()) {
                params.put("groupName", moved.trim());
            }
        }

        String groupName = params.getString("groupName");
        if (groupName == null || groupName.trim().isEmpty()) {
            String extracted = tryExtractGroupName(userText);
            if (extracted != null) {
                params.put("groupName", extracted);
            }
        }

        removeNoiseParamKeys(params);
    }

    @Override
    public List<ComponentId> requiredComponents() {
        return List.of(ComponentId.FUTU);
    }

    protected FutuOpenD getFutuOpenD() {
        return FutuOpenD.getInstance();
    }

    private static String tryExtractGroupName(String text) {
        if (text == null) return null;
        String v = text.trim();
        if (v.isEmpty()) return null;

        java.util.regex.Matcher m1 = java.util.regex.Pattern
                .compile("我的\\s*([^，。,\\s]{1,32})\\s*(?:里面|里的|内|中)\\s*的\\s*股票")
                .matcher(v);
        if (m1.find()) {
            String g = m1.group(1);
            if (g != null && !g.trim().isEmpty()) return g.trim();
        }

        java.util.regex.Matcher m2 = java.util.regex.Pattern
                .compile("分组\\s*[:：]?\\s*([^，。,\\s]{1,32})")
                .matcher(v);
        if (m2.find()) {
            String g = m2.group(1);
            if (g != null && !g.trim().isEmpty()) return g.trim();
        }

        java.util.regex.Matcher m3 = java.util.regex.Pattern
                .compile("自选股\\s*([^，。,\\s]{1,32})\\s*(?:分组|组)")
                .matcher(v);
        if (m3.find()) {
            String g = m3.group(1);
            if (g != null && !g.trim().isEmpty()) return g.trim();
        }

        return null;
    }

    private static String tryMoveStringParam(JSONObject params, String canonicalKey) {
        if (params == null || params.isEmpty() || canonicalKey == null || canonicalKey.trim().isEmpty()) return null;
        if (params.containsKey(canonicalKey)) return params.getString(canonicalKey);

        String bestKey = null;
        for (String k : params.keySet()) {
            if (k == null) continue;
            if (k.equals(canonicalKey)) continue;
            if (k.contains(canonicalKey) || k.toLowerCase().contains(canonicalKey.toLowerCase()) || k.contains("分组")) {
                bestKey = k;
                break;
            }
        }
        if (bestKey == null) return null;
        Object val = params.get(bestKey);
        params.remove(bestKey);
        return val == null ? null : String.valueOf(val);
    }

    private static void removeNoiseParamKeys(JSONObject params) {
        if (params == null || params.isEmpty()) return;
        java.util.List<String> toRemove = new java.util.ArrayList<>();
        for (String k : params.keySet()) {
            if (k == null) continue;
            String kk = k.trim();
            if (kk.isEmpty()) continue;
            if ("必填".equals(kk) || "必选".equals(kk) || kk.startsWith("返回") || kk.contains("返回：") || kk.contains("返回:")) {
                toRemove.add(k);
            }
        }
        for (String k : toRemove) {
            params.remove(k);
        }
    }

    @Override
    public String execute(JSONObject params, ToolContext context, ToolMessenger messenger) {
        String groupName = params.getString("groupName");
        if (groupName == null || groupName.isEmpty()) {
            return "Error: groupName is required";
        }

        try {
            FutuOpenD openD = getFutuOpenD();
            
            QotGetUserSecurity.C2S c2s = QotGetUserSecurity.C2S.newBuilder()
                    .setGroupName(groupName)
                    .build();

            QotGetUserSecurity.Request req = QotGetUserSecurity.Request.newBuilder()
                    .setC2S(c2s)
                    .build();

            int serialNo = openD.getQotClient().getUserSecurity(req);
            
            QotGetUserSecurity.Response response = openD.sendQotRequest(serialNo, QotGetUserSecurity.Response.class);
            
            if (response.getRetType() == 0) {
                StringBuilder sb = new StringBuilder();
                sb.append("分组[").append(groupName).append("]的自选股列表：\n");
                
                for (QotCommon.SecurityStaticInfo info : response.getS2C().getStaticInfoListList()) {
                    QotCommon.SecurityStaticBasic basic = info.getBasic();
                    sb.append(basic.getSecurity().getCode())
                      .append(" ").append(basic.getName())
                      .append(" (类型:").append(basic.getSecType()).append(")\n");
                }
                
                if (response.getS2C().getStaticInfoListCount() == 0) {
                    sb.append("该分组下无股票。");
                }
                
                String result = sb.toString();
                try {
                    if (messenger != null) messenger.sendText(result);
                } catch (Exception e) {
                    AppLog.error(e);
                }
                return result;
            } else {
                String errorMsg = "获取自选股列表失败: " + response.getRetMsg();
                try {
                    if (messenger != null) messenger.sendText(errorMsg);
                } catch (Exception e) {
                    AppLog.error(e);
                }
                return errorMsg;
            }
            
        } catch (Exception e) {
            AppLog.error(e);
            String exceptionMsg = "Exception: " + e.getMessage();
            try {
                if (messenger != null) messenger.sendText("获取自选股列表发生异常: " + e.getMessage());
            } catch (Exception ex) {
                AppLog.error(ex);
            }
            return exceptionMsg;
        }
    }
}
