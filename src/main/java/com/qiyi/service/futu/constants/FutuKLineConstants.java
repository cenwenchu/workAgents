package com.qiyi.service.futu.constants;

/**
 * FutuKLineConstants
 * Refactored from FutuQuoteConstants
 */
public class FutuKLineConstants {


    /**
     * 财务过滤属性周期
     */
    public enum FinancialQuarter {
        UNKNOWN(0, "未知"),
        ANNUAL(1, "年报"),
        FIRST_QUARTER(2, "一季报"),
        INTERIM(3, "中报"),
        THIRD_QUARTER(4, "三季报"),
        MOST_RECENT_QUARTER(5, "最近季报");

        private final int code;
        private final String description;

        FinancialQuarter(int code, String description) {
            this.code = code;
            this.description = description;
        }

        public int getCode() {
            return code;
        }

        public String getDescription() {
            return description;
        }
    }


    /**
     * K线类型
     */
    public enum KLType {
        UNKNOWN(0, "未知"),
        K_1M(1, "1分 K"),
        K_DAY(2, "日 K"),
        K_WEEK(3, "周 K"), // 期权暂不支持
        K_MONTH(4, "月 K"), // 期权暂不支持
        K_YEAR(5, "年 K"), // 期权暂不支持
        K_5M(6, "5分 K"),
        K_15M(7, "15分 K"),
        K_30M(8, "30分 K"), // 期权暂不支持
        K_60M(9, "60分 K"),
        K_3M(10, "3分 K"), // 期权暂不支持
        K_QUARTER(11, "季 K"); // 期权暂不支持

        private final int code;
        private final String description;

        KLType(int code, String description) {
            this.code = code;
            this.description = description;
        }

        public int getCode() {
            return code;
        }

        public String getDescription() {
            return description;
        }
    }


    /**
     * 周期类型
     */
    public enum PeriodType {
        INTRADAY(0, "实时"),
        DAY(1, "日"),
        WEEK(2, "周"),
        MONTH(3, "月");

        private final int code;
        private final String description;

        PeriodType(int code, String description) {
            this.code = code;
            this.description = description;
        }

        public int getCode() {
            return code;
        }

        public String getDescription() {
            return description;
        }
    }

}
