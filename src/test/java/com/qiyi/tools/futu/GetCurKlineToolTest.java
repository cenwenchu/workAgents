package com.qiyi.tools.futu;

import com.alibaba.fastjson2.JSONObject;
import com.futu.openapi.FTAPI_Conn_Qot;
import com.futu.openapi.pb.QotCommon;
import com.futu.openapi.pb.QotGetKL;
import com.qiyi.service.futu.FutuOpenD;
import com.qiyi.tools.ToolContext;
import com.qiyi.tools.ToolMessenger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class GetCurKlineToolTest {

    @Mock
    private ToolContext context;
    
    @Mock
    private ToolMessenger messenger;

    @Mock
    private FutuOpenD futuOpenD;

    @Mock
    private FTAPI_Conn_Qot qotClient;

    private GetCurKlineTool tool;

    @BeforeEach
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        tool = spy(new GetCurKlineTool());
        doReturn(futuOpenD).when(tool).getFutuOpenD();
        when(futuOpenD.getQotClient()).thenReturn(qotClient);
    }

    @Test
    public void testGetName() {
        assertEquals("get_cur_kline", tool.getName());
    }

    @Test
    public void testExecuteMissingCode() {
        JSONObject params = new JSONObject();
        String result = tool.execute(params, context, messenger);
        assertEquals("Error: code is required", result);
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
        when(qotClient.getKL(any())).thenReturn(100);

        QotCommon.Security security = QotCommon.Security.newBuilder()
                .setCode("00700")
                .setMarket(QotCommon.QotMarket.QotMarket_HK_Security_VALUE)
                .build();

        QotCommon.KLine kLine = QotCommon.KLine.newBuilder()
                .setTime("2023-10-27 10:00:00")
                .setOpenPrice(300.0)
                .setHighPrice(310.0)
                .setLowPrice(290.0)
                .setClosePrice(305.0)
                .setVolume(100000)
                .setTurnover(30000000)
                .setIsBlank(false)
                .build();

        QotGetKL.S2C s2c = QotGetKL.S2C.newBuilder()
                .setSecurity(security)
                .addKlList(kLine)
                .build();

        QotGetKL.Response response = QotGetKL.Response.newBuilder()
                .setRetType(0)
                .setS2C(s2c)
                .build();

        doReturn(response).when(futuOpenD).sendQotRequest(anyInt(), eq(QotGetKL.Response.class));

        String result = tool.execute(params, context, messenger);

        assertTrue(result.contains("K线数据 (前1条)"));
        assertTrue(result.contains("收: 305.0"));
    }

    @Test
    public void testExecuteApiError() {
        JSONObject params = new JSONObject();
        params.put("code", "HK.00700");

        doReturn(true).when(futuOpenD).ensureSubscription(any(QotCommon.Security.class), any(QotCommon.SubType.class));
        when(qotClient.getKL(any())).thenReturn(100);

        QotGetKL.Response response = QotGetKL.Response.newBuilder()
                .setRetType(-1)
                .setRetMsg("Some API Error")
                .build();

        doReturn(response).when(futuOpenD).sendQotRequest(anyInt(), eq(QotGetKL.Response.class));

        String result = tool.execute(params, context, messenger);

        assertEquals("Error: Some API Error", result);
    }
}
