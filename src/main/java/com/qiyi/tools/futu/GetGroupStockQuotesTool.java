package com.qiyi.tools.futu;

import com.alibaba.fastjson2.JSONObject;
import com.qiyi.futu.FutuOpenD;
import com.qiyi.tools.Tool;
import com.qiyi.tools.ToolContext;
import com.futu.openapi.pb.QotGetUserSecurity;
import com.futu.openapi.pb.QotGetBasicQot;
import com.futu.openapi.pb.QotCommon;

import java.util.List;
import java.util.ArrayList;

public class GetGroupStockQuotesTool implements Tool {
    @Override
    public String getName() {
        return "get_group_stock_quotes";
    }

    @Override
    public String getDescription() {
        return "功能：获取指定自选股分组下所有股票的实时报价。参数：groupName（字符串，必填，分组名称）。返回：该分组下所有股票的实时价格列表。";
    }

    protected FutuOpenD getFutuOpenD() {
        return FutuOpenD.getInstance();
    }

    @Override
    public String execute(JSONObject params, ToolContext context) {
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
                context.sendText(errorMsg);
                return errorMsg;
            }

            List<QotCommon.Security> securityList = new ArrayList<>();
            for (QotCommon.SecurityStaticInfo info : groupResp.getS2C().getStaticInfoListList()) {
                securityList.add(info.getBasic().getSecurity());
            }

            if (securityList.isEmpty()) {
                String msg = "分组[" + groupName + "]下无股票。";
                context.sendText(msg);
                return msg;
            }

            // Step 2: Ensure Subscription (Batch)
            boolean subscriptionSuccess = openD.ensureSubscription(securityList, QotCommon.SubType.SubType_Basic);
            if (!subscriptionSuccess) {
                 System.out.println("Subscription warning: Some securities might not be subscribed.");
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
                 
                 List<com.qiyi.futu.domain.BasicQot> qotList = com.qiyi.futu.util.ProtoToDomainConverter.convertBasicQotList(quoteResp.getS2C().getBasicQotListList());
                 
                 for (com.qiyi.futu.domain.BasicQot qot : qotList) {
                     sb.append(qot.getSecurity().getCode())
                       .append(" | 现价: ").append(qot.getCurPrice())
                       .append(" | 涨跌幅: ").append(String.format("%.2f%%", qot.getCurPrice() > 0 ? (qot.getCurPrice() - qot.getLastClosePrice()) / qot.getLastClosePrice() * 100 : 0))
                       .append("\n");
                 }
                 
                 String result = sb.toString();
                 context.sendText(result);
                 return result;
            } else {
                 String errorMsg = "获取批量报价失败: " + quoteResp.getRetMsg();
                 context.sendText(errorMsg);
                 return errorMsg;
            }

        } catch (Exception e) {
            e.printStackTrace();
            String exceptionMsg = "Exception: " + e.getMessage();
            try {
                context.sendText("获取分组报价发生异常: " + e.getMessage());
            } catch (Exception ex) {
                ex.printStackTrace();
            }
            return exceptionMsg;
        }
    }
}