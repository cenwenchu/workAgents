package com.qiyi.service.futu.constants;

/**
 * FutuMarketConstants
 * Refactored from FutuQuoteConstants
 */
public class FutuMarketConstants {


    /**
     * 暗盘状态
     */
    public enum DarkStatus {
        NONE(0, "无暗盘交易"),
        TRADING(1, "暗盘交易中"),
        END(2, "暗盘交易结束");

        private final int code;
        private final String description;

        DarkStatus(int code, String description) {
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
     * 市场状态
     */
    public enum MarketStatus {
        UNKNOWN(0, "未知"),
        OPEN(1, "盘中"),
        US_PRE(2, "美股盘前"),
        US_AFTER(3, "美股盘后");

        private final int code;
        private final String description;

        MarketStatus(int code, String description) {
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
     * 行情市场
     */
    public enum QotMarket {
        UNKNOWN(0, "未知市场"),
        HK_SECURITY(1, "香港市场"),
        HK_FUTURE(2, "港期货"), // 已废弃，使用 QotMarket_HK_Security 即可
        US_SECURITY(11, "美国市场"),
        CNSH_SECURITY(21, "沪股市场"),
        CNSZ_SECURITY(22, "深股市场"),
        SG_SECURITY(31, "新加坡市场"),
        JP_SECURITY(41, "日本市场"),
        AU_SECURITY(51, "澳大利亚市场"),
        MY_SECURITY(61, "马来西亚市场"),
        CA_SECURITY(71, "加拿大市场"),
        FX_SECURITY(81, "外汇市场");

        private final int code;
        private final String description;

        QotMarket(int code, String description) {
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
     * 行情市场状态
     */
    public enum QotMarketState {
        NONE(0, "无交易"),
        AUCTION(1, "盘前竞价"),
        WAITING_OPEN(2, "等待开盘"),
        MORNING(3, "早盘"),
        REST(4, "午间休市"),
        AFTERNOON(5, "午盘 / 美股持续交易时段"),
        CLOSED(6, "收盘"),
        PRE_MARKET_BEGIN(8, "美股盘前交易时段"),
        PRE_MARKET_END(9, "美股盘前交易结束"),
        AFTER_HOURS_BEGIN(10, "美股盘后交易时段"),
        AFTER_HOURS_END(11, "美股收盘"),
        NIGHT_OPEN(13, "夜市交易时段"),
        NIGHT_END(14, "夜市收盘"),
        FUTURE_DAY_OPEN(15, "日市交易时段"),
        FUTURE_DAY_BREAK(16, "日市休市"),
        FUTURE_DAY_CLOSE(17, "日市收盘"),
        FUTURE_DAY_WAIT_FOR_OPEN(18, "期货待开盘"),
        HK_CAS(19, "盘后竞价"), // 港股市场增加 CAS 机制对应的市场状态
        FUTURE_NIGHT_WAIT(20, "夜市等待开盘"), // 已废弃
        FUTURE_AFTERNOON(21, "期货下午开盘"), // 已废弃
        FUTURE_SWITCH_DATE(22, "美期待开盘"),
        FUTURE_OPEN(23, "美期交易时段"),
        FUTURE_BREAK(24, "美期中盘休息"),
        FUTURE_BREAK_OVER(25, "美期休息后交易时段"),
        FUTURE_CLOSE(26, "美期收盘"),
        STIB_AFTER_HOURS_WAIT(27, "科创板的盘后撮合时段"), // 已废弃
        STIB_AFTER_HOURS_BEGIN(28, "科创板的盘后交易开始"), // 已废弃
        STIB_AFTER_HOURS_END(29, "科创板的盘后交易结束"), // 已废弃
        NIGHT(32, "美指期权夜市交易时段"),
        TRADE_AT_LAST(35, "美指期权盘尾交易时段"),
        OVERNIGHT(37, "美股夜盘交易时段");

        private final int code;
        private final String description;

        QotMarketState(int code, String description) {
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
     * 交易时段
     */
    public enum Session {
        NONE(0, "未知"),
        RTH(1, "盘中"),
        ETH(2, "盘中+盘前盘后"),
        ALL(3, "全时段"),
        OVERNIGHT(4, "夜盘");

        private final int code;
        private final String description;

        Session(int code, String description) {
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
     * 交易日市场
     */
    public enum TradeDateMarket {
        UNKNOWN(0, "未知"),
        HK(1, "香港市场"),
        US(2, "美国市场"),
        CN(3, "A 股市场"),
        NT(4, "深（沪）股通"),
        ST(5, "港股通（深、沪）"),
        JP_FUTURE(6, "日本期货"),
        SG_FUTURE(7, "新加坡期货");

        private final int code;
        private final String description;

        TradeDateMarket(int code, String description) {
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
     * 交易日类型
     */
    public enum TradeDateType {
        WHOLE(0, "全天交易"),
        MORNING(1, "上午交易，下午休市"),
        AFTERNOON(2, "下午交易，上午休市");

        private final int code;
        private final String description;

        TradeDateType(int code, String description) {
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
     * 交易所类型
     */
    public enum ExchType {
        UNKNOWN(0, "未知"),
        HK_MAIN_BOARD(1, "港交所·主板"),
        HK_GEM_BOARD(2, "港交所·创业板"),
        HK_HKEX(3, "港交所"),
        US_NYSE(4, "纽交所"),
        US_NASDAQ(5, "纳斯达克"),
        US_PINK(6, "OTC 市场"),
        US_AMEX(7, "美交所"),
        US_OPTION(8, "美国（仅美股期权适用）"),
        US_NYMEX(9, "NYMEX"),
        US_COMEX(10, "COMEX"),
        US_CBOT(11, "CBOT"),
        US_CME(12, "CME"),
        US_CBOE(13, "CBOE"),
        CN_SH(14, "上交所"),
        CN_SZ(15, "深交所"),
        CN_STIB(16, "科创板"),
        SG_SGX(17, "新交所"),
        JP_OSE(18, "大阪交易所");

        private final int code;
        private final String description;

        ExchType(int code, String description) {
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
