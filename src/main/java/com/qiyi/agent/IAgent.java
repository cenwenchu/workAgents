package com.qiyi.agent;

/**
 * Agent 对外统一接口。
 *
 * <p>一个 Agent 代表一种交互入口或运行形态（例如：钉钉消息入口、命令行入口、专项任务入口）。</p>
 *
 * <ul>
 *     <li>start/stop：生命周期控制，通常由 {@link AbstractAgent} 负责并发控制</li>
 *     <li>chat：输入一句自然语言，返回 Agent 的处理结果（适用于控制台/测试/嵌入式调用）</li>
 * </ul>
 */
public interface IAgent {
    void start();

    void stop();

    String chat(String userInput);
}
