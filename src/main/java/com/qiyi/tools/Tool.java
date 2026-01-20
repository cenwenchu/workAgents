package com.qiyi.tools;

import com.alibaba.fastjson2.JSONObject;

public interface Tool {
    String getName();
    String getDescription();
    String execute(JSONObject params, ToolContext context);
}
