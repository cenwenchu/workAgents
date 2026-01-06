package com.qiyi.tools;


import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONArray;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.APIRequestContext;
import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.options.RequestOptions;
import com.qiyi.util.DingTalkUtil;
import com.qiyi.util.PlayWrightUtil;

import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;
import java.time.LocalDate;
import java.time.ZoneId;

public class ErpAfterSaleTool extends ErpBaseTool {
    private static final String PAGE_URL = "https://sc.scm121.com/dataCenter/afterSaleEarlyWarning";
    
    // Mapping for readable field names
    private static final Map<String, String> FIELD_MAPPING = new HashMap<>();
    static {
        FIELD_MAPPING.put("wait_intercept_cnt", "待拦截总数");
        FIELD_MAPPING.put("wait_intercept_no_collected_cnt", "待拦截未揽收");
        FIELD_MAPPING.put("wait_intercept_transiting_cnt", "待拦截运输中");
        FIELD_MAPPING.put("wait_intercept_delivering_cnt", "待拦截派送中");
        FIELD_MAPPING.put("wait_intercept_sign_cnt", "待拦截已签收");
        FIELD_MAPPING.put("wait_intercept_lid_noupdate_cnt", "待拦截单号未更新");
        FIELD_MAPPING.put("intercept_transiting_cnt", "拦截中运输中");
        FIELD_MAPPING.put("buyer_back_cnt", "买家退回");
        FIELD_MAPPING.put("wait_inbound_cnt", "待入库");
        // Add more mappings as needed based on the JSON keys
    }

    @Override
    public String getName() {
        return "query_erp_aftersale";
    }

    @Override
    public String getDescription() {
        return "Query ERP After-Sales Early Warning data. Fetches statistics about returns, intercepts, etc. Parameters: none (defaults to today's data).";
    }

    @Override
    public String execute(JSONObject params, String senderId, List<String> atUserIds) {
        List<String> notifyUsers = new ArrayList<>();
        if (senderId != null) notifyUsers.add(senderId);
        if (atUserIds != null && !atUserIds.isEmpty()) {
            notifyUsers.addAll(atUserIds);
        }

        if (!TOOL_LOCK.tryLock()) {
            try {
                DingTalkUtil.sendTextMessageToEmployees(notifyUsers, "ERP查询任务正在执行中，请稍后再试。");
            } catch (Exception e) {
                e.printStackTrace();
            }
            return "Error: Task locked";
        }

        PlayWrightUtil.Connection connection = null;
        try {
            DingTalkUtil.sendTextMessageToEmployees(notifyUsers, "开始执行ERP售后预警查询任务...");

            connection = PlayWrightUtil.connectAndAutomate();
            if (connection == null) {
                DingTalkUtil.sendTextMessageToEmployees(notifyUsers, "无法连接到浏览器，任务终止");
                return "Error: Browser connection failed";
            }

            BrowserContext context;
            if (connection.browser.contexts().isEmpty()) {
                context = connection.browser.newContext();
            } else {
                context = connection.browser.contexts().get(0);
            }

            Page page = context.newPage();
            try {
                // 1. Check Login
                if (!ensureLogin(page, PAGE_URL, notifyUsers)) {
                    return "Error: Login failed";
                }

                // 2. Fetch Data
                return fetchData(page, notifyUsers);

            } finally {
                page.close();
            }

        } catch (Exception e) {
            e.printStackTrace();
            try {
                DingTalkUtil.sendTextMessageToEmployees(notifyUsers, "任务执行异常: " + e.getMessage());
            } catch (Exception ex) {
                ex.printStackTrace();
            }
            return "Error: " + e.getMessage();
        } finally {
            if (connection != null) {
                PlayWrightUtil.disconnectBrowser(connection.playwright, connection.browser);
            }
            TOOL_LOCK.unlock();
        }
    }

    private String fetchData(Page page, List<String> notifyUsers) {
        try {
            // Capture the real API request to get the URL and Template Payload
            // We reload the page to trigger the request
            Request[] capturedRequest = new Request[1];
            
            try {
                page.waitForRequest(request -> {
                    String url = request.url();
                    if (url.contains("innerapi.scm121.com") && request.method().equalsIgnoreCase("POST")) {
                        String postData = request.postData();
                        if (postData != null && postData.contains("aftersale_alarm_logistics_summary")) {
                            capturedRequest[0] = request;
                            return true;
                        }
                    }
                    return false;
                }, new Page.WaitForRequestOptions().setTimeout(10000), () -> {
                    page.reload();
                });
            } catch (Exception e) {
                System.out.println("Wait for request timeout or failed: " + e.getMessage());
            }

            String apiUrl;
            JSONObject payload;

            if (capturedRequest[0] != null) {
                apiUrl = capturedRequest[0].url();
                try {
                    payload = JSONObject.parseObject(capturedRequest[0].postData());
                } catch (Exception e) {
                    // Fallback payload if parsing fails
                    payload = createDefaultPayload();
                }
            } else {
                // Fallback if capture failed
                System.out.println("Could not capture API request, using default guess.");
                apiUrl = "https://innerapi.scm121.com/api/inner/data/center/query"; // Guess
                // Or try finding it from network logs if capturedApiHeaders has it? No, that's just headers.
                // Let's hope the default payload works or the capture works.
                payload = createDefaultPayload();
            }
            
            // Update Payload with Time
            updatePayloadTime(payload);

            // Send Request
            APIRequestContext requestContext = page.context().request();
            RequestOptions options = createApiRequestOptions(page, payload, PAGE_URL);

            System.out.println("Sending request to: " + apiUrl);
            APIResponse response = requestContext.post(apiUrl, options);
            
            int status = response.status();
            String body = response.text();
            
            JSONObject bodyJson = null;
            try {
                bodyJson = JSONObject.parseObject(body);
            } catch (Exception e) {
                return "Error: Non-JSON response (Status " + status + ")";
            }

            return handleApiResponse(notifyUsers, status, bodyJson);

        } catch (Exception e) {
            e.printStackTrace();
            return "Error: " + e.getMessage();
        }
    }

    private JSONObject createDefaultPayload() {
        // Based on user provided example
        String json = "{ \n" +
                "     \"coid\": \"\", \n" +
                "     \"data\": { \n" +
                "         \"apiCode\": \"aftersale_alarm_logistics_summary\", \n" +
                "         \"apiParams\": { \n" +
                "             \"buyerReturnLcCreatedTime\": 24, \n" +
                "             \"buyerReturnLIdNoUpdateTime\": 48, \n" +
                "             \"endTime\": 1767715199000, \n" +
                "             \"interceptReturnLIdNoUpdateTime\": 48, \n" +
                "             \"interceptReturnStartLevel1TimeMax\": 24, \n" +
                "             \"interceptReturnStartLevel1TimeMin\": 0, \n" +
                "             \"interceptReturnStartLevel2TimeMin\": 24, \n" +
                "             \"refundBuyerReturnLcCreatedTime\": 24, \n" +
                "             \"refundLIdNoUpdateTime\": 48, \n" +
                "             \"saleType\": \"total\", \n" +
                "             \"startTime\": 1767542400000, \n" +
                "             \"timeType\": \"MERGED_AS_DATE\", \n" +
                "             \"waitInterceptLIdNoUpdateTime\": 48 \n" +
                "         }, \n" +
                "         \"bizDomain\": \"aftersaleAlarm\", \n" +
                "         \"debounce\": true, \n" +
                "         \"pageCode\": \"AFTERSALE_ALARM\", \n" +
                "         \"sysEnv\": \"GYL_SUP\" \n" +
                "     }, \n" +
                "     \"uid\": \"\" \n" +
                " }";
        return JSONObject.parseObject(json);
    }

    private void updatePayloadTime(JSONObject payload) {
        try {
            JSONObject data = payload.getJSONObject("data");
            if (data != null) {
                JSONObject apiParams = data.getJSONObject("apiParams");
                if (apiParams != null) {
                    // Set to Today 00:00:00 to 23:59:59
                    LocalDate today = LocalDate.now();
                    long startOfDay = today.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli();
                    long endOfDay = today.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() - 1000;
                    
                    apiParams.put("startTime", startOfDay);
                    apiParams.put("endTime", endOfDay);
                }
            }
        } catch (Exception e) {
            System.err.println("Failed to update payload time: " + e.getMessage());
        }
    }

    private String handleApiResponse(List<String> notifyUsers, int status, JSONObject bodyJson) {
        try {
            if (status == 200) {
                boolean success = bodyJson.getBooleanValue("success");
                if (success) {
                    Object data = bodyJson.get("data");
                    if (data != null) {
                        StringBuilder resultBuilder = new StringBuilder();
                        
                        if (data instanceof JSONArray) {
                            JSONArray dataArray = (JSONArray) data;
                            if (dataArray.isEmpty()) {
                                DingTalkUtil.sendTextMessageToEmployees(notifyUsers, "查询成功，但今日无售后预警数据。");
                                return "No data found";
                            }
                            
                            for (int i = 0; i < dataArray.size(); i++) {
                                JSONObject item = dataArray.getJSONObject(i);
                                resultBuilder.append(formatAfterSaleInfo(item));
                            }
                        } else {
                            // Should be array according to example, but handle object just in case
                             resultBuilder.append(formatAfterSaleInfo((JSONObject)data));
                        }

                        String finalOutput = resultBuilder.toString();
                        DingTalkUtil.sendTextMessageToEmployees(notifyUsers, "ERP售后预警查询结果:\n" + finalOutput);
                        return finalOutput;

                    } else {
                        DingTalkUtil.sendTextMessageToEmployees(notifyUsers, "查询成功，但返回数据为空。");
                        return "Data is null";
                    }
                } else {
                    String msg = bodyJson.getString("message");
                    DingTalkUtil.sendTextMessageToEmployees(notifyUsers, "查询失败: " + msg);
                    return "Error: " + msg;
                }
            } else {
                DingTalkUtil.sendTextMessageToEmployees(notifyUsers, "接口调用失败 (Status " + status + ")");
                return "Error: Status " + status;
            }
        } catch (Exception e) {
            e.printStackTrace();
            return "Error processing response: " + e.getMessage();
        }
    }

    private String formatAfterSaleInfo(JSONObject item) {
        StringBuilder sb = new StringBuilder();
        for (String key : item.keySet()) {
            String label = FIELD_MAPPING.getOrDefault(key, key);
            // Skip fields with 0 value if desired? Or show all. 
            // User example has many 0s. Let's show non-zero or specific important ones.
            // Or just show everything formatted.
            sb.append(label).append(": ").append(item.get(key)).append("\n");
        }
        sb.append("----------------------------------------\n");
        return sb.toString();
    }
}
