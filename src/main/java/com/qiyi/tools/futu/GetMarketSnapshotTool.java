package com.qiyi.tools.futu;

import com.alibaba.fastjson2.JSONObject;
import com.qiyi.futu.FutuOpenD;
import com.qiyi.tools.Tool;
import com.qiyi.tools.ToolContext;
import com.futu.openapi.pb.QotGetSecuritySnapshot;
import com.futu.openapi.pb.QotCommon;

import java.util.List;
import java.util.ArrayList;

public class GetMarketSnapshotTool implements Tool {
    @Override
    public String getName() {
        return "get_market_snapshot";
    }

    @Override
    public String getDescription() {
        return "功能：获取指定证券的市场快照（SecuritySnapshot）。参数：code（字符串，必填，格式如：HK.00700/US.AAPL/SH.600519/SZ.000001）。返回：包含最新价、昨收、最高、最低、成交量等快照信息的响应字符串。";
    }

    protected FutuOpenD getFutuOpenD() {
        return FutuOpenD.getInstance();
    }

    @Override
    public String execute(JSONObject params, ToolContext context) {
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
                         context.sendText(msg);
                     } catch (Exception e) {
                         e.printStackTrace();
                     }
                     return msg;
                 }
                 String result = sb.toString();
                 try {
                     context.sendText("市场快照查询结果:\n" + result);
                 } catch (Exception e) {
                     e.printStackTrace();
                 }
                 return result;
            } else {
                 String errorMsg = "Error: " + response.getRetMsg();
                 try {
                     context.sendText("查询市场快照失败: " + response.getRetMsg());
                 } catch (Exception e) {
                     e.printStackTrace();
                 }
                 return errorMsg;
            }
            
        } catch (Error e) { // Catch UnresolvedCompilationProblem if runtime
             return "Error: Method not found (Compilation Error)";
        } catch (Exception e) {
            e.printStackTrace();
            String exceptionMsg = "Exception: " + e.getMessage();
            try {
                context.sendText("查询市场快照发生异常: " + e.getMessage());
            } catch (Exception ex) {
                ex.printStackTrace();
            }
            return exceptionMsg;
        }
    }
}
