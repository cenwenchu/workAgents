package com.qiyi.service.futu.domain;

/**
 * 期权额外静态信息
 */
public class OptionStaticExData {
    /**
     * Qot_Common.OptionType,期权
     */
    private Integer type;
    
    /**
     * 标的股
     */
    private Security owner;
    
    /**
     * 行权日（格式：yyyy-MM-dd）
     */
    private String strikeTime;
    
    /**
     * 行权价
     */
    private Double strikePrice;
    
    /**
     * 是否停牌
     */
    private boolean suspend;
    
    /**
     * 发行市场名字
     */
    private String market;
    
    /**
     * 行权日时间戳
     */
    private Double strikeTimestamp;
    
    /**
     * Qot_Common.IndexOptionType, 指数期权的类型，仅在指数期权有效
     */
    private Integer indexOptionType;
    
    /**
     * ExpirationCycle，交割周期
     */
    private Integer expirationCycle;
    
    /**
     * OptionStandardType，标准期权
     */
    private Integer optionStandardType;
    
    /**
     * OptionSettlementMode，结算方式
     */
    private Integer optionSettlementMode;

    public Integer getType() {
        return type;
    }

    public void setType(Integer type) {
        this.type = type;
    }

    public Security getOwner() {
        return owner;
    }

    public void setOwner(Security owner) {
        this.owner = owner;
    }

    public String getStrikeTime() {
        return strikeTime;
    }

    public void setStrikeTime(String strikeTime) {
        this.strikeTime = strikeTime;
    }

    public Double getStrikePrice() {
        return strikePrice;
    }

    public void setStrikePrice(Double strikePrice) {
        this.strikePrice = strikePrice;
    }

    public boolean isSuspend() {
        return suspend;
    }

    public void setSuspend(boolean suspend) {
        this.suspend = suspend;
    }

    public String getMarket() {
        return market;
    }

    public void setMarket(String market) {
        this.market = market;
    }

    public Double getStrikeTimestamp() {
        return strikeTimestamp;
    }

    public void setStrikeTimestamp(Double strikeTimestamp) {
        this.strikeTimestamp = strikeTimestamp;
    }

    public Integer getIndexOptionType() {
        return indexOptionType;
    }

    public void setIndexOptionType(Integer indexOptionType) {
        this.indexOptionType = indexOptionType;
    }

    public Integer getExpirationCycle() {
        return expirationCycle;
    }

    public void setExpirationCycle(Integer expirationCycle) {
        this.expirationCycle = expirationCycle;
    }

    public Integer getOptionStandardType() {
        return optionStandardType;
    }

    public void setOptionStandardType(Integer optionStandardType) {
        this.optionStandardType = optionStandardType;
    }

    public Integer getOptionSettlementMode() {
        return optionSettlementMode;
    }

    public void setOptionSettlementMode(Integer optionSettlementMode) {
        this.optionSettlementMode = optionSettlementMode;
    }
}
