package com.qiyi.component;

import com.qiyi.component.impl.DingTalkComponent;
import com.qiyi.component.impl.FutuComponent;
import com.qiyi.util.AppLog;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 组件管理器：
 * <ul>
 *     <li>负责组件注册、默认组件初始化</li>
 *     <li>负责组件的启动/停止与状态读取</li>
 * </ul>
 *
 * 组件用于承载“可被多个工具复用的外部依赖连接”，例如：钉钉、富途等。
 */
public final class ComponentManager {
    private static final ComponentManager INSTANCE = new ComponentManager();

    private final Map<ComponentId, AgentComponent> components = new LinkedHashMap<>();

    private ComponentManager() {
    }

    public static ComponentManager getInstance() {
        return INSTANCE;
    }

    public synchronized void initDefaults() {
        if (!components.isEmpty()) return;
        AppLog.info("[component] init defaults begin");
        register(new DingTalkComponent());
        register(new FutuComponent());
        AppLog.info("[component] init defaults done, count=" + components.size());
    }

    public synchronized void register(AgentComponent component) {
        if (component == null) return;
        ComponentId id = component.id();
        if (id == null) return;
        components.put(id, component);
        AppLog.info("[component] registered: " + id.id());
    }

    public synchronized AgentComponent get(String id) {
        return get(ComponentId.fromString(id));
    }

    public synchronized AgentComponent get(ComponentId id) {
        if (id == null) return null;
        return components.get(id);
    }

    public synchronized List<AgentComponent> list() {
        return Collections.unmodifiableList(new ArrayList<>(components.values()));
    }

    public ComponentStatus start(String id) {
        if (id == null) {
            return ComponentStatus.error(null, "Component not found", "NOT_FOUND");
        }
        String raw = id.trim();
        if (raw.isEmpty()) {
            return ComponentStatus.error(raw, "Component not found", "NOT_FOUND");
        }
        ComponentId cid = ComponentId.fromString(raw);
        if (cid == null) {
            return ComponentStatus.error(raw, "Component not found", "NOT_FOUND");
        }
        return start(cid);
    }

    /**
     * 启动组件（类型安全入口）。
     *
     * <p>通常由：</p>
     * <ul>
     *     <li>start_component 工具触发</li>
     *     <li>工具执行前置检查（requiredComponents）提示用户启动</li>
     * </ul>
     */
    public ComponentStatus start(ComponentId id) {
        AgentComponent c;
        synchronized (this) {
            c = get(id);
        }
        if (c == null) {
            String idStr = id == null ? null : id.id();
            AppLog.warn("[component] start failed (not found): " + idStr);
            return ComponentStatus.error(idStr, "Component not found", "NOT_FOUND");
        }
        if (!c.isConfigured()) {
            AppLog.warn("[component] start skipped (not configured): " + c.id().id());
            return ComponentStatus.error(c.id().id(), "Component not configured", c.configurationHint());
        }
        long begin = System.nanoTime();
        try {
            AppLog.info("[component] start begin: " + c.id().id());
            c.start();
            ComponentStatus st = c.status();
            long costMs = (System.nanoTime() - begin) / 1_000_000;
            AppLog.info("[component] start done: " + c.id().id() + ", state=" + (st == null ? "UNKNOWN" : st.getState()) + ", costMs=" + costMs);
            return st;
        } catch (Exception e) {
            long costMs = (System.nanoTime() - begin) / 1_000_000;
            AppLog.error("[component] start failed: " + c.id().id() + ", costMs=" + costMs, e);
            return ComponentStatus.error(c.id().id(), "Start failed", e.getMessage());
        }
    }

    public ComponentStatus stop(String id) {
        if (id == null) {
            return ComponentStatus.error(null, "Component not found", "NOT_FOUND");
        }
        String raw = id.trim();
        if (raw.isEmpty()) {
            return ComponentStatus.error(raw, "Component not found", "NOT_FOUND");
        }
        ComponentId cid = ComponentId.fromString(raw);
        if (cid == null) {
            return ComponentStatus.error(raw, "Component not found", "NOT_FOUND");
        }
        return stop(cid);
    }

    /**
     * 停止组件（类型安全入口）。
     */
    public ComponentStatus stop(ComponentId id) {
        AgentComponent c;
        synchronized (this) {
            c = get(id);
        }
        if (c == null) {
            String idStr = id == null ? null : id.id();
            AppLog.warn("[component] stop failed (not found): " + idStr);
            return ComponentStatus.error(idStr, "Component not found", "NOT_FOUND");
        }
        long begin = System.nanoTime();
        try {
            AppLog.info("[component] stop begin: " + c.id().id());
            c.stop();
            ComponentStatus st = c.status();
            long costMs = (System.nanoTime() - begin) / 1_000_000;
            AppLog.info("[component] stop done: " + c.id().id() + ", state=" + (st == null ? "UNKNOWN" : st.getState()) + ", costMs=" + costMs);
            return st;
        } catch (Exception e) {
            long costMs = (System.nanoTime() - begin) / 1_000_000;
            AppLog.error("[component] stop failed: " + c.id().id() + ", costMs=" + costMs, e);
            return ComponentStatus.error(c.id().id(), "Stop failed", e.getMessage());
        }
    }
}
