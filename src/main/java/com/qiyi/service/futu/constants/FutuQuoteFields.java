package com.qiyi.service.futu.constants;

/**
 * FutuQuoteFields
 * Refactored from FutuQuoteConstants
 */
public class FutuQuoteFields {


    /**
     * 股票属性
     */
    public enum StockField {
        UNKNOWN(0, "未知"),
        STOCK_CODE(1, "股票代码"),
        STOCK_NAME(2, "股票名称"),
        CUR_PRICE(3, "最新价"),
        CUR_PRICE_TO_HIGHEST_52_WEEKS_RATIO(4, "(现价 - 52周最高)/52周最高"),
        CUR_PRICE_TO_LOWEST_52_WEEKS_RATIO(5, "(现价 - 52周最低)/52周最低"),
        HIGH_PRICE_TO_HIGHEST_52_WEEKS_RATIO(6, "(今日最高 - 52周最高)/52周最高"),
        LOW_PRICE_TO_LOWEST_52_WEEKS_RATIO(7, "(今日最低 - 52周最低)/52周最低"),
        VOLUME_RATIO(8, "量比"),
        BID_ASK_RATIO(9, "委比"),
        LOT_PRICE(10, "每手价格"),
        MARKET_VAL(11, "市值"),
        PE_ANNUAL(12, "市盈率(静态)"),
        PE_TTM(13, "市盈率 TTM"),
        PB_RATE(14, "市净率"),
        CHANGE_RATE_5MIN(15, "五分钟价格涨跌幅"),
        CHANGE_RATE_BEGIN_YEAR(16, "年初至今价格涨跌幅"),
        PSTTM(17, "市销率(TTM)"),
        PCFTTM(18, "市现率(TTM)"),
        TOTAL_SHARE(19, "总股数"),
        FLOAT_SHARE(20, "流通股数"),
        FLOAT_MARKET_VAL(21, "流通市值");

        private final int code;
        private final String description;

        StockField(int code, String description) {
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
     * 累积过滤属性
     */
    public enum AccumulateField {
        UNKNOWN(0, "未知"),
        CHANGE_RATE(1, "涨跌幅"), // 例如填写[-10.2,20.4]值区间
        AMPLITUDE(2, "振幅"), // 例如填写[0.5,20.6]值区间
        VOLUME(3, "日均成交量"), // 例如填写[2000,70000]值区间
        TURNOVER(4, "日均成交额"), // 例如填写[1400,890000]值区间
        TURNOVER_RATE(5, "换手率"); // 例如填写[2,30]值区间

        private final int code;
        private final String description;

        AccumulateField(int code, String description) {
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
     * 财务属性
     */
    public enum FinancialField {
        // 基础财务属性
        UNKNOWN(0, "未知"),
        NET_PROFIT(1, "净利润"), // 例如填写[100000000,2500000000]值区间
        NET_PROFIT_GROWTH(2, "净利润增长率"), // 例如填写[-10,300]值区间
        SUM_OF_BUSINESS(3, "营业收入"), // 例如填写[100000000,6400000000]值区间
        SUM_OF_BUSINESS_GROWTH(4, "营收同比增长率"), // 例如填写[-5,200]值区间
        NET_PROFIT_RATE(5, "净利率"), // 例如填写[10,113]值区间
        GROSS_PROFIT_RATE(6, "毛利率"), // 例如填写[4,65]值区间
        DEBT_ASSETS_RATE(7, "资产负债率"), // 例如填写[5,470]值区间
        RETURN_ON_EQUITY_RATE(8, "净资产收益率"), // 例如填写[20,230]值区间

        // 盈利能力属性
        ROIC(9, "投入资本回报率"), // 例如填写 [1.0,10.0] 值区间
        ROA_TTM(10, "资产回报率(TTM)"), // 例如填写 [1.0,10.0] 值区间
        EBIT_TTM(11, "息税前利润(TTM)"), // 例如填写 [1000000000,1000000000] 值区间
        EBITDA(12, "税息折旧及摊销前利润"), // 例如填写 [1000000000,1000000000] 值区间
        OPERATING_MARGIN_TTM(13, "营业利润率(TTM)"), // 例如填写 [1.0,10.0] 值区间
        EBIT_MARGIN(14, "EBIT 利润率"), // 例如填写 [1.0,10.0] 值区间
        EBITDA_MARGIN(15, "EBITDA 利润率"), // 例如填写 [1.0,10.0] 值区间
        FINANCIAL_COST_RATE(16, "财务成本率"), // 例如填写 [1.0,10.0] 值区间
        OPERATING_PROFIT_TTM(17, "营业利润(TTM)"), // 例如填写 [1000000000,1000000000] 值区间
        SHAREHOLDER_NET_PROFIT_TTM(18, "归属于母公司的净利润"), // 例如填写 [1000000000,1000000000] 值区间
        NET_PROFIT_CASH_COVER_TTM(19, "盈利中的现金收入比例"), // 例如填写 [1.0,60.0] 值区间

        // 偿债能力属性
        CURRENT_RATIO(20, "流动比率"), // 例如填写 [100,250] 值区间
        QUICK_RATIO(21, "速动比率"), // 例如填写 [100,250] 值区间

        // 清债能力属性
        CURRENT_ASSET_RATIO(22, "流动资产率"), // 例如填写 [10,100] 值区间
        CURRENT_DEBT_RATIO(23, "流动负债率"), // 例如填写 [10,100] 值区间
        EQUITY_MULTIPLIER(24, "权益乘数"), // 例如填写 [100,180] 值区间
        PROPERTY_RATIO(25, "产权比率"), // 例如填写 [50,100] 值区间
        CASH_AND_CASH_EQUIVALENTS(26, "现金和现金等价"), // 例如填写 [1000000000,1000000000] 值区间

        // 运营能力属性
        TOTAL_ASSET_TURNOVER(27, "总资产周转率"), // 例如填写 [50,100] 值区间
        FIXED_ASSET_TURNOVER(28, "固定资产周转率"), // 例如填写 [50,100] 值区间
        INVENTORY_TURNOVER(29, "存货周转率"), // 例如填写 [50,100] 值区间
        OPERATING_CASH_FLOW_TTM(30, "经营活动现金流(TTM)"), // 例如填写 [1000000000,1000000000] 值区间
        ACCOUNTS_RECEIVABLE(31, "应收账款净额"), // 例如填写 [1000000000,1000000000] 值区间

        // 成长能力属性
        EBIT_GROWTH_RATE(32, "EBIT 同比增长率"), // 例如填写 [1.0,10.0] 值区间
        OPERATING_PROFIT_GROWTH_RATE(33, "营业利润同比增长率"), // 例如填写 [1.0,10.0] 值区间
        TOTAL_ASSETS_GROWTH_RATE(34, "总资产同比增长率"), // 例如填写 [1.0,10.0] 值区间
        PROFIT_TO_SHAREHOLDERS_GROWTH_RATE(35, "归母净利润同比增长率"), // 例如填写 [1.0,10.0] 值区间
        PROFIT_BEFORE_TAX_GROWTH_RATE(36, "总利润同比增长率"), // 例如填写 [1.0,10.0] 值区间
        EPS_GROWTH_RATE(37, "EPS 同比增长率"), // 例如填写 [1.0,10.0] 值区间
        ROE_GROWTH_RATE(38, "ROE 同比增长率"), // 例如填写 [1.0,10.0] 值区间
        ROIC_GROWTH_RATE(39, "ROIC 同比增长率"), // 例如填写 [1.0,10.0] 值区间
        NOCF_GROWTH_RATE(40, "经营现金流同比增长率"), // 例如填写 [1.0,10.0] 值区间
        NOCF_PER_SHARE_GROWTH_RATE(41, "每股经营现金流同比增长率"), // 例如填写 [1.0,10.0] 值区间

        // 现金流属性
        OPERATING_REVENUE_CASH_COVER(42, "经营现金收入比"), // 例如填写 [10,100] 值区间
        OPERATING_PROFIT_TO_TOTAL_PROFIT(43, "营业利润占比"), // 例如填写 [10,100] 值区间

        // 市场表现属性
        BASIC_EPS(44, "基本每股收益"), // 例如填写 [0.1,10] 值区间
        DILUTED_EPS(45, "稀释每股收益"), // 例如填写 [0.1,10] 值区间
        NOCF_PER_SHARE(46, "每股经营现金净流量"); // 例如填写 [0.1,10] 值区间

        private final int code;
        private final String description;

        FinancialField(int code, String description) {
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
     * 自定义技术指标属性
     */
    public enum CustomIndicatorField {
        UNKNOWN(0, "未知"),
        PRICE(1, "最新价格"),
        MA5(2, "5日简单均线（不建议使用）"),
        MA10(3, "10日简单均线（不建议使用）"),
        MA20(4, "20日简单均线（不建议使用）"),
        MA30(5, "30日简单均线（不建议使用）"),
        MA60(6, "60日简单均线（不建议使用）"),
        MA120(7, "120日简单均线（不建议使用）"),
        MA250(8, "250日简单均线（不建议使用）"),
        RSI(9, "RSI 指标参数的默认值为[12]"),
        EMA5(10, "5日指数移动均线（不建议使用）"),
        EMA10(11, "10日指数移动均线（不建议使用）"),
        EMA20(12, "20日指数移动均线（不建议使用）"),
        EMA30(13, "30日指数移动均线（不建议使用）"),
        EMA60(14, "60日指数移动均线（不建议使用）"),
        EMA120(15, "120日指数移动均线（不建议使用）"),
        EMA250(16, "250日指数移动均线（不建议使用）"),
        VALUE(17, "自定义数值（stock_field1不支持此字段）"),
        MA(30, "简单均线"),
        EMA(40, "指数移动均线"),
        KDJ_K(50, "KDJ 指标的 K 值"), // 指标参数需要根据 KDJ 进行传参。不传则默认为 [9,3,3]
        KDJ_D(51, "KDJ 指标的 D 值"), // 指标参数需要根据 KDJ 进行传参。不传则默认为 [9,3,3]
        KDJ_J(52, "KDJ 指标的 J 值"), // 指标参数需要根据 KDJ 进行传参。不传则默认为 [9,3,3]
        MACD_DIFF(60, "MACD 指标的 DIFF 值"), // 指标参数需要根据 MACD 进行传参。不传则默认为 [12,26,9]
        MACD_DEA(61, "MACD 指标的 DEA 值"), // 指标参数需要根据 MACD 进行传参。不传则默认为 [12,26,9]
        MACD(62, "MACD 指标的 MACD 值"), // 指标参数需要根据 MACD 进行传参。不传则默认为 [12,26,9]
        BOLL_UPPER(70, "BOLL 指标的 UPPER 值"), // 指标参数需要根据 BOLL 进行传参。不传则默认为 [20,2]
        BOLL_MIDDLER(71, "BOLL 指标的 MIDDLER 值"), // 指标参数需要根据 BOLL 进行传参。不传则默认为 [20,2]
        BOLL_LOWER(72, "BOLL 指标的 LOWER 值"); // 指标参数需要根据 BOLL 进行传参。不传则默认为 [20,2]

        private final int code;
        private final String description;

        CustomIndicatorField(int code, String description) {
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
     * 形态技术指标属性
     */
    public enum PatternField {
        UNKNOWN(0, "未知"),
        MA_ALIGNMENT_LONG(1, "MA多头排列"), // 连续两天MA5>MA10>MA20>MA30>MA60，且当日收盘价大于前一天收盘价
        MA_ALIGNMENT_SHORT(2, "MA空头排列"), // 连续两天MA5 <MA10 <MA20 <MA30 <MA60，且当日收盘价小于前一天收盘价
        EMA_ALIGNMENT_LONG(3, "EMA多头排列"), // 连续两天EMA5>EMA10>EMA20>EMA30>EMA60，且当日收盘价大于前一天收盘价
        EMA_ALIGNMENT_SHORT(4, "EMA空头排列"), // 连续两天EMA5 <EMA10 <EMA20 <EMA30 <EMA60，且当日收盘价小于前一天收盘价
        RSI_GOLD_CROSS_LOW(5, "RSI低位金叉"), // 50以下，短线RSI上穿长线RSI
        RSI_DEATH_CROSS_HIGH(6, "RSI高位死叉"), // 50以上，短线RSI下穿长线RSI
        RSI_TOP_DIVERGENCE(7, "RSI顶背离"), // 相邻的两个K线波峰，后面的波峰对应的CLOSE>前面的波峰对应的CLOSE，后面波峰的RSI12值 <前面波峰的RSI12值
        RSI_BOTTOM_DIVERGENCE(8, "RSI底背离"), // 相邻的两个K线波谷，后面的波谷对应的CLOSE <前面的波谷对应的CLOSE，后面波谷的RSI12值>前面波谷的RSI12值
        KDJ_GOLD_CROSS_LOW(9, "KDJ低位金叉"), // KDJ的值都小于或等于30，且前一日K,J值分别小于D值，当日K,J值分别大于D值
        KDJ_DEATH_CROSS_HIGH(10, "KDJ高位死叉"), // KDJ的值都大于或等于70，且前一日K,J值分别大于D值，当日K,J值分别小于D值
        KDJ_TOP_DIVERGENCE(11, "KDJ顶背离"), // 相邻的两个K线波峰，后面的波峰对应的CLOSE>前面的波峰对应的CLOSE，后面波峰的J值 <前面波峰的J值
        KDJ_BOTTOM_DIVERGENCE(12, "KDJ底背离"), // 相邻的两个K线波谷，后面的波谷对应的CLOSE <前面的波谷对应的CLOSE，后面波谷的J值>前面波谷的J值
        MACD_GOLD_CROSS_LOW(13, "MACD低位金叉"), // DIFF上穿DEA
        MACD_DEATH_CROSS_HIGH(14, "MACD高位死叉"), // DIFF下穿DEA
        MACD_TOP_DIVERGENCE(15, "MACD顶背离"), // 相邻的两个K线波峰，后面的波峰对应的CLOSE>前面的波峰对应的CLOSE，后面波峰的macd值 <前面波峰的macd值
        MACD_BOTTOM_DIVERGENCE(16, "MACD底背离"), // 相邻的两个K线波谷，后面的波谷对应的CLOSE <前面的波谷对应的CLOSE，后面波谷的macd值>前面波谷的macd值
        BOLL_BREAK_UPPER(17, "BOLL突破上轨"), // 前一日股价低于上轨值，当日股价大于上轨值
        BOLL_LOWER(18, "BOLL突破下轨"), // 前一日股价高于下轨值，当日股价小于下轨值
        BOLL_CROSS_MIDDLE_UP(19, "BOLL向上破中轨"), // 前一日股价低于中轨值，当日股价大于中轨值
        BOLL_CROSS_MIDDLE_DOWN(20, "BOLL向下破中轨"); // 前一日股价大于中轨值，当日股价小于中轨值

        private final int code;
        private final String description;

        PatternField(int code, String description) {
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
     * K线字段
     */
    public enum KLFields {
        NONE(0, "无"),
        HIGH(1, "最高价"),
        OPEN(2, "开盘价"),
        LOW(4, "最低价"),
        CLOSE(8, "收盘价"),
        LAST_CLOSE(16, "昨收价"),
        VOLUME(32, "成交量"),
        TURNOVER(64, "成交额"),
        TURNOVER_RATE(128, "换手率"),
        PE(256, "市盈率"),
        CHANGE_RATE(512, "涨跌幅");

        private final int code;
        private final String description;

        KLFields(int code, String description) {
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
     * 排序字段
     */
    public enum SortField {
        UNKNOWN(0, "未知"),
        CODE(1, "代码"),
        CUR_PRICE(2, "最新价"),
        CHANGE_RATE(3, "涨跌幅"),
        LAST_CLOSE_PRICE(4, "昨收价"),
        OPEN_PRICE(5, "开盘价"),
        LOW_PRICE(6, "最低价"),
        HIGH_PRICE(7, "最高价"),
        AMPLITUDE(8, "振幅"),
        VOLUME(9, "成交量"),
        TURNOVER(10, "成交额"),
        TURNOVER_RATE(11, "换手率"),
        PE(12, "市盈率"),
        CHANGE_VAL(13, "涨跌额"),
        PE_TTM(14, "市盈率TTM"),
        SUB_TYPE(15, "窝轮子类型"),
        ISSUER(16, "发行人"),
        MATURITY_TIME(17, "到期日"),
        STRIKE_PRICE(18, "行权价"),
        STREET_RATE(19, "街货占比"),
        PREMIUM(20, "溢价"),
        STREET_VOL(21, "街货量"),
        OPTION_IMPLIED_VOLATILITY(22, "期权隐含波动率"),
        OPTION_DELTA(23, "期权Delta"),
        OPTION_GAMMA(24, "期权Gamma"),
        OPTION_VEGA(25, "期权Vega"),
        OPTION_THETA(26, "期权Theta"),
        OPTION_RHO(27, "期权Rho"),
        NET_ASSET_VALUE(28, "净值"),
        EXPIRY_DATE_DISTANCE(29, "到期日距离"),
        DIVIDEND_TIME(30, "派息日"),
        STREET_VAL(31, "街货市值"),
        PERCENT(32, "百分比"),
        LIST_TIME(33, "上市日"),
        VALUATION(34, "估值"),
        GENE_AMPLITUDE(35, "振幅（基因）"),
        GENE_AVERAGE(36, "均线（基因）"),
        GENE_TURNOVER_RATE(37, "换手率（基因）"),
        GENE_MACD(38, "MACD（基因）"),
        GENE_KDJ(39, "KDJ（基因）"),
        GENE_RSI(40, "RSI（基因）");

        private final int code;
        private final String description;

        SortField(int code, String description) {
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
