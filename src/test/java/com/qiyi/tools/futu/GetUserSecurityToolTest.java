package com.qiyi.tools.futu;

import com.alibaba.fastjson2.JSONObject;
import com.futu.openapi.FTAPI_Conn_Qot;
import com.futu.openapi.pb.QotCommon;
import com.futu.openapi.pb.QotGetUserSecurity;
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

public class GetUserSecurityToolTest {

    @Mock
    private ToolContext context;
    
    @Mock
    private FutuOpenD futuOpenD;

    @Mock
    private FTAPI_Conn_Qot qotClient;

    private GetUserSecurityTool tool;

    @BeforeEach
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        tool = spy(new GetUserSecurityTool());
        doReturn(futuOpenD).when(tool).getFutuOpenD();
        when(futuOpenD.getQotClient()).thenReturn(qotClient);
    }

    @Test
    public void testGetName() {
        assertEquals("get_user_security", tool.getName());
    }

    @Test
    public void testExecuteMissingGroupName() {
        JSONObject params = new JSONObject();
        String result = tool.execute(params, context);
        assertEquals("Error: groupName is required", result);
    }

    @Test
    public void testExecuteSuccess() {
        JSONObject params = new JSONObject();
        params.put("groupName", "MyGroup");

        QotCommon.Security security = QotCommon.Security.newBuilder()
                .setCode("HK.00700")
                .setMarket(QotCommon.QotMarket.QotMarket_HK_Security_VALUE)
                .build();

        QotCommon.SecurityStaticBasic basic = QotCommon.SecurityStaticBasic.newBuilder()
                .setSecurity(security)
                .setName("Tencent")
                .setSecType(QotCommon.SecurityType.SecurityType_Eqty_VALUE)
                .setId(12345)
                .setLotSize(100)
                .setListTime("2000-01-01")
                .build();

        QotCommon.SecurityStaticInfo info = QotCommon.SecurityStaticInfo.newBuilder()
                .setBasic(basic)
                .build();

        QotGetUserSecurity.S2C s2c = QotGetUserSecurity.S2C.newBuilder()
                .addStaticInfoList(info)
                .build();

        QotGetUserSecurity.Response response = QotGetUserSecurity.Response.newBuilder()
                .setRetType(0)
                .setS2C(s2c)
                .build();

        when(futuOpenD.sendQotRequest(anyInt(), eq(QotGetUserSecurity.Response.class)))
                .thenReturn(response);

        String result = tool.execute(params, context);

        assertTrue(result.contains("HK.00700"));
        assertTrue(result.contains("Tencent"));
    }

    @Test
    public void testExecuteFailed() {
        JSONObject params = new JSONObject();
        params.put("groupName", "MyGroup");

        QotGetUserSecurity.Response response = QotGetUserSecurity.Response.newBuilder()
                .setRetType(-1)
                .setRetMsg("Security failed")
                .build();

        when(futuOpenD.sendQotRequest(anyInt(), eq(QotGetUserSecurity.Response.class)))
                .thenReturn(response);

        String result = tool.execute(params, context);
        assertEquals("获取自选股列表失败: Security failed", result);
    }
}
