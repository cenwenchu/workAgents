package com.qiyi.service.futu.domain;

/**
 * 期货特有字段
 */
public class FutureBasicQotExData {
    /**
     * 昨结
     */
    private Double lastSettlePrice;
    
    /**
     * 持仓量
     */
    private Integer position;
    
    /**
     * 日增仓
     */
    private Integer positionChange;
    
    /**
     * 距离到期日天数
     */
    private Integer expiryDateDistance;

    public Double getLastSettlePrice() {
        return lastSettlePrice;
    }

    public void setLastSettlePrice(Double lastSettlePrice) {
        this.lastSettlePrice = lastSettlePrice;
    }

    public Integer getPosition() {
        return position;
    }

    public void setPosition(Integer position) {
        this.position = position;
    }

    public Integer getPositionChange() {
        return positionChange;
    }

    public void setPositionChange(Integer positionChange) {
        this.positionChange = positionChange;
    }

    public Integer getExpiryDateDistance() {
        return expiryDateDistance;
    }

    public void setExpiryDateDistance(Integer expiryDateDistance) {
        this.expiryDateDistance = expiryDateDistance;
    }
}
