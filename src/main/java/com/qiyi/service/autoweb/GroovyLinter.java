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
        // 核心逻辑：尽量拦截明显的无限循环
        Pattern.compile("while\\s*\\(\\s*true\\s*\\)") 
    };

    /**
     * 执行静态检查并返回问题列表。
     * 核心逻辑：先做安全检查，再做 Groovy 语法解析。
     */
    public static List<String> check(String code) {
        List<String> errors = new ArrayList<>();

        // 核心逻辑：安全模式检测
        for (Pattern p : UNSAFE_PATTERNS) {
            if (p.matcher(code).find()) {
                errors.add("Security Error: Code contains unsafe pattern '" + p.pattern() + "'");
            }
        }

        // 核心逻辑：语法解析检测
        try {
            GroovyShell shell = new GroovyShell();
            shell.parse(code);
        } catch (CompilationFailedException e) {
            errors.add("Syntax Error: " + e.getMessage());
        } catch (Exception e) {
            errors.add("Parse Error: " + e.getMessage());
        }

        return errors;
    }
}
