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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class QueryErpOrderToolTest {

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

    private QueryErpOrderTool tool;

    @BeforeEach
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        tool = spy(new QueryErpOrderTool());

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
        assertEquals("query_erp_order", tool.getName());
    }

    @Test
    public void testExecuteMissingOrderId() {
        JSONObject params = new JSONObject();
        String result = tool.execute(params, context);
        
        assertEquals("Error: Missing orderId", result);
    }

    @Test
    public void testExecuteBrowserConnectionFailed() {
        doReturn(null).when(tool).connectToBrowser();

        JSONObject params = new JSONObject();
        params.put("orderId", "12345");
        
        String result = tool.execute(params, context);
        
        assertTrue(result.contains("Browser connection failed"));
    }

    @Test
    public void testExecuteLoginFailed() {
        doReturn(false).when(tool).ensureLogin(any(), anyString(), any());

        JSONObject params = new JSONObject();
        params.put("orderId", "12345");

        String result = tool.execute(params, context);

        assertEquals("Error: Login failed", result);
        verify(tool).ensureLogin(eq(page), anyString(), eq(context));
    }

    @Test
    public void testExecuteSuccess() {
        doReturn(true).when(tool).ensureLogin(any(), anyString(), any());
        
        // Mock API call
        RequestOptions options = RequestOptions.create();
        doReturn(options).when(tool).createApiRequestOptions(any(), any(), anyString());
        
        when(apiRequestContext.post(anyString(), any())).thenReturn(apiResponse);
        when(apiResponse.status()).thenReturn(200);
        
        JSONObject data = new JSONObject();
        data.put("id", "12345");
        data.put("oid", "ORDER_123");
        data.put("orderId", "ORDER_123");
        
        JSONObject responseBody = new JSONObject();
        responseBody.put("success", true);
        responseBody.put("data", data);
        
        when(apiResponse.text()).thenReturn(responseBody.toJSONString());

        JSONObject params = new JSONObject();
        params.put("orderId", "12345");

        String result = tool.execute(params, context);

        // Verify result contains order info
        assertTrue(result.contains("ORDER_123"));
        verify(tool).createApiRequestOptions(eq(page), any(), anyString());
        verify(apiRequestContext).post(contains("innerapi.scm121.com"), eq(options));
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
        params.put("orderId", "12345");

        String result = tool.execute(params, context);

        assertEquals("No records found", result);
    }
}
