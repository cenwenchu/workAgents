package com.qiyi.service.futu.domain;

/**
 * 结算方式
 */
public enum OptionSettlementMode {
    OptionSettlementMode_Unknown(0, "未知"),
    OptionSettlementMode_AM(1, "AM"),
    OptionSettlementMode_PM(2, "PM");

    private int code;
    private String desc;

    OptionSettlementMode(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public int getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }

    public static OptionSettlementMode fromCode(int code) {
        for (OptionSettlementMode mode : values()) {
            if (mode.code == code) {
                return mode;
            }
        }
        return OptionSettlementMode_Unknown;
    }
}
