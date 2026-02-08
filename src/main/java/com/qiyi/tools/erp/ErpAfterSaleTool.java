package com.qiyi.tools.erp;


import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONArray;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.APIRequestContext;
import com.microsoft.playwright.APIResponse;
import com.microsoft.playwright.options.RequestOptions;
import com.qiyi.tools.ToolContext;
import com.qiyi.tools.ToolMessenger;
import com.qiyi.util.PlayWrightUtil;
import com.qiyi.util.AppLog;

import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;
import java.time.LocalDate;
import java.time.ZoneId;

@com.qiyi.tools.Tool.Info(
        name = "query_erp_aftersale",
        description = "Query ERP After-Sales Early Warning data. Fetches statistics about returns, intercepts, etc. Parameters: none (defaults to today's data)."
)
public class ErpAfterSaleTool extends ErpBaseTool {
    private static final String PAGE_URL = "https://sc.scm121.com/dataCenter/afterSaleEarlyWarning";
    private static final String API_URL = "https://api.erp321.com/jst-data-report/getData/transportData";
    
    // Mapping for readable field names
    //只有加入了对应关系，才会返回.
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
    public String execute(JSONObject params, ToolContext context, ToolMessenger messenger) {
        if (!TOOL_LOCK.tryLock()) {
            sendTextSafe(messenger, "ERP查询任务正在执行中，请稍后再试。");
            return "Error: Task locked";
        }

        PlayWrightUtil.Connection connection = null;
        try {
            sendTextSafe(messenger, "开始执行ERP售后预警查询任务...");

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
                // 1. Check Login
                if (!ensureLogin(page, PAGE_URL, messenger)) {
                    return "Error: Login failed";
                }

                // 2. Fetch Data
                return fetchData(page, messenger);

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

    private String fetchData(Page page, ToolMessenger messenger) {
        try {
            // Capture the real API request to get the URL and Template Payload
            // We reload the page to trigger the request
            Request[] capturedRequest = new Request[1];
            
            try {
                page.waitForRequest(request -> {
                    String url = request.url();
                    if (url.contains("api.erp321.com") && request.method().equalsIgnoreCase("POST")) {
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
                AppLog.info("Wait for request timeout or failed: " + e.getMessage());
            }

            String apiUrl = API_URL;
            JSONObject payload;

            if (capturedRequest[0] != null) {
                // We prefer the captured URL if available, but user specified one explicitly.
                // Let's stick to the constant as user requested, or use captured if it's dynamic?
                // User said "api请求发起的地址是...", implying we should use that.
                // But usually captured one is safer for tokens/params.
                // Let's use the constant if capture fails, or capture to get payload.
                // Actually, let's use the captured URL if we found it, as it might have extra params.
                // If not, use the constant.
                apiUrl = capturedRequest[0].url();
                try {
                    payload = JSONObject.parseObject(capturedRequest[0].postData());
                } catch (Exception e) {
                    // Fallback payload if parsing fails
                    payload = createDefaultPayload();
                }
            } else {
                // Fallback if capture failed
                AppLog.info("Could not capture API request, using default URL and payload.");
                apiUrl = API_URL;
                payload = createDefaultPayload();
            }
            
            // Update Payload with Time
            updatePayloadTime(payload);

            // Send Request
            APIRequestContext requestContext = page.context().request();
            RequestOptions options = createApiRequestOptions(page, payload, PAGE_URL);

            AppLog.info("Sending request to: " + apiUrl);
            APIResponse response = requestContext.post(apiUrl, options);
            
            int status = response.status();
            String body = response.text();
            
            JSONObject bodyJson = null;
            try {
                bodyJson = JSONObject.parseObject(body);
            } catch (Exception e) {
                return "Error: Non-JSON response (Status " + status + ")";
            }

            return handleApiResponse(messenger, status, bodyJson);

        } catch (Exception e) {
            AppLog.error(e);
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
            AppLog.error("Failed to update payload time: " + e.getMessage());
        }
    }

    private String handleApiResponse(ToolMessenger messenger, int status, JSONObject bodyJson) {
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
                                sendTextSafe(messenger, "查询成功，但今日无售后预警数据。");
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
                        sendTextSafe(messenger, "ERP售后预警查询结果:\n" + finalOutput);
                        return finalOutput;

                    } else {
                        sendTextSafe(messenger, "查询成功，但返回数据为空。");
                        return "Data is null";
                    }
                } else {
                    String msg = bodyJson.getString("message");
                    sendTextSafe(messenger, "查询失败: " + msg);
                    return "Error: " + msg;
                }
            } else {
                sendTextSafe(messenger, "接口调用失败 (Status " + status + ")");
                return "Error: Status " + status;
            }
        } catch (Exception e) {
            AppLog.error(e);
            return "Error processing response: " + e.getMessage();
        }
    }

    private void sendTextSafe(ToolMessenger messenger, String content) {
        if (messenger == null) return;
        try {
            messenger.sendText(content);
        } catch (Exception ignored) {
        }
    }

    private String formatAfterSaleInfo(JSONObject item) {
        StringBuilder sb = new StringBuilder();
        for (String key : item.keySet()) {
            if (!FIELD_MAPPING.containsKey(key)) {
                continue;
            }
            String label = FIELD_MAPPING.get(key);
            sb.append(label).append(": ").append(item.get(key)).append("\n");
        }
        sb.append("----------------------------------------\n");
        return sb.toString();
    }
}
