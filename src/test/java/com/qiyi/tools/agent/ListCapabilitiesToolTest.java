package com.qiyi.tools.agent;

import com.alibaba.fastjson2.JSONObject;
import com.qiyi.tools.Tool;
import com.qiyi.tools.ToolContext;
import com.qiyi.tools.ToolMessenger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.lang.reflect.Field;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

public class ListCapabilitiesToolTest {

    @Mock
    private ToolContext context;
    
    @Mock
    private ToolMessenger messenger;

    @Mock
    private Tool mockTool;

    private ListCapabilitiesTool tool;

    @BeforeEach
    public void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);

        // Clear static cache
        Field field = ListCapabilitiesTool.class.getDeclaredField("cachedCapabilities");
        field.setAccessible(true);
        field.set(null, null);

        tool = spy(new ListCapabilitiesTool());
        
        when(mockTool.getName()).thenReturn("mock_tool");
        when(mockTool.getDescription()).thenReturn("A mock tool for testing");
        
        // Mock protected methods
        doReturn(Collections.singletonList(mockTool)).when(tool).getAllTools();
        doReturn("LLM response").when(tool).llmChat(anyString());
    }

    @Test
    public void testGetName() {
        assertEquals("list_capabilities", tool.getName());
    }

    @Test
    public void testExecuteWithoutQuery() {
        JSONObject params = new JSONObject();
        
        String result = tool.execute(params, context, messenger);
        
        verify(tool, times(1)).llmChat(anyString());
        assertEquals("LLM response", result);
    }

    @Test
    public void testExecuteWithQuery() {
        JSONObject params = new JSONObject();
        params.put("query", "help me");
        
        String result = tool.execute(params, context, messenger);
        
        // Query is ignored in current implementation, just check result
        assertEquals("LLM response", result);
        verify(tool, times(1)).llmChat(anyString());
    }
}
