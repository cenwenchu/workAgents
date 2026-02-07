package com.qiyi.tools.futu;

import com.alibaba.fastjson2.JSONObject;
import com.futu.openapi.FTAPI_Conn_Qot;
import com.futu.openapi.pb.QotCommon;
import com.futu.openapi.pb.QotGetBasicQot;
import com.futu.openapi.pb.QotGetUserSecurity;
import com.qiyi.service.futu.FutuOpenD;
import com.qiyi.tools.ToolContext;
import com.qiyi.tools.ToolMessenger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class GetGroupStockQuotesToolTest {

    @Mock
    private ToolContext context;
    
    @Mock
    private ToolMessenger messenger;

    @Mock
    private FutuOpenD futuOpenD;

    @Mock
    private FTAPI_Conn_Qot qotClient;

    private GetGroupStockQuotesTool tool;

    @BeforeEach
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        tool = spy(new GetGroupStockQuotesTool());
        doReturn(futuOpenD).when(tool).getFutuOpenD();
        when(futuOpenD.getQotClient()).thenReturn(qotClient);
    }

    @Test
    public void testGetName() {
        assertEquals("get_group_stock_quotes", tool.getName());
    }

    @Test
    public void testExecuteMissingGroupName() {
        JSONObject params = new JSONObject();
        String result = tool.execute(params, context, messenger);
        
        assertEquals("Error: groupName is required", result);
        verify(messenger, never()).sendText(anyString());
    }

    @Test
    public void testExecuteSuccess() {
        JSONObject params = new JSONObject();
        params.put("groupName", "MyGroup");

        // Mock getUserSecurity
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

        QotGetUserSecurity.S2C securityS2C = QotGetUserSecurity.S2C.newBuilder()
                .addStaticInfoList(info)
                .build();

        QotGetUserSecurity.Response securityResp = QotGetUserSecurity.Response.newBuilder()
                .setRetType(0)
                .setS2C(securityS2C)
                .build();

        when(futuOpenD.sendQotRequest(anyInt(), eq(QotGetUserSecurity.Response.class)))
                .thenReturn(securityResp);

        // Mock ensureSubscription
        doReturn(true).when(futuOpenD).ensureSubscription(anyList(), any(QotCommon.SubType.class));

        // Mock getBasicQot
        QotCommon.BasicQot basicQot = QotCommon.BasicQot.newBuilder()
                .setSecurity(security)
                .setCurPrice(300.0)
                .setLastClosePrice(295.0)
                .setIsSuspended(false)
                .setListTime("2000-01-01")
                .setPriceSpread(0.2)
                .setUpdateTime("2023-10-27 10:00:00")
                .setHighPrice(310.0)
                .setOpenPrice(290.0)
                .setLowPrice(280.0)
                .setVolume(1000000)
                .setTurnover(300000000)
                .setTurnoverRate(1.5)
                .setAmplitude(5.0)
                .build();

        QotGetBasicQot.S2C quoteS2C = QotGetBasicQot.S2C.newBuilder()
                .addBasicQotList(basicQot)
                .build();

        QotGetBasicQot.Response quoteResp = QotGetBasicQot.Response.newBuilder()
                .setRetType(0)
                .setS2C(quoteS2C)
                .build();

        when(futuOpenD.sendQotRequest(anyInt(), eq(QotGetBasicQot.Response.class)))
                .thenReturn(quoteResp);

        String result = tool.execute(params, context, messenger);

        assertTrue(result.contains("HK.00700"));
        assertTrue(result.contains("现价: 300.0"));
    }

    @Test
    public void testExecuteGetUserSecurityFailed() {
        JSONObject params = new JSONObject();
        params.put("groupName", "MyGroup");

        QotGetUserSecurity.Response securityResp = QotGetUserSecurity.Response.newBuilder()
                .setRetType(-1)
                .setRetMsg("Failed to get security list")
                .build();

        when(futuOpenD.sendQotRequest(anyInt(), eq(QotGetUserSecurity.Response.class)))
                .thenReturn(securityResp);

        String result = tool.execute(params, context, messenger);
        assertTrue(result.contains("获取自选股列表失败: Failed to get security list"));
        verify(messenger).sendText(contains("获取自选股列表失败"));
    }
}
