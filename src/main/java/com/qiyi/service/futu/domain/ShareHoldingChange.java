package com.qiyi.service.futu.domain;

/**
 * 持股变动
 */
public class ShareHoldingChange {
    /**
     * 持有者名称（机构名称 或 基金名称 或 高管姓名）
     */
    private String holderName;
    
    /**
     * 当前持股数量
     */
    private Double holdingQty;
    
    /**
     * 当前持股百分比（该字段为百分比字段，默认不展示 %，如 20 实际对应 20%）
     */
    private Double holdingRatio;
    
    /**
     * 较上一次变动数量
     */
    private Double changeQty;
    
    /**
     * 较上一次变动百分比（该字段为百分比字段，默认不展示 %，如20实际对应20%。是相对于自身的比例，而不是总的。如总股本1万股，持有100股，持股百分比是1%，卖掉50股，变动比例是50%，而不是0.5%）
     */
    private Double changeRatio;
    
    /**
     * 发布时间（格式：yyyy-MM-dd HH:mm:ss）
     */
    private String time;
    
    /**
     * 时间戳
     */
    private Double timestamp;

    public String getHolderName() {
        return holderName;
    }

    public void setHolderName(String holderName) {
        this.holderName = holderName;
    }

    public Double getHoldingQty() {
        return holdingQty;
    }

    public void setHoldingQty(Double holdingQty) {
        this.holdingQty = holdingQty;
    }

    public Double getHoldingRatio() {
        return holdingRatio;
    }

    public void setHoldingRatio(Double holdingRatio) {
        this.holdingRatio = holdingRatio;
    }

    public Double getChangeQty() {
        return changeQty;
    }

    public void setChangeQty(Double changeQty) {
        this.changeQty = changeQty;
    }

    public Double getChangeRatio() {
        return changeRatio;
    }

    public void setChangeRatio(Double changeRatio) {
        this.changeRatio = changeRatio;
    }

    public String getTime() {
        return time;
    }

    public void setTime(String time) {
        this.time = time;
    }

    public Double getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Double timestamp) {
        this.timestamp = timestamp;
    }
}
