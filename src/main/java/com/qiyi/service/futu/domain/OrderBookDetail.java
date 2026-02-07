package com.qiyi.service.futu.domain;

/**
 * 订单信息，港股 SF，美股深度摆盘特有
 */
public class OrderBookDetail {
    /**
     * 交易所订单 ID，与交易接口返回的订单 ID 并不一样
     */
    private Long orderID;
    
    /**
     * 订单股数
     */
    private Long volume;

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
