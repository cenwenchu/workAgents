package com.qiyi.service.futu.domain;

/**
 * 逐笔数据
 */
public class Ticker {
    /**
     * 时间字符串（格式：yyyy-MM-dd HH:mm:ss）
     */
    private String time;
    
    /**
     * 唯一标识
     */
    private Long sequence;
    
    /**
     * TickerDirection, 买卖方向
     */
    private Integer dir;
    
    /**
     * 价格
     */
    private Double price;
    
    /**
     * 成交量
     */
    private Long volume;
    
    /**
     * 成交额
     */
    private Double turnover;
    
    /**
     * 收到推送数据的本地时间戳，用于定位延迟
     */
    private Double recvTime;
    
    /**
     * TickerType, 逐笔类型
     */
    private Integer type;
    
    /**
     * 逐笔类型符号
     */
    private Integer typeSign;
    
    /**
     * 用于区分推送情况，仅推送时有该字段
     */
    private Integer pushDataType;
    
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

    public Long getSequence() {
        return sequence;
    }

    public void setSequence(Long sequence) {
        this.sequence = sequence;
    }

    public Integer getDir() {
        return dir;
    }

    public void setDir(Integer dir) {
        this.dir = dir;
    }

    public Double getPrice() {
        return price;
    }

    public void setPrice(Double price) {
        this.price = price;
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

    public Double getRecvTime() {
        return recvTime;
    }

    public void setRecvTime(Double recvTime) {
        this.recvTime = recvTime;
    }

    public Integer getType() {
        return type;
    }

    public void setType(Integer type) {
        this.type = type;
    }

    public Integer getTypeSign() {
        return typeSign;
    }

    public void setTypeSign(Integer typeSign) {
        this.typeSign = typeSign;
    }

    public Integer getPushDataType() {
        return pushDataType;
    }

    public void setPushDataType(Integer pushDataType) {
        this.pushDataType = pushDataType;
    }

    public Double getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Double timestamp) {
        this.timestamp = timestamp;
    }
}
