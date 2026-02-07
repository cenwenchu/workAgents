package com.qiyi.component;

/**
 * 组件唯一标识枚举。
 *
 * <p>用于替代散落的字符串常量，便于统一管理与类型安全校验。</p>
 */
public enum ComponentId {
    DINGTALK("dingtalk"),
    FUTU("futu");

    private final String id;

    ComponentId(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public static ComponentId fromString(String raw) {
        if (raw == null) return null;
        String v = raw.trim();
        if (v.isEmpty()) return null;
        for (ComponentId c : values()) {
            if (c.id.equalsIgnoreCase(v) || c.name().equalsIgnoreCase(v)) {
                return c;
            }
        }
        return null;
    }
}
