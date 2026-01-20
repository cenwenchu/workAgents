package com.qiyi.tools.futu;

import com.alibaba.fastjson2.JSONObject;
import com.futu.openapi.pb.QotGetUserSecurityGroup;
import com.qiyi.futu.FutuOpenD;
import com.qiyi.tools.Tool;
import com.qiyi.tools.ToolContext;

import java.util.List;
import java.util.ArrayList;

public class GetUserSecurityGroupTool implements Tool {
    @Override
    public String getName() {
        return "get_user_security_group";
    }

    @Override
    public String getDescription() {
        return "功能：获取用户的自选股分组列表。参数：groupType（整数，选填，分组类型，默认全部）。返回：分组名称和类型列表。";
    }

    protected FutuOpenD getFutuOpenD() {
        return FutuOpenD.getInstance();
    }

    @Override
    public String execute(JSONObject params, ToolContext context) {
        try {
            FutuOpenD openD = getFutuOpenD();
            
            // Default to ALL if not specified
            int groupType = QotGetUserSecurityGroup.GroupType.GroupType_All_VALUE;
            if (params != null && params.containsKey("groupType")) {
                groupType = params.getIntValue("groupType");
            }

            QotGetUserSecurityGroup.C2S c2s = QotGetUserSecurityGroup.C2S.newBuilder()
                    .setGroupType(groupType)
                    .build();

            QotGetUserSecurityGroup.Request req = QotGetUserSecurityGroup.Request.newBuilder()
                    .setC2S(c2s)
                    .build();

            int serialNo = openD.getQotClient().getUserSecurityGroup(req);
            
            QotGetUserSecurityGroup.Response response = openD.sendQotRequest(serialNo, QotGetUserSecurityGroup.Response.class);
            
            if (response.getRetType() == 0) {
                StringBuilder sb = new StringBuilder();
                sb.append("自选股分组列表：\n");
                for (QotGetUserSecurityGroup.GroupData group : response.getS2C().getGroupListList()) {
                    sb.append("分组名: ").append(group.getGroupName())
                      .append(" | 类型: ").append(group.getGroupType()).append("\n");
                }
                
                if (response.getS2C().getGroupListCount() == 0) {
                    sb.append("无自选股分组。");
                }
                
                String result = sb.toString();
                try {
                    context.sendText(result);
                } catch (Exception e) {
                    e.printStackTrace();
                }
                return result;
            } else {
                String errorMsg = "获取自选股分组失败: " + response.getRetMsg();
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
                context.sendText("获取自选股分组发生异常: " + e.getMessage());
            } catch (Exception ex) {
                ex.printStackTrace();
            }
            return exceptionMsg;
        }
    }
}