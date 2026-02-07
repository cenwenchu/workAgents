package com.qiyi.tools.example;

import com.alibaba.fastjson2.JSONObject;
import com.qiyi.tools.ToolContext;
import com.qiyi.tools.ToolMessenger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.verify;

public class HelloWorldToolTest {

    @Mock
    private ToolContext context;

    @Mock
    private ToolMessenger messenger;

    private HelloWorldTool tool;

    @BeforeEach
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        tool = new HelloWorldTool();
    }

    @Test
    public void testGetName() {
        assertEquals("hello_world", tool.getName());
    }

    @Test
    public void testExecuteWithParam() {
        JSONObject params = new JSONObject();
        params.put("name", "Tester");

        String result = tool.execute(params, context, messenger);
        
        verify(messenger).sendText("Hello, Tester! 欢迎来到 WorkAgents 世界。");
        // Verify that the guide is also sent
        verify(messenger).sendText(contains("这是一个教学示例"));
        
        assertTrue(result.contains("Hello, Tester!"));
    }

    @Test
    public void testExecuteWithoutParam() {
        JSONObject params = new JSONObject();

        String result = tool.execute(params, context, messenger);
        
        verify(messenger).sendText("Hello, 开发者! 欢迎来到 WorkAgents 世界。");
        assertTrue(result.contains("Hello, 开发者!"));
    }
}
