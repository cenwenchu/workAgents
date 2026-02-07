package com.qiyi.tools.futu;

import com.alibaba.fastjson2.JSONObject;
import com.futu.openapi.FTAPI_Conn_Qot;
import com.futu.openapi.pb.QotCommon;
import com.futu.openapi.pb.QotGetBasicQot;
import com.qiyi.service.futu.FutuOpenD;
import com.qiyi.tools.ToolContext;
import com.qiyi.tools.ToolMessenger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

public class GetStockQuoteToolTest {

    @Mock
    private ToolContext context;

    @Mock
    private ToolMessenger messenger;

    @Mock
    private FutuOpenD futuOpenD;

    @Mock
    private FTAPI_Conn_Qot qotClient;

    private GetStockQuoteTool tool;

    @BeforeEach
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        tool = spy(new GetStockQuoteTool());
        doReturn(futuOpenD).when(tool).getFutuOpenD();
        when(futuOpenD.getQotClient()).thenReturn(qotClient);
    }

    @Test
    public void testGetName() {
        assertEquals("get_stock_quote", tool.getName());
    }

    @Test
    public void testExecuteMissingCode() {
        JSONObject params = new JSONObject();
        String result = tool.execute(params, context, messenger);
        
        assertEquals("Error: code is required", result);
        verify(messenger, atLeastOnce()).sendText(anyString());
    }

    @Test
    public void testExecuteSubscriptionFailed() {
        JSONObject params = new JSONObject();
        params.put("code", "HK.00700");

        doReturn(false).when(futuOpenD).ensureSubscription(any(QotCommon.Security.class), any(QotCommon.SubType.class));

        String result = tool.execute(params, context, messenger);
        assertEquals("Subscription Failed", result);
    }

    @Test
    public void testExecuteSuccess() {
        JSONObject params = new JSONObject();
        params.put("code", "HK.00700");

        doReturn(true).when(futuOpenD).ensureSubscription(any(QotCommon.Security.class), any(QotCommon.SubType.class));
        when(qotClient.getBasicQot(any())).thenReturn(100);

        // Construct a successful response
        QotCommon.Security security = QotCommon.Security.newBuilder()
                .setCode("00700")
                .setMarket(QotCommon.QotMarket.QotMarket_HK_Security_VALUE)
                .build();
        
        QotCommon.BasicQot basicQot = QotCommon.BasicQot.newBuilder()
                .setSecurity(security)
                .setCurPrice(300.0)
                .setOpenPrice(290.0)
                .setHighPrice(310.0)
                .setLowPrice(280.0)
                .setLastClosePrice(295.0)
                .setVolume(1000000)
                .setTurnover(300000000)
                .setTurnoverRate(1.5)
                .setAmplitude(5.0)
                .setUpdateTime("2023-10-27 10:00:00")
                .setIsSuspended(false)
                .setListTime("2000-01-01")
                .setPriceSpread(0.2)
                .build();

        QotGetBasicQot.S2C s2c = QotGetBasicQot.S2C.newBuilder()
                .addBasicQotList(basicQot)
                .build();

        QotGetBasicQot.Response response = QotGetBasicQot.Response.newBuilder()
                .setRetType(0)
                .setS2C(s2c)
                .build();

        doReturn(response).when(futuOpenD).sendQotRequest(anyInt(), eq(QotGetBasicQot.Response.class));

        String result = tool.execute(params, context, messenger);

        assertTrue(result.contains("股票代码: 00700"));
        assertTrue(result.contains("当前价: 300.0"));
    }

    @Test
    public void testExecuteApiError() {
        JSONObject params = new JSONObject();
        params.put("code", "HK.00700");

        doReturn(true).when(futuOpenD).ensureSubscription(any(QotCommon.Security.class), any(QotCommon.SubType.class));
        when(qotClient.getBasicQot(any())).thenReturn(100);

        QotGetBasicQot.Response response = QotGetBasicQot.Response.newBuilder()
                .setRetType(-1)
                .setRetMsg("Some API Error")
                .build();

        doReturn(response).when(futuOpenD).sendQotRequest(anyInt(), eq(QotGetBasicQot.Response.class));

        String result = tool.execute(params, context, messenger);

        assertEquals("Error: Some API Error", result);
    }
    
    @Test
    public void testExecuteException() {
        JSONObject params = new JSONObject();
        params.put("code", "HK.00700");
        
        doThrow(new RuntimeException("Unexpected error")).when(futuOpenD).ensureSubscription(any(QotCommon.Security.class), any(QotCommon.SubType.class));
        
        String result = tool.execute(params, context, messenger);
        
        assertTrue(result.contains("Exception: Unexpected error"));
    }
}
