package com.qiyi.tools.wechat;

import com.alibaba.fastjson2.JSONObject;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.Playwright;
import com.qiyi.tools.ToolContext;
import com.qiyi.util.PlayWrightUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.*;

public class PublishWechatToolTest {

    private PublishWechatTool tool;

    @Mock
    private ToolContext context;

    @Mock
    private Playwright playwright;

    @Mock
    private Browser browser;

    @BeforeEach
    public void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);
        tool = spy(new PublishWechatTool());

        // Mock config
        doReturn("/tmp/publish").when(tool).getPodcastPublishDir();
        doReturn("/tmp/summary").when(tool).getPodcastSummaryDir();
        doReturn("/tmp/published").when(tool).getPodcastPublishedDir();
        doReturn(5).when(tool).getPodcastPublishBatchSize();

        // Mock helpers
        doNothing().when(tool).stageFilesForPublishing(any());
        doReturn(true).when(tool).checkWechatLogin(any(), any());
        doReturn("Success").when(tool).processPublishFiles(any(), any(), anyBoolean());
        doNothing().when(tool).disconnectBrowser(any());
    }

    @Test
    public void testExecute_Success() {
        PlayWrightUtil.Connection connection = new PlayWrightUtil.Connection();
        connection.playwright = playwright;
        connection.browser = browser;
        doReturn(connection).when(tool).connectToBrowser();

        JSONObject params = new JSONObject();
        params.put("isDraft", true);

        String result = tool.execute(params, context);

        verify(tool).stageFilesForPublishing(context);
        verify(tool).connectToBrowser();
        verify(tool).checkWechatLogin(eq(connection), eq(context));
        verify(tool).processPublishFiles(eq(connection), eq(context), eq(true));
        verify(tool).disconnectBrowser(connection);
        assertTrue(result.contains("Publish Completed"));
    }

    @Test
    public void testExecute_ConfigError() {
        doReturn(null).when(tool).getPodcastPublishDir();

        String result = tool.execute(new JSONObject(), context);

        assertTrue(result.equals("Config Error"));
        verify(context).sendText(contains("发布目录未配置"));
        verify(tool, never()).connectToBrowser();
    }

    @Test
    public void testExecute_BrowserError() {
        doReturn(null).when(tool).connectToBrowser();

        String result = tool.execute(new JSONObject(), context);

        assertTrue(result.equals("Browser Error"));
        verify(context).sendText(contains("无法连接到浏览器"));
        verify(tool, never()).checkWechatLogin(any(), any());
    }

    @Test
    public void testExecute_LoginFailed() {
        PlayWrightUtil.Connection connection = new PlayWrightUtil.Connection();
        connection.playwright = playwright;
        connection.browser = browser;
        doReturn(connection).when(tool).connectToBrowser();
        doReturn(false).when(tool).checkWechatLogin(any(), any());

        String result = tool.execute(new JSONObject(), context);

        assertTrue(result.equals("Login Failed"));
        verify(tool).disconnectBrowser(connection);
        verify(tool, never()).processPublishFiles(any(), any(), anyBoolean());
    }

    @Test
    public void testExecute_Exception() {
        doThrow(new RuntimeException("Test Exception")).when(tool).stageFilesForPublishing(any());

        String result = tool.execute(new JSONObject(), context);

        assertTrue(result.startsWith("Error:"));
        verify(context).sendText(contains("发布任务执行异常"));
        // Ensure lock is released (implicit by function returning)
    }
}
