package com.qiyi.service.futu.domain;

/**
 * 交割周期
 */
public enum ExpirationCycle {
    ExperationCycle_Unknow(0, "未知"),
    ExperationCycle_Week(1, "周期权"),
    ExperationCycle_Month(2, "月期权"),
    ExpirationCycle_MonthEnd(3, "月末期权"),
    ExpirationCycle_Quarter(4, "季度期权"),
    ExpirationCycle_WeekMon(11, "周一"),
    ExpirationCycle_WeekTue(12, "周二"),
    ExpirationCycle_WeekWed(13, "周三"),
    ExpirationCycle_WeekThu(14, "周四"),
    ExpirationCycle_WeekFri(15, "周五");

    private int code;
    private String desc;

    ExpirationCycle(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public int getCode() {
        return code;
    }

    public String getDesc() {
        return desc;
    }

    public static ExpirationCycle fromCode(int code) {
        for (ExpirationCycle cycle : values()) {
            if (cycle.code == code) {
                return cycle;
            }
        }
        return ExperationCycle_Unknow;
    }
}
