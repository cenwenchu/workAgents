package com.qiyi.tools.podcast;

import com.alibaba.fastjson2.JSONObject;
import com.qiyi.agent.PodwiseAgent;
import com.qiyi.tools.ToolContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.*;

public class DownloadPodcastToolTest {

    @Mock
    private ToolContext context;

    @Mock
    private PodwiseAgent podwiseAgent;

    private DownloadPodcastTool tool;

    @BeforeEach
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        tool = new DownloadPodcastTool();
        tool.setPodwiseAgent(podwiseAgent);
    }

    @Test
    public void testGetName() {
        assertEquals("download_podcast", tool.getName());
    }

    @Test
    public void testExecuteDefault() throws Exception {
        JSONObject params = new JSONObject();
        when(podwiseAgent.run(anyInt(), anyInt(), anyInt(), anyInt(), anyInt())).thenReturn(5);

        String result = tool.execute(params, context);

        verify(podwiseAgent).run(
                DownloadPodcastTool.DOWNLOAD_MAX_PROCESS_COUNT,
                DownloadPodcastTool.DOWNLOAD_MAX_TRY_TIMES,
                DownloadPodcastTool.DOWNLOAD_MAX_DUPLICATE_PAGES,
                DownloadPodcastTool.DOWNLOAD_DOWNLOAD_MAX_PROCESS_COUNT,
                DownloadPodcastTool.DOWNLOAD_THREAD_POOL_SIZE
        );
        assertTrue(result.contains("5"));
        verify(context, atLeastOnce()).sendText(anyString());
    }

    @Test
    public void testExecuteWithParams() throws Exception {
        JSONObject params = new JSONObject();
        params.put("maxProcessCount", 10);
        params.put("maxTryTimes", 20);
        params.put("maxDuplicatePages", 3);
        params.put("downloadMaxProcessCount", 2);
        params.put("threadPoolSize", 5);

        when(podwiseAgent.run(anyInt(), anyInt(), anyInt(), anyInt(), anyInt())).thenReturn(10);

        String result = tool.execute(params, context);

        verify(podwiseAgent).run(10, 20, 3, 2, 5);
        assertTrue(result.contains("10"));
    }

    @Test
    public void testExecuteException() throws Exception {
        JSONObject params = new JSONObject();
        when(podwiseAgent.run(anyInt(), anyInt(), anyInt(), anyInt(), anyInt())).thenThrow(new RuntimeException("Test Exception"));

        String result = tool.execute(params, context);

        assertTrue(result.contains("Error: Test Exception"));
        verify(context).sendText(contains("异常"));
    }
}
