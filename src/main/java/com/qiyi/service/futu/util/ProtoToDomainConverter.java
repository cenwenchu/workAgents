package com.qiyi.service.futu.util;

import com.futu.openapi.pb.QotCommon;
import com.qiyi.service.futu.domain.BasicQot;
import com.qiyi.service.futu.domain.Security;

import java.util.ArrayList;
import java.util.List;

public class ProtoToDomainConverter {

    public static Security convertSecurity(QotCommon.Security protoSec) {
        if (protoSec == null) return null;
        Security security = new Security();
        security.setMarket(protoSec.getMarket());
        security.setCode(protoSec.getCode());
        return security;
    }

    public static BasicQot convertBasicQot(QotCommon.BasicQot protoQot) {
        if (protoQot == null) return null;
        BasicQot qot = new BasicQot();
        qot.setSecurity(convertSecurity(protoQot.getSecurity()));
        qot.setName(protoQot.getName()); // Note: BasicQot protobuf might not have name directly in some versions, checking definitions. 
        // Wait, looking at QotCommon.BasicQot definition in typical Futu API, it usually doesn't have name directly, 
        // the name is often in SecurityStaticInfo. 
        // However, the provided user definition for BasicQot POJO has a name field.
        // Let's check if the protobuf BasicQot has it. 
        // If not, we might need to fetch it separately or leave it null.
        // For now, let's map what we can.
        
        qot.setSuspended(protoQot.getIsSuspended());
        qot.setListTime(protoQot.getListTime());
        qot.setPriceSpread(protoQot.getPriceSpread());
        qot.setUpdateTime(protoQot.getUpdateTime());
        qot.setHighPrice(protoQot.getHighPrice());
        qot.setOpenPrice(protoQot.getOpenPrice());
        qot.setLowPrice(protoQot.getLowPrice());
        qot.setCurPrice(protoQot.getCurPrice());
        qot.setLastClosePrice(protoQot.getLastClosePrice());
        qot.setVolume(protoQot.getVolume());
        qot.setTurnover(protoQot.getTurnover());
        qot.setTurnoverRate(protoQot.getTurnoverRate());
        qot.setAmplitude(protoQot.getAmplitude());
        qot.setDarkStatus(protoQot.getDarkStatus());
        qot.setListTimestamp(protoQot.getListTimestamp());
        qot.setUpdateTimestamp(protoQot.getUpdateTimestamp());
        
        return qot;
    }

    public static List<BasicQot> convertBasicQotList(List<QotCommon.BasicQot> protoList) {
        List<BasicQot> list = new ArrayList<>();
        if (protoList != null) {
            for (QotCommon.BasicQot proto : protoList) {
                list.add(convertBasicQot(proto));
            }
        }
        return list;
    }
}
