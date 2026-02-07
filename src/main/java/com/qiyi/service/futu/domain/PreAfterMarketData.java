package com.qiyi.service.futu.domain;

/**
 * 盘前盘后数据
 * 美股支持盘前盘后数据
 * 科创板仅支持盘后数据：成交量，成交额
 */
public class PreAfterMarketData {
    /**
     * 盘前或盘后 价格
     */
    private Double price;
    
    /**
     * 盘前或盘后 最高价
     */
    private Double highPrice;
    
    /**
     * 盘前或盘后 最低价
     */
    private Double lowPrice;
    
    /**
     * 盘前或盘后 成交量
     */
    private Long volume;
    
    /**
     * 盘前或盘后 成交额
     */
    private Double turnover;
    
    /**
     * 盘前或盘后 涨跌额
     */
    private Double changeVal;
    
    /**
     * 盘前或盘后 涨跌幅（该字段为百分比字段，默认不展示 %，如 20 实际对应 20%）
     */
    private Double changeRate;
    
    /**
     * 盘前或盘后 振幅（该字段为百分比字段，默认不展示 %，如 20 实际对应 20%）
     */
    private Double amplitude;

    public Double getPrice() {
        return price;
    }

    public void setPrice(Double price) {
        this.price = price;
    }

    public Double getHighPrice() {
        return highPrice;
    }

    public void setHighPrice(Double highPrice) {
        this.highPrice = highPrice;
    }

    public Double getLowPrice() {
        return lowPrice;
    }

    public void setLowPrice(Double lowPrice) {
        this.lowPrice = lowPrice;
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

    public Double getChangeVal() {
        return changeVal;
    }

    public void setChangeVal(Double changeVal) {
        this.changeVal = changeVal;
    }

    public Double getChangeRate() {
        return changeRate;
    }

    public void setChangeRate(Double changeRate) {
        this.changeRate = changeRate;
    }

    public Double getAmplitude() {
        return amplitude;
    }

    public void setAmplitude(Double amplitude) {
        this.amplitude = amplitude;
    }
}
