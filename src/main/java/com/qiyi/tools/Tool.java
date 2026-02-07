package com.qiyi.tools;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.qiyi.component.ComponentId;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Agent 可调用的原子能力抽象（“工具”）。
 *
 * <p>工具应尽量做到：</p>
 * <ul>
 *     <li>无状态或轻状态：可复用、可并发</li>
 *     <li>通过 ToolContext/ToolMessenger 与外部交互，避免耦合具体渠道（钉钉/控制台等）</li>
 *     <li>如依赖外部组件（例如钉钉客户端、富途连接），通过 requiredComponents() 显式声明</li>
 * </ul>
 */
public interface Tool {
    String getName();
    String getDescription();
    String execute(JSONObject params, ToolContext context, ToolMessenger messenger);

    /**
     * 对 LLM 规划出来的单条任务做“入参补全/纠错”。
     *
     * <p>调用时机：LLM 输出 tasks 后、真正执行 tool 之前。适用于：</p>
     * <ul>
     *     <li>从 userText 中补齐遗漏的必填参数（例如分组名、订单号等）</li>
     *     <li>把常见的别名/同义参数归一到 Schema 参数名</li>
     *     <li>对明显不合法的参数做轻量修正（不做业务推理，不发起外部调用）</li>
     * </ul>
     *
     * <p>注意：实现应尽量保持幂等；如需写回参数，应写入 plannedTask.parameters。</p>
     */
    default void enrichPlannedTask(String userText, JSONObject plannedTask) {
    }

    /**
     * 参数展示名（用于 UI/回传提示的可读性），建议由工具自己提供，避免全局散落维护。
     *
     * <p>ToolManager 在导出 schema 时会优先使用该映射；当工具未提供时再回退到全局兜底映射。</p>
     */
    default Map<String, String> getParamDisplayNames(String locale) {
        return Collections.emptyMap();
    }

    default List<ComponentId> requiredComponents() {
        return Collections.emptyList();
    }

    default String getBusinessDomain() {
        String pkg = this.getClass().getPackage() != null ? this.getClass().getPackage().getName() : "";
        String prefix = "com.qiyi.tools.";
        if (pkg.startsWith(prefix)) {
            String sub = pkg.substring(prefix.length());
            int dotIndex = sub.indexOf('.');
            if (dotIndex > 0) {
                return sub.substring(0, dotIndex);
            }
            if (!sub.isEmpty()) {
                return sub;
            }
        }
        return "general";
    }

    default ToolType getType() {
        String domain = getBusinessDomain();
        
        if ("android".equalsIgnoreCase(domain)) {
            return ToolType.AUTO_MOBILE_SCRIPT;
        }
        return ToolType.API;
    }

    default InterfaceDescription getInterfaceDescription() {
        return InterfaceDescription.fromLegacyDescription(getDescription());
    }

    enum ToolType {
        API("API"),
        CHROME_PLUGIN_SERVICE("Chrome Plugin Service"),
        AUTO_WEB_SCRIPT("Auto Web Script"),
        AUTO_MOBILE_SCRIPT("Auto Mobile Script");

        private final String label;

        ToolType(String label) {
            this.label = label;
        }

        public String getLabel() {
            return label;
        }
    }

    final class InterfaceDescription {
        private final JSONObject input;
        private final JSONObject output;
        private final JSONArray errors;

        public InterfaceDescription(JSONObject input, JSONObject output, JSONArray errors) {
            this.input = input;
            this.output = output;
            this.errors = errors;
        }

        public JSONObject getInput() {
            return input;
        }

        public JSONObject getOutput() {
            return output;
        }

        public JSONArray getErrors() {
            return errors;
        }

        public JSONObject toJson() {
            JSONObject obj = new JSONObject();
            obj.put("input", input);
            obj.put("output", output);
            obj.put("errors", errors);
            return obj;
        }

        public static InterfaceDescription fromLegacyDescription(String legacyDescription) {
            JSONObject input = new JSONObject();
            input.put("type", "object");
            JSONObject properties = new JSONObject();
            JSONArray required = new JSONArray();

            if (legacyDescription != null) {
                String[] markers = new String[]{"Parameters:", "参数：", "参数:", "入参：", "入参:"};
                String paramsPart = null;
                for (String marker : markers) {
                    int idx = legacyDescription.indexOf(marker);
                    if (idx >= 0) {
                        paramsPart = legacyDescription.substring(idx + marker.length()).trim();
                        break;
                    }
                }

                if (paramsPart != null && !paramsPart.isEmpty()) {
                    for (String seg : splitLegacySegments(paramsPart)) {
                        String s = seg.trim();
                        if (s.isEmpty()) continue;
                        if ("none".equalsIgnoreCase(s) || "无".equalsIgnoreCase(s) || "无参数".equalsIgnoreCase(s)) {
                            continue;
                        }

                        String name = s;
                        String desc = null;
                        int lp = s.indexOf('(');
                        int rp = s.lastIndexOf(')');
                        if (lp > 0 && rp > lp) {
                            name = s.substring(0, lp).trim();
                            desc = s.substring(lp + 1, rp).trim();
                        } else {
                            int colon = s.indexOf(':');
                            if (colon > 0) {
                                name = s.substring(0, colon).trim();
                                desc = s.substring(colon + 1).trim();
                            }
                        }

                        if (name.isEmpty()) continue;

                        JSONObject prop = new JSONObject();
                        prop.put("type", "string");
                        if (desc != null && !desc.isEmpty()) {
                            prop.put("description", desc);
                            String lower = desc.toLowerCase();
                            if (lower.contains("mandatory") || lower.contains("required") || desc.contains("必填") || desc.contains("必选")) {
                                required.add(name);
                            }
                        }
                        properties.put(name, prop);
                    }
                }
            }

            input.put("properties", properties);
            if (!required.isEmpty()) {
                input.put("required", required);
            }

            JSONObject output = new JSONObject();
            output.put("type", "string");
            output.put("description", "工具执行结果（文本）");

            JSONArray errors = new JSONArray();
            JSONObject invalidParams = new JSONObject();
            invalidParams.put("code", "INVALID_PARAMS");
            invalidParams.put("message", "参数缺失或格式错误");
            errors.add(invalidParams);
            JSONObject execError = new JSONObject();
            execError.put("code", "EXECUTION_ERROR");
            execError.put("message", "执行失败");
            errors.add(execError);

            return new InterfaceDescription(input, output, errors);
        }

        private static List<String> splitLegacySegments(String paramsPart) {
            List<String> out = new ArrayList<>();
            if (paramsPart == null || paramsPart.isEmpty()) return out;

            StringBuilder cur = new StringBuilder();
            int depth = 0;
            for (int i = 0; i < paramsPart.length(); i++) {
                char c = paramsPart.charAt(i);
                if (c == '(' || c == '（') {
                    depth++;
                    cur.append(c);
                    continue;
                }
                if (c == ')' || c == '）') {
                    if (depth > 0) depth--;
                    cur.append(c);
                    continue;
                }

                boolean isSplit = (c == ',' || c == '，' || c == ';' || c == '；');
                if (isSplit && depth == 0) {
                    out.add(cur.toString());
                    cur.setLength(0);
                    continue;
                }
                cur.append(c);
            }
            if (cur.length() > 0) out.add(cur.toString());
            return out;
        }
    }
}
