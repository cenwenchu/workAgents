package com.qiyi.service.autoweb;

import groovy.lang.GroovyShell;
import org.codehaus.groovy.control.CompilationFailedException;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Groovy 脚本静态检查器。
 * 负责执行前的安全模式识别与语法解析校验。
 */
public class GroovyLinter {

    /**
     * 需要拦截的危险代码模式。
     * 核心逻辑：用正则快速识别高风险调用或死循环。
     */
    private static final Pattern[] UNSAFE_PATTERNS = {
        Pattern.compile("System\\.exit"),
        Pattern.compile("Runtime\\.getRuntime\\(\\)\\.exec"),
        Pattern.compile("Thread\\.stop"),
        Pattern.compile("File\\.delete"),
        Pattern.compile("(?m)^\\s*package\\s+"),
        Pattern.compile("(?m)^\\s*(class|interface|trait|enum)\\s+"),
        Pattern.compile("(?m)^\\s*@Grab\\b"),
        Pattern.compile("(?m)^\\s*import\\s+groovy\\."),
        Pattern.compile("GroovyShell"),
        Pattern.compile("groovy\\.util\\.Eval"),
        Pattern.compile("java\\.io\\.File"),
        Pattern.compile("java\\.nio\\.file\\.Files"),
        Pattern.compile("java\\.net\\.(URL|Socket|ServerSocket)"),
        // 核心逻辑：尽量拦截明显的无限循环
        Pattern.compile("while\\s*\\(\\s*true\\s*\\)") 
    };

    /**
     * 执行静态检查并返回问题列表。
     * 核心逻辑：先做安全检查，再做 Groovy 语法解析。
     */
    public static List<String> check(String code) {
        List<String> errors = new ArrayList<>();
        if (code == null) return errors;

        // 核心逻辑：安全模式检测
        for (Pattern p : UNSAFE_PATTERNS) {
            if (p.matcher(code).find()) {
                errors.add("Security Error: Code contains unsafe pattern '" + p.pattern() + "'");
            }
        }

        // 核心逻辑：语法解析检测
        try {
            GroovyShell shell = new GroovyShell(new groovy.lang.Binding(), AutoWebAgent.secureGroovyCompilerConfig());
            shell.parse(code);
        } catch (CompilationFailedException e) {
            errors.add("Syntax Error: " + e.getMessage());
        } catch (Exception e) {
            errors.add("Parse Error: " + e.getMessage());
        }

        return errors;
    }
}
