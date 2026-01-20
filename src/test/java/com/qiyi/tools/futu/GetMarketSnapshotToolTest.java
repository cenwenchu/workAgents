package com.qiyi.tools.futu;

import com.alibaba.fastjson2.JSONObject;
import com.futu.openapi.FTAPI_Conn_Qot;
import com.futu.openapi.pb.QotCommon;
import com.futu.openapi.pb.QotGetSecuritySnapshot;
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

public class GetMarketSnapshotToolTest {

    @Mock
    private ToolContext context;
    
    @Mock
    private FutuOpenD futuOpenD;

    @Mock
    private FTAPI_Conn_Qot qotClient;

    private GetMarketSnapshotTool tool;

    @BeforeEach
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        tool = spy(new GetMarketSnapshotTool());
        doReturn(futuOpenD).when(tool).getFutuOpenD();
        when(futuOpenD.getQotClient()).thenReturn(qotClient);
    }

    @Test
    public void testGetName() {
        assertEquals("get_market_snapshot", tool.getName());
    }

    @Test
    public void testExecuteMissingCode() {
        JSONObject params = new JSONObject();
        String result = tool.execute(params, context);
        assertEquals("Error: code is required", result);
    }

    @Test
    public void testExecuteSuccess() {
        JSONObject params = new JSONObject();
        params.put("code", "HK.00700");

        QotCommon.Security security = QotCommon.Security.newBuilder()
                .setCode("00700")
                .setMarket(QotCommon.QotMarket.QotMarket_HK_Security_VALUE)
                .build();

        QotGetSecuritySnapshot.SnapshotBasicData basicData = QotGetSecuritySnapshot.SnapshotBasicData.newBuilder()
                .setSecurity(security)
                .setCurPrice(300.0)
                .setOpenPrice(290.0)
                .setHighPrice(310.0)
                .setLowPrice(280.0)
                .setLastClosePrice(295.0)
                .setVolume(100000)
                .setTurnover(30000000)
                .setTurnoverRate(1.5)
                .setAmplitude(5.0)
                .setBidAskRatio(0.8)
                .setVolumeRatio(1.2)
                .setUpdateTime("2023-10-27 10:00:00")
                .setType(QotCommon.SecurityType.SecurityType_Eqty_VALUE)
                .setIsSuspend(false)
                .setListTime("2000-01-01")
                .setLotSize(100)
                .setPriceSpread(0.2)
                .build();

        QotGetSecuritySnapshot.Snapshot snapshot = QotGetSecuritySnapshot.Snapshot.newBuilder()
                .setBasic(basicData)
                .build();

        QotGetSecuritySnapshot.S2C s2c = QotGetSecuritySnapshot.S2C.newBuilder()
                .addSnapshotList(snapshot)
                .build();

        QotGetSecuritySnapshot.Response response = QotGetSecuritySnapshot.Response.newBuilder()
                .setRetType(0)
                .setS2C(s2c)
                .build();

        when(futuOpenD.sendQotRequest(anyInt(), eq(QotGetSecuritySnapshot.Response.class)))
                .thenReturn(response);

        String result = tool.execute(params, context);

        assertTrue(result.contains("00700"), "Result should contain stock code. Actual: " + result);
        assertTrue(result.contains("当前价: 300.0"), "Result should contain current price. Actual: " + result);
    }

    @Test
    public void testExecuteFailed() {
        JSONObject params = new JSONObject();
        params.put("code", "HK.00700");

        QotGetSecuritySnapshot.Response response = QotGetSecuritySnapshot.Response.newBuilder()
                .setRetType(-1)
                .setRetMsg("Snapshot failed")
                .build();

        when(futuOpenD.sendQotRequest(anyInt(), eq(QotGetSecuritySnapshot.Response.class)))
                .thenReturn(response);

        String result = tool.execute(params, context);
        assertEquals("Error: Snapshot failed", result);
    }
}
