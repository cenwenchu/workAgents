package com.qiyi.service.futu.domain;

/**
 * 期权特有字段
 */
public class OptionBasicQotExData {
    /**
     * 行权价
     */
    private Double strikePrice;
    
    /**
     * 每份合约数(整型数据)
     */
    private Integer contractSize;
    
    /**
     * 每份合约数（浮点型数据）
     */
    private Double contractSizeFloat;
    
    /**
     * 未平仓合约数
     */
    private Integer openInterest;
    
    /**
     * 隐含波动率（该字段为百分比字段，默认不展示 %，如 20 实际对应 20%）
     */
    private Double impliedVolatility;
    
    /**
     * 溢价（该字段为百分比字段，默认不展示 %，如 20 实际对应 20%）
     */
    private Double premium;
    
    /**
     * 希腊值 Delta
     */
    private Double delta;
    
    /**
     * 希腊值 Gamma
     */
    private Double gamma;
    
    /**
     * 希腊值 Vega
     */
    private Double vega;
    
    /**
     * 希腊值 Theta
     */
    private Double theta;
    
    /**
     * 希腊值 Rho
     */
    private Double rho;
    
    /**
     * 净未平仓合约数，仅港股期权适用
     */
    private Integer netOpenInterest;
    
    /**
     * 距离到期日天数，负数表示已过期
     */
    private Integer expiryDateDistance;
    
    /**
     * 合约名义金额，仅港股期权适用
     */
    private Double contractNominalValue;
    
    /**
     * 相等正股手数，指数期权无该字段，仅港股期权适用
     */
    private Double ownerLotMultiplier;
    
    /**
     * OptionAreaType，期权类型（按行权时间）
     */
    private Integer optionAreaType;
    
    /**
     * 合约乘数
     */
    private Double contractMultiplier;
    
    /**
     * IndexOptionType，指数期权类型
     */
    private Integer indexOptionType;

    public Double getStrikePrice() {
        return strikePrice;
    }

    public void setStrikePrice(Double strikePrice) {
        this.strikePrice = strikePrice;
    }

    public Integer getContractSize() {
        return contractSize;
    }

    public void setContractSize(Integer contractSize) {
        this.contractSize = contractSize;
    }

    public Double getContractSizeFloat() {
        return contractSizeFloat;
    }

    public void setContractSizeFloat(Double contractSizeFloat) {
        this.contractSizeFloat = contractSizeFloat;
    }

    public Integer getOpenInterest() {
        return openInterest;
    }

    public void setOpenInterest(Integer openInterest) {
        this.openInterest = openInterest;
    }

    public Double getImpliedVolatility() {
        return impliedVolatility;
    }

    public void setImpliedVolatility(Double impliedVolatility) {
        this.impliedVolatility = impliedVolatility;
    }

    public Double getPremium() {
        return premium;
    }

    public void setPremium(Double premium) {
        this.premium = premium;
    }

    public Double getDelta() {
        return delta;
    }

    public void setDelta(Double delta) {
        this.delta = delta;
    }

    public Double getGamma() {
        return gamma;
    }

    public void setGamma(Double gamma) {
        this.gamma = gamma;
    }

    public Double getVega() {
        return vega;
    }

    public void setVega(Double vega) {
        this.vega = vega;
    }

    public Double getTheta() {
        return theta;
    }

    public void setTheta(Double theta) {
        this.theta = theta;
    }

    public Double getRho() {
        return rho;
    }

    public void setRho(Double rho) {
        this.rho = rho;
    }

    public Integer getNetOpenInterest() {
        return netOpenInterest;
    }

    public void setNetOpenInterest(Integer netOpenInterest) {
        this.netOpenInterest = netOpenInterest;
    }

    public Integer getExpiryDateDistance() {
        return expiryDateDistance;
    }

    public void setExpiryDateDistance(Integer expiryDateDistance) {
        this.expiryDateDistance = expiryDateDistance;
    }

    public Double getContractNominalValue() {
        return contractNominalValue;
    }

    public void setContractNominalValue(Double contractNominalValue) {
        this.contractNominalValue = contractNominalValue;
    }

    public Double getOwnerLotMultiplier() {
        return ownerLotMultiplier;
    }

    public void setOwnerLotMultiplier(Double ownerLotMultiplier) {
        this.ownerLotMultiplier = ownerLotMultiplier;
    }

    public Integer getOptionAreaType() {
        return optionAreaType;
    }

    public void setOptionAreaType(Integer optionAreaType) {
        this.optionAreaType = optionAreaType;
    }

    public Double getContractMultiplier() {
        return contractMultiplier;
    }

    public void setContractMultiplier(Double contractMultiplier) {
        this.contractMultiplier = contractMultiplier;
    }

    public Integer getIndexOptionType() {
        return indexOptionType;
    }

    public void setIndexOptionType(Integer indexOptionType) {
        this.indexOptionType = indexOptionType;
    }
}
