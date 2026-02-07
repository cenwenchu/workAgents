package com.qiyi.service.futu.domain;

/**
 * 分时数据
 */
public class TimeShare {
    /**
     * 时间字符串（格式：yyyy-MM-dd HH:mm:ss）
     */
    private String time;
    
    /**
     * 距离0点过了多少分钟
     */
    private Integer minute;
    
    /**
     * 是否是空内容的点,若为 true 则只有时间信息
     */
    private boolean isBlank;
    
    /**
     * 当前价
     */
    private Double price;
    
    /**
     * 昨收价
     */
    private Double lastClosePrice;
    
    /**
     * 均价
     */
    private Double avgPrice;
    
    /**
     * 成交量
     */
    private Long volume;
    
    /**
     * 成交额
     */
    private Double turnover;
    
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

    public Integer getMinute() {
        return minute;
    }

    public void setMinute(Integer minute) {
        this.minute = minute;
    }

    public boolean isBlank() {
        return isBlank;
    }

    public void setBlank(boolean blank) {
        isBlank = blank;
    }

    public Double getPrice() {
        return price;
    }

    public void setPrice(Double price) {
        this.price = price;
    }

    public Double getLastClosePrice() {
        return lastClosePrice;
    }

    public void setLastClosePrice(Double lastClosePrice) {
        this.lastClosePrice = lastClosePrice;
    }

    public Double getAvgPrice() {
        return avgPrice;
    }

    public void setAvgPrice(Double avgPrice) {
        this.avgPrice = avgPrice;
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

    public Double getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Double timestamp) {
        this.timestamp = timestamp;
    }
}
