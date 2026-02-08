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
        name = "list_components",
        description = "列出当前 Agent 的组件列表与状态。参数：无。返回：组件数组（id/description/state/message/error）。"
)
public class ListComponentsTool implements Tool {
    @Override
    public String execute(JSONObject params, ToolContext context, ToolMessenger messenger) {
        ComponentManager mgr = ComponentManager.getInstance();
        JSONArray arr = new JSONArray();
        AppLog.info("[tool] list_components request");
        for (AgentComponent c : mgr.list()) {
            ComponentStatus st = c.status();
            JSONObject one = new JSONObject();
            one.put("id", c.id() == null ? null : c.id().id());
            one.put("description", c.description());
            one.put("configured", c.isConfigured());
            one.put("status", st == null ? null : st.toJson());
            arr.add(one);
        }
        String result = arr.toJSONString();
        if (messenger != null) {
            try {
                messenger.sendText(result);
            } catch (Exception ignored) {
            }
        }
        AppLog.info("[tool] list_components done, count=" + arr.size());
        return result;
    }
}
