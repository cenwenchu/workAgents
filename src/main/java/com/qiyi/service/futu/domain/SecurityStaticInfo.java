package com.qiyi.service.futu.domain;

/**
 * 证券静态信息
 */
public class SecurityStaticInfo {
    /**
     * 证券基本静态信息
     */
    private SecurityStaticBasic basic;
    
    /**
     * 窝轮额外静态信息
     */
    private WarrantStaticExData warrantExData;
    
    /**
     * 期权额外静态信息
     */
    private OptionStaticExData optionExData;
    
    /**
     * 期货额外静态信息
     */
    private FutureStaticExData futureExData;

    public SecurityStaticBasic getBasic() {
        return basic;
    }

    public void setBasic(SecurityStaticBasic basic) {
        this.basic = basic;
    }

    public WarrantStaticExData getWarrantExData() {
        return warrantExData;
    }

    public void setWarrantExData(WarrantStaticExData warrantExData) {
        this.warrantExData = warrantExData;
    }

    public OptionStaticExData getOptionExData() {
        return optionExData;
    }

    public void setOptionExData(OptionStaticExData optionExData) {
        this.optionExData = optionExData;
    }

    public FutureStaticExData getFutureExData() {
        return futureExData;
    }

    public void setFutureExData(FutureStaticExData futureExData) {
        this.futureExData = futureExData;
    }
}
