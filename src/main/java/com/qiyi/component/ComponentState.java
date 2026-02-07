package com.qiyi.component;

/**
 * 组件运行状态枚举。
 *
 * <p>约定：</p>
 * <ul>
 *     <li>STOPPED：未启动或已停止</li>
 *     <li>RUNNING：可正常提供能力</li>
 *     <li>ERROR：启动/运行中出现异常，需要排查 error/message</li>
 * </ul>
 */
public enum ComponentState {
    STOPPED,
    RUNNING,
    ERROR
}
