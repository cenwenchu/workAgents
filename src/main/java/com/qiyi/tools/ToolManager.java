package com.qiyi.tools;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.qiyi.tools.context.ConsoleToolContext;
import com.qiyi.util.AppLog;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.ServiceLoader;

/**
 * 工具注册与查询中心。
 *
 * <p>主要职责：</p>
 * <ul>
 *     <li>注册系统内置工具（init/registerTools）</li>
 *     <li>按名称获取工具实例（get/contains/getAll）</li>
 *     <li>导出工具 Schema，用于 LLM 进行工具选择与参数抽取（toToolSchema/exportToolsSchema）</li>
 * </ul>
 *
 * <p>工具发现方式：</p>
 * <ul>
 *     <li>通过 {@link java.util.ServiceLoader} 发现 {@link Tool} 实现类</li>
 *     <li>清单文件位于 {@code src/main/resources/META-INF/services/com.qiyi.tools.Tool}</li>
 *     <li>本项目不提供 legacy 注册兜底，发现不到工具将直接失败，便于尽早暴露配置问题</li>
 * </ul>
 *
 * <p>调用链路：</p>
 * <ul>
 *     <li>Agent 启动时调用 {@link #init()}，同时初始化默认组件</li>
 *     <li>{@link com.qiyi.tools.TaskProcessor} 运行时通过 {@link #get(String)} 获取工具并执行</li>
 * </ul>
 */
public class ToolManager {

    private static final Map<String, Tool> tools = new HashMap<>();
    public static final String LOCALE_ZH_CN = "zh-CN";
    public static final String LOCALE_EN_US = "en-US";

    private static final Map<String, Map<String, String>> paramDisplayNameByLocale = new HashMap<>();
    private static volatile boolean capabilitiesCacheInitStarted = false;

    static {
        Map<String, String> zh = new HashMap<>();
        zh.put("maxProcessCount", "单次最大处理数量");
        zh.put("maxTryTimes", "最大重试次数");
        zh.put("maxDuplicatePages", "最大重复页数");
        zh.put("downloadMaxProcessCount", "下载后最大处理数量");
        zh.put("threadPoolSize", "线程池大小");
        zh.put("isDraft", "是否存为草稿");
        zh.put("atUserIds", "@用户列表");
        zh.put("userIds", "接收人用户ID列表");
        zh.put("names", "接收人姓名列表");
        zh.put("content", "消息内容");
        zh.put("summary", "日程标题");
        zh.put("startTime", "开始时间");
        zh.put("endTime", "结束时间");
        zh.put("description", "日程描述");
        zh.put("location", "地点");
        zh.put("attendees", "参与人");
        zh.put("departments", "部门列表");
        zh.put("orderId", "订单号");
        zh.put("name", "用户姓名关键词");
        paramDisplayNameByLocale.put(LOCALE_ZH_CN, zh);

        Map<String, String> en = new HashMap<>();
        en.put("maxProcessCount", "Max items per run");
        en.put("maxTryTimes", "Max retry times");
        en.put("maxDuplicatePages", "Max duplicate pages");
        en.put("downloadMaxProcessCount", "Max items after download");
        en.put("threadPoolSize", "Thread pool size");
        en.put("isDraft", "Save as draft");
        en.put("atUserIds", "Mentioned user IDs");
        en.put("userIds", "Recipient user IDs");
        en.put("names", "Recipient names");
        en.put("content", "Message content");
        en.put("summary", "Event summary");
        en.put("startTime", "Start time");
        en.put("endTime", "End time");
        en.put("description", "Description");
        en.put("location", "Location");
        en.put("attendees", "Attendees");
        en.put("departments", "Departments");
        en.put("orderId", "Order ID");
        en.put("name", "Name keyword");
        paramDisplayNameByLocale.put(LOCALE_EN_US, en);
    }

    /**
     * 初始化：先初始化默认组件，再完成工具发现与注册。
     */
    public static void init() {
        AppLog.info("[tool] init begin");
        com.qiyi.component.ComponentManager.getInstance().initDefaults();
        registerTools();
        AppLog.info("[tool] init done, toolCount=" + tools.size());
    }

    public static void registerTools() {
        AppLog.info("[tool] register begin, alreadyRegistered=" + tools.size());
        registerToolsFromServiceLoader();
        if (tools.isEmpty()) {
            throw new IllegalStateException("No tools discovered by ServiceLoader. Ensure META-INF/services/com.qiyi.tools.Tool is configured.");
        }
        AppLog.info("[tool] register done, toolCount=" + tools.size());
        
        // 异步初始化 ListCapabilitiesTool 的缓存，避免首次调用时延迟
        if (!capabilitiesCacheInitStarted) {
            capabilitiesCacheInitStarted = true;
            new Thread(() -> {
                try {
                    AppLog.info("Initializing capabilities cache...");
                    ConsoleToolContext ctx = new ConsoleToolContext();
                    Tool t = ToolManager.get("list_capabilities");
                    if (t != null) {
                        t.execute(null, ctx, null);
                    } else {
                        AppLog.warn("[tool] list_capabilities not discovered; skip capabilities cache init");
                    }
                    AppLog.info("Capabilities cache initialized.");
                } catch (Exception e) {
                    AppLog.error("Failed to initialize capabilities cache", e);
                }
            }).start();
        }
    }

    private static void registerToolsFromServiceLoader() {
        String servicesPath = "META-INF/services/" + Tool.class.getName();
        try {
            ClassLoader cl = ToolManager.class.getClassLoader();
            List<String> lines = new java.util.ArrayList<>();
            try (InputStream in = cl.getResourceAsStream(servicesPath)) {
                if (in == null) {
                    AppLog.warn("[tool] services file missing: " + servicesPath);
                } else {
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            String v = line.trim();
                            if (v.isEmpty() || v.startsWith("#")) continue;
                            lines.add(v);
                        }
                    }
                    AppLog.info("[tool] services file loaded: " + servicesPath + ", configuredCount=" + lines.size());
                    int maxLog = Math.min(lines.size(), 50);
                    for (int i = 0; i < maxLog; i++) {
                        AppLog.info("[tool] services[" + i + "]=" + lines.get(i));
                    }
                    if (lines.size() > maxLog) {
                        AppLog.info("[tool] services truncated, remaining=" + (lines.size() - maxLog));
                    }
                }
            }
        } catch (Exception e) {
            AppLog.error("[tool] failed to read services file: " + servicesPath, e);
        }

        int discoveredCount = 0;
        int registeredCount = 0;
        int duplicateCount = 0;
        int invalidCount = 0;

        try {
            ServiceLoader<Tool> loader = ServiceLoader.load(Tool.class);
            for (Tool tool : loader) {
                if (tool == null) continue;
                discoveredCount++;
                String toolName = null;
                try {
                    toolName = tool.getName();
                } catch (Exception ignored) {
                }

                boolean ok = registerInternal(tool, "ServiceLoader");
                if (ok) {
                    registeredCount++;
                    AppLog.info("[tool] registered ok: name=" + toolName
                            + ", class=" + tool.getClass().getName()
                            + ", domain=" + tool.getBusinessDomain()
                            + ", type=" + (tool.getType() == null ? null : tool.getType().getLabel()));
                } else {
                    if (toolName == null || toolName.trim().isEmpty()) {
                        invalidCount++;
                        AppLog.warn("[tool] registered failed (invalid name): class=" + tool.getClass().getName());
                    } else {
                        duplicateCount++;
                        AppLog.warn("[tool] registered failed (duplicate or rejected): name=" + toolName + ", class=" + tool.getClass().getName());
                    }
                }
            }
        } catch (Exception e) {
            AppLog.error("[tool] ServiceLoader discovery failed", e);
        }

        AppLog.info("[tool] ServiceLoader summary: discovered=" + discoveredCount
                + ", registered=" + registeredCount
                + ", duplicateOrRejected=" + duplicateCount
                + ", invalidName=" + invalidCount);
    }

    public static boolean register(Tool tool) {
        return registerInternal(tool, "manual");
    }

    private static boolean registerInternal(Tool tool, String source) {
        if (tool == null) return false;
        String name = tool.getName();
        if (name == null || name.trim().isEmpty()) {
            AppLog.warn("[tool] invalid tool name, source=" + source + ", class=" + tool.getClass().getName());
            return false;
        }
        String n = name.trim();
        if (tools.containsKey(n)) {
            AppLog.warn("[tool] duplicated tool name ignored: " + n + ", source=" + source + ", class=" + tool.getClass().getName());
            return false;
        }
        tools.put(n, tool);
        AppLog.info("[tool] register tool: name=" + n + ", source=" + source + ", class=" + tool.getClass().getName());
        return true;
    }

    public static Tool get(String name) {
        if (name == null) return null;
        if (tools.isEmpty() || !tools.containsKey(name)) {
            registerTools();
        }
        return tools.get(name);
    }

    public static java.util.Collection<Tool> getAll() {
        if (tools.isEmpty()) {
            registerTools();
        }
        return tools.values();
    }

    public static boolean contains(String name) {
        if (name == null) return false;
        if (tools.isEmpty() || !tools.containsKey(name)) {
            registerTools();
        }
        return tools.containsKey(name);
    }

    public static String getParamDisplayName(String paramName) {
        return getParamDisplayName(LOCALE_ZH_CN, paramName);
    }

    public static String getParamDisplayName(String locale, String paramName) {
        if (paramName == null || paramName.trim().isEmpty()) return "";
        String l = (locale == null || locale.trim().isEmpty()) ? LOCALE_ZH_CN : locale.trim();
        Map<String, String> map = paramDisplayNameByLocale.get(l);
        if (map == null) map = paramDisplayNameByLocale.get(LOCALE_ZH_CN);
        if (map == null) return paramName;
        return map.getOrDefault(paramName, paramName);
    }

    public static String getParamDisplayName(Tool tool, String paramName) {
        return getParamDisplayName(LOCALE_ZH_CN, tool, paramName);
    }

    public static String getParamDisplayName(String locale, Tool tool, String paramName) {
        if (paramName == null || paramName.trim().isEmpty()) return "";
        if (tool != null) {
            try {
                Map<String, String> toolMap = tool.getParamDisplayNames(locale);
                if (toolMap != null) {
                    String v = toolMap.get(paramName);
                    if (v != null && !v.trim().isEmpty()) return v.trim();
                }
            } catch (Exception ignored) {
            }
        }
        return getParamDisplayName(locale, paramName);
    }

    public static JSONObject toToolSchema(Tool tool) {
        return toToolSchema(tool, LOCALE_ZH_CN);
    }

    public static JSONObject toToolSchema(Tool tool, String locale) {
        JSONObject obj = new JSONObject();
        if (tool == null) return obj;
        obj.put("name", tool.getName());
        obj.put("businessDomain", tool.getBusinessDomain());
        Tool.ToolType type = tool.getType();
        obj.put("type", type != null ? type.getLabel() : Tool.ToolType.API.getLabel());
        obj.put("description", tool.getDescription());

        Tool.InterfaceDescription iface = tool.getInterfaceDescription();
        JSONObject ifaceJson = iface != null ? iface.toJson() : new JSONObject();
        obj.put("interface", ifaceJson);

        Set<String> paramNames = new LinkedHashSet<>();
        try {
            JSONObject input = ifaceJson.getJSONObject("input");
            if (input != null) {
                JSONObject props = input.getJSONObject("properties");
                if (props != null) {
                    paramNames.addAll(props.keySet());
                }
            }
        } catch (Exception ignored) {}

        JSONObject paramI18n = new JSONObject(new LinkedHashMap<>());
        for (String p : paramNames) {
            JSONObject one = new JSONObject();
            one.put(LOCALE_ZH_CN, getParamDisplayName(LOCALE_ZH_CN, tool, p));
            one.put(LOCALE_EN_US, getParamDisplayName(LOCALE_EN_US, tool, p));
            paramI18n.put(p, one);
        }

        JSONObject i18n = new JSONObject();
        i18n.put("defaultLocale", LOCALE_ZH_CN);
        i18n.put("locale", (locale == null || locale.trim().isEmpty()) ? LOCALE_ZH_CN : locale.trim());
        i18n.put("paramDisplayNames", paramI18n);
        obj.put("i18n", i18n);
        return obj;
    }

    public static JSONArray exportToolsSchema() {
        return exportToolsSchema(LOCALE_ZH_CN);
    }

    public static JSONArray exportToolsSchema(String locale) {
        JSONArray arr = new JSONArray();
        Collection<Tool> all = getAll();
        for (Tool t : all) {
            arr.add(toToolSchema(t, locale));
        }
        return arr;
    }

    static void clearForTests() {
        // 测试隔离：清空工具与异步缓存初始化状态，避免用例间相互污染
        tools.clear();
        capabilitiesCacheInitStarted = false;
    }
}
