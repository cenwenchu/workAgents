package com.qiyi.service.futu.domain;

import java.util.List;

/**
 * 连接订阅信息
 */
public class ConnSubInfo {
    /**
     * 该连接订阅信息
     */
    private List<SubInfo> subInfoList;
    
    /**
     * 该连接已经使用的订阅额度
     */
    private Integer usedQuota;
    
    /**
     * 用于区分是否是自己连接的数据
     */
    private boolean isOwnConnData;

    public List<SubInfo> getSubInfoList() {
        return subInfoList;
    }

    public void setSubInfoList(List<SubInfo> subInfoList) {
        this.subInfoList = subInfoList;
    }

    public Integer getUsedQuota() {
        return usedQuota;
    }

    public void setUsedQuota(Integer usedQuota) {
        this.usedQuota = usedQuota;
    }

    public boolean isOwnConnData() {
        return isOwnConnData;
    }

    public void setOwnConnData(boolean ownConnData) {
        isOwnConnData = ownConnData;
    }
}
