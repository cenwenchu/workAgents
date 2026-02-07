package com.qiyi.service.futu.domain;

import java.util.List;

/**
 * 委托订单
 */
public class OrderBook {
    /**
     * 委托价格
     */
    private Double price;
    
    /**
     * 委托数量
     */
    private Long volume;
    
    /**
     * 委托订单个数
     */
    private Integer orederCount;
    
    /**
     * 订单信息，港股 SF，美股深度摆盘特有
     */
    private List<OrderBookDetail> detailList;

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

    public Integer getOrederCount() {
        return orederCount;
    }

    public void setOrederCount(Integer orederCount) {
        this.orederCount = orederCount;
    }

    public List<OrderBookDetail> getDetailList() {
        return detailList;
    }

    public void setDetailList(List<OrderBookDetail> detailList) {
        this.detailList = detailList;
    }
}
