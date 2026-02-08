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
import static org.mockito.Mockito.verify;

/**
 * HelloWorldTool 单元测试。
 *
 * <p>覆盖注释模式元信息读取（{@link com.qiyi.tools.Tool.Info}）与 execute 行为。</p>
 */
public class HelloWorldToolTest {

    @Mock
    private ToolContext context;

    @Mock
    private ToolMessenger messenger;

    private HelloWorldTool tool;

    /**
     * 初始化 tool 与 mock。
     */
    @BeforeEach
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        tool = new HelloWorldTool();
    }

    /**
     * name 由 {@link com.qiyi.tools.Tool.Info} 提供，应可通过默认 getName() 读取。
     */
    @Test
    public void testGetName() {
        assertEquals("hello_world", tool.getName());
    }

    /**
     * 传入 name 参数时，应生成对应欢迎语并通过 messenger 回传。
     */
    @Test
    public void testExecuteWithParam() {
        JSONObject params = new JSONObject();
        params.put("name", "Tester");

        String result = tool.execute(params, context, messenger);
        
        verify(messenger).sendText("Hello, Tester! 欢迎来到 WorkAgents 世界。");
        
        assertTrue(result.contains("Hello, Tester!"));
    }

    /**
     * 未提供 name 参数时，应使用默认值并回传欢迎语。
     */
    @Test
    public void testExecuteWithoutParam() {
        JSONObject params = new JSONObject();

        String result = tool.execute(params, context, messenger);
        
        verify(messenger).sendText("Hello, 开发者! 欢迎来到 WorkAgents 世界。");
        assertTrue(result.contains("Hello, 开发者!"));
    }
}
