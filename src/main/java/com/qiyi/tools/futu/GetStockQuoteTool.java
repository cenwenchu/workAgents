package com.qiyi.tools.futu;

import com.alibaba.fastjson2.JSONObject;
import com.qiyi.component.ComponentId;
import com.qiyi.service.futu.FutuOpenD;
import com.qiyi.tools.Tool;
import com.qiyi.tools.ToolContext;
import com.qiyi.tools.ToolMessenger;
import com.futu.openapi.pb.QotGetBasicQot;
import com.futu.openapi.pb.QotCommon;
import com.qiyi.util.AppLog;

import java.util.List;
import java.util.ArrayList;

/**
 * 获取单支证券实时报价（富途 BasicQot）。
 *
 * <p>规划补参：当 code 缺失时，从 userText 中提取如 HK.00700 / US.AAPL 等证券代码。</p>
 */
public class GetStockQuoteTool implements Tool {
    @Override
    public String getName() {
        return "get_stock_quote";
    }

    @Override
    public String getDescription() {
        return "功能：获取指定证券的基础实时报价（BasicQot）。参数：code（字符串，必填，格式如：HK.00700/US.AAPL/SH.600519/SZ.000001）。返回：包含当前价、涨跌幅等基础字段的响应字符串。";
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
        if (code == null) {
            String msg = "Error: code is required";
            try {
                if (messenger != null) messenger.sendText(msg);
            } catch (Exception e) {
                AppLog.error(e);
            }
            return msg;
        }
        
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

            // 1. Ensure Subscription
            boolean subscriptionSuccess = openD.ensureSubscription(security, QotCommon.SubType.SubType_Basic);
            if (!subscriptionSuccess) {
                return "Subscription Failed";
            }

            // 2. Get Basic Quote
            QotGetBasicQot.C2S c2s = QotGetBasicQot.C2S.newBuilder()
                    .addSecurityList(security)
                    .build();
            
            QotGetBasicQot.Request req = QotGetBasicQot.Request.newBuilder()
                    .setC2S(c2s)
                    .build();
            
            int serialNo = openD.getQotClient().getBasicQot(req);
            
            QotGetBasicQot.Response response = openD.sendQotRequest(serialNo, QotGetBasicQot.Response.class);
            
            if (response.getRetType() == 0) {
                 // Format the output
                 List<com.qiyi.service.futu.domain.BasicQot> qotList = com.qiyi.service.futu.util.ProtoToDomainConverter.convertBasicQotList(response.getS2C().getBasicQotListList());
                 StringBuilder sb = new StringBuilder();
                 for (com.qiyi.service.futu.domain.BasicQot qot : qotList) {
                     sb.append("股票代码: ").append(qot.getSecurity().getCode()).append("\n");
                     sb.append("当前价: ").append(qot.getCurPrice()).append("\n");
                     sb.append("开盘价: ").append(qot.getOpenPrice()).append("\n");
                     sb.append("最高价: ").append(qot.getHighPrice()).append("\n");
                     sb.append("最低价: ").append(qot.getLowPrice()).append("\n");
                     sb.append("昨收价: ").append(qot.getLastClosePrice()).append("\n");
                     sb.append("成交量: ").append(qot.getVolume()).append("\n");
                     sb.append("成交额: ").append(qot.getTurnover()).append("\n");
                     sb.append("换手率: ").append(qot.getTurnoverRate()).append("%\n");
                     sb.append("振幅: ").append(qot.getAmplitude()).append("%\n");
                     sb.append("更新时间: ").append(qot.getUpdateTime()).append("\n");
                     sb.append("------------------------\n");
                 }
                 if (sb.length() == 0) {
                     String msg = "未查询到相关股票报价信息。";
                     try {
                         if (messenger != null) messenger.sendText(msg);
                     } catch (Exception e) {
                         AppLog.error(e);
                     }
                     return msg;
                 }
                 String result = sb.toString();
                 try {
                     if (messenger != null) messenger.sendText("股票报价查询结果:\n" + result);
                 } catch (Exception e) {
                     AppLog.error(e);
                 }
                 return result;
            } else {
                 String errorMsg = "Error: " + response.getRetMsg();
                 try {
                     if (messenger != null) messenger.sendText("查询股票报价失败: " + response.getRetMsg());
                 } catch (Exception e) {
                     AppLog.error(e);
                 }
                 return errorMsg;
            }
            
        } catch (Exception e) {
            AppLog.error(e);
            String exceptionMsg = "Exception: " + e.getMessage();
            try {
                if (messenger != null) messenger.sendText("查询股票报价发生异常: " + e.getMessage());
            } catch (Exception ex) {
                AppLog.error(ex);
            }
            return exceptionMsg;
        }
    }
}
