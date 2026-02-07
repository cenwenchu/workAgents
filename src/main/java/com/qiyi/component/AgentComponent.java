package com.qiyi.component;

/**
 * Agent 运行所依赖的外部组件抽象。
 *
 * <p>组件通常封装“可复用的外部连接/客户端”，例如钉钉、富途等。工具（Tool）通过 requiredComponents()
 * 声明依赖，由 {@link com.qiyi.tools.TaskProcessor} 在执行前校验组件状态。</p>
 */
public interface AgentComponent {
    ComponentId id();
    String description();

    boolean isConfigured();
    String configurationHint();

    void start();
    void stop();

    ComponentStatus status();
}
