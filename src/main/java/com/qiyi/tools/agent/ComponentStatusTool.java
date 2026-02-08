package com.qiyi.tools.agent;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.qiyi.component.AgentComponent;
import com.qiyi.component.ComponentManager;
import com.qiyi.component.ComponentStatus;
import com.qiyi.tools.Tool;
import com.qiyi.tools.ToolContext;
import com.qiyi.tools.ToolMessenger;
import com.qiyi.util.AppLog;

@Tool.Info(
        name = "component_status",
        description = "查询组件状态。参数：id（可选；不传则返回所有组件状态）。返回：状态 JSON。"
)
public class ComponentStatusTool implements Tool {
    @Override
    public String execute(JSONObject params, ToolContext context, ToolMessenger messenger) {
        String id = params == null ? null : params.getString("id");
        ComponentManager mgr = ComponentManager.getInstance();

        String result;
        if (id == null || id.trim().isEmpty()) {
            AppLog.info("[tool] component_status request, id=ALL");
            JSONArray arr = new JSONArray();
            for (AgentComponent c : mgr.list()) {
                ComponentStatus st = c.status();
                arr.add(st == null ? null : st.toJson());
            }
            result = arr.toJSONString();
        } else {
            String raw = id.trim();
            AppLog.info("[tool] component_status request, id=" + raw);
            AgentComponent c = mgr.get(raw);
            if (c == null) {
                AppLog.warn("[tool] component_status not found, id=" + raw);
                result = ComponentStatus.error(raw, "Component not found", "NOT_FOUND").toJson().toJSONString();
            } else {
                ComponentStatus st = c.status();
                result = st == null ? "" : st.toJson().toJSONString();
            }
        }

        if (messenger != null) {
            try {
                messenger.sendText(result);
            } catch (Exception ignored) {
            }
        }
        return result;
    }
}
