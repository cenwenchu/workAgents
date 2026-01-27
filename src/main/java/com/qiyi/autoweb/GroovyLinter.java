package com.qiyi.autoweb;

import groovy.lang.GroovyShell;
import org.codehaus.groovy.control.CompilationFailedException;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public class GroovyLinter {

    private static final Pattern[] UNSAFE_PATTERNS = {
        Pattern.compile("System\\.exit"),
        Pattern.compile("Runtime\\.getRuntime\\(\\)\\.exec"),
        Pattern.compile("Thread\\.stop"),
        Pattern.compile("File\\.delete"),
        // Prevent infinite loops if possible (hard to do statically, but maybe check for `while(true)`)
        Pattern.compile("while\\s*\\(\\s*true\\s*\\)") 
    };

    public static List<String> check(String code) {
        List<String> errors = new ArrayList<>();

        // 1. Security / Safety Checks
        for (Pattern p : UNSAFE_PATTERNS) {
            if (p.matcher(code).find()) {
                errors.add("Security Error: Code contains unsafe pattern '" + p.pattern() + "'");
            }
        }

        // 2. Syntax Check
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
