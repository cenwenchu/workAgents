package com.qiyi.tools.futu;

import com.alibaba.fastjson2.JSONObject;
import com.qiyi.futu.FutuOpenD;
import com.qiyi.tools.Tool;
import com.qiyi.tools.ToolContext;
import com.futu.openapi.pb.QotGetKL;
import com.futu.openapi.pb.QotCommon;

import java.util.List;
import java.util.ArrayList;

public class GetCurKlineTool implements Tool {
    @Override
    public String getName() {
        return "get_cur_kline";
    }

    @Override
    public String getDescription() {
        return "功能：获取指定证券的最新 K 线数据。参数：code（字符串，必填，格式如：HK.00700/US.AAPL/SH.600519/SZ.000001）；klType（整数，选填，K线类型，默认日线。常用值：1=1分钟，2=日线，3=周线，4=月线）；reqNum（整数，选填，请求数量，默认10）。返回：包含所请求数量的K线数据（时间、开高低收、成交量等）的响应字符串。";
    }

    protected FutuOpenD getFutuOpenD() {
        return FutuOpenD.getInstance();
    }

    @Override
    public String execute(JSONObject params, ToolContext context) {
        String code = params.getString("code");
        if (code == null) return "Error: code is required";
        
        int klTypeVal = params.getIntValue("klType", QotCommon.KLType.KLType_Day_VALUE);
        int reqNum = params.getIntValue("reqNum", 10);
        
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
            // Map klType to SubType
            int subTypeVal = QotCommon.SubType.SubType_KL_Day_VALUE;
            if (klTypeVal == QotCommon.KLType.KLType_1Min_VALUE) subTypeVal = QotCommon.SubType.SubType_KL_1Min_VALUE;
            else if (klTypeVal == QotCommon.KLType.KLType_Day_VALUE) subTypeVal = QotCommon.SubType.SubType_KL_Day_VALUE;
            else if (klTypeVal == QotCommon.KLType.KLType_Week_VALUE) subTypeVal = QotCommon.SubType.SubType_KL_Week_VALUE;
            else if (klTypeVal == QotCommon.KLType.KLType_Month_VALUE) subTypeVal = QotCommon.SubType.SubType_KL_Month_VALUE;
            // Add more mappings as needed, default to Day if unknown simple mapping
            
            QotCommon.SubType subType = QotCommon.SubType.forNumber(subTypeVal);
            if (subType == null) subType = QotCommon.SubType.SubType_KL_Day;

            boolean subscriptionSuccess = openD.ensureSubscription(security, subType);
            if (!subscriptionSuccess) {
                return "Subscription Failed";
            }

            // 3. Get K-Line Data
            QotGetKL.C2S c2s = QotGetKL.C2S.newBuilder()
                    .setSecurity(security)
                    .setKlType(klTypeVal)
                    .setReqNum(reqNum)
                    .setRehabType(QotCommon.RehabType.RehabType_None_VALUE)
                    .build();
            
            QotGetKL.Request req = QotGetKL.Request.newBuilder()
                    .setC2S(c2s)
                    .build();
            
            int serialNo = openD.getQotClient().getKL(req);
            
            QotGetKL.Response response = openD.sendQotRequest(serialNo, QotGetKL.Response.class);
            
            if (response.getRetType() == 0) {
                 // Format the output
                 StringBuilder sb = new StringBuilder();
                 sb.append("股票代码: ").append(response.getS2C().getSecurity().getCode()).append("\n");
                 sb.append("K线数据 (前").append(response.getS2C().getKlListCount()).append("条):\n");
                 
                 for (QotCommon.KLine kline : response.getS2C().getKlListList()) {
                     sb.append("时间: ").append(kline.getTime()).append(" | ");
                     sb.append("开: ").append(kline.getOpenPrice()).append(" | ");
                     sb.append("高: ").append(kline.getHighPrice()).append(" | ");
                     sb.append("低: ").append(kline.getLowPrice()).append(" | ");
                     sb.append("收: ").append(kline.getClosePrice()).append(" | ");
                     sb.append("量: ").append(kline.getVolume()).append("\n");
                 }
                 
                 if (response.getS2C().getKlListCount() == 0) {
                     String msg = "未查询到K线数据。";
                     try {
                         context.sendText(msg);
                     } catch (Exception e) {
                         e.printStackTrace();
                     }
                     return msg;
                 }
                 String result = sb.toString();
                 try {
                     context.sendText("K线数据查询结果:\n" + result);
                 } catch (Exception e) {
                     e.printStackTrace();
                 }
                 return result;
            } else {
                 String errorMsg = "Error: " + response.getRetMsg();
                 try {
                     context.sendText("查询K线数据失败: " + response.getRetMsg());
                 } catch (Exception e) {
                     e.printStackTrace();
                 }
                 return errorMsg;
            }
            
        } catch (Exception e) {
            e.printStackTrace();
            String exceptionMsg = "Exception: " + e.getMessage();
            try {
                context.sendText("查询K线数据发生异常: " + e.getMessage());
            } catch (Exception ex) {
                ex.printStackTrace();
            }
            return exceptionMsg;
        }
    }
}
