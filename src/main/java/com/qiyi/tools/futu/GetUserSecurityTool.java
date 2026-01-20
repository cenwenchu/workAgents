package com.qiyi.tools.futu;

import com.alibaba.fastjson2.JSONObject;
import com.qiyi.futu.FutuOpenD;
import com.qiyi.tools.Tool;
import com.qiyi.tools.ToolContext;
import com.futu.openapi.pb.QotGetUserSecurity;
import com.futu.openapi.pb.QotCommon;

import java.util.List;
import java.util.ArrayList;

public class GetUserSecurityTool implements Tool {
    @Override
    public String getName() {
        return "get_user_security";
    }

    @Override
    public String getDescription() {
        return "功能：获取指定分组下的自选股列表。参数：groupName（字符串，必填，分组名称）。返回：股票代码、名称等信息列表。";
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
                    context.sendText(result);
                } catch (Exception e) {
                    e.printStackTrace();
                }
                return result;
            } else {
                String errorMsg = "获取自选股列表失败: " + response.getRetMsg();
                try {
                    context.sendText(errorMsg);
                } catch (Exception e) {
                    e.printStackTrace();
                }
                return errorMsg;
            }
            
        } catch (Exception e) {
            e.printStackTrace();
            String exceptionMsg = "Exception: " + e.getMessage();
            try {
                context.sendText("获取自选股列表发生异常: " + e.getMessage());
            } catch (Exception ex) {
                ex.printStackTrace();
            }
            return exceptionMsg;
        }
    }
}
