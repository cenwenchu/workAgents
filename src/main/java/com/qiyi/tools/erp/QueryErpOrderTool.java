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
import com.qiyi.tools.ToolMessenger;
import com.qiyi.util.PlayWrightUtil;
import java.util.Collections;
import com.qiyi.util.AppLog;

/**
 * ERP 订单查询工具。
 *
 * <p>规划补参：从用户输入中抽取订单号，写入 parameters.orderId。</p>
 */
@com.qiyi.tools.Tool.Info(
        name = "query_erp_order",
        description = "Query ERP order list. Checks login status on ERP page first, notifies if login is needed, then fetches data. Parameters: orderId (string, mandatory)."
)
public class QueryErpOrderTool extends ErpBaseTool {
    private static final String ERP_ORDER_PAGE_URL = "https://sc.scm121.com/tradeManage/tower/distribute";
    private static final String API_URL = "https://innerapi.scm121.com/api/inner/order/list";

    @Override
    public void enrichPlannedTask(String userText, JSONObject plannedTask) {
        if (plannedTask == null) return;
        JSONObject params = plannedTask.getJSONObject("parameters");
        if (params == null) {
            params = new JSONObject();
            plannedTask.put("parameters", params);
        }
        String orderId = params.getString("orderId");
        if (orderId == null || orderId.trim().isEmpty()) {
            String extracted = tryExtractOrderId(userText);
            if (extracted != null) {
                params.put("orderId", extracted);
            }
        }
    }

    @Override
    public String execute(JSONObject params, ToolContext context, ToolMessenger messenger) {
        String orderId = params != null ? params.getString("orderId") : null;
        if (orderId == null || orderId.isEmpty()) {
            sendTextSafe(messenger, "ERP订单查询缺少必填参数：订单号 (orderId)");
            return "Error: Missing orderId";
        }

        if (!TOOL_LOCK.tryLock()) {
            sendTextSafe(messenger, "ERP查询任务正在执行中，请稍后再试。");
            return "Error: Task locked";
        }

        PlayWrightUtil.Connection connection = null;
        try {
            sendTextSafe(messenger, "开始执行ERP订单查询任务...");

            connection = connectToBrowser();
            if (connection == null) {
                sendTextSafe(messenger, "无法连接到浏览器，任务终止");
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
                if (!ensureLogin(page, ERP_ORDER_PAGE_URL, messenger)) {
                    return "Error: Login failed";
                }

                // 2. Fetch Data
                return fetchData(page, messenger, orderId);

            } finally {
                page.close();
            }

        } catch (Exception e) {
            AppLog.error(e);
            sendTextSafe(messenger, "任务执行异常: " + e.getMessage());
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

    private String fetchData(Page page, ToolMessenger messenger, String orderId) {
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
                sendTextSafe(messenger, "接口返回非JSON数据 (Status " + status + "):\n" + body);
                return "Error: Non-JSON response (Status " + status + ")";
            }

            return handleApiResponse(messenger, status, bodyJson);

        } catch (Exception e) {
            AppLog.error(e);
            sendTextSafe(messenger, "获取数据失败: " + e.getMessage());
            return "Error: " + e.getMessage();
        }
    }

    private static String tryExtractOrderId(String text) {
        if (text == null) return null;
        String v = text.trim();
        if (v.isEmpty()) return null;
        java.util.regex.Matcher m1 = java.util.regex.Pattern
                .compile("(?i)orderId\\s*[:=]\\s*([0-9]{5,})")
                .matcher(v);
        if (m1.find()) return m1.group(1);
        java.util.regex.Matcher m2 = java.util.regex.Pattern
                .compile("(?:订单|order)\\s*#?\\s*([0-9]{5,})", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(v);
        if (m2.find()) return m2.group(1);
        return null;
    }

    private String handleApiResponse(ToolMessenger messenger, int status, JSONObject bodyJson) {
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
                                sendTextSafe(messenger, "查询成功，但未找到匹配的订单记录 (data is empty)。");
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
                             sendTextSafe(messenger, "查询成功 (未知数据结构):\n" + displayData);
                             return "Unknown data structure: " + displayData;
                        }

                        String finalOutput = resultBuilder.toString();
                        // Truncate if too long for DingTalk (limit usually ~2000-4000 chars, keeping safe)
                        if (finalOutput.length() > 3500) {
                             finalOutput = finalOutput.substring(0, 3500) + "\n...(内容过长已截断)";
                        }
                        
                        sendTextSafe(messenger, "查询成功，找到 " + count + " 条记录:\n" + finalOutput);
                        return finalOutput;

                    } else {
                        sendTextSafe(messenger, "查询成功，但返回数据为空 (data is null)。");
                        return "Data is null";
                    }
                } else {
                    String msg = bodyJson.getString("message");
                    if ("empty token".equals(msg)) {
                        sendTextSafe(messenger, "查询失败: 认证Token丢失 (empty token)。\n建议：请在浏览器中刷新页面或重新登录。");
                    } else {
                        sendTextSafe(messenger, "查询失败 (Business Error): " + msg);
                    }
                    return "Error: " + msg;
                }
            } else {
                sendTextSafe(messenger, "接口调用失败 (Status " + status + "):\n" + bodyJson.toJSONString());
                return "Error: Status " + status;
            }
        } catch (Exception e) {
            AppLog.error(e);
            sendTextSafe(messenger, "处理响应结果失败: " + e.getMessage());
            return "Error: " + e.getMessage();
        }
    }

    private void sendTextSafe(ToolMessenger messenger, String content) {
        if (messenger == null) return;
        try {
            messenger.sendText(content);
        } catch (Exception ignored) {
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
