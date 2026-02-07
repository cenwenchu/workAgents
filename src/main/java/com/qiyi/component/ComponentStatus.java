package com.qiyi.component;

import com.alibaba.fastjson2.JSONObject;

/**
 * 组件运行状态快照。
 *
 * <p>用于在“组件管理”和“工具执行前置检查”中统一表达组件状态，便于：</p>
 * <ul>
 *     <li>运维/排障：输出当前 state/message/error</li>
 *     <li>工具前置校验：判断 requiredComponents 是否 RUNNING</li>
 * </ul>
 */
public final class ComponentStatus {
    private final String id;
    private final ComponentState state;
    private final String message;
    private final String error;

    private ComponentStatus(String id, ComponentState state, String message, String error) {
        this.id = id;
        this.state = state;
        this.message = message;
        this.error = error;
    }

    public static ComponentStatus running(String id, String message) {
        return new ComponentStatus(id, ComponentState.RUNNING, message, null);
    }

    public static ComponentStatus stopped(String id, String message) {
        return new ComponentStatus(id, ComponentState.STOPPED, message, null);
    }

    public static ComponentStatus error(String id, String message, String error) {
        return new ComponentStatus(id, ComponentState.ERROR, message, error);
    }

    public String getId() {
        return id;
    }

    public ComponentState getState() {
        return state;
    }

    public String getMessage() {
        return message;
    }

    public String getError() {
        return error;
    }

    public JSONObject toJson() {
        JSONObject obj = new JSONObject();
        obj.put("id", id);
        obj.put("state", state == null ? null : state.name());
        obj.put("message", message);
        obj.put("error", error);
        return obj;
    }
}
