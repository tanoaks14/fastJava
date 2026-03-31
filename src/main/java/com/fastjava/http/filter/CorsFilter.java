package com.fastjava.http.filter;

import com.fastjava.http.impl.DefaultHttpServletResponse;
import com.fastjava.servlet.Filter;
import com.fastjava.servlet.FilterChain;
import com.fastjava.servlet.HttpServletRequest;
import com.fastjava.servlet.HttpServletResponse;
import com.fastjava.servlet.ServletException;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * CORS filter with explicit allow-list policy for origins, methods and headers.
 */
public class CorsFilter implements Filter {

    private static final String HEADER_ORIGIN = "Origin";
    private static final String HEADER_VARY = "Vary";
    private static final String HEADER_ACR_METHOD = "Access-Control-Request-Method";
    private static final String HEADER_ACR_HEADERS = "Access-Control-Request-Headers";
    private static final String HEADER_AC_ALLOW_ORIGIN = "Access-Control-Allow-Origin";
    private static final String HEADER_AC_ALLOW_METHODS = "Access-Control-Allow-Methods";
    private static final String HEADER_AC_ALLOW_HEADERS = "Access-Control-Allow-Headers";
    private static final String HEADER_AC_ALLOW_CREDENTIALS = "Access-Control-Allow-Credentials";
    private static final String HEADER_AC_MAX_AGE = "Access-Control-Max-Age";
    private static final String HEADER_AC_EXPOSE_HEADERS = "Access-Control-Expose-Headers";

    private final Set<String> allowedOrigins;
    private final Set<String> allowedMethods;
    private final Set<String> allowedHeaders;
    private final boolean allowAnyOrigin;
    private final boolean allowAnyHeader;
    private final boolean allowCredentials;
    private final long maxAgeSeconds;
    private final String allowMethodsValue;
    private final String allowHeadersValue;
    private final String exposeHeadersValue;

    public CorsFilter(Set<String> allowedOrigins) {
        this(
                allowedOrigins,
                Set.of("GET", "HEAD", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"),
                Set.of("*"),
                Set.of(),
                false,
                600);
    }

    public CorsFilter(
            Set<String> allowedOrigins,
            Set<String> allowedMethods,
            Set<String> allowedHeaders,
            Set<String> exposedHeaders,
            boolean allowCredentials,
            long maxAgeSeconds) {
        this.allowedOrigins = normalizeOrigins(allowedOrigins);
        this.allowedMethods = normalizeTokens(allowedMethods, true);
        this.allowedHeaders = normalizeTokens(allowedHeaders, false);
        Set<String> normalizedExposedHeaders = normalizeTokens(exposedHeaders, false);

        this.allowAnyOrigin = this.allowedOrigins.contains("*");
        this.allowAnyHeader = this.allowedHeaders.contains("*");
        this.allowCredentials = allowCredentials;
        this.maxAgeSeconds = Math.max(0L, maxAgeSeconds);

        if (allowCredentials && allowAnyOrigin) {
            throw new IllegalArgumentException("allowCredentials=true cannot be used with wildcard origin");
        }

        this.allowMethodsValue = joinWithoutWildcard(this.allowedMethods);
        this.allowHeadersValue = joinWithoutWildcard(this.allowedHeaders);
        this.exposeHeadersValue = joinWithoutWildcard(normalizedExposedHeaders);
    }

    @Override
    public void doFilter(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException {
        String origin = trimToNull(request.getHeader(HEADER_ORIGIN));
        if (origin == null || !isOriginAllowed(origin)) {
            chain.doFilter(request, response);
            return;
        }

        if (isPreflight(request)) {
            handlePreflight(request, response, origin);
            return;
        }

        chain.doFilter(request, response);
        applyAllowOrigin(response, origin);
        if (!exposeHeadersValue.isEmpty()) {
            response.setHeader(HEADER_AC_EXPOSE_HEADERS, exposeHeadersValue);
        }
        addVary(response, HEADER_ORIGIN);
    }

    private void handlePreflight(HttpServletRequest request, HttpServletResponse response, String origin)
            throws ServletException {
        String requestedMethod = trimToNull(request.getHeader(HEADER_ACR_METHOD));
        if (requestedMethod == null || !isMethodAllowed(requestedMethod)) {
            denyPreflight(response);
            return;
        }

        String requestedHeaders = trimToNull(request.getHeader(HEADER_ACR_HEADERS));
        if (!areHeadersAllowed(requestedHeaders)) {
            denyPreflight(response);
            return;
        }

        response.setStatus(204);
        response.setContentLength(0);
        applyAllowOrigin(response, origin);
        response.setHeader(HEADER_AC_ALLOW_METHODS, allowMethodsValue);
        if (requestedHeaders != null) {
            if (allowAnyHeader) {
                response.setHeader(HEADER_AC_ALLOW_HEADERS, requestedHeaders);
            } else if (!allowHeadersValue.isEmpty()) {
                response.setHeader(HEADER_AC_ALLOW_HEADERS, allowHeadersValue);
            }
        }
        if (maxAgeSeconds > 0) {
            response.setHeader(HEADER_AC_MAX_AGE, String.valueOf(maxAgeSeconds));
        }
        addVary(response, HEADER_ORIGIN);
        addVary(response, HEADER_ACR_METHOD);
        addVary(response, HEADER_ACR_HEADERS);
    }

    private void denyPreflight(HttpServletResponse response) {
        response.setStatus(403);
        response.setContentLength(0);
    }

    private void applyAllowOrigin(HttpServletResponse response, String origin) {
        if (allowAnyOrigin && !allowCredentials) {
            response.setHeader(HEADER_AC_ALLOW_ORIGIN, "*");
        } else {
            response.setHeader(HEADER_AC_ALLOW_ORIGIN, origin);
        }
        if (allowCredentials) {
            response.setHeader(HEADER_AC_ALLOW_CREDENTIALS, "true");
        }
    }

    private boolean isOriginAllowed(String origin) {
        return allowAnyOrigin || allowedOrigins.contains(origin);
    }

    private boolean isPreflight(HttpServletRequest request) {
        return "OPTIONS".equalsIgnoreCase(request.getMethod())
                && trimToNull(request.getHeader(HEADER_ACR_METHOD)) != null;
    }

    private boolean isMethodAllowed(String method) {
        return allowedMethods.contains(method.trim().toUpperCase(Locale.ROOT));
    }

    private boolean areHeadersAllowed(String requestedHeaders) {
        if (requestedHeaders == null || requestedHeaders.isBlank()) {
            return true;
        }
        if (allowAnyHeader) {
            return true;
        }
        int length = requestedHeaders.length();
        int tokenStart = 0;
        while (tokenStart < length) {
            int tokenEnd = requestedHeaders.indexOf(',', tokenStart);
            if (tokenEnd < 0) {
                tokenEnd = length;
            }
            String normalized = requestedHeaders.substring(tokenStart, tokenEnd).trim().toLowerCase(Locale.ROOT);
            if (normalized.isEmpty() || !isValidHeaderName(normalized) || !allowedHeaders.contains(normalized)) {
                return false;
            }
            tokenStart = tokenEnd + 1;
        }
        return true;
    }

    private void addVary(HttpServletResponse response, String token) {
        if (response instanceof DefaultHttpServletResponse dsr) {
            String merged = mergeCommaTokens(dsr.getHeader(HEADER_VARY), token);
            response.setHeader(HEADER_VARY, merged);
            return;
        }
        response.addHeader(HEADER_VARY, token);
    }

    private static String mergeCommaTokens(String existing, String add) {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        if (existing != null && !existing.isBlank()) {
            addCommaSeparatedTokens(existing, merged);
        }
        if (add != null && !add.isBlank()) {
            String trimmed = add.trim();
            boolean present = false;
            for (String token : merged) {
                if (token.equalsIgnoreCase(trimmed)) {
                    present = true;
                    break;
                }
            }
            if (!present) {
                merged.add(trimmed);
            }
        }
        return String.join(", ", merged);
    }

    private static void addCommaSeparatedTokens(String source, Set<String> target) {
        int length = source.length();
        int tokenStart = 0;
        while (tokenStart < length) {
            int tokenEnd = source.indexOf(',', tokenStart);
            if (tokenEnd < 0) {
                tokenEnd = length;
            }
            int start = tokenStart;
            while (start < tokenEnd && Character.isWhitespace(source.charAt(start))) {
                start++;
            }
            int end = tokenEnd;
            while (end > start && Character.isWhitespace(source.charAt(end - 1))) {
                end--;
            }
            if (start < end) {
                target.add(source.substring(start, end));
            }
            tokenStart = tokenEnd + 1;
        }
    }

    private static Set<String> normalizeOrigins(Set<String> origins) {
        if (origins == null || origins.isEmpty()) {
            throw new IllegalArgumentException("allowedOrigins cannot be null or empty");
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String origin : origins) {
            String value = trimToNull(origin);
            if (value != null) {
                normalized.add(value);
            }
        }
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("allowedOrigins cannot be empty");
        }
        return Set.copyOf(normalized);
    }

    private static Set<String> normalizeTokens(Set<String> values, boolean uppercase) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String raw : values) {
            String value = trimToNull(raw);
            if (value == null) {
                continue;
            }
            if ("*".equals(value)) {
                normalized.add("*");
                continue;
            }
            normalized.add(uppercase ? value.toUpperCase(Locale.ROOT) : value.toLowerCase(Locale.ROOT));
        }
        return Set.copyOf(normalized);
    }

    private static String joinWithoutWildcard(Set<String> values) {
        return values.stream()
                .filter(value -> !"*".equals(value))
                .sorted()
                .reduce((left, right) -> left + ", " + right)
                .orElse("");
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static boolean isValidHeaderName(String headerName) {
        for (int i = 0; i < headerName.length(); i++) {
            char ch = headerName.charAt(i);
            if (!isTokenChar(ch)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isTokenChar(char ch) {
        return (ch >= 'a' && ch <= 'z')
                || (ch >= '0' && ch <= '9')
                || ch == '!'
                || ch == '#'
                || ch == '$'
                || ch == '%'
                || ch == '&'
                || ch == '\''
                || ch == '*'
                || ch == '+'
                || ch == '-'
                || ch == '.'
                || ch == '^'
                || ch == '_'
                || ch == '`'
                || ch == '|'
                || ch == '~';
    }
}