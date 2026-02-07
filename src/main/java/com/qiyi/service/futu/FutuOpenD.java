package com.qiyi.service.futu;

import com.futu.openapi.FTAPI;
import com.futu.openapi.FTAPI_Conn_Qot;
import com.futu.openapi.FTAPI_Conn;
import com.futu.openapi.FTSPI_Conn;
import com.futu.openapi.FTSPI_Qot;
import com.futu.openapi.pb.QotGetSecuritySnapshot;
import com.futu.openapi.pb.QotGetBasicQot;
import com.futu.openapi.pb.QotGetKL;
import com.futu.openapi.pb.QotGetOrderBook;
import com.futu.openapi.pb.QotGetUserSecurityGroup;
import com.futu.openapi.pb.QotGetUserSecurity;
import com.futu.openapi.pb.QotGetSubInfo;
import com.futu.openapi.pb.QotSub;
import com.futu.openapi.pb.QotCommon;
import com.google.protobuf.GeneratedMessageV3;
import com.qiyi.util.AppLog;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class FutuOpenD implements FTSPI_Qot, FTSPI_Conn {
    private static volatile FutuOpenD instance;
    private FTAPI_Conn_Qot qotClient;
    private boolean isConnected = false;
    private CompletableFuture<Boolean> connectFuture;
    
    // Pending requests map: SerialNo -> Future
    private final Map<Integer, CompletableFuture<GeneratedMessageV3>> pendingRequests = new ConcurrentHashMap<>();
    // Unclaimed responses map: SerialNo -> Response (for responses arriving before request tracking)
    private final Map<Integer, GeneratedMessageV3> unclaimedResponses = new ConcurrentHashMap<>();

    private String host = "127.0.0.1";
    private int port = 11111;

    private FutuOpenD() {
        FTAPI.init();
        qotClient = new FTAPI_Conn_Qot();
        qotClient.setClientInfo("agent-client", 1);
        qotClient.setQotSpi(this);
        qotClient.setConnSpi(this);
    }

    // --- Subscription Helper Methods ---

    public boolean ensureSubscription(QotCommon.Security security, QotCommon.SubType subType) {
        if (checkSubscription(security, subType)) {
            return true;
        }
        return subscribe(security, subType);
    }

    public boolean ensureSubscription(List<QotCommon.Security> securityList, QotCommon.SubType subType) {
        if (securityList == null || securityList.isEmpty()) return true;
        
        List<QotCommon.Security> unsubscribed = checkSubscription(securityList, subType);
        if (unsubscribed.isEmpty()) {
            return true;
        }
        return subscribe(unsubscribed, subType);
    }

    public boolean checkSubscription(QotCommon.Security security, QotCommon.SubType subType) {
        List<QotCommon.Security> unsubscribed = checkSubscription(java.util.Collections.singletonList(security), subType);
        return unsubscribed.isEmpty();
    }

    /**
     * Checks subscription status for a list of securities.
     * @return List of securities that are NOT subscribed.
     */
    public List<QotCommon.Security> checkSubscription(List<QotCommon.Security> securityList, QotCommon.SubType subType) {
        List<QotCommon.Security> unsubscribed = new java.util.ArrayList<>(securityList);
        try {
            QotGetSubInfo.C2S subInfoC2S = QotGetSubInfo.C2S.newBuilder()
                    .setIsReqAllConn(false) // Check own connection
                    .build();
            QotGetSubInfo.Request subInfoReq = QotGetSubInfo.Request.newBuilder()
                    .setC2S(subInfoC2S)
                    .build();
            
            int subInfoSerial = getQotClient().getSubInfo(subInfoReq);
            QotGetSubInfo.Response subInfoResp = sendQotRequest(subInfoSerial, QotGetSubInfo.Response.class);
            
            if (subInfoResp.getRetType() == 0) {
                for (QotCommon.ConnSubInfo connSubInfo : subInfoResp.getS2C().getConnSubInfoListList()) {
                    if (connSubInfo.getIsOwnConnData()) {
                        for (QotCommon.SubInfo subInfo : connSubInfo.getSubInfoListList()) {
                            if (subInfo.getSubType() == subType.getNumber()) {
                                for (QotCommon.Security subscribedSec : subInfo.getSecurityListList()) {
                                    unsubscribed.removeIf(sec -> sec.getMarket() == subscribedSec.getMarket() && sec.getCode().equals(subscribedSec.getCode()));
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            AppLog.error(e);
        }
        return unsubscribed;
    }

    public boolean subscribe(QotCommon.Security security, QotCommon.SubType subType) {
        return subscribe(java.util.Collections.singletonList(security), subType);
    }

    public boolean subscribe(List<QotCommon.Security> securityList, QotCommon.SubType subType) {
        try {
            QotSub.C2S subC2S = QotSub.C2S.newBuilder()
                    .addAllSecurityList(securityList)
                    .addSubTypeList(subType.getNumber())
                    .setIsSubOrUnSub(true)
                    .setIsRegOrUnRegPush(false)
                    .build();

            QotSub.Request subReq = QotSub.Request.newBuilder()
                    .setC2S(subC2S)
                    .build();

            int subSerialNo = getQotClient().sub(subReq);
            QotSub.Response subResp = sendQotRequest(subSerialNo, QotSub.Response.class);

            return subResp.getRetType() == 0;
        } catch (Exception e) {
            AppLog.error(e);
            return false;
        }
    }

    public static FutuOpenD getInstance() {
        if (instance == null) {
            synchronized (FutuOpenD.class) {
                if (instance == null) {
                    instance = new FutuOpenD();
                }
            }
        }
        return instance;
    }

    public synchronized void connect(String host, int port) {
        if (isConnected && this.host.equals(host) && this.port == port) {
            return;
        }
        this.host = host;
        this.port = port;
        
        connectFuture = new CompletableFuture<>();
        qotClient.initConnect(host, (short)port, false);
        
        try {
            Boolean success = connectFuture.get(10, TimeUnit.SECONDS);
            if (!Boolean.TRUE.equals(success)) {
                throw new RuntimeException("Connection failed or timed out.");
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to connect to Futu OpenD: " + e.getMessage(), e);
        }
    }
    
    public FTAPI_Conn_Qot getQotClient() {
        if (!isConnected) {
            connect(host, port);
        }
        return qotClient;
    }

    public boolean isConnected() {
        return isConnected;
    }

    public synchronized void disconnect() {
        try {
            if (qotClient != null) {
                invokeIfExists(qotClient, "close");
                invokeIfExists(qotClient, "disconnect");
                invokeIfExists(qotClient, "unInitConnect");
                invokeIfExists(qotClient, "uninitConnect");
                invokeIfExists(qotClient, "release");
            }
        } catch (Exception e) {
            AppLog.error(e);
        } finally {
            isConnected = false;
        }
    }

    private static void invokeIfExists(Object target, String methodName) {
        if (target == null || methodName == null || methodName.isEmpty()) return;
        try {
            java.lang.reflect.Method m = target.getClass().getMethod(methodName);
            m.setAccessible(true);
            m.invoke(target);
        } catch (NoSuchMethodException ignored) {
        } catch (Exception e) {
            AppLog.error(e);
        }
    }

    // --- Sync Helper Methods ---

    public <T extends GeneratedMessageV3> T sendQotRequest(int serialNo, Class<T> responseType) {
        // 1. Check if response already arrived
        GeneratedMessageV3 existingResponse = unclaimedResponses.remove(serialNo);
        if (existingResponse != null) {
             return responseType.cast(existingResponse);
        }

        CompletableFuture<GeneratedMessageV3> future = new CompletableFuture<>();
        pendingRequests.put(serialNo, future);
        
        // 2. Double check in case it arrived while we were setting up
        existingResponse = unclaimedResponses.remove(serialNo);
        if (existingResponse != null) {
             pendingRequests.remove(serialNo);
             return responseType.cast(existingResponse);
        }

        try {
            return responseType.cast(future.get(30, TimeUnit.SECONDS));
        } catch (Exception e) {
            pendingRequests.remove(serialNo);
            throw new RuntimeException("Request timeout or failed: " + e.getMessage(), e);
        }
    }

    // --- FTSPI_Conn Implementation ---

    @Override
    public void onInitConnect(FTAPI_Conn client, long errCode, String desc) {
        if (errCode == 0) {
            isConnected = true;
            AppLog.info("Futu OpenD Connected.");
            if (connectFuture != null) connectFuture.complete(true);
        } else {
            isConnected = false;
            AppLog.error("Futu OpenD Connection Failed: " + desc);
            if (connectFuture != null) connectFuture.complete(false);
        }
    }

    @Override
    public void onDisconnect(FTAPI_Conn client, long errCode) {
        isConnected = false;
        AppLog.info("Futu OpenD Disconnected.");
    }

    // --- FTSPI_Qot Implementation ---
    
    @Override
    public void onReply_Sub(FTAPI_Conn client, int nSerialNo, QotSub.Response rsp) {
        completeRequest(nSerialNo, rsp);
    }

    @Override
    public void onReply_GetSubInfo(FTAPI_Conn client, int nSerialNo, QotGetSubInfo.Response rsp) {
        completeRequest(nSerialNo, rsp);
    }

    @Override
    public void onReply_GetBasicQot(FTAPI_Conn client, int nSerialNo, QotGetBasicQot.Response rsp) {
        completeRequest(nSerialNo, rsp);
    }

    @Override
    public void onReply_GetSecuritySnapshot(FTAPI_Conn client, int nSerialNo, QotGetSecuritySnapshot.Response rsp) {
        completeRequest(nSerialNo, rsp);
    }
    
    @Override
    public void onReply_GetKL(FTAPI_Conn client, int nSerialNo, QotGetKL.Response rsp) {
        completeRequest(nSerialNo, rsp);
    }

    @Override
    public void onReply_GetOrderBook(FTAPI_Conn client, int nSerialNo, QotGetOrderBook.Response rsp) {
        completeRequest(nSerialNo, rsp);
    }

    @Override
    public void onReply_GetUserSecurityGroup(FTAPI_Conn client, int nSerialNo, QotGetUserSecurityGroup.Response rsp) {
        completeRequest(nSerialNo, rsp);
    }

    @Override
    public void onReply_GetUserSecurity(FTAPI_Conn client, int nSerialNo, QotGetUserSecurity.Response rsp) {
        completeRequest(nSerialNo, rsp);
    }

    private void completeRequest(int serialNo, GeneratedMessageV3 response) {
        CompletableFuture<GeneratedMessageV3> future = pendingRequests.remove(serialNo);
        if (future != null) {
            future.complete(response);
        } else {
            unclaimedResponses.put(serialNo, response);
        }
    }

}
