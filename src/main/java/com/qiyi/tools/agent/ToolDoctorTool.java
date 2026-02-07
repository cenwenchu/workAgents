package com.qiyi.tools.agent;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.qiyi.component.ComponentId;
import com.qiyi.component.ComponentManager;
import com.qiyi.tools.Tool;
import com.qiyi.tools.ToolContext;
import com.qiyi.tools.ToolManager;
import com.qiyi.tools.ToolMessenger;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;

/**
 * Tool 自检工具（运行态）。
 *
 * <p>用于在正式程序中快速检查工具生态是否健康，输出 JSON 报告（便于在控制台/钉钉直接查看）。</p>
 *
 * <p>检查范围：</p>
 * <ul>
 *     <li>ServiceLoader 发现结果：是否存在 services 清单、清单内容、实际发现的工具列表</li>
 *     <li>重复 tool name：同名工具会导致注册/路由歧义</li>
 *     <li>Schema 健康：description 声明了参数但 schema 解析为空（常见于格式不规范）</li>
 *     <li>skills.md 缺失：按 domain 检查 resources/com/qiyi/skills/&lt;domain&gt;.md（缺失不阻断，仅提示）</li>
 *     <li>组件依赖：requiredComponents() 必须在 ComponentManager.initDefaults() 覆盖范围内</li>
 * </ul>
 *
 * <p>返回字段示例：</p>
 * <ul>
 *     <li>ok: true/false（缺失工具、重复、schema 问题、组件缺失会置为 false）</li>
 *     <li>discoveredTools: [{name, class, domain, type}, ...]</li>
 *     <li>schemaIssues / duplicateToolNames / missingComponents / missingDomainSkills</li>
 * </ul>
 */
public class ToolDoctorTool implements Tool {
    @Override
    public String getName() {
        return "tool_doctor";
    }

    @Override
    public String getDescription() {
        return "对当前 Agent 的 tools 做自检（发现/重复/Schema/skills.md/组件依赖）。参数：无。返回：JSON 报告。";
    }

    @Override
    public String execute(JSONObject params, ToolContext context, ToolMessenger messenger) {
        JSONObject report = new JSONObject();

        ClassLoader cl = ToolDoctorTool.class.getClassLoader();
        String servicesPath = "META-INF/services/" + Tool.class.getName();
        JSONArray configuredToolClasses = new JSONArray();
        boolean servicesFilePresent = false;
        try (InputStream in = cl.getResourceAsStream(servicesPath)) {
            servicesFilePresent = (in != null);
            if (in != null) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        String v = line.trim();
                        if (v.isEmpty() || v.startsWith("#")) continue;
                        configuredToolClasses.add(v);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        report.put("servicesFilePresent", servicesFilePresent);
        report.put("servicesPath", servicesPath);
        report.put("configuredToolClasses", configuredToolClasses);

        List<Tool> discovered = new ArrayList<>();
        JSONArray discoveredTools = new JSONArray();
        for (Tool t : ServiceLoader.load(Tool.class)) {
            if (t == null) continue;
            discovered.add(t);
            JSONObject one = new JSONObject();
            one.put("name", t.getName());
            one.put("class", t.getClass().getName());
            one.put("domain", t.getBusinessDomain());
            Tool.ToolType type = t.getType();
            one.put("type", type == null ? null : type.getLabel());
            discoveredTools.add(one);
        }
        report.put("discoveredCount", discovered.size());
        report.put("discoveredTools", discoveredTools);

        Map<String, List<String>> nameToClasses = new LinkedHashMap<>();
        for (Tool t : discovered) {
            String name = t.getName();
            String cls = t.getClass().getName();
            nameToClasses.computeIfAbsent(name, k -> new ArrayList<>()).add(cls);
        }
        JSONArray duplicateToolNames = new JSONArray();
        for (Map.Entry<String, List<String>> e : nameToClasses.entrySet()) {
            String name = e.getKey();
            List<String> classes = e.getValue();
            if (name == null || name.trim().isEmpty()) {
                JSONObject one = new JSONObject();
                one.put("name", "INVALID_NAME");
                one.put("classes", classes);
                duplicateToolNames.add(one);
                continue;
            }
            if (classes.size() > 1) {
                JSONObject one = new JSONObject();
                one.put("name", name);
                one.put("classes", classes);
                duplicateToolNames.add(one);
            }
        }
        report.put("duplicateToolNames", duplicateToolNames);

        JSONArray schemaIssues = new JSONArray();
        for (Tool t : discovered) {
            JSONObject schema = ToolManager.toToolSchema(t);
            if (schema == null || schema.isEmpty()) {
                JSONObject one = new JSONObject();
                one.put("tool", t.getName());
                one.put("class", t.getClass().getName());
                one.put("issue", "empty_schema");
                schemaIssues.add(one);
                continue;
            }

            String desc = t.getDescription();
            boolean declaresParams = containsAny(desc, "Parameters:", "参数：", "参数:", "入参：", "入参:");
            JSONObject iface = schema.getJSONObject("interface");
            JSONObject input = iface == null ? null : iface.getJSONObject("input");
            JSONObject props = input == null ? null : input.getJSONObject("properties");
            boolean parsedAnyParams = props != null && !props.isEmpty();
            if (declaresParams && !parsedAnyParams) {
                JSONObject one = new JSONObject();
                one.put("tool", t.getName());
                one.put("class", t.getClass().getName());
                one.put("issue", "declares_params_but_schema_empty");
                schemaIssues.add(one);
            }
        }
        report.put("schemaIssues", schemaIssues);

        Set<String> domains = new LinkedHashSet<>();
        for (Tool t : discovered) {
            String d = t.getBusinessDomain();
            if (d == null) continue;
            String v = d.trim();
            if (!v.isEmpty() && !"general".equalsIgnoreCase(v)) {
                domains.add(v);
            }
        }
        JSONArray missingDomainSkills = new JSONArray();
        for (String domain : domains) {
            String path = "com/qiyi/skills/" + domain + ".md";
            try (InputStream in = cl.getResourceAsStream(path)) {
                if (in == null) {
                    missingDomainSkills.add(domain);
                }
            } catch (Exception e) {
                missingDomainSkills.add(domain);
            }
        }
        report.put("missingDomainSkills", missingDomainSkills);

        ComponentManager mgr = ComponentManager.getInstance();
        mgr.initDefaults();
        JSONArray missingComponents = new JSONArray();
        for (Tool t : discovered) {
            List<ComponentId> required = t.requiredComponents();
            if (required == null || required.isEmpty()) continue;
            for (ComponentId id : required) {
                if (id == null) continue;
                if (mgr.get(id) == null) {
                    JSONObject one = new JSONObject();
                    one.put("tool", t.getName());
                    one.put("componentId", id.id());
                    missingComponents.add(one);
                }
            }
        }
        report.put("missingComponents", missingComponents);

        boolean ok = !discovered.isEmpty()
                && duplicateToolNames.isEmpty()
                && schemaIssues.isEmpty()
                && missingComponents.isEmpty();
        report.put("ok", ok);

        String result = report.toJSONString();
        if (messenger != null) {
            try {
                messenger.sendText(result);
            } catch (Exception ignored) {
            }
        }
        return result;
    }

    private static boolean containsAny(String text, String... needles) {
        if (text == null || text.isEmpty()) return false;
        for (String n : needles) {
            if (n != null && !n.isEmpty() && text.contains(n)) return true;
        }
        return false;
    }
}
