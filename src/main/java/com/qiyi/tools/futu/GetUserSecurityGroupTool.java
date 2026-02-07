package com.qiyi.tools.futu;

import com.alibaba.fastjson2.JSONObject;
import com.futu.openapi.pb.QotGetUserSecurityGroup;
import com.qiyi.component.ComponentId;
import com.qiyi.service.futu.FutuOpenD;
import com.qiyi.tools.Tool;
import com.qiyi.tools.ToolContext;
import com.qiyi.tools.ToolMessenger;
import com.qiyi.util.AppLog;

import java.util.List;
import java.util.ArrayList;

/**
 * 获取用户的自选股分组列表（富途）。
 *
 * <p>用于在后续查询中选择 groupName，例如 get_user_security / get_group_stock_quotes。</p>
 */
public class GetUserSecurityGroupTool implements Tool {
    @Override
    public String getName() {
        return "get_user_security_group";
    }

    @Override
    public String getDescription() {
        return "功能：获取用户的自选股分组列表。参数：groupType（整数，选填，分组类型，默认全部）。返回：分组名称和类型列表。";
    }

    @Override
    public List<ComponentId> requiredComponents() {
        return List.of(ComponentId.FUTU);
    }

    protected FutuOpenD getFutuOpenD() {
        return FutuOpenD.getInstance();
    }

    @Override
    public String execute(JSONObject params, ToolContext context, ToolMessenger messenger) {
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
                    if (messenger != null) messenger.sendText(result);
                } catch (Exception e) {
                    AppLog.error(e);
                }
                return result;
            } else {
                String errorMsg = "获取自选股分组失败: " + response.getRetMsg();
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
                if (messenger != null) messenger.sendText("获取自选股分组发生异常: " + e.getMessage());
            } catch (Exception ex) {
                AppLog.error(ex);
            }
            return exceptionMsg;
        }
    }
}
