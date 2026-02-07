package com.qiyi.service.futu.constants;

/**
 * FutuSecurityConstants
 * Refactored from FutuQuoteConstants
 */
public class FutuSecurityConstants {


    /**
     * 资产类别
     */
    public enum AssetClass {
        UNKNOWN(0, "未知"),
        STOCK(1, "股票"),
        BOND(2, "债券"),
        COMMODITY(3, "商品"),
        CURRENCY_MARKET(4, "货币市场"),
        FUTURE(5, "期货"),
        SWAP(6, "掉期（互换）");

        private final int code;
        private final String description;

        AssetClass(int code, String description) {
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
     * 公司行动
     */
    public enum CompanyAct {
        NONE(0, "无"),
        SPLIT(1, "拆股"),
        JOIN(2, "合股"),
        BONUS(4, "送股"),
        TRANSFER(8, "转赠股"),
        ALLOT(16, "配股"),
        ADD(32, "增发股"),
        DIVIDEND(64, "现金分红"),
        SP_DIVIDEND(128, "特别股息");

        private final int code;
        private final String description;

        CompanyAct(int code, String description) {
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
     * IPO 上市周期
     */
    public enum IpoPeriod {
        UNKNOWN(0, "未知"),
        TODAY(1, "今日上市"),
        TOMORROW(2, "明日上市"),
        NEXT_WEEK(3, "未来一周上市"),
        LAST_WEEK(4, "过去一周上市"),
        LAST_MONTH(5, "过去一月上市");

        private final int code;
        private final String description;

        IpoPeriod(int code, String description) {
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
     * 板块集合类型
     */
    public enum PlateSetType {
        ALL(0, "所有板块"),
        INDUSTRY(1, "行业板块"),
        REGION(2, "地域板块"), // 港美股市场的地域分类数据暂为空
        CONCEPT(3, "概念板块"),
        OTHER(4, "其他板块"); // 仅用于3207（获取股票所属板块）协议返回,不可作为其他协议的请求参数

        private final int code;
        private final String description;

        PlateSetType(int code, String description) {
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
     * 关联类型
     */
    public enum ReferenceType {
        UNKNOWN(0, "未知"),
        WARRANT(1, "正股相关的窝轮"),
        FUTURE(2, "期货主连的相关合约");

        private final int code;
        private final String description;

        ReferenceType(int code, String description) {
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
     * 复权类型
     */
    public enum RehabType {
        NONE(0, "不复权"),
        FORWARD(1, "前复权"),
        BACKWARD(2, "后复权");

        private final int code;
        private final String description;

        RehabType(int code, String description) {
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
     * 股票状态
     */
    public enum SecurityStatus {
        UNKNOWN(0, "未知"),
        NORMAL(1, "正常"),
        LISTING(2, "待上市"),
        PURCHASING(3, "申购中"),
        SUBSCRIBING(4, "认购中"),
        BEFORE_DARK_TRADE_OPEN(5, "暗盘开盘前"),
        DARK_TRADING(6, "暗盘交易中"),
        DARK_TRADED(7, "暗盘已收盘"),
        LISTED(8, "已上市"),
        DELISTING(9, "待退市"),
        DELISTED(10, "已退市"),
        SUSPENDED(11, "停牌"),
        CALL_MARGIN(12, "追保"),
        FORCE_LIQUIDATION(13, "强平"),
        EXCHANGE_TRADING_SUSPENDED(14, "交易所已停牌"),
        EXCHANGE_TRADING_RESUMED(15, "交易所已复牌"),
        EXCHANGE_TRADING_HALTED(16, "交易所已临时停牌"),
        EXCHANGE_TRADING_UNHALTED(17, "交易所已取消临时停牌");

        private final int code;
        private final String description;

        SecurityStatus(int code, String description) {
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
     * 证券类型
     */
    public enum SecurityType {
        UNKNOWN(0, "未知"),
        BOND(1, "债券"),
        BWRT(2, "一篮子权证"),
        EQTY(3, "正股"),
        TRUST(4, "信托,基金"),
        WARRANT(5, "窝轮"),
        INDEX(6, "指数"),
        PLATE(7, "板块"),
        DRVT(8, "期权"),
        PLATE_SET(9, "板块集"),
        FUTURE(10, "期货");

        private final int code;
        private final String description;

        SecurityType(int code, String description) {
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
