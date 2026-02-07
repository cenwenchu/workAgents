package com.qiyi.service.futu.domain;

/**
 * 证券基本静态信息
 */
public class SecurityStaticBasic {
    /**
     * 股票
     */
    private Security security;
    
    /**
     * 股票 ID
     */
    private Long id;
    
    /**
     * 每手数量,期权类型表示一份合约的股数
     */
    private Integer lotSize;
    
    /**
     * Qot_Common.SecurityType,股票类型
     */
    private Integer secType;
    
    /**
     * 股票名字
     */
    private String name;
    
    /**
     * 上市时间字符串（此字段停止维护，不建议使用，格式：yyyy-MM-dd）
     */
    private String listTime;
    
    /**
     * 是否退市
     */
    private Boolean delisting;
    
    /**
     * 上市时间戳（此字段停止维护，不建议使用）
     */
    private Double listTimestamp;
    
    /**
     * Qot_Common.ExchType,所属交易所
     */
    private Integer exchType;

    public Security getSecurity() {
        return security;
    }

    public void setSecurity(Security security) {
        this.security = security;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Integer getLotSize() {
        return lotSize;
    }

    public void setLotSize(Integer lotSize) {
        this.lotSize = lotSize;
    }

    public Integer getSecType() {
        return secType;
    }

    public void setSecType(Integer secType) {
        this.secType = secType;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getListTime() {
        return listTime;
    }

    public void setListTime(String listTime) {
        this.listTime = listTime;
    }

    public Boolean getDelisting() {
        return delisting;
    }

    public void setDelisting(Boolean delisting) {
        this.delisting = delisting;
    }

    public Double getListTimestamp() {
        return listTimestamp;
    }

    public void setListTimestamp(Double listTimestamp) {
        this.listTimestamp = listTimestamp;
    }

    public Integer getExchType() {
        return exchType;
    }

    public void setExchType(Integer exchType) {
        this.exchType = exchType;
    }
}
