package com.qiyi.service.futu.domain;

/**
 * K线数据
 */
public class KLine {
    /**
     * 时间戳字符串（格式：yyyy-MM-dd HH:mm:ss）
     */
    private String time;
    
    /**
     * 是否是空内容的点,若为 true 则只有时间信息
     */
    private boolean isBlank;
    
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
     * 收盘价
     */
    private Double closePrice;
    
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
     * 换手率（该字段为百分比字段，展示为小数表示）
     */
    private Double turnoverRate;
    
    /**
     * 市盈率
     */
    private Double pe;
    
    /**
     * 涨跌幅（该字段为百分比字段，默认不展示 %，如 20 实际对应 20%）
     */
    private Double changeRate;
    
    /**
     * 时间戳
     */
    private Double timestamp;

    public String getTime() {
        return time;
    }

    public void setTime(String time) {
        this.time = time;
    }

    public boolean isBlank() {
        return isBlank;
    }

    public void setBlank(boolean blank) {
        isBlank = blank;
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

    public Double getClosePrice() {
        return closePrice;
    }

    public void setClosePrice(Double closePrice) {
        this.closePrice = closePrice;
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

    public Double getPe() {
        return pe;
    }

    public void setPe(Double pe) {
        this.pe = pe;
    }

    public Double getChangeRate() {
        return changeRate;
    }

    public void setChangeRate(Double changeRate) {
        this.changeRate = changeRate;
    }

    public Double getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Double timestamp) {
        this.timestamp = timestamp;
    }
}
