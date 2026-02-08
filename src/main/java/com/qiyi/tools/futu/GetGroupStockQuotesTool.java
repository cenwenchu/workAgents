package com.qiyi.tools.futu;

import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONArray;
import com.qiyi.component.ComponentId;
import com.qiyi.service.futu.FutuOpenD;
import com.qiyi.tools.Tool;
import com.qiyi.tools.ToolContext;
import com.qiyi.tools.ToolMessenger;
import com.futu.openapi.pb.QotGetUserSecurity;
import com.futu.openapi.pb.QotGetBasicQot;
import com.futu.openapi.pb.QotCommon;
import com.qiyi.util.AppLog;

import java.util.List;
import java.util.ArrayList;

/**
 * 获取指定自选股分组下所有股票的实时报价（富途）。
 *
 * <p>规划补参：在 LLM 未给出 groupName 或给错 key 时，尝试从 userText 中抽取并写回 parameters.groupName。</p>
 */
@Tool.Info(
        name = "get_group_stock_quotes",
        description = "功能：获取指定自选股分组下所有股票的实时报价。参数：groupName(必填 分组名称)。返回：该分组下所有股票的实时价格列表。",
        requiredComponents = {ComponentId.FUTU}
)
public class GetGroupStockQuotesTool implements Tool {
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
    public InterfaceDescription getInterfaceDescription() {
        JSONObject input = new JSONObject();
        input.put("type", "object");
        JSONObject properties = new JSONObject();
        JSONObject groupName = new JSONObject();
        groupName.put("type", "string");
        groupName.put("description", "分组名称");
        properties.put("groupName", groupName);
        input.put("properties", properties);
        JSONArray required = new JSONArray();
        required.add("groupName");
        input.put("required", required);

        JSONObject output = new JSONObject();
        output.put("type", "string");
        output.put("description", "该分组下所有股票的实时价格列表");

        JSONArray errors = new JSONArray();
        JSONObject invalidParams = new JSONObject();
        invalidParams.put("code", "INVALID_PARAMS");
        invalidParams.put("message", "参数缺失或格式错误");
        errors.add(invalidParams);
        JSONObject execError = new JSONObject();
        execError.put("code", "EXECUTION_ERROR");
        execError.put("message", "执行失败");
        errors.add(execError);

        return new InterfaceDescription(input, output, errors);
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
            
            // Step 1: Get User Security List
            QotGetUserSecurity.C2S c2s = QotGetUserSecurity.C2S.newBuilder()
                    .setGroupName(groupName)
                    .build();

            QotGetUserSecurity.Request req = QotGetUserSecurity.Request.newBuilder()
                    .setC2S(c2s)
                    .build();

            int serialNo = openD.getQotClient().getUserSecurity(req);
            QotGetUserSecurity.Response groupResp = openD.sendQotRequest(serialNo, QotGetUserSecurity.Response.class);
            
            if (groupResp.getRetType() != 0) {
                String errorMsg = "获取自选股列表失败: " + groupResp.getRetMsg();
                if (messenger != null) messenger.sendText(errorMsg);
                return errorMsg;
            }

            List<QotCommon.Security> securityList = new ArrayList<>();
            for (QotCommon.SecurityStaticInfo info : groupResp.getS2C().getStaticInfoListList()) {
                securityList.add(info.getBasic().getSecurity());
            }

            if (securityList.isEmpty()) {
                String msg = "分组[" + groupName + "]下无股票。";
                if (messenger != null) messenger.sendText(msg);
                return msg;
            }

            // Step 2: Ensure Subscription (Batch)
            boolean subscriptionSuccess = openD.ensureSubscription(securityList, QotCommon.SubType.SubType_Basic);
            if (!subscriptionSuccess) {
                 AppLog.info("Subscription warning: Some securities might not be subscribed.");
            }

            // Step 3: Get Basic Quotes (Batch)
            QotGetBasicQot.C2S quoteC2S = QotGetBasicQot.C2S.newBuilder()
                    .addAllSecurityList(securityList)
                    .build();
            
            QotGetBasicQot.Request quoteReq = QotGetBasicQot.Request.newBuilder()
                    .setC2S(quoteC2S)
                    .build();
            
            int quoteSerialNo = openD.getQotClient().getBasicQot(quoteReq);
            QotGetBasicQot.Response quoteResp = openD.sendQotRequest(quoteSerialNo, QotGetBasicQot.Response.class);
            
            if (quoteResp.getRetType() == 0) {
                 StringBuilder sb = new StringBuilder();
                 sb.append("分组[").append(groupName).append("] 实时报价：\n");
                 
                 List<com.qiyi.service.futu.domain.BasicQot> qotList = com.qiyi.service.futu.util.ProtoToDomainConverter.convertBasicQotList(quoteResp.getS2C().getBasicQotListList());
                 
                 for (com.qiyi.service.futu.domain.BasicQot qot : qotList) {
                     sb.append(qot.getSecurity().getCode())
                       .append(" | 现价: ").append(qot.getCurPrice())
                       .append(" | 涨跌幅: ").append(String.format("%.2f%%", qot.getCurPrice() > 0 ? (qot.getCurPrice() - qot.getLastClosePrice()) / qot.getLastClosePrice() * 100 : 0))
                       .append("\n");
                 }
                 
                 String result = sb.toString();
                 if (messenger != null) messenger.sendText(result);
                 return result;
            } else {
                 String errorMsg = "获取批量报价失败: " + quoteResp.getRetMsg();
                 if (messenger != null) messenger.sendText(errorMsg);
                 return errorMsg;
            }

        } catch (Exception e) {
            AppLog.error(e);
            String exceptionMsg = "Exception: " + e.getMessage();
            try {
                if (messenger != null) messenger.sendText("获取分组报价发生异常: " + e.getMessage());
            } catch (Exception ex) {
                AppLog.error(ex);
            }
            return exceptionMsg;
        }
    }
}
