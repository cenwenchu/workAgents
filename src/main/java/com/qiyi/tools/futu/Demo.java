package com.qiyi.tools.futu;

import com.futu.openapi.FTAPI;
import com.futu.openapi.FTAPI_Conn;
import com.futu.openapi.FTAPI_Conn_Qot;
import com.futu.openapi.FTSPI_Conn;
import com.futu.openapi.FTSPI_Qot;
import com.futu.openapi.pb.Common;
import com.futu.openapi.pb.QotCommon;
import com.futu.openapi.pb.QotGetBasicQot;
import com.futu.openapi.pb.QotSub;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;
import com.qiyi.util.AppLog;

public class Demo implements FTSPI_Qot, FTSPI_Conn {
    FTAPI_Conn_Qot qot = new FTAPI_Conn_Qot();

    public Demo() {
        qot.setClientInfo("javaclient", 1);  //设置客户端信息
        qot.setConnSpi(this);  //设置连接回调
        qot.setQotSpi(this);   //设置交易回调
    }

    public void start() {
        qot.initConnect("127.0.0.1", (short)11111, false);
    }

    @Override
    public void onInitConnect(FTAPI_Conn client, long errCode, String desc)
    {
        AppLog.info(String.format("Qot onInitConnect: ret=%b desc=%s connID=%d\n", errCode, desc, client.getConnectID()));
        if (errCode != 0)
            return;

        QotCommon.Security sec = QotCommon.Security.newBuilder()
                .setMarket(QotCommon.QotMarket.QotMarket_HK_Security_VALUE)
                .setCode("00700")
                .build();
        QotSub.C2S c2s = QotSub.C2S.newBuilder()
                .addSecurityList(sec)
                .addSubTypeList(QotCommon.SubType.SubType_Basic_VALUE)
                .setIsSubOrUnSub(true)
                .build();
        QotSub.Request req = QotSub.Request.newBuilder().setC2S(c2s).build();
        int seqNo = qot.sub(req);
        AppLog.info(String.format("Send QotSub: %d\n", seqNo));
    }

    @Override
    public void onDisconnect(FTAPI_Conn client, long errCode) {
        AppLog.info(String.format("Qot onDisConnect: %d\n", errCode));
    }

    @Override
    public void onReply_Sub(FTAPI_Conn client, int nSerialNo, QotSub.Response rsp) {
        AppLog.info(String.format("Reply: QotSub: %d  %s\n", nSerialNo, rsp.toString()));

        if (rsp.getRetType() != Common.RetType.RetType_Succeed_VALUE)
            return;

        QotCommon.Security sec = QotCommon.Security.newBuilder()
                .setMarket(QotCommon.QotMarket.QotMarket_HK_Security_VALUE)
                .setCode("00700")
                .build();
        QotGetBasicQot.C2S c2s = QotGetBasicQot.C2S.newBuilder()
                .addSecurityList(sec)
                .build();
        QotGetBasicQot.Request req = QotGetBasicQot.Request.newBuilder().setC2S(c2s).build();
        int seqNo = qot.getBasicQot(req);
        AppLog.info(String.format("Send QotGetBasicQot: %d\n", seqNo));
    }

    @Override
    public void onReply_GetBasicQot(FTAPI_Conn client, int nSerialNo, QotGetBasicQot.Response rsp) {
        if (rsp.getRetType() != 0) {
            AppLog.info(String.format("QotGetBasicQot failed: %s\n", rsp.getRetMsg()));
        }
        else {
            try {
                String json = JsonFormat.printer().print(rsp);
                AppLog.info(String.format("Receive QotGetBasicQot: %s\n", json));
            } catch (InvalidProtocolBufferException e) {
                AppLog.error(e);
            }
        }
    }

    public static void main(String[] args) {
        FTAPI.init();
        Demo qot = new Demo();
        qot.start();

        while (true) {
            try {
                Thread.sleep(1000 * 600);
            } catch (InterruptedException exc) {

            }
        }
    }
}
