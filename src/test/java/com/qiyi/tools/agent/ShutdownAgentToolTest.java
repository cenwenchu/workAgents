package com.qiyi.tools.agent;

import com.alibaba.fastjson2.JSONObject;
import com.qiyi.tools.ToolContext;
import com.qiyi.tools.ToolMessenger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

public class ShutdownAgentToolTest {

    @Mock
    private ToolContext context;

    @Mock
    private ToolMessenger messenger;

    private ShutdownAgentTool tool;

    @BeforeEach
    public void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);
        tool = spy(new ShutdownAgentTool());
        
        // Mock protected methods
        doNothing().when(tool).stopRobotMsgCallbackConsumer();
        doNothing().when(tool).exit(anyInt());
    }

    @Test
    public void testGetName() {
        assertEquals("shutdown_agent", tool.getName());
    }

    @Test
    public void testExecute() throws Exception {
        JSONObject params = new JSONObject();
        String result = tool.execute(params, context, messenger);
        
        assertEquals("已关闭钉钉机器人服务并退出 DingTalkAgent。", result);
        verify(messenger).sendText(contains("已收到关闭指令"));
        verify(tool).stopRobotMsgCallbackConsumer();
        verify(tool).exit(0);
    }
}
