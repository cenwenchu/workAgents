package com.qiyi.tools;

import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.qiyi.component.ComponentId;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.ArrayList;
import java.util.Arrays;
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
    /**
     * Tool 元信息声明（注释模式）。
     *
     * <p>当一个类仅实现 {@link #execute(JSONObject, ToolContext, ToolMessenger)} 时，可通过该注解提供
     * name/description 等必要信息，使其能被系统发现、导出 schema 并被 LLM 调用。</p>
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    @interface Info {
        /**
         * 工具唯一名称（任务规划与路由的主键）。
         */
        String name();

        /**
         * 工具描述（会被导出到 schema，并作为 LLM 选型与参数抽取的主要依据）。
         *
         * <p>历史兼容：如描述中包含 “Parameters:” / “参数:” 等标记，系统会尝试从文本中解析入参结构。</p>
         */
        String description();

        /**
         * 是否允许该 Tool 被自动发现并注册。
         *
         * <p>用于排除抽象基类之外的“可实例化但不应暴露”的实现，或用于灰度/临时下线。</p>
         */
        boolean register() default true;

        /**
         * 业务域（用于注入 skills prompt、分类展示等）。
         *
         * <p>建议填写完整包名（如 com.qiyi.tools.futu）；留空则默认使用类所在包名；当包名不可用时回退为 general。</p>
         */
        String businessDomain() default "";

        /**
         * 工具类型（用于前端展示与路由策略）。
         */
        ToolType type() default ToolType.API;

        /**
         * 工具执行所需外部组件依赖（由 {@link com.qiyi.component.ComponentManager} 管理）。
         */
        ComponentId[] requiredComponents() default {};
    }

    /**
     * Tool 自动注册控制（注释模式）。
     *
     * <p>与 {@link Info#register()} 语义等价，提供更轻量的开关形式。</p>
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.TYPE)
    @interface AutoRegister {
        boolean value() default true;
    }

    /**
     * 获取工具名称。
     *
     * <p>默认优先读取 {@link Info#name()}；如未提供则抛出异常，避免工具被注册后无法路由。</p>
     */
    default String getName() {
        Info info = this.getClass().getAnnotation(Info.class);
        if (info != null && info.name() != null && !info.name().trim().isEmpty()) {
            return info.name().trim();
        }
        throw new IllegalStateException("Tool name missing. Override getName() or add @Tool.Info. class=" + this.getClass().getName());
    }

    /**
     * 获取工具描述。
     *
     * <p>默认优先读取 {@link Info#description()}；如未提供则抛出异常，避免 schema 导出为空。</p>
     */
    default String getDescription() {
        Info info = this.getClass().getAnnotation(Info.class);
        if (info != null && info.description() != null && !info.description().trim().isEmpty()) {
            return info.description().trim();
        }
        throw new IllegalStateException("Tool description missing. Override getDescription() or add @Tool.Info. class=" + this.getClass().getName());
    }

    /**
     * 工具核心执行入口。
     *
     * <p>params 为本次任务的参数对象；context 为执行上下文（如会话/环境信息）；messenger 用于进度与结果回传。</p>
     */
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

    /**
     * 声明工具依赖的组件列表。
     *
     * <p>默认优先读取 {@link Info#requiredComponents()}；未声明则返回空集合。</p>
     */
    default List<ComponentId> requiredComponents() {
        Info info = this.getClass().getAnnotation(Info.class);
        if (info != null && info.requiredComponents() != null && info.requiredComponents().length > 0) {
            return Arrays.asList(info.requiredComponents());
        }
        return Collections.emptyList();
    }

    /**
     * 获取工具业务域。
     *
     * <p>默认优先读取 {@link Info#businessDomain()}；未声明则使用类所在包名；不可用时回退为 general。</p>
     */
    default String getBusinessDomain() {
        Info info = this.getClass().getAnnotation(Info.class);
        if (info != null && info.businessDomain() != null && !info.businessDomain().trim().isEmpty()) {
            return info.businessDomain().trim();
        }
        Package p = this.getClass().getPackage();
        if (p == null) return "general";
        String pkg = p.getName();
        if (pkg == null || pkg.trim().isEmpty()) return "general";
        return pkg.trim();
    }

    /**
     * 获取工具类型。
     *
     * <p>默认优先读取 {@link Info#type()}；未声明则使用业务域做简单推断（如 android 归类为移动端脚本）。</p>
     */
    default ToolType getType() {
        Info info = this.getClass().getAnnotation(Info.class);
        if (info != null && info.type() != null) return info.type();
        String domain = getBusinessDomain();

        if (domain != null) {
            String d = domain.trim().toLowerCase();
            if ("android".equals(d) || d.endsWith(".android") || d.contains(".android.")) {
                return ToolType.AUTO_MOBILE_SCRIPT;
            }
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
