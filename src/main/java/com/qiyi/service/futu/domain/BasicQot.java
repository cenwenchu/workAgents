package com.qiyi.service.futu.domain;

/**
 * 基础报价
 */
public class BasicQot {
    /**
     * 股票
     */
    private Security security;
    
    /**
     * 股票名称
     */
    private String name;
    
    /**
     * 是否停牌
     */
    private boolean isSuspended;
    
    /**
     * 上市日期字符串（此字段停止维护，不建议使用，格式：yyyy-MM-dd）
     */
    private String listTime;
    
    /**
     * 价差
     */
    private Double priceSpread;
    
    /**
     * 最新价的更新时间字符串（格式：yyyy-MM-dd HH:mm:ss），对其他字段不适用
     */
    private String updateTime;
    
    /**
     * 最高价
     */
    private Double highPrice;
    
    /**
     * 开盘价
     */
    private Double openPrice;
    
    /**
     * 最低价
     */
    private Double lowPrice;
    
    /**
     * 最新价
     */
    private Double curPrice;
    
    /**
     * 昨收价
     */
    private Double lastClosePrice;
    
    /**
     * 成交量
     */
    private Long volume;
    
    /**
     * 成交额
     */
    private Double turnover;
    
    /**
     * 换手率（该字段为百分比字段，默认不展示 %，如 20 实际对应 20%）
     */
    private Double turnoverRate;
    
    /**
     * 振幅（该字段为百分比字段，默认不展示 %，如 20 实际对应 20%）
     */
    private Double amplitude;
    
    /**
     * DarkStatus, 暗盘交易状态
     */
    private Integer darkStatus;
    
    /**
     * 期权特有字段
     */
    private OptionBasicQotExData optionExData;
    
    /**
     * 上市日期时间戳（此字段停止维护，不建议使用）
     */
    private Double listTimestamp;
    
    /**
     * 最新价的更新时间戳，对其他字段不适用
     */
    private Double updateTimestamp;
    
    /**
     * 盘前数据
     */
    private PreAfterMarketData preMarket;
    
    /**
     * 盘后数据
     */
    private PreAfterMarketData afterMarket;
    
    /**
     * SecurityStatus, 股票状态
     */
    private Integer secStatus;
    
    /**
     * 期货特有字段
     */
    private FutureBasicQotExData futureExData;

    public Security getSecurity() {
        return security;
    }

    public void setSecurity(Security security) {
        this.security = security;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public boolean isSuspended() {
        return isSuspended;
    }

    public void setSuspended(boolean suspended) {
        isSuspended = suspended;
    }

    public String getListTime() {
        return listTime;
    }

    public void setListTime(String listTime) {
        this.listTime = listTime;
    }

    public Double getPriceSpread() {
        return priceSpread;
    }

    public void setPriceSpread(Double priceSpread) {
        this.priceSpread = priceSpread;
    }

    public String getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(String updateTime) {
        this.updateTime = updateTime;
    }

    public Double getHighPrice() {
        return highPrice;
    }

    public void setHighPrice(Double highPrice) {
        this.highPrice = highPrice;
    }

    public Double getOpenPrice() {
        return openPrice;
    }

    public void setOpenPrice(Double openPrice) {
        this.openPrice = openPrice;
    }

    public Double getLowPrice() {
        return lowPrice;
    }

    public void setLowPrice(Double lowPrice) {
        this.lowPrice = lowPrice;
    }

    public Double getCurPrice() {
        return curPrice;
    }

    public void setCurPrice(Double curPrice) {
        this.curPrice = curPrice;
    }

    public Double getLastClosePrice() {
        return lastClosePrice;
    }

    public void setLastClosePrice(Double lastClosePrice) {
        this.lastClosePrice = lastClosePrice;
    }

    public Long getVolume() {
        return volume;
    }

    public void setVolume(Long volume) {
        this.volume = volume;
    }

    public Double getTurnover() {
        return turnover;
    }

    public void setTurnover(Double turnover) {
        this.turnover = turnover;
    }

    public Double getTurnoverRate() {
        return turnoverRate;
    }

    public void setTurnoverRate(Double turnoverRate) {
        this.turnoverRate = turnoverRate;
    }

    public Double getAmplitude() {
        return amplitude;
    }

    public void setAmplitude(Double amplitude) {
        this.amplitude = amplitude;
    }

    public Integer getDarkStatus() {
        return darkStatus;
    }

    public void setDarkStatus(Integer darkStatus) {
        this.darkStatus = darkStatus;
    }

    public OptionBasicQotExData getOptionExData() {
        return optionExData;
    }

    public void setOptionExData(OptionBasicQotExData optionExData) {
        this.optionExData = optionExData;
    }

    public Double getListTimestamp() {
        return listTimestamp;
    }

    public void setListTimestamp(Double listTimestamp) {
        this.listTimestamp = listTimestamp;
    }

    public Double getUpdateTimestamp() {
        return updateTimestamp;
    }

    public void setUpdateTimestamp(Double updateTimestamp) {
        this.updateTimestamp = updateTimestamp;
    }

    public PreAfterMarketData getPreMarket() {
        return preMarket;
    }

    public void setPreMarket(PreAfterMarketData preMarket) {
        this.preMarket = preMarket;
    }

    public PreAfterMarketData getAfterMarket() {
        return afterMarket;
    }

    public void setAfterMarket(PreAfterMarketData afterMarket) {
        this.afterMarket = afterMarket;
    }

    public Integer getSecStatus() {
        return secStatus;
    }

    public void setSecStatus(Integer secStatus) {
        this.secStatus = secStatus;
    }

    public FutureBasicQotExData getFutureExData() {
        return futureExData;
    }

    public void setFutureExData(FutureBasicQotExData futureExData) {
        this.futureExData = futureExData;
    }
}
