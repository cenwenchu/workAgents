package com.qiyi.tools;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class TaskProcessorHeuristicsTest {

    @BeforeAll
    public static void initTools() {
        ToolManager.registerTools();
    }

    @Test
    public void enrichPlannedTasks_fillsOrderIdForQueryErpOrder() throws Exception {
        JSONArray tasks = new JSONArray();
        JSONObject task = new JSONObject();
        task.put("tool", "query_erp_order");
        task.put("confidence", "high");
        task.put("parameters", new JSONObject());
        task.put("missing_info", "");
        tasks.add(task);

        TaskPlanEnricher.enrich("查一下订单1063063的信息", tasks);

        JSONObject params = tasks.getJSONObject(0).getJSONObject("parameters");
        assertNotNull(params);
        assertEquals("1063063", params.getString("orderId"));
    }

    @Test
    public void enrichPlannedTasks_fillsRecipientNameForSendMessage() throws Exception {
        JSONArray tasks = new JSONArray();
        JSONObject task = new JSONObject();
        task.put("tool", "send_message");
        task.put("confidence", "high");
        task.put("parameters", new JSONObject());
        task.put("missing_info", "");
        tasks.add(task);

        TaskPlanEnricher.enrich("把结果告诉小文", tasks);

        JSONObject params = tasks.getJSONObject(0).getJSONObject("parameters");
        assertNotNull(params);
        assertEquals("小文", params.getString("names"));
        assertEquals("{{PREV_RESULT}}", params.getString("content"));
    }

    @Test
    public void enrichPlannedTasks_fillsStockCodeForTencent() throws Exception {
        JSONArray tasks = new JSONArray();
        JSONObject task = new JSONObject();
        task.put("tool", "get_stock_quote");
        task.put("confidence", "high");
        task.put("parameters", new JSONObject());
        task.put("missing_info", "");
        tasks.add(task);

        TaskPlanEnricher.enrich("腾讯最新的股价情况", tasks);

        JSONObject params = tasks.getJSONObject(0).getJSONObject("parameters");
        assertNotNull(params);
        assertEquals("HK.00700", params.getString("code"));
    }

    @Test
    public void enrichPlannedTasks_fillsGroupNameForGroupStockQuotes() throws Exception {
        JSONArray tasks = new JSONArray();
        JSONObject task = new JSONObject();
        task.put("tool", "get_group_stock_quotes");
        task.put("confidence", "high");
        task.put("parameters", new JSONObject());
        task.put("missing_info", "");
        tasks.add(task);

        TaskPlanEnricher.enrich("我的港股核心里面的股票实时价格", tasks);

        JSONObject params = tasks.getJSONObject(0).getJSONObject("parameters");
        assertNotNull(params);
        assertEquals("港股核心", params.getString("groupName"));
    }
}
