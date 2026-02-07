package com.qiyi.service.futu.constants;

/**
 * FutuUserConstants
 * Refactored from FutuQuoteConstants
 */
public class FutuUserConstants {


    /**
     * 分组类型
     */
    public enum GroupType {
        UNKNOWN(0, "未知"),
        CUSTOM(1, "自定义分组"),
        SYSTEM(2, "系统分组"),
        ALL(3, "全部分组");

        private final int code;
        private final String description;

        GroupType(int code, String description) {
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
     * 修改自选股操作
     */
    public enum ModifyUserSecurityOp {
        UNKNOWN(0, "未知"),
        ADD(1, "新增"),
        DEL(2, "删除自选"),
        MOVE_OUT(3, "移出分组");

        private final int code;
        private final String description;

        ModifyUserSecurityOp(int code, String description) {
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
     * 到价提醒频率
     */
    public enum PriceReminderFreq {
        UNKNOWN(0, "未知"),
        ALWAYS(1, "持续提醒"),
        ONCE_A_DAY(2, "每日一次"),
        ONLY_ONCE(3, "仅提醒一次");

        private final int code;
        private final String description;

        PriceReminderFreq(int code, String description) {
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
     * 到价提醒类型
     */
    public enum PriceReminderType {
        UNKNOWN(0, "未知"),
        PRICE_UP(1, "价格涨到"),
        PRICE_DOWN(2, "价格跌到"),
        CHANGE_RATE_UP(3, "日涨幅超"), // 该字段为百分比字段，设置时填 20 表示 20%
        CHANGE_RATE_DOWN(4, "日跌幅超"), // 该字段为百分比字段，设置时填 20 表示 20%
        CHANGE_RATE_5MIN_UP(5, "5 分钟涨幅超"), // 该字段为百分比字段，设置时填 20 表示 20%
        CHANGE_RATE_5MIN_DOWN(6, "5 分钟跌幅超"), // 该字段为百分比字段，设置时填 20 表示 20%
        VOLUME_UP(7, "成交量超过"),
        TURNOVER_UP(8, "成交额超过"),
        TURNOVER_RATE_UP(9, "换手率超过"), // 该字段为百分比字段，设置时填 20 表示 20%
        BID_PRICE_UP(10, "买一价高于"),
        ASK_PRICE_DOWN(11, "卖一价低于"),
        BID_VOL_UP(12, "买一量高于"),
        ASK_VOL_UP(13, "卖一量高于"),
        CHANGE_RATE_3MIN_UP(14, "3 分钟涨幅超"), // 该字段为百分比字段，设置时填 20 表示 20%
        CHANGE_RATE_3MIN_DOWN(15, "3 分钟跌幅超"); // 该字段为百分比字段，设置时填 20 表示 20%

        private final int code;
        private final String description;

        PriceReminderType(int code, String description) {
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
     * 行情权限
     */
    public enum QotRight {
        UNKNOWN(0, "未知"),
        BMP(1, "BMP"), // 此权限不支持订阅
        LEVEL1(2, "Level1"),
        LEVEL2(3, "Level2"),
        SF(4, "SF 高级行情"),
        NO(5, "无权限");

        private final int code;
        private final String description;

        QotRight(int code, String description) {
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
     * 设置到价提醒操作
     */
    public enum SetPriceReminderOp {
        UNKNOWN(0, "未知"),
        ADD(1, "新增"),
        DEL(2, "删除"),
        ENABLE(3, "启用"),
        DISABLE(4, "禁用"),
        MODIFY(5, "修改");

        private final int code;
        private final String description;

        SetPriceReminderOp(int code, String description) {
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
