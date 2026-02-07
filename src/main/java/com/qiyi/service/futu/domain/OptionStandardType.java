package com.qiyi.service.futu.domain;

/**
 * 标准期权
 */
public enum OptionStandardType {
    OptionStandardType_Unknown(0, "未知"),
    OptionStandardType_Standard(1, "标准"),
    OptionStandardType_NonStandard(2, "非标准");

    private int code;
    private String desc;

    OptionStandardType(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public int getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }

    public static OptionStandardType fromCode(int code) {
        for (OptionStandardType type : values()) {
            if (type.code == code) {
                return type;
            }
        }
        return OptionStandardType_Unknown;
    }
}
