package com.qiyi.tools.futu;

import com.alibaba.fastjson2.JSONObject;
import com.futu.openapi.FTAPI_Conn_Qot;
import com.futu.openapi.pb.QotGetUserSecurityGroup;
import com.qiyi.futu.FutuOpenD;
import com.qiyi.tools.ToolContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

public class GetUserSecurityGroupToolTest {

    @Mock
    private ToolContext context;
    
    @Mock
    private FutuOpenD futuOpenD;

    @Mock
    private FTAPI_Conn_Qot qotClient;

    private GetUserSecurityGroupTool tool;

    @BeforeEach
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        tool = spy(new GetUserSecurityGroupTool());
        doReturn(futuOpenD).when(tool).getFutuOpenD();
        when(futuOpenD.getQotClient()).thenReturn(qotClient);
    }

    @Test
    public void testGetName() {
        assertEquals("get_user_security_group", tool.getName());
    }

    @Test
    public void testExecuteSuccess() {
        JSONObject params = new JSONObject();

        QotGetUserSecurityGroup.GroupData groupData = QotGetUserSecurityGroup.GroupData.newBuilder()
                .setGroupName("MyGroup")
                .setGroupType(QotGetUserSecurityGroup.GroupType.GroupType_Custom_VALUE)
                .build();

        QotGetUserSecurityGroup.S2C s2c = QotGetUserSecurityGroup.S2C.newBuilder()
                .addGroupList(groupData)
                .build();

        QotGetUserSecurityGroup.Response response = QotGetUserSecurityGroup.Response.newBuilder()
                .setRetType(0)
                .setS2C(s2c)
                .build();

        when(futuOpenD.sendQotRequest(anyInt(), eq(QotGetUserSecurityGroup.Response.class)))
                .thenReturn(response);

        String result = tool.execute(params, context);

        assertTrue(result.contains("MyGroup"));
        assertTrue(result.contains("类型: " + QotGetUserSecurityGroup.GroupType.GroupType_Custom_VALUE));
    }

    @Test
    public void testExecuteFailed() {
        JSONObject params = new JSONObject();

        QotGetUserSecurityGroup.Response response = QotGetUserSecurityGroup.Response.newBuilder()
                .setRetType(-1)
                .setRetMsg("Group failed")
                .build();

        when(futuOpenD.sendQotRequest(anyInt(), eq(QotGetUserSecurityGroup.Response.class)))
                .thenReturn(response);

        String result = tool.execute(params, context);
        assertEquals("获取自选股分组失败: Group failed", result);
    }
}
