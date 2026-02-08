package com.qiyi.tools;

import com.alibaba.fastjson2.JSONObject;
import com.qiyi.component.ComponentId;
import com.qiyi.component.ComponentManager;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tool 生态健康检查测试。
 *
 * <p>该测试用于在本地/CI 提前发现工具注册、schema 导出、组件依赖等问题。</p>
 */
public class ToolDoctorTest {

    /**
     * 校验工具可被发现、无重复名称、schema 可导出、组件依赖可满足。
     *
     * <p>工具发现以 {@link ToolManager} 为准；ServiceLoader 清单存在与否不影响核心断言。</p>
     */
    @Test
    public void toolDiscoveryAndHealth() {
        ClassLoader cl = ToolDoctorTest.class.getClassLoader();
        String servicesPath = "META-INF/services/" + Tool.class.getName();
        List<String> configuredToolClasses = new ArrayList<>();
        try (InputStream in = cl.getResourceAsStream(servicesPath)) {
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
        } catch (Exception e) {
            configuredToolClasses.clear();
        }

        List<Tool> discovered = new ArrayList<>(ToolManager.getAll());
        assertFalse(discovered.isEmpty(),
                "No tools discovered. Ensure tools are on classpath and can be instantiated.");

        Map<String, List<String>> nameToClasses = new LinkedHashMap<>();
        for (Tool t : discovered) {
            String name = t.getName();
            String cls = t.getClass().getName();
            nameToClasses.computeIfAbsent(name, k -> new ArrayList<>()).add(cls);
        }
        List<String> duplicateNames = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : nameToClasses.entrySet()) {
            if (e.getKey() == null || e.getKey().trim().isEmpty()) {
                duplicateNames.add("INVALID_NAME (blank) -> " + e.getValue());
                continue;
            }
            if (e.getValue().size() > 1) {
                duplicateNames.add(e.getKey() + " -> " + e.getValue());
            }
        }
        assertTrue(duplicateNames.isEmpty(), "Duplicated tool names discovered: " + duplicateNames);

        List<String> schemaIssues = new ArrayList<>();
        for (Tool t : discovered) {
            JSONObject schema = ToolManager.toToolSchema(t);
            if (schema == null || schema.isEmpty()) {
                schemaIssues.add(t.getClass().getName() + ": empty schema");
                continue;
            }

            String desc = t.getDescription();
            boolean declaresParams = containsAny(desc, "Parameters:", "参数：", "参数:", "入参：", "入参:");
            JSONObject iface = schema.getJSONObject("interface");
            JSONObject input = iface == null ? null : iface.getJSONObject("input");
            JSONObject props = input == null ? null : input.getJSONObject("properties");
            boolean parsedAnyParams = props != null && !props.isEmpty();

            if (declaresParams && !parsedAnyParams) {
                schemaIssues.add(t.getName() + " (" + t.getClass().getName() + "): declares params but schema input.properties is empty");
            }
        }
        assertTrue(schemaIssues.isEmpty(), "Tool schema issues: " + schemaIssues);

        Set<String> domains = new LinkedHashSet<>();
        for (Tool t : discovered) {
            String d = t.getBusinessDomain();
            if (d == null) continue;
            String v = d.trim();
            if (!v.isEmpty() && !"general".equalsIgnoreCase(v)) {
                domains.add(v);
            }
        }
        List<String> missingDomainSkills = new ArrayList<>();
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
        if (!missingDomainSkills.isEmpty()) {
            System.out.println("[ToolDoctor] WARN: missing domain skills md: " + missingDomainSkills);
        }

        ComponentManager mgr = ComponentManager.getInstance();
        mgr.initDefaults();
        List<String> missingComponents = new ArrayList<>();
        for (Tool t : discovered) {
            List<ComponentId> required = t.requiredComponents();
            if (required == null || required.isEmpty()) continue;
            for (ComponentId id : required) {
                if (id == null) continue;
                if (mgr.get(id) == null) {
                    missingComponents.add(t.getName() + " requires " + id.id());
                }
            }
        }
        assertTrue(missingComponents.isEmpty(), "Tools require components not registered in ComponentManager.initDefaults(): " + missingComponents);
    }

    private static boolean containsAny(String text, String... needles) {
        if (text == null || text.isEmpty()) return false;
        for (String n : needles) {
            if (n != null && !n.isEmpty() && text.contains(n)) return true;
        }
        return false;
    }
}
