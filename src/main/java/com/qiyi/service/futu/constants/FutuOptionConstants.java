package com.qiyi.service.futu.constants;

/**
 * FutuOptionConstants
 * Refactored from FutuQuoteConstants
 */
public class FutuOptionConstants {


    /**
     * 指数期权类型
     */
    public enum IndexOptionType {
        UNKNOWN(0, "未知"),
        NORMAL(1, "普通的指数期权"),
        SMALL(2, "小型指数期权");

        private final int code;
        private final String description;

        IndexOptionType(int code, String description) {
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
     * 期权地区类型
     */
    public enum OptionAreaType {
        UNKNOWN(0, "未知"),
        AMERICAN(1, "美式"),
        EUROPEAN(2, "欧式"),
        BERMUDA(3, "百慕大");

        private final int code;
        private final String description;

        OptionAreaType(int code, String description) {
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
     * 期权价内/价外
     */
    public enum OptionCondType {
        UNKNOWN(0, "未知"),
        WITH_IN(1, "价内"),
        OUTSIDE(2, "价外");

        private final int code;
        private final String description;

        OptionCondType(int code, String description) {
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
     * 期权类型
     */
    public enum OptionType {
        UNKNOWN(0, "未知"),
        CALL(1, "看涨期权"),
        PUT(2, "看跌期权");

        private final int code;
        private final String description;

        OptionType(int code, String description) {
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
