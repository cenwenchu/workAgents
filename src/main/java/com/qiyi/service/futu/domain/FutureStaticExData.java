package com.qiyi.service.futu.domain;

/**
 * 期货额外静态信息
 */
public class FutureStaticExData {
    /**
     * 最后交易日，只有非主连期货合约才有该字段
     */
    private String lastTradeTime;
    
    /**
     * 最后交易日时间戳，只有非主连期货合约才有该字段
     */
    private Double lastTradeTimestamp;
    
    /**
     * 是否主连合约
     */
    private boolean isMainContract;

    public String getLastTradeTime() {
        return lastTradeTime;
    }

    public void setLastTradeTime(String lastTradeTime) {
        this.lastTradeTime = lastTradeTime;
    }

    public Double getLastTradeTimestamp() {
        return lastTradeTimestamp;
    }

    public void setLastTradeTimestamp(Double lastTradeTimestamp) {
        this.lastTradeTimestamp = lastTradeTimestamp;
    }

    public boolean isMainContract() {
        return isMainContract;
    }

    public void setMainContract(boolean mainContract) {
        isMainContract = mainContract;
    }
}
