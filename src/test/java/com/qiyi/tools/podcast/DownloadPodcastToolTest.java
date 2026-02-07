package com.qiyi.tools.podcast;

import com.alibaba.fastjson2.JSONObject;
import com.qiyi.agent.PodwiseAgent;
import com.qiyi.tools.ToolContext;
import com.qiyi.tools.ToolMessenger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

public class DownloadPodcastToolTest {

    @Mock
    private ToolContext context;

    @Mock
    private ToolMessenger messenger;

    @Mock
    private PodwiseAgent podwiseAgent;

    private DownloadPodcastTool tool;

    @BeforeEach
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        tool = new DownloadPodcastTool();
        tool.setPodwiseAgent(podwiseAgent);
        unlockDownloadLock();
    }

    private void unlockDownloadLock() {
        try {
            java.lang.reflect.Field f = DownloadPodcastTool.class.getDeclaredField("DOWNLOAD_LOCK");
            f.setAccessible(true);
            java.util.concurrent.locks.ReentrantLock lock = (java.util.concurrent.locks.ReentrantLock) f.get(null);
            while (lock != null && lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
            while (lock != null && lock.isLocked()) {
                try {
                    lock.unlock();
                } catch (IllegalMonitorStateException ignored) {
                    break;
                }
            }
        } catch (Exception ignored) {
        }
    }

    @Test
    public void testGetName() {
        assertEquals("download_podcast", tool.getName());
    }

    @Test
    public void testExecuteDefault() throws Exception {
        JSONObject params = new JSONObject();
        when(podwiseAgent.run(anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), any(ToolContext.class), any(ToolMessenger.class))).thenReturn(5);

        String result = tool.execute(params, context, messenger);

        verify(podwiseAgent).run(
                DownloadPodcastTool.DOWNLOAD_MAX_PROCESS_COUNT,
                DownloadPodcastTool.DOWNLOAD_MAX_TRY_TIMES,
                DownloadPodcastTool.DOWNLOAD_MAX_DUPLICATE_PAGES,
                DownloadPodcastTool.DOWNLOAD_DOWNLOAD_MAX_PROCESS_COUNT,
                DownloadPodcastTool.DOWNLOAD_THREAD_POOL_SIZE,
                context,
                messenger
        );
        assertTrue(result.contains("5"));
        verify(messenger, atLeastOnce()).sendText(anyString());
    }

    @Test
    public void testExecuteWithParams() throws Exception {
        JSONObject params = new JSONObject();
        params.put("maxProcessCount", 10);
        params.put("maxTryTimes", 20);
        params.put("maxDuplicatePages", 3);
        params.put("downloadMaxProcessCount", 2);
        params.put("threadPoolSize", 5);

        when(podwiseAgent.run(anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), any(ToolContext.class), any(ToolMessenger.class))).thenReturn(10);

        String result = tool.execute(params, context, messenger);

        verify(podwiseAgent).run(10, 20, 3, 2, 5, context, messenger);
        assertTrue(result.contains("10"));
    }

    @Test
    public void testExecuteException() throws Exception {
        JSONObject params = new JSONObject();
        when(podwiseAgent.run(anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), any(ToolContext.class), any(ToolMessenger.class))).thenThrow(new RuntimeException("Test Exception"));

        String result = tool.execute(params, context, messenger);

        assertTrue(result.contains("Error: Test Exception"));
        verify(messenger).sendText(contains("异常"));
    }
}
