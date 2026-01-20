package com.qiyi.tools.erp;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONWriter;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.APIRequestContext;
import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.options.RequestOptions;
import com.qiyi.tools.ToolContext;
import com.qiyi.util.PlayWrightUtil;
import java.util.Collections;

public class QueryErpOrderTool extends ErpBaseTool {
    private static final String ERP_ORDER_PAGE_URL = "https://sc.scm121.com/tradeManage/tower/distribute";
    private static final String API_URL = "https://innerapi.scm121.com/api/inner/order/list";

    @Override
    public String getName() {
        return "query_erp_order";
    }

    @Override
    public String getDescription() {
        return "Query ERP order list. Checks login status on ERP page first, notifies if login is needed, then fetches data. Parameters: orderId (string, mandatory).";
    }

    @Override
    public String execute(JSONObject params, ToolContext context) {
        String orderId = params != null ? params.getString("orderId") : null;
        if (orderId == null || orderId.isEmpty()) {
            try {
                context.sendText("ERP订单查询缺少必填参数：订单号 (orderId)");
            } catch (Exception e) {
                e.printStackTrace();
            }
            return "Error: Missing orderId";
        }

        if (!TOOL_LOCK.tryLock()) {
            try {
                context.sendText("ERP查询任务正在执行中，请稍后再试。");
            } catch (Exception e) {
                e.printStackTrace();
            }
            return "Error: Task locked";
        }

        PlayWrightUtil.Connection connection = null;
        try {
            context.sendText("开始执行ERP订单查询任务...");

            connection = connectToBrowser();
            if (connection == null) {
                context.sendText("无法连接到浏览器，任务终止");
                return "Error: Browser connection failed";
            }

            BrowserContext browserContext;
            if (connection.browser.contexts().isEmpty()) {
                browserContext = connection.browser.newContext();
            } else {
                browserContext = connection.browser.contexts().get(0);
            }

            Page page = browserContext.newPage();
            try {
                // 1. Check Login using base class method
                if (!ensureLogin(page, ERP_ORDER_PAGE_URL, context)) {
                    return "Error: Login failed";
                }

                // 2. Fetch Data
                return fetchData(page, context, orderId);

            } finally {
                page.close();
            }

        } catch (Exception e) {
            e.printStackTrace();
            try {
                context.sendText("任务执行异常: " + e.getMessage());
            } catch (Exception ex) {
                ex.printStackTrace();
            }
            return "Error: " + e.getMessage();
        } finally {
            disconnectBrowser(connection);
            TOOL_LOCK.unlock();
        }
    }

    @Override
    protected boolean isLoginSuccess(Page page) {
        // Override with specific logic for Order page if needed, 
        // or just rely on base class. 
        // For safety, let's keep the original logic which was specific to "全部订单"
        try {
             Locator userElement = page.frameLocator("iframe") 
                    .locator("//div[contains(text(),'全部订单')]").first();
             userElement.waitFor(new Locator.WaitForOptions().setTimeout(1000));
             return true;
        } catch (Exception e) {
            // If specific check fails, fallback to base class check?
            // Or maybe the base class check is better because it handles more cases.
            // Let's fallback to base class check if this one fails, or just return false?
            // Original code returned false.
            // Let's call super to see if it finds other indicators.
            return super.isLoginSuccess(page);
        }
    }

    private String fetchData(Page page, ToolContext context, String orderId) {
        try {
            // Construct payload
            JSONObject payload = new JSONObject();
            payload.put("ascOrDesc", false);
            payload.put("coId", "10533653");
            payload.put("dateQueryType", "OrderDate");
            payload.put("noteType", "NOFILTER");
            payload.put("oidList", Collections.singletonList(orderId));
            payload.put("orderByKey", 0);
            payload.put("orderTypeEnum", "ALL");
            payload.put("pageNum", 1);
            payload.put("pageSize", 50);
            payload.put("searchType", 1);
            payload.put("uid", "11449363");

            // Use APIRequestContext
            APIRequestContext request = page.context().request();
            
            // Create options using base class helper
            RequestOptions options = createApiRequestOptions(page, payload, "https://sc.scm121.com/tradeManage/tower/distribute");

            APIResponse response = request.post(API_URL, options);
            int status = response.status();
            String body = response.text();
            
            JSONObject bodyJson = null;
            try {
                bodyJson = JSONObject.parseObject(body);
            } catch (Exception e) {
                try {
                    context.sendText("接口返回非JSON数据 (Status " + status + "):\n" + body);
                } catch (Exception ex) { ex.printStackTrace(); }
                return "Error: Non-JSON response (Status " + status + ")";
            }

            return handleApiResponse(context, status, bodyJson);

        } catch (Exception e) {
            e.printStackTrace();
            try {
                context.sendText("获取数据失败: " + e.getMessage());
            } catch (Exception ex) { ex.printStackTrace(); }
            return "Error: " + e.getMessage();
        }
    }

    private String handleApiResponse(ToolContext context, int status, JSONObject bodyJson) {
        try {
            if (status == 200) {
                boolean success = bodyJson.getBooleanValue("success");
                if (success) {
                    Object data = bodyJson.get("data");
                    if (data != null) {
                        StringBuilder resultBuilder = new StringBuilder();
                        int count = 0;

                        if (data instanceof JSONArray) {
                            JSONArray dataArray = (JSONArray) data;
                            if (dataArray.isEmpty()) {
                                context.sendText("查询成功，但未找到匹配的订单记录 (data is empty)。");
                                return "No records found";
                            }
                            count = dataArray.size();
                            for (int i = 0; i < dataArray.size(); i++) {
                                if (i > 0) resultBuilder.append("\n----------------------------------------\n");
                                resultBuilder.append(formatOrderInfo(dataArray.getJSONObject(i)));
                            }
                        } else if (data instanceof JSONObject) {
                            count = 1;
                            resultBuilder.append(formatOrderInfo((JSONObject) data));
                        } else {
                             // Fallback for unexpected type
                             String displayData = JSON.toJSONString(data, JSONWriter.Feature.PrettyFormat);
                             if (displayData.length() > 2000) {
                                  displayData = displayData.substring(0, 2000) + "\n...(truncated)";
                             }
                             context.sendText("查询成功 (未知数据结构):\n" + displayData);
                             return "Unknown data structure: " + displayData;
                        }

                        String finalOutput = resultBuilder.toString();
                        // Truncate if too long for DingTalk (limit usually ~2000-4000 chars, keeping safe)
                        if (finalOutput.length() > 3500) {
                             finalOutput = finalOutput.substring(0, 3500) + "\n...(内容过长已截断)";
                        }
                        
                        context.sendText("查询成功，找到 " + count + " 条记录:\n" + finalOutput);
                        return finalOutput;

                    } else {
                        context.sendText("查询成功，但返回数据为空 (data is null)。");
                        return "Data is null";
                    }
                } else {
                    String msg = bodyJson.getString("message");
                    if ("empty token".equals(msg)) {
                        context.sendText("查询失败: 认证Token丢失 (empty token)。\n建议：请在浏览器中刷新页面或重新登录。");
                    } else {
                        context.sendText("查询失败 (Business Error): " + msg);
                    }
                    return "Error: " + msg;
                }
            } else {
                context.sendText("接口调用失败 (Status " + status + "):\n" + bodyJson.toJSONString());
                return "Error: Status " + status;
            }
        } catch (Exception e) {
            e.printStackTrace();
            try {
                context.sendText("处理响应结果失败: " + e.getMessage());
            } catch (Exception ex) { ex.printStackTrace(); }
            return "Error: " + e.getMessage();
        }
    }

    private String formatOrderInfo(JSONObject order) {
        if (order == null) return "";
        StringBuilder sb = new StringBuilder();
        
        // Basic Fields
        sb.append("订单ID: ").append(order.getString("oid")).append("\n");
        sb.append("平台: ").append(order.getString("shopSite")).append("\n");
        sb.append("店铺名称: ").append(order.getString("shopName")).append("\n");
        sb.append("供应商名称: ").append(order.getString("supplierName")).append("\n");
        sb.append("订单状态: ").append(order.getString("orderStatus")).append("\n");
        sb.append("订单类型: ").append(order.getString("orderType")).append("\n");
        sb.append("订单标签: ").append(order.getString("labels")).append("\n");
        sb.append("客户下单金额: ").append(order.getString("paidAmount")).append("\n");
        sb.append("分销采购金额: ").append(order.getString("drpAmount")).append("\n");
        sb.append("订单商品数量: ").append(order.getString("goodsQty")).append("\n");
        sb.append("订单支付时间: ").append(order.getString("payTime")).append("\n");
        sb.append("物流发货时间: ").append(order.getString("deliveryDate")).append("\n");
        sb.append("快递公司: ").append(order.getString("expressCompany")).append("\n");
        sb.append("物流单号: ").append(order.getString("trackNo")).append("\n");

        // Goods List
        JSONArray goodsList = order.getJSONArray("disInnerOrderGoodsViewList");
        if (goodsList != null && !goodsList.isEmpty()) {
            sb.append("商品列表:\n");
            for (int i = 0; i < goodsList.size(); i++) {
                JSONObject item = goodsList.getJSONObject(i);
                sb.append("  - 商品名称: ").append(item.getString("itemName")).append("\n");
                sb.append("    商品数量: ").append(item.getString("itemCount")).append("\n");
                sb.append("    商品规格: ").append(item.getString("properties")).append("\n");
                sb.append("    总价: ").append(item.getString("totalPrice")).append("\n");
            }
        } else {
            sb.append("商品列表: (空)\n");
        }

        return sb.toString();
    }
}