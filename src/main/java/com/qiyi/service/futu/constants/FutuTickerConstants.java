package com.qiyi.service.futu.constants;

/**
 * FutuTickerConstants
 * Refactored from FutuQuoteConstants
 */
public class FutuTickerConstants {


    /**
     * 相对位置
     */
    public enum RelativePosition {
        UNKNOWN(0, "未知"),
        MORE(1, "大于，first位于second的上方"),
        LESS(2, "小于，first位于second的下方"),
        CROSS_UP(3, "升穿，first从下往上穿second"),
        CROSS_DOWN(4, "跌穿，first从上往下穿second");

        private final int code;
        private final String description;

        RelativePosition(int code, String description) {
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
     * 推送数据类型
     */
    public enum PushDataType {
        UNKNOWN(0, "未知"),
        REALTIME(1, "实时推送的数据"),
        BY_DISCONN(2, "对后台行情连接断开期间拉取补充的数据"), // 最多50个
        CACHE(3, "非实时非连接断开补充数据");

        private final int code;
        private final String description;

        PushDataType(int code, String description) {
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
     * 排序方向
     */
    public enum SortDir {
        ASCEND(0, "升序"),
        DESCEND(1, "降序");

        private final int code;
        private final String description;

        SortDir(int code, String description) {
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
     * 定阅类型
     */
    public enum SubType {
        NONE(0, "无"),
        BASIC(1, "基础报价"),
        ORDER_BOOK(2, "摆盘"),
        TICKER(4, "逐笔"),
        RT(5, "分时"),
        KL_DAY(6, "日 K"),
        KL_5MIN(7, "5分 K"),
        KL_15MIN(8, "15分 K"),
        KL_30MIN(9, "30分 K"),
        KL_60MIN(10, "60分 K"),
        KL_1MIN(11, "1分 K"),
        KL_WEEK(12, "周 K"),
        KL_MONTH(13, "月 K"),
        BROKER(14, "经纪队列"),
        KL_QUARTER(15, "季 K"),
        KL_YEAR(16, "年 K"),
        KL_3MIN(17, "3分 K");

        private final int code;
        private final String description;

        SubType(int code, String description) {
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
     * 逐笔方向
     */
    public enum TickerDirection {
        UNKNOWN(0, "未知"),
        BID(1, "外盘（主动买入）"),
        ASK(2, "内盘（主动卖出）"),
        NEUTRAL(3, "中性盘");

        private final int code;
        private final String description;

        TickerDirection(int code, String description) {
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
     * 逐笔类型
     */
    public enum TickerType {
        UNKNOWN(0, "未知"),
        AUTOMATCH(1, "自动对盘"),
        LATE(2, "开市前成交盘"),
        NONE_AUTOMATCH(3, "非自动对盘"),
        INTER_AUTOMATCH(4, "同一证券商自动对盘"),
        INTER_NONE_AUTOMATCH(5, "同一证券商非自动对盘"),
        ODD_LOT(6, "碎股交易"),
        AUCTION(7, "竞价交易"),
        BULK(8, "批量交易"),
        CRASH(9, "现金交易"),
        CROSS_MARKET(10, "跨市场交易"),
        BULK_SOLD(11, "批量卖出"),
        FREE_ON_BOARD(12, "离价交易"),
        RULE_127_OR_155(13, "第127条交易（纽交所规则）或第155条交易"),
        DELAY(14, "延迟交易"),
        MARKET_CENTER_CLOSE_PRICE(15, "中央收市价"),
        NEXT_DAY(16, "隔日交易"),
        MARKET_CENTER_OPENING(17, "中央开盘价交易"),
        PRIOR_REFERENCE_PRICE(18, "前参考价"),
        MARKET_CENTER_OPEN_PRICE(19, "中央开盘价"),
        SELLER(20, "卖方"),
        T(21, "T 类交易(盘前和盘后交易)"),
        EXTENDED_TRADING_HOURS(22, "延长交易时段"),
        CONTINGENT(23, "合单交易"),
        AVG_PRICE(24, "平均价成交"),
        OTC_SOLD(25, "场外售出"),
        ODD_LOT_CROSS_MARKET(26, "碎股跨市场交易"),
        DERIVATIVELY_PRICED(27, "衍生工具定价"),
        RE_OPENING_PRICED(28, "再开盘定价"),
        CLOSING_PRICED(29, "收盘定价"),
        COMPREHENSIVE_DELAY_PRICE(30, "综合延迟价格"),
        OVERSEAS(31, "场外交易");

        private final int code;
        private final String description;

        TickerType(int code, String description) {
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
