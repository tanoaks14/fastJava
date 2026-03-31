package com.fastjava.websocket;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class WebSocketPathTemplate {

    private static final Pattern PARAM_NAME_PATTERN = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private final String pattern;
    private final boolean template;
    private final Pattern compiled;
    private final List<String> paramNames;

    private WebSocketPathTemplate(String pattern, boolean template, Pattern compiled, List<String> paramNames) {
        this.pattern = pattern;
        this.template = template;
        this.compiled = compiled;
        this.paramNames = paramNames;
    }

    public static WebSocketPathTemplate compile(String pathPattern) {
        if (pathPattern == null || pathPattern.isBlank() || pathPattern.charAt(0) != '/') {
            throw new IllegalArgumentException("WebSocket template path must start with '/'");
        }

        if (pathPattern.indexOf('{') < 0) {
            return new WebSocketPathTemplate(pathPattern, false, null, List.of());
        }

        StringBuilder regex = new StringBuilder(pathPattern.length() * 2);
        List<String> params = new ArrayList<>();
        regex.append('^');

        int cursor = 0;
        while (cursor < pathPattern.length()) {
            int openBrace = pathPattern.indexOf('{', cursor);
            if (openBrace < 0) {
                appendEscaped(regex, pathPattern.substring(cursor));
                break;
            }

            appendEscaped(regex, pathPattern.substring(cursor, openBrace));
            int closeBrace = pathPattern.indexOf('}', openBrace + 1);
            if (closeBrace < 0) {
                throw new IllegalArgumentException("Unclosed path variable in WebSocket template: " + pathPattern);
            }

            String paramName = pathPattern.substring(openBrace + 1, closeBrace).trim();
            if (!PARAM_NAME_PATTERN.matcher(paramName).matches()) {
                throw new IllegalArgumentException("Invalid path parameter name in WebSocket template: " + paramName);
            }
            if (params.contains(paramName)) {
                throw new IllegalArgumentException("Duplicate path parameter name in WebSocket template: " + paramName);
            }

            params.add(paramName);
            regex.append("([^/]+)");
            cursor = closeBrace + 1;
        }

        regex.append('$');
        return new WebSocketPathTemplate(pathPattern, true, Pattern.compile(regex.toString()), List.copyOf(params));
    }

    public boolean isTemplate() {
        return template;
    }

    public String pattern() {
        return pattern;
    }

    public Map<String, String> match(String requestPath) {
        if (!template) {
            return pattern.equals(requestPath) ? Map.of() : null;
        }

        Matcher matcher = compiled.matcher(requestPath);
        if (!matcher.matches()) {
            return null;
        }

        Map<String, String> values = new LinkedHashMap<>();
        for (int index = 0; index < paramNames.size(); index++) {
            values.put(paramNames.get(index), matcher.group(index + 1));
        }
        return Map.copyOf(values);
    }

    private static void appendEscaped(StringBuilder target, String literal) {
        for (int index = 0; index < literal.length(); index++) {
            char c = literal.charAt(index);
            if ("\\.^$|?*+()[]{}".indexOf(c) >= 0) {
                target.append('\\');
            }
            target.append(c);
        }
    }
}
