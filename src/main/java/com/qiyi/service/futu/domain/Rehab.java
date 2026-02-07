package com.qiyi.service.futu.domain;

/**
 * 复权信息
 */
public class Rehab {
    /**
     * 时间字符串（格式：yyyy-MM-dd）
     */
    private String time;
    
    /**
     * 公司行动(CompanyAct)组合标志位,指定某些字段值是否有效
     */
    private Long companyActFlag;
    
    /**
     * 前复权因子 A
     */
    private Double fwdFactorA;
    
    /**
     * 前复权因子 B
     */
    private Double fwdFactorB;
    
    /**
     * 后复权因子 A
     */
    private Double bwdFactorA;
    
    /**
     * 后复权因子 B
     */
    private Double bwdFactorB;
    
    /**
     * 拆股(例如，1拆5，Base 为1，Ert 为5)
     */
    private Integer splitBase;
    
    private Integer splitErt;
    
    /**
     * 合股(例如，50合1，Base 为50，Ert 为1)
     */
    private Integer joinBase;
    
    private Integer joinErt;
    
    /**
     * 送股(例如，10送3, Base 为10,Ert 为3)
     */
    private Integer bonusBase;
    
    private Integer bonusErt;
    
    /**
     * 转赠股(例如，10转3, Base 为10,Ert 为3)
     */
    private Integer transferBase;
    
    private Integer transferErt;
    
    /**
     * 配股(例如，10送2, 配股价为6.3元, Base 为10, Ert 为2, Price 为6.3)
     */
    private Integer allotBase;
    
    private Integer allotErt;
    
    private Double allotPrice;
    
    /**
     * 增发股(例如，10送2, 增发股价为6.3元, Base 为10, Ert 为2, Price 为6.3)
     */
    private Integer addBase;
    
    private Integer addErt;
    
    private Double addPrice;
    
    /**
     * 现金分红(例如，每10股派现0.5元,则该字段值为0.05)
     */
    private Double dividend;
    
    /**
     * 特别股息(例如，每10股派特别股息0.5元,则该字段值为0.05)
     */
    private Double spDividend;
    
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

    public Long getCompanyActFlag() {
        return companyActFlag;
    }

    public void setCompanyActFlag(Long companyActFlag) {
        this.companyActFlag = companyActFlag;
    }

    public Double getFwdFactorA() {
        return fwdFactorA;
    }

    public void setFwdFactorA(Double fwdFactorA) {
        this.fwdFactorA = fwdFactorA;
    }

    public Double getFwdFactorB() {
        return fwdFactorB;
    }

    public void setFwdFactorB(Double fwdFactorB) {
        this.fwdFactorB = fwdFactorB;
    }

    public Double getBwdFactorA() {
        return bwdFactorA;
    }

    public void setBwdFactorA(Double bwdFactorA) {
        this.bwdFactorA = bwdFactorA;
    }

    public Double getBwdFactorB() {
        return bwdFactorB;
    }

    public void setBwdFactorB(Double bwdFactorB) {
        this.bwdFactorB = bwdFactorB;
    }

    public Integer getSplitBase() {
        return splitBase;
    }

    public void setSplitBase(Integer splitBase) {
        this.splitBase = splitBase;
    }

    public Integer getSplitErt() {
        return splitErt;
    }

    public void setSplitErt(Integer splitErt) {
        this.splitErt = splitErt;
    }

    public Integer getJoinBase() {
        return joinBase;
    }

    public void setJoinBase(Integer joinBase) {
        this.joinBase = joinBase;
    }

    public Integer getJoinErt() {
        return joinErt;
    }

    public void setJoinErt(Integer joinErt) {
        this.joinErt = joinErt;
    }

    public Integer getBonusBase() {
        return bonusBase;
    }

    public void setBonusBase(Integer bonusBase) {
        this.bonusBase = bonusBase;
    }

    public Integer getBonusErt() {
        return bonusErt;
    }

    public void setBonusErt(Integer bonusErt) {
        this.bonusErt = bonusErt;
    }

    public Integer getTransferBase() {
        return transferBase;
    }

    public void setTransferBase(Integer transferBase) {
        this.transferBase = transferBase;
    }

    public Integer getTransferErt() {
        return transferErt;
    }

    public void setTransferErt(Integer transferErt) {
        this.transferErt = transferErt;
    }

    public Integer getAllotBase() {
        return allotBase;
    }

    public void setAllotBase(Integer allotBase) {
        this.allotBase = allotBase;
    }

    public Integer getAllotErt() {
        return allotErt;
    }

    public void setAllotErt(Integer allotErt) {
        this.allotErt = allotErt;
    }

    public Double getAllotPrice() {
        return allotPrice;
    }

    public void setAllotPrice(Double allotPrice) {
        this.allotPrice = allotPrice;
    }

    public Integer getAddBase() {
        return addBase;
    }

    public void setAddBase(Integer addBase) {
        this.addBase = addBase;
    }

    public Integer getAddErt() {
        return addErt;
    }

    public void setAddErt(Integer addErt) {
        this.addErt = addErt;
    }

    public Double getAddPrice() {
        return addPrice;
    }

    public void setAddPrice(Double addPrice) {
        this.addPrice = addPrice;
    }

    public Double getDividend() {
        return dividend;
    }

    public void setDividend(Double dividend) {
        this.dividend = dividend;
    }

    public Double getSpDividend() {
        return spDividend;
    }

    public void setSpDividend(Double spDividend) {
        this.spDividend = spDividend;
    }

    public Double getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Double timestamp) {
        this.timestamp = timestamp;
    }
}
