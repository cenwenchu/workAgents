package com.qiyi.service.futu.domain;

/**
 * 经纪
 */
public class Broker {
    /**
     * 经纪 ID
     */
    private Long id;
    
    /**
     * 经纪名称
     */
    private String name;

    /**
     * 经纪档位
     */
    private Integer pos;

    /**
     * 交易所订单 ID，与交易接口返回的订单 ID 并不一样
     */
    private Long orderID;

    /**
     * 订单股数
     */
    private Long volume;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Integer getPos() {
        return pos;
    }

    public void setPos(Integer pos) {
        this.pos = pos;
    }

    public Long getOrderID() {
        return orderID;
    }

    public void setOrderID(Long orderID) {
        this.orderID = orderID;
    }

    public Long getVolume() {
        return volume;
    }

    public void setVolume(Long volume) {
        this.volume = volume;
    }
}
