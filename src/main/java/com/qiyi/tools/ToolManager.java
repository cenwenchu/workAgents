package com.qiyi.tools;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.qiyi.config.AppConfig;
import com.qiyi.tools.context.ConsoleToolContext;
import com.qiyi.util.AppLog;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.ServiceLoader;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.net.JarURLConnection;
import java.net.URL;

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
 *     <li>通过 {@link java.util.ServiceLoader} 发现 {@link Tool} 实现类（可选）</li>
 *     <li>通过类路径扫描发现 {@link Tool} 实现类（默认开启）</li>
 * </ul>
 *
 * <p>类路径扫描配置：</p>
 * <ul>
 *     <li>开关：{@code -Dworkagents.tools.autoregister=false} 可关闭扫描</li>
 *     <li>扫描包：优先读取 {@code -Dworkagents.tools.scanPackages}；其次读取配置文件 key {@code tools.scan.packages}；默认 {@code com.qiyi.tools}</li>
 *     <li>支持多包：逗号/分号/空白分隔</li>
 *     <li>单工具开关：{@link Tool.Info#register()} / {@link Tool.AutoRegister}</li>
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

    /**
     * 发现并注册工具实现。
     *
     * <p>注册顺序：先尝试 ServiceLoader（如存在），再执行类路径扫描；工具名冲突会拒绝后续注册。</p>
     */
    public static void registerTools() {
        AppLog.info("[tool] register begin, alreadyRegistered=" + tools.size());
        registerToolsFromServiceLoader();
        registerToolsFromClasspathScan();
        if (tools.isEmpty()) {
            throw new IllegalStateException("No tools discovered. Ensure tools are on classpath and can be instantiated.");
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

    /**
     * 尝试通过 ServiceLoader 发现 Tool 实现类并注册。
     *
     * <p>该方式不再要求必须存在 services 清单；主要用于兼容某些打包形态或外部扩展。</p>
     */
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

    /**
     * 通过类路径扫描发现 Tool 实现类并注册（默认开启）。
     *
     * <p>限制与约束：</p>
     * <ul>
     *     <li>仅扫描配置包前缀下的 class（默认 com.qiyi.tools）</li>
     *     <li>仅注册非 abstract、非 interface、非内部类（包含 $）的实现</li>
     *     <li>要求存在无参构造（可为默认构造）；否则跳过</li>
     *     <li>如类上声明 {@link Tool.Info#register()} 为 false 或 {@link Tool.AutoRegister} 为 false，则跳过</li>
     * </ul>
     */
    private static void registerToolsFromClasspathScan() {
        String enabled = System.getProperty("workagents.tools.autoregister", "true");
        if (!"true".equalsIgnoreCase(enabled) && !"1".equals(enabled)) {
            AppLog.info("[tool] classpath scan disabled by system property: workagents.tools.autoregister=" + enabled);
            return;
        }

        ClassLoader cl = ToolManager.class.getClassLoader();
        Set<String> pkgs = getScanPackages();
        int discoveredCount = 0;
        int instantiatedCount = 0;
        int registeredCount = 0;
        int skippedCount = 0;
        int failedCount = 0;

        for (String pkg : pkgs) {
            for (String className : listClassNamesUnderPackage(cl, pkg)) {
                discoveredCount++;
                Class<?> clazz;
                try {
                    clazz = Class.forName(className, false, cl);
                } catch (Throwable t) {
                    failedCount++;
                    continue;
                }

                if (!Tool.class.isAssignableFrom(clazz)) continue;
                if (clazz.isInterface() || Modifier.isAbstract(clazz.getModifiers())) continue;
                if (clazz == Tool.class) continue;
                if (!shouldAutoRegister(clazz)) {
                    skippedCount++;
                    continue;
                }

                Tool tool;
                try {
                    Constructor<?> ctor = clazz.getDeclaredConstructor();
                    if (!ctor.canAccess(null)) {
                        ctor.setAccessible(true);
                    }
                    Object obj = ctor.newInstance();
                    if (!(obj instanceof Tool)) {
                        failedCount++;
                        continue;
                    }
                    tool = (Tool) obj;
                    instantiatedCount++;
                } catch (Throwable t) {
                    failedCount++;
                    continue;
                }

                if (registerInternal(tool, "classpath-scan")) {
                    registeredCount++;
                }
            }
        }

        AppLog.info("[tool] classpath scan summary: packages=" + pkgs
                + ", discoveredClasses=" + discoveredCount
                + ", instantiated=" + instantiatedCount
                + ", registered=" + registeredCount
                + ", skipped=" + skippedCount
                + ", failed=" + failedCount);
    }

    /**
     * 获取需要扫描的包列表（支持多包）。
     *
     * <p>优先级：System Property {@code workagents.tools.scanPackages} → 配置文件 {@code tools.scan.packages} → 默认 {@code com.qiyi.tools}。</p>
     */
    private static Set<String> getScanPackages() {
        String raw = System.getProperty("workagents.tools.scanPackages");
        if (raw == null || raw.trim().isEmpty()) {
            raw = AppConfig.getInstance().getToolsScanPackages();
        }
        if (raw == null || raw.trim().isEmpty()) {
            raw = "com.qiyi.tools";
        }
        Set<String> pkgs = new LinkedHashSet<>();
        for (String seg : raw.split("[,;\\s]+")) {
            if (seg == null) continue;
            String v = seg.trim();
            if (!v.isEmpty()) pkgs.add(v);
        }
        if (pkgs.isEmpty()) pkgs.add("com.qiyi.tools");
        return pkgs;
    }

    /**
     * 判断某个 Tool 实现类是否允许被自动注册。
     *
     * <p>优先使用 {@link Tool.Info#register()}；同时兼容 {@link Tool.AutoRegister} 的显式开关。</p>
     */
    private static boolean shouldAutoRegister(Class<?> clazz) {
        Tool.Info info = clazz.getAnnotation(Tool.Info.class);
        if (info != null && !info.register()) return false;
        Tool.AutoRegister auto = clazz.getAnnotation(Tool.AutoRegister.class);
        if (auto != null && !auto.value()) return false;
        return true;
    }

    /**
     * 列出某个包（含子包）下的所有 class 全限定名。
     *
     * <p>实现同时支持目录（开发/测试 classpath）与 jar（生产打包）两种形态。</p>
     */
    private static Set<String> listClassNamesUnderPackage(ClassLoader cl, String basePackage) {
        String pkg = basePackage == null ? "" : basePackage.trim();
        if (pkg.isEmpty()) return java.util.Collections.emptySet();

        String path = pkg.replace('.', '/');
        Set<String> out = new LinkedHashSet<>();
        try {
            Enumeration<URL> urls = cl.getResources(path);
            while (urls.hasMoreElements()) {
                URL url = urls.nextElement();
                if (url == null) continue;
                String protocol = url.getProtocol();
                if ("file".equalsIgnoreCase(protocol)) {
                    try {
                        Path root = Paths.get(url.toURI());
                        out.addAll(listClassNamesFromDirectory(root, pkg));
                    } catch (Exception ignored) {
                    }
                } else if ("jar".equalsIgnoreCase(protocol)) {
                    try {
                        JarURLConnection conn = (JarURLConnection) url.openConnection();
                        try (JarFile jar = conn.getJarFile()) {
                            out.addAll(listClassNamesFromJar(jar, path));
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /**
     * 从 classpath 目录中收集 class 文件并转为全限定名。
     *
     * <p>仅收集顶层类（忽略内部类 $）。</p>
     */
    private static Set<String> listClassNamesFromDirectory(Path rootDir, String basePackage) {
        Set<String> out = new LinkedHashSet<>();
        if (rootDir == null) return out;
        if (!Files.exists(rootDir)) return out;

        Queue<Path> q = new ArrayDeque<>();
        q.add(rootDir);
        while (!q.isEmpty()) {
            Path p = q.poll();
            try {
                if (Files.isDirectory(p)) {
                    try (java.util.stream.Stream<Path> s = Files.list(p)) {
                        s.forEach(q::add);
                    }
                    continue;
                }
                String fn = p.getFileName() == null ? null : p.getFileName().toString();
                if (fn == null || !fn.endsWith(".class")) continue;
                if (fn.contains("$")) continue;

                Path rel = rootDir.relativize(p);
                String relStr = rel.toString().replace('/', '.').replace('\\', '.');
                if (!relStr.endsWith(".class")) continue;
                String cls = relStr.substring(0, relStr.length() - ".class".length());
                out.add(basePackage + "." + cls);
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    /**
     * 从 jar 包中收集 class 文件并转为全限定名。
     *
     * <p>仅收集顶层类（忽略内部类 $）。</p>
     */
    private static Set<String> listClassNamesFromJar(JarFile jar, String basePath) {
        Set<String> out = new LinkedHashSet<>();
        if (jar == null) return out;
        Enumeration<JarEntry> entries = jar.entries();
        while (entries.hasMoreElements()) {
            JarEntry e = entries.nextElement();
            if (e == null) continue;
            String name = e.getName();
            if (name == null) continue;
            if (!name.startsWith(basePath + "/")) continue;
            if (!name.endsWith(".class")) continue;
            if (name.contains("$")) continue;
            String cls = name.substring(0, name.length() - ".class".length()).replace('/', '.');
            out.add(cls);
        }
        return out;
    }

    public static boolean register(Tool tool) {
        return registerInternal(tool, "manual");
    }

    /**
     * 注册单个工具实例到全局注册表。
     *
     * <p>注册以 tool name 为唯一键；当 name 缺失、抛异常或重复时将拒绝注册并返回 false。</p>
     */
    private static boolean registerInternal(Tool tool, String source) {
        if (tool == null) return false;
        String name;
        try {
            name = tool.getName();
        } catch (Exception e) {
            AppLog.warn("[tool] invalid tool name (exception), source=" + source + ", class=" + tool.getClass().getName() + ", err=" + e.getMessage());
            return false;
        }
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
