package com.qiyi.tools.erp;

import com.alibaba.fastjson2.JSONObject;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.RequestOptions;
import com.qiyi.tools.ToolContext;
import com.qiyi.util.PlayWrightUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

public class ErpAfterSaleToolTest {

    @Mock
    private ToolContext context;

    @Mock
    private Playwright playwright;

    @Mock
    private Browser browser;

    @Mock
    private BrowserContext browserContext;

    @Mock
    private Page page;

    @Mock
    private APIRequestContext apiRequestContext;

    @Mock
    private APIResponse apiResponse;

    private ErpAfterSaleTool tool;

    @BeforeEach
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        tool = spy(new ErpAfterSaleTool());
        
        // Mock Playwright structure
        PlayWrightUtil.Connection connection = new PlayWrightUtil.Connection();
        connection.playwright = playwright;
        connection.browser = browser;

        doReturn(connection).when(tool).connectToBrowser();
        doNothing().when(tool).disconnectBrowser(any());

        when(browser.contexts()).thenReturn(Collections.singletonList(browserContext));
        when(browserContext.newPage()).thenReturn(page);
        when(page.context()).thenReturn(browserContext);
        when(browserContext.request()).thenReturn(apiRequestContext);
    }

    @Test
    public void testGetName() {
        assertEquals("query_erp_aftersale", tool.getName());
    }

    @Test
    public void testExecuteBrowserConnectionFailed() {
        doReturn(null).when(tool).connectToBrowser();
        JSONObject params = new JSONObject();
        String result = tool.execute(params, context);
        
        assertTrue(result.contains("Browser connection failed"));
        try {
            verify(context, atLeastOnce()).sendText(contains("无法连接到浏览器"));
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Test
    public void testExecuteLoginFailed() {
        doReturn(false).when(tool).ensureLogin(any(), anyString(), any());
        
        JSONObject params = new JSONObject();
        String result = tool.execute(params, context);
        
        assertEquals("Error: Login failed", result);
    }

    @Test
    public void testExecuteSuccess() {
        doReturn(true).when(tool).ensureLogin(any(), anyString(), any());
        
        RequestOptions options = RequestOptions.create();
        doReturn(options).when(tool).createApiRequestOptions(any(), any(), anyString());
        
        when(apiRequestContext.post(anyString(), any())).thenReturn(apiResponse);
        when(apiResponse.status()).thenReturn(200);
        
        JSONObject responseBody = new JSONObject();
        responseBody.put("success", true);
        com.alibaba.fastjson2.JSONArray data = new com.alibaba.fastjson2.JSONArray();
        JSONObject item = new JSONObject();
        item.put("wait_intercept_cnt", 5);
        data.add(item);
        responseBody.put("data", data);
        
        when(apiResponse.text()).thenReturn(responseBody.toJSONString());

        JSONObject params = new JSONObject();
        String result = tool.execute(params, context);

        assertTrue(result.contains("待拦截总数: 5"));
    }

    @Test
    public void testExecuteNoRecords() {
        doReturn(true).when(tool).ensureLogin(any(), anyString(), any());
        
        RequestOptions options = RequestOptions.create();
        doReturn(options).when(tool).createApiRequestOptions(any(), any(), anyString());
        
        when(apiRequestContext.post(anyString(), any())).thenReturn(apiResponse);
        when(apiResponse.status()).thenReturn(200);
        
        JSONObject responseBody = new JSONObject();
        responseBody.put("success", true);
        responseBody.put("data", new com.alibaba.fastjson2.JSONArray()); // Empty array
        
        when(apiResponse.text()).thenReturn(responseBody.toJSONString());

        JSONObject params = new JSONObject();
        String result = tool.execute(params, context);

        assertEquals("No data found", result);
    }
}
