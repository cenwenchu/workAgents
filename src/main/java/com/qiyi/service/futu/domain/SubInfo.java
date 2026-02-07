package com.qiyi.service.futu.domain;

import java.util.List;

/**
 * 订阅信息
 */
public class SubInfo {
    /**
     * Qot_Common.SubType,订阅类型
     */
    private Integer subType;
    
    /**
     * 订阅该类型行情的证券
     */
    private List<Security> securityList;

    public Integer getSubType() {
        return subType;
    }

    public void setSubType(Integer subType) {
        this.subType = subType;
    }

    public List<Security> getSecurityList() {
        return securityList;
    }

    public void setSecurityList(List<Security> securityList) {
        this.securityList = securityList;
    }
}
