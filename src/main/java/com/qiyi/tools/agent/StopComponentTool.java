package com.qiyi.tools.agent;

import com.alibaba.fastjson2.JSONObject;
import com.qiyi.component.ComponentManager;
import com.qiyi.component.ComponentStatus;
import com.qiyi.tools.Tool;
import com.qiyi.tools.ToolContext;
import com.qiyi.tools.ToolMessenger;
import com.qiyi.util.AppLog;

@Tool.Info(
        name = "stop_component",
        description = "停止指定组件。参数：id（必填，组件ID，例如 dingtalk / futu）。返回：组件状态。"
)
public class StopComponentTool implements Tool {
    @Override
    public String execute(JSONObject params, ToolContext context, ToolMessenger messenger) {
        String id = params == null ? null : params.getString("id");
        if (id == null || id.trim().isEmpty()) {
            AppLog.warn("[tool] stop_component missing required param: id");
            return "Error: id is required";
        }
        String raw = id.trim();
        AppLog.info("[tool] stop_component request, id=" + raw);
        ComponentStatus st = ComponentManager.getInstance().stop(raw);
        String result = st == null ? "" : st.toJson().toJSONString();
        if (messenger != null) {
            try {
                messenger.sendText(result);
            } catch (Exception ignored) {
            }
        }
        AppLog.info("[tool] stop_component done, id=" + raw + ", state=" + (st == null ? "UNKNOWN" : st.getState()));
        return result;
    }
}
