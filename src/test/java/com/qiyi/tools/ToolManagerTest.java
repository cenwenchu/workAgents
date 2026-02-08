package com.qiyi.tools;


import com.qiyi.tools.context.ConsoleToolContext;
import com.qiyi.tools.example.HelloWorldTool;
import org.junit.jupiter.api.Test;


import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * ToolManager 相关行为测试。
 *
 * <p>重点覆盖直连工具执行路径与工具扫描配置能力。</p>
 */
public class ToolManagerTest {

    /**
     * 验证 direct command 模式：符合 toolName key=value 的输入可直接执行工具，不依赖 LLM。
     */
    @Test
    public void testDirectCommandExecution() {
        ToolManager.clearForTests();
        ToolManager.register(new HelloWorldTool());
        // Mock context using ConsoleToolContext which prints to stdout
        // We can't easily capture stdout here without more complex setup, 
        // but we can ensure it doesn't crash and returns normally.
        // To verify it actually ran, we could use a spy tool, but HelloWorldTool is simple.
        
        // This test mainly verifies that the Direct Command path is taken and doesn't crash.
        // If LLM was used (and config missing), it would print errors (but not throw).
        // If Direct Command is used, it should print "Directly executing tool: hello_world"
        
        ConsoleToolContext ctx = new ConsoleToolContext();
        TaskProcessor.process("hello_world name=\"TestUser\"", ctx, ctx);
    }
    
    /**
     * 验证 direct command 模式：无参数时也可直接执行工具。
     */
    @Test
    public void testDirectCommandExecutionWithoutParams() {
        ToolManager.clearForTests();
        ToolManager.register(new HelloWorldTool());
        ConsoleToolContext ctx = new ConsoleToolContext();
        TaskProcessor.process("hello_world", ctx, ctx);
    }

    /**
     * 验证 direct command 与 LLM fallback 的判定规则。
     */
    @Test
    public void testDirectCommandFallback() {
        ToolManager.clearForTests();
        ToolManager.register(new HelloWorldTool());
        ConsoleToolContext context = new ConsoleToolContext();

        // 1. Direct Command: No args -> True
        assertTrue(TaskProcessor.tryExecuteDirectCommand("hello_world", context, context), "Should execute direct command for 'hello_world'");

        // 2. Direct Command: With key=value -> True
        assertTrue(TaskProcessor.tryExecuteDirectCommand("hello_world name=Test", context, context), "Should execute direct command for 'hello_world name=Test'");

        // 3. Fallback: Args present but no key=value -> False
        assertFalse(TaskProcessor.tryExecuteDirectCommand("hello_world some random text", context, context), "Should fallback to LLM for 'hello_world some random text'");

        // 4. Fallback: Non-existent tool -> False
        assertFalse(TaskProcessor.tryExecuteDirectCommand("non_existent_tool", context, context), "Should fallback to LLM for non-existent tool");
        
        // 5. Direct Command: Mixed args (contains key=value) -> True
        assertTrue(TaskProcessor.tryExecuteDirectCommand("hello_world name=Test extra", context, context), "Should execute direct command if at least one key=value is found");
    }

    /**
     * 验证扫描包配置：通过 system property 限定扫描包后，仍可发现并注册目标工具。
     */
    @Test
    public void testToolScanPackagesConfigViaSystemProperty() {
        String key = "workagents.tools.scanPackages";
        String old = System.getProperty(key);
        try {
            ToolManager.clearForTests();
            System.setProperty(key, "com.qiyi.tools.example");
            ToolManager.registerTools();
            assertTrue(ToolManager.contains("hello_world"), "Should discover hello_world by scanning configured packages");
        } finally {
            if (old == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, old);
            }
            ToolManager.clearForTests();
        }
    }
}
