package com.qiyi.tools.futu;

import com.alibaba.fastjson2.JSONObject;
import com.qiyi.component.ComponentId;
import com.qiyi.service.futu.FutuOpenD;
import com.qiyi.tools.Tool;
import com.qiyi.tools.ToolContext;
import com.qiyi.tools.ToolMessenger;
import com.futu.openapi.pb.QotGetSecuritySnapshot;
import com.futu.openapi.pb.QotCommon;
import com.qiyi.util.AppLog;

import java.util.List;
import java.util.ArrayList;

/**
 * 获取证券市场快照（富途 SecuritySnapshot）。
 *
 * <p>规划补参：当 code 缺失时，从 userText 中提取如 HK.00700 / US.AAPL 等证券代码。</p>
 */
public class GetMarketSnapshotTool implements Tool {
    @Override
    public String getName() {
        return "get_market_snapshot";
    }

    @Override
    public String getDescription() {
        return "功能：获取指定证券的市场快照（SecuritySnapshot）。参数：code（字符串，必填，格式如：HK.00700/US.AAPL/SH.600519/SZ.000001）。返回：包含最新价、昨收、最高、最低、成交量等快照信息的响应字符串。";
    }

    @Override
    public void enrichPlannedTask(String userText, JSONObject plannedTask) {
        if (plannedTask == null) return;
        JSONObject params = plannedTask.getJSONObject("parameters");
        if (params == null) {
            params = new JSONObject();
            plannedTask.put("parameters", params);
        }
        String code = params.getString("code");
        if (code == null || code.trim().isEmpty()) {
            String extracted = tryExtractStockCode(userText);
            if (extracted != null) {
                params.put("code", extracted);
            }
        }
    }

    @Override
    public List<ComponentId> requiredComponents() {
        return List.of(ComponentId.FUTU);
    }

    protected FutuOpenD getFutuOpenD() {
        return FutuOpenD.getInstance();
    }

    private static String tryExtractStockCode(String text) {
        if (text == null) return null;
        String v = text.trim();
        if (v.isEmpty()) return null;

        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\\b(HK|US|SH|SZ)\\.([A-Za-z0-9]{1,10})\\b", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(v);
        if (m.find()) {
            return m.group(1).toUpperCase() + "." + m.group(2).toUpperCase();
        }

        if (v.contains("腾讯")) return "HK.00700";
        if (v.contains("茅台") || v.contains("贵州茅台")) return "SH.600519";
        if (v.contains("苹果")) return "US.AAPL";
        if (v.contains("特斯拉")) return "US.TSLA";

        return null;
    }

    @Override
    public String execute(JSONObject params, ToolContext context, ToolMessenger messenger) {
        String code = params.getString("code");
        if (code == null) return "Error: code is required";
        
        try {
            FutuOpenD openD = getFutuOpenD();
            
            int marketVal = QotCommon.QotMarket.QotMarket_HK_Security_VALUE;
            String stockCode = code;
            
            if (code.contains(".")) {
                String[] parts = code.split("\\.");
                if (parts.length >= 2) {
                    String mktStr = parts[0].toUpperCase();
                    stockCode = parts[1];
                    if ("HK".equals(mktStr)) marketVal = QotCommon.QotMarket.QotMarket_HK_Security_VALUE;
                    else if ("US".equals(mktStr)) marketVal = QotCommon.QotMarket.QotMarket_US_Security_VALUE;
                    else if ("SH".equals(mktStr)) marketVal = QotCommon.QotMarket.QotMarket_CNSH_Security_VALUE;
                    else if ("SZ".equals(mktStr)) marketVal = QotCommon.QotMarket.QotMarket_CNSZ_Security_VALUE;
                }
            }

            QotCommon.Security security = QotCommon.Security.newBuilder()
                    .setMarket(marketVal)
                    .setCode(stockCode)
                    .build();

            QotGetSecuritySnapshot.C2S c2s = QotGetSecuritySnapshot.C2S.newBuilder()
                    .addSecurityList(security)
                    .build();
            
            QotGetSecuritySnapshot.Request req = QotGetSecuritySnapshot.Request.newBuilder()
                    .setC2S(c2s)
                    .build();
            
            // Try qotGetMarketSnapshot if qotGetSecuritySnapshot doesn't exist
            // Note: The method name in SDK v9.6 is getSecuritySnapshot
            int serialNo = openD.getQotClient().getSecuritySnapshot(req);
            
            QotGetSecuritySnapshot.Response response = openD.sendQotRequest(serialNo, QotGetSecuritySnapshot.Response.class);
            
            if (response.getRetType() == 0) {
                 // Format the output
                 StringBuilder sb = new StringBuilder();
                 for (QotGetSecuritySnapshot.Snapshot snapshot : response.getS2C().getSnapshotListList()) {
                     QotGetSecuritySnapshot.SnapshotBasicData basic = snapshot.getBasic();
                     sb.append("股票代码: ").append(basic.getSecurity().getCode()).append("\n");
                     sb.append("当前价: ").append(basic.getCurPrice()).append("\n");
                     sb.append("开盘价: ").append(basic.getOpenPrice()).append("\n");
                     sb.append("最高价: ").append(basic.getHighPrice()).append("\n");
                     sb.append("最低价: ").append(basic.getLowPrice()).append("\n");
                     sb.append("昨收价: ").append(basic.getLastClosePrice()).append("\n");
                     sb.append("成交量: ").append(basic.getVolume()).append("\n");
                     sb.append("成交额: ").append(basic.getTurnover()).append("\n");
                     sb.append("换手率: ").append(basic.getTurnoverRate()).append("%\n");
                     sb.append("振幅: ").append(basic.getAmplitude()).append("%\n");
                     sb.append("委比: ").append(basic.getBidAskRatio()).append("%\n");
                     sb.append("量比: ").append(basic.getVolumeRatio()).append("\n");
                     sb.append("更新时间: ").append(basic.getUpdateTime()).append("\n");
                     sb.append("------------------------\n");
                 }
                 if (sb.length() == 0) {
                     String msg = "未查询到市场快照信息。";
                     try {
                         if (messenger != null) messenger.sendText(msg);
                     } catch (Exception e) {
                         AppLog.error(e);
                     }
                     return msg;
                 }
                 String result = sb.toString();
                 try {
                     if (messenger != null) messenger.sendText("市场快照查询结果:\n" + result);
                 } catch (Exception e) {
                     AppLog.error(e);
                 }
                 return result;
            } else {
                 String errorMsg = "Error: " + response.getRetMsg();
                 try {
                     if (messenger != null) messenger.sendText("查询市场快照失败: " + response.getRetMsg());
                 } catch (Exception e) {
                     AppLog.error(e);
                 }
                 return errorMsg;
            }
            
        } catch (Error e) { // Catch UnresolvedCompilationProblem if runtime
             return "Error: Method not found (Compilation Error)";
        } catch (Exception e) {
            AppLog.error(e);
            String exceptionMsg = "Exception: " + e.getMessage();
            try {
                if (messenger != null) messenger.sendText("查询市场快照发生异常: " + e.getMessage());
            } catch (Exception ex) {
                AppLog.error(ex);
            }
            return exceptionMsg;
        }
    }
}
