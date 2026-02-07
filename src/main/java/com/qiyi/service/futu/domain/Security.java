package com.qiyi.service.futu.domain;

/**
 * 股票标识
 */
public class Security {
    /**
     * QotMarket,股票市场
     */
    private int market;
    
    /**
     * 股票代码
     */
    private String code;

    public int getMarket() {
        return market;
    }

    public void setMarket(int market) {
        this.market = market;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }
}
