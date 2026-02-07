package com.qiyi.service.autoweb;

import com.qiyi.util.LLMUtil;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Groovy 脚本与提示词封装
 * 负责加载提示模板、拼装 payload、调用模型、归一化代码并执行
 */
class GroovySupport {
    private static String GROOVY_SCRIPT_PROMPT_TEMPLATE = "";
    private static String REFINED_GROOVY_SCRIPT_PROMPT_TEMPLATE = "";

    /**
     * 从 autoweb/skills 目录加载提示词模板
     */
    static void loadPrompts() {
        loadPrompts(null);
    }

    static void loadPrompts(java.util.function.Consumer<String> uiLogger) {
        try {
            Path skillsDir = Paths.get(System.getProperty("user.dir"), "autoweb", "skills");

            Path groovyPromptPath = skillsDir.resolve("groovy_script_prompt.txt");
            if (Files.exists(groovyPromptPath)) {
                String next = new String(Files.readAllBytes(groovyPromptPath), java.nio.charset.StandardCharsets.UTF_8);
                if (!next.equals(GROOVY_SCRIPT_PROMPT_TEMPLATE)) {
                    GROOVY_SCRIPT_PROMPT_TEMPLATE = next;
                    StorageSupport.log(uiLogger, "PROMPT_LOAD", "Loaded groovy_script_prompt.txt", null);
                }
            } else {
                StorageSupport.log(uiLogger, "PROMPT_LOAD", "groovy_script_prompt.txt not found | path=" + groovyPromptPath.toAbsolutePath(), null);
            }

            Path refinedPromptPath = skillsDir.resolve("refined_groovy_script_prompt.txt");
            if (Files.exists(refinedPromptPath)) {
                String next = new String(Files.readAllBytes(refinedPromptPath), java.nio.charset.StandardCharsets.UTF_8);
                if (!next.equals(REFINED_GROOVY_SCRIPT_PROMPT_TEMPLATE)) {
                    REFINED_GROOVY_SCRIPT_PROMPT_TEMPLATE = next;
                    StorageSupport.log(uiLogger, "PROMPT_LOAD", "Loaded refined_groovy_script_prompt.txt", null);
                }
            } else {
                StorageSupport.log(uiLogger, "PROMPT_LOAD", "refined_groovy_script_prompt.txt not found | path=" + refinedPromptPath.toAbsolutePath(), null);
            }

        } catch (IOException e) {
            StorageSupport.log(uiLogger, "PROMPT_LOAD", "Error loading prompts", e);
        }
    }

    /**
     * 生成 Groovy 代码或计划文本
     *
     * @param userPrompt 用户任务描述
     * @param cleanedHtml 已清洗的 payload
     * @param uiLogger 可选日志输出
     * @param modelName 模型名称
     * @return 模型输出文本
     */
    static String generateGroovyScript(String userPrompt, String cleanedHtml, java.util.function.Consumer<String> uiLogger, String modelName) {
        loadPrompts(uiLogger);

        String mode = extractModeFromPayload(cleanedHtml);
        String template = GROOVY_SCRIPT_PROMPT_TEMPLATE;

        if ("PLAN_ONLY".equalsIgnoreCase(mode) || "PLAN_REFINE".equalsIgnoreCase(mode) || "PLAN_ENTRY".equalsIgnoreCase(mode)) {
            template = stripTaggedBlocks(template, "[CODEGEN_ONLY_START]", "[CODEGEN_ONLY_END]");
        }

        String prompt = String.format(template, userPrompt, cleanedHtml);

        String ts = StorageSupport.newDebugTimestamp();
        String debugMode = (mode == null || mode.trim().isEmpty()) ? "UNKNOWN" : mode.trim();
        StorageSupport.logPayloadSummary(cleanedHtml, uiLogger);
        StorageSupport.logRequestBytes("generateGroovyScript", modelName, debugMode, cleanedHtml, prompt, uiLogger);
        String payloadPath = StorageSupport.saveDebugArtifact(ts, modelName, debugMode, "payload", cleanedHtml, uiLogger);
        String promptPath = StorageSupport.saveDebugArtifact(ts, modelName, debugMode, "prompt", prompt, uiLogger);

        if (uiLogger != null) {
            uiLogger.accept("Prompt Context Length (Get Code): " + prompt.length() + " chars");
            if (payloadPath != null || promptPath != null) {
                uiLogger.accept("Debug Saved: ts=" + ts + ", payload=" + (payloadPath == null ? "" : payloadPath) + ", prompt=" + (promptPath == null ? "" : promptPath));
            }
        }

        String result = callModel(modelName, prompt, uiLogger);

        String responsePath = StorageSupport.saveDebugArtifact(ts, modelName, debugMode, "response", result, uiLogger);
        if (uiLogger != null && responsePath != null) {
            uiLogger.accept("Debug Saved: ts=" + ts + ", response=" + responsePath);
            uiLogger.accept("请求完成: stage=generateGroovyScript, model=" + (modelName == null ? "" : modelName) + ", mode=" + debugMode + ", bytes(response)=" + StorageSupport.utf8Bytes(result));
        }
        return result;
    }

    /**
     * 从 payload 中解析 MODE 字段
     *
     * @param payload payload 文本
     * @return 解析到的 MODE
     */
    static String extractModeFromPayload(String payload) {
        if (payload == null) return "";
        try {
            java.util.regex.Pattern p = java.util.regex.Pattern.compile("(?m)^\\s*MODE\\s*:\\s*([A-Z_]+)\\s*$");
            java.util.regex.Matcher m = p.matcher(payload);
            if (m.find()) {
                String v = m.group(1);
                return v == null ? "" : v.trim();
            }
        } catch (Exception ignored) {}
        return "";
    }

    private static String stripTaggedBlocks(String src, String startTag, String endTag) {
        if (src == null) return "";
        if (startTag == null || endTag == null) return src;
        String out = src;
        int guard = 0;
        while (guard < 50) {
            int s = out.indexOf(startTag);
            if (s < 0) break;
            int e = out.indexOf(endTag, s + startTag.length());
            if (e < 0) {
                out = out.substring(0, s);
                break;
            }
            out = out.substring(0, s) + out.substring(e + endTag.length());
            guard++;
        }
        return out;
    }

    /**
     * 根据执行结果和补充说明生成修正后的 Groovy 脚本
     *
     * @param originalUserPrompt 用户任务描述
     * @param cleanedHtml 已清洗的 payload
     * @param previousCode 旧代码
     * @param execOutput 执行输出
     * @param refineHint 修正说明
     * @param uiLogger 可选日志输出
     * @param modelName 模型名称
     * @return 模型输出文本
     */
    static String generateRefinedGroovyScript(
        String originalUserPrompt,
        String cleanedHtml,
        String previousCode,
        String execOutput,
        String refineHint,
        java.util.function.Consumer<String> uiLogger,
        String modelName
    ) {
        loadPrompts(uiLogger);

        String mode = extractModeFromPayload(cleanedHtml);
        String template = REFINED_GROOVY_SCRIPT_PROMPT_TEMPLATE;
        if ("PLAN_REFINE".equalsIgnoreCase(mode)) {
            template = stripTaggedBlocks(template, "[CODEGEN_ONLY_START]", "[CODEGEN_ONLY_END]");
        }

        String prompt = String.format(
            template,
            originalUserPrompt,
            cleanedHtml,
            previousCode,
            execOutput,
            refineHint
        );

        String ts = StorageSupport.newDebugTimestamp();
        String debugMode = (mode == null || mode.trim().isEmpty()) ? "UNKNOWN" : mode.trim();
        StorageSupport.logPayloadSummary(cleanedHtml, uiLogger);
        StorageSupport.logRequestBytes("generateRefinedGroovyScript", modelName, debugMode, cleanedHtml, prompt, uiLogger);
        String payloadPath = StorageSupport.saveDebugArtifact(ts, modelName, debugMode, "payload", cleanedHtml, uiLogger);
        String promptPath = StorageSupport.saveDebugArtifact(ts, modelName, debugMode, "prompt", prompt, uiLogger);

        if (uiLogger != null) {
            uiLogger.accept("Prompt Context Length (Refine Code): " + prompt.length() + " chars");
            if (payloadPath != null || promptPath != null) {
                uiLogger.accept("Debug Saved: ts=" + ts + ", payload=" + (payloadPath == null ? "" : payloadPath) + ", prompt=" + (promptPath == null ? "" : promptPath));
            }
        }

        String result = callModel(modelName, prompt, uiLogger);
        String responsePath = StorageSupport.saveDebugArtifact(ts, modelName, debugMode, "response", result, uiLogger);
        if (uiLogger != null && responsePath != null) {
            uiLogger.accept("Debug Saved: ts=" + ts + ", response=" + responsePath);
            uiLogger.accept("请求完成: stage=generateRefinedGroovyScript, model=" + (modelName == null ? "" : modelName) + ", mode=" + debugMode + ", bytes(response)=" + StorageSupport.utf8Bytes(result));
        }
        return result;
    }

    /**
     * 将模型输出归一化为可执行 Groovy 形式
     *
     * @param code 模型输出
     * @return 归一化后的代码
     */
    static String normalizeGeneratedGroovy(String code) {
        if (code == null) return null;
        String normalized = code;
        normalized = normalizePlanBlockCommentFormat(normalized);
        normalized = normalized.replaceAll("(?m)^(\\s*)(PLAN:|THINK:|ANALYSIS:|REASONING:|思考过程|计划|QUESTION:)\\b", "$1// $2");
        normalized = commentPlanMarkersOutsideBlockComment(normalized);
        normalized = normalized.replaceAll("(?m)^(\\s*)(-\\s*[Pp]lan\\b.*)", "$1// $2");
        normalized = normalized.replaceAll("(?m)^(\\s*)(\\*\\s*[Pp]lan\\b.*)", "$1// $2");
        normalized = normalized.replaceAll("(?m)^(\\s*)(\\[Plan\\].*)", "$1// $2");
        normalized = normalized.replaceAll("(?m)^(\\s*)(<plan>.*)</plan>\\s*$", "$1// $2");
        normalized = normalized.replaceAll("(?m)^(\\s*)(<think>.*)</think>\\s*$", "$1// $2");
        normalized = normalized.replaceAll("(?m)^(\\s*)(思考:.*)", "$1// $2");
        normalized = normalizeClickListboxToSelectDropdown(normalized);

        boolean applyNormalization =
                normalized.contains("web.extractList(") ||
                normalized.matches("(?s).*\\browCount\\b\\s*=\\s*web\\.count\\(.*") ||
                normalized.contains("rowTexts") ||
                normalized.contains("joinedRow");
        if (applyNormalization) {
            String replacement = "def rows = web.extractFirstPageRows(containerSelector, rowSelector, cellSelector)\n" +
                    "rows.each { row -> web.log(row) }\n";
            java.util.regex.Pattern blockPatternA = java.util.regex.Pattern.compile(
                    "(?s)def\\s+rowCount\\s*=\\s*web\\.count\\([^\\n]*\\).*?allRowsOutput\\.each\\s*\\{.*?\\}\\s*"
            );
            java.util.regex.Pattern blockPatternB = java.util.regex.Pattern.compile(
                    "(?s)def\\s+rowCount\\s*=\\s*web\\.count\\([^\\n]*\\).*?(?=def\\s+totalCountText|def\\s+totalText|web\\.getText\\()"
            );
            normalized = blockPatternA.matcher(normalized).replaceAll(replacement);
            normalized = blockPatternB.matcher(normalized).replaceAll(replacement);
            normalized = normalized.replaceAll("(?s)def\\s+rowTexts\\s*=\\s*\\[\\].*?def\\s+joinedRow\\s*=.*?web\\.log\\(joinedRow\\).*?(?=def\\s+totalCountText|def\\s+totalText|web\\.getText\\()", "");
            normalized = normalized.replaceAll("(?s)int\\s+rowCount\\s*=\\s*web\\.count\\([^\\n]*\\).*?for\\s*\\(\\s*int\\s+i\\s*=\\s*0;.*?\\)\\s*\\{.*?web\\.log\\(joinedRow\\)\\s*;?\\s*\\}.*?(?=def\\s+totalCountText|def\\s+totalText|web\\.getText\\()", replacement);

            java.util.regex.Pattern getTextLogAssignPattern = java.util.regex.Pattern.compile("(?s)(?:String|def|var)?\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*web\\.getText\\((\"|')(.*?)\\2\\)\\s*\\n\\s*web\\.log\\(\\1\\)");
            java.util.regex.Matcher getTextLogAssignMatcher = getTextLogAssignPattern.matcher(normalized);
            StringBuffer getTextLogAssignBuffer = new StringBuffer();
            while (getTextLogAssignMatcher.find()) {
                String varName = getTextLogAssignMatcher.group(1);
                String sel = getTextLogAssignMatcher.group(3);
                String replacementBlock = "def " + varName + " = web.getText(\"" + sel.replace("\"", "\\\"") + "\")\nweb.log(" + varName + ")";
                getTextLogAssignMatcher.appendReplacement(getTextLogAssignBuffer, java.util.regex.Matcher.quoteReplacement(replacementBlock));
            }
            getTextLogAssignMatcher.appendTail(getTextLogAssignBuffer);
            normalized = getTextLogAssignBuffer.toString();

            java.util.regex.Pattern getTextLogPattern = java.util.regex.Pattern.compile("(?s)web\\.getText\\((\"|')(.*?)\\1\\)\\s*\\n\\s*web\\.log\\(([^\\)]+)\\)");
            java.util.regex.Matcher getTextLogMatcher = getTextLogPattern.matcher(normalized);
            StringBuffer getTextLogBuffer = new StringBuffer();
            while (getTextLogMatcher.find()) {
                String sel = getTextLogMatcher.group(2);
                String varName = getTextLogMatcher.group(3).trim();
                String replacementBlock = "def " + varName + " = web.getText(\"" + sel.replace("\"", "\\\"") + "\")\nweb.log(" + varName + ")";
                getTextLogMatcher.appendReplacement(getTextLogBuffer, java.util.regex.Matcher.quoteReplacement(replacementBlock));
            }
            getTextLogMatcher.appendTail(getTextLogBuffer);
            normalized = getTextLogBuffer.toString();
        }
        normalized = escapeNonInterpolatedDollarInDoubleQuotedStrings(normalized);
        return normalized;
    }

    private static String normalizeClickListboxToSelectDropdown(String code) {
        if (code == null || code.isEmpty()) return code;
        String[] lines = code.split("\\n", -1);
        java.util.List<String> out = new java.util.ArrayList<>();

        java.util.regex.Pattern openByText = java.util.regex.Pattern.compile("^\\s*web\\.click\\(\\s*(['\"])text=\\\"([^\\\"]+)\\\"\\1\\s*\\)\\s*;?\\s*$");
        java.util.regex.Pattern waitLine = java.util.regex.Pattern.compile("^\\s*web\\.wait\\(\\s*\\d+\\s*\\)\\s*;?\\s*$");
        java.util.regex.Pattern logLine = java.util.regex.Pattern.compile("^\\s*web\\.log\\(.*\\)\\s*;?\\s*$");
        java.util.regex.Pattern blankLine = java.util.regex.Pattern.compile("^\\s*$");
        java.util.regex.Pattern lineComment = java.util.regex.Pattern.compile("^\\s*//.*$");
        java.util.regex.Pattern selectFromListbox = java.util.regex.Pattern.compile("^\\s*web\\.click\\(\\s*(['\"])(?:div\\[role=\\\"listbox\\\"\\]|\\[role=\\\"listbox\\\"\\]|div\\[role=listbox\\]|\\[role=listbox\\])\\s*>>\\s*text=\\\"([^\\\"]+)\\\"\\1\\s*\\)\\s*;?\\s*$");

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            java.util.regex.Matcher mOpen = openByText.matcher(line);
            if (!mOpen.matches()) {
                out.add(line);
                continue;
            }
            String label = mOpen.group(2);

            int j = i + 1;
            java.util.List<String> between = new java.util.ArrayList<>();
            int scanLimit = 10;
            while (j < lines.length && scanLimit-- > 0) {
                String next = lines[j];
                if (waitLine.matcher(next).matches() || logLine.matcher(next).matches() || blankLine.matcher(next).matches() || lineComment.matcher(next).matches()) {
                    between.add(next);
                    j++;
                    continue;
                }
                break;
            }

            if (j < lines.length) {
                java.util.regex.Matcher mPick = selectFromListbox.matcher(lines[j]);
                if (mPick.matches()) {
                    String option = mPick.group(2);
                    String indent = "";
                    int p = 0;
                    while (p < line.length() && Character.isWhitespace(line.charAt(p))) p++;
                    indent = line.substring(0, p);
                    out.addAll(between);
                    out.add(indent + "web.selectDropdown(\"" + label.replace("\\", "\\\\").replace("\"", "\\\"") + "\", \"" + option.replace("\\", "\\\\").replace("\"", "\\\"") + "\")");
                    i = j;
                    continue;
                }
            }

            out.add(line);
        }
        return String.join("\n", out);
    }

    private static String normalizePlanBlockCommentFormat(String code) {
        if (code == null) return null;
        int ps = code.indexOf("PLAN_START");
        int pe = code.indexOf("PLAN_END");
        if (ps < 0 || pe < 0 || pe <= ps) return code;
        int blockStart = code.lastIndexOf("/*", ps);
        if (blockStart < 0) return code;
        int blockEnd = code.indexOf("*/", pe);
        if (blockEnd < 0) return code;

        String before = code.substring(0, blockStart + 2);
        String inside = code.substring(blockStart + 2, blockEnd);
        String after = code.substring(blockEnd);

        String[] lines = inside.split("\\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            int p = 0;
            while (p < line.length() && Character.isWhitespace(line.charAt(p))) p++;
            String indent = line.substring(0, p);
            String rest = line.substring(p);
            if (rest.startsWith("*")) {
                rest = rest.substring(1);
                if (!rest.isEmpty() && rest.charAt(0) == ' ') rest = rest.substring(1);
            }
            if (rest.startsWith("//")) {
                rest = rest.substring(2);
                if (!rest.isEmpty() && rest.charAt(0) == ' ') rest = rest.substring(1);
            }
            lines[i] = indent + rest;
        }
        String rebuiltInside = String.join("\n", lines);
        return before + rebuiltInside + after;
    }

    private static String commentPlanMarkersOutsideBlockComment(String code) {
        if (code == null) return null;
        String[] lines = code.split("\\n", -1);
        boolean inBlock = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (!inBlock) {
                String trimmed = line == null ? "" : line.trim();
                if (trimmed.startsWith("PLAN_START") || trimmed.startsWith("PLAN_END")) {
                    lines[i] = "// " + line;
                }
            }
            inBlock = updateBlockCommentState(inBlock, line);
        }
        return String.join("\n", lines);
    }

    private static boolean updateBlockCommentState(boolean inBlock, String line) {
        if (line == null || line.isEmpty()) return inBlock;
        int i = 0;
        int n = line.length();
        boolean state = inBlock;
        while (i < n - 1) {
            char c = line.charAt(i);
            char d = line.charAt(i + 1);
            if (!state && c == '/' && d == '*') {
                state = true;
                i += 2;
                continue;
            }
            if (state && c == '*' && d == '/') {
                state = false;
                i += 2;
                continue;
            }
            i++;
        }
        return state;
    }

    private static String escapeNonInterpolatedDollarInDoubleQuotedStrings(String code) {
        if (code == null || code.indexOf('$') < 0 || code.indexOf('"') < 0) return code;
        StringBuilder out = new StringBuilder(code.length() + 16);
        int n = code.length();
        int i = 0;
        boolean inDouble = false;
        int doubleQuoteLen = 0;
        while (i < n) {
            char c = code.charAt(i);
            if (!inDouble) {
                if (c == '"') {
                    if (i + 2 < n && code.charAt(i + 1) == '"' && code.charAt(i + 2) == '"') {
                        inDouble = true;
                        doubleQuoteLen = 3;
                        out.append("\"\"\"");
                        i += 3;
                        continue;
                    }
                    inDouble = true;
                    doubleQuoteLen = 1;
                    out.append('"');
                    i++;
                    continue;
                }
                out.append(c);
                i++;
                continue;
            }

            if (c == '\\') {
                out.append(c);
                if (i + 1 < n) {
                    out.append(code.charAt(i + 1));
                    i += 2;
                } else {
                    i++;
                }
                continue;
            }

            if (doubleQuoteLen == 3) {
                if (c == '"' && i + 2 < n && code.charAt(i + 1) == '"' && code.charAt(i + 2) == '"') {
                    inDouble = false;
                    doubleQuoteLen = 0;
                    out.append("\"\"\"");
                    i += 3;
                    continue;
                }
            } else {
                if (c == '"') {
                    inDouble = false;
                    doubleQuoteLen = 0;
                    out.append('"');
                    i++;
                    continue;
                }
            }

            if (c == '$') {
                char next = (i + 1) < n ? code.charAt(i + 1) : '\0';
                boolean interpolation = next == '{' || next == '_' || Character.isLetter(next);
                if (!interpolation) out.append('\\');
                out.append('$');
                i++;
                continue;
            }

            out.append(c);
            i++;
        }
        return out.toString();
    }

    /**
     * 进行静态检查并执行 Groovy 脚本。
     *
     * 注入变量：
     * - page：当前执行上下文（Page 或 Frame）
     * - web：{@link WebDSL}，脚本应优先使用该 DSL 完成网页自动化
     * - out：PrintWriter，支持 println 输出（按行回调到 logger）
     *
     * 执行前：
     * - 通过 {@link GroovyLinter} 执行安全/语法检查；
     * - 若存在安全或语法级错误，直接中止执行，避免对本机产生副作用。
     */
    static void executeWithGroovy(String scriptCode, Object pageOrFrame, java.util.function.Consumer<String> logger) throws Exception {
        if (Thread.currentThread().isInterrupted()) {
            throw new RuntimeException("Execution interrupted.");
        }
        java.util.List<String> lintErrors = GroovyLinter.check(scriptCode);
        if (!lintErrors.isEmpty()) {
            StringBuilder sb = new StringBuilder("Static Analysis Found Issues:\n");
            for (String err : lintErrors) {
                sb.append("- ").append(err).append("\n");
            }
            logger.accept(sb.toString());

            boolean hasSecurityError = lintErrors.stream().anyMatch(e -> e.startsWith("Security Error"));
            boolean hasSyntaxError = lintErrors.stream().anyMatch(e -> e.startsWith("Syntax Error") || e.startsWith("Parse Error"));
            if (hasSecurityError || hasSyntaxError) {
                 throw new RuntimeException("Execution aborted due to static analysis violations.");
            }
        }

        try {
            // 核心逻辑：通过 Binding 注入运行时对象，让脚本只关注业务步骤而非 Playwright 细节
            groovy.lang.Binding binding = new groovy.lang.Binding();
            binding.setVariable("page", pageOrFrame);

            WebDSL dsl = new WebDSL(pageOrFrame, logger);
            binding.setVariable("web", dsl);

            // 核心逻辑：把 Groovy 的 println/print 输出按行转发到 logger，便于 UI 统一展示
            binding.setVariable("out", new java.io.PrintWriter(new java.io.Writer() {
                private StringBuilder buffer = new StringBuilder();
                @Override
                public void write(char[] cbuf, int off, int len) {
                    buffer.append(cbuf, off, len);
                    checkBuffer();
                }
                @Override
                public void flush() { checkBuffer(); }
                @Override
                public void close() { flush(); }

                private void checkBuffer() {
                    int newline = buffer.indexOf("\n");
                    while (newline != -1) {
                        String line = buffer.substring(0, newline);
                        logger.accept(line);
                        buffer.delete(0, newline + 1);
                        newline = buffer.indexOf("\n");
                    }
                }
            }, true));

            groovy.lang.GroovyShell shell = new groovy.lang.GroovyShell(binding);
            shell.evaluate(scriptCode);
            if (Thread.currentThread().isInterrupted()) {
                throw new RuntimeException("Execution interrupted.");
            }
            logger.accept("Groovy script executed successfully.");
        } catch (Exception e) {
            logger.accept("Groovy execution failed: " + e.getMessage());
            throw e;
        }
    }

    /**
     * 按模型名称路由到对应 LLM 实现。
     * 核心逻辑：统一清理 code fence，并对部分模型增加超时保护，避免 UI 长时间阻塞。
     */
    private static String callModel(String modelName, String prompt, java.util.function.Consumer<String> uiLogger) {
        String modelKey = getModelKey(modelName);
        StorageSupport.log(uiLogger, "LLM", "Calling LLM | model=" + (modelName == null ? "" : modelName) + " | key=" + modelKey, null);
        long t0 = System.currentTimeMillis();
        String code = "";

        try {
            switch (modelKey) {
                case "MINIMAX":
                    // 核心逻辑：部分云端模型偶发卡住，增加超时取消以提高交互可用性
                    code = AutoWebAgentUtils.callLLMWithTimeout(() -> LLMUtil.chatWithMinimax(prompt), 180000L, uiLogger, "Minimax");
                    break;
                case "QWEN_MAX":
                    code = LLMUtil.chatWithAliyun(prompt);
                    break;
                case "MOONSHOT":
                    code = AutoWebAgentUtils.callLLMWithTimeout(() -> LLMUtil.chatWithMoonshot(prompt), 180000L, uiLogger, "Moonshot");
                    break;
                case "GLM":
                    code = AutoWebAgentUtils.callLLMWithTimeout(() -> LLMUtil.chatWithGLM(prompt), 180000L, uiLogger, "GLM");
                    break;
                case "GEMINI":
                    code = AutoWebAgentUtils.callLLMWithTimeout(() -> LLMUtil.chatWithGemini(prompt), 180000L, uiLogger, "Gemini");
                    break;
                case "OLLAMA_QWEN3_8B":
                    code = LLMUtil.chatWithOllama(prompt, LLMUtil.OLLAMA_MODEL_QWEN3_8B, null, false);
                    break;
                case "DEEPSEEK":
                default:
                    code = LLMUtil.chatWithDeepSeek(prompt);
                    break;
            }
        } catch (Exception ex) {
            long elapsed = System.currentTimeMillis() - t0;
            StorageSupport.log(uiLogger, "LLM", "Request failed | model=" + (modelName == null ? "" : modelName) + " | key=" + modelKey + " | elapsedMs=" + elapsed, ex);
            return "";
        }

        if (code != null) {
            // 核心逻辑：兼容模型输出 ```groovy``` / ```java``` / ``` 代码块
            code = code.replaceAll("```groovy", "").replaceAll("```java", "").replaceAll("```", "").trim();
        }
        long elapsed = System.currentTimeMillis() - t0;
        if (uiLogger != null) {
            StorageSupport.log(uiLogger, "LLM", String.format("模型 %s 生成耗时: %.2f秒", modelName, elapsed / 1000.0), null);
        }
        if (code == null || code.trim().isEmpty()) {
            StorageSupport.log(uiLogger, "LLM", "Empty response | model=" + (modelName == null ? "" : modelName) + " | key=" + modelKey + " | elapsedMs=" + elapsed, null);
        }
        return code;
    }

    private static String getModelKey(String displayName) {
        return AutoWebAgent.normalizeModelKey(displayName);
    }
}
