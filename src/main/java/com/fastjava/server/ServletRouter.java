package com.fastjava.server;

import com.fastjava.http.simd.SIMDByteScanner;
import com.fastjava.servlet.Filter;
import com.fastjava.servlet.FilterConfig;
import com.fastjava.servlet.HttpServlet;
import com.fastjava.servlet.ServletConfig;
import com.fastjava.servlet.ServletException;
import com.fastjava.websocket.WebSocketEndpointMatch;
import com.fastjava.websocket.WebSocketEndpointMetadata;
import com.fastjava.websocket.WebSocketPathTemplate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Routes HTTP requests to appropriate servlets.
 * Supports exact path and pattern matching.
 */
public class ServletRouter {

    private static final int SIMD_MIN_PATH_LENGTH = 64;
    private static final int SIMD_MIN_PATTERN_LENGTH = 8;

    private final Map<String, RegisteredServlet> exactMappings = new ConcurrentHashMap<>();
    private final List<PathPattern<RegisteredServlet>> patternMappings = new CopyOnWriteArrayList<>();
    private final List<PathPattern<RegisteredFilter>> filterMappings = new CopyOnWriteArrayList<>();
    private final Map<String, WebSocketEndpointMetadata> webSocketMappings = new ConcurrentHashMap<>();
    private final List<RegisteredWebSocketTemplate> webSocketTemplateMappings = new CopyOnWriteArrayList<>();
    private final Map<String, DeploymentRegistration> deployments = new ConcurrentHashMap<>();
    private final Map<Integer, ErrorPage> statusErrorPages = new ConcurrentHashMap<>();
    private final Map<Class<?>, ErrorPage> exceptionErrorPages = new ConcurrentHashMap<>();
    private volatile boolean initialized;

    /**
     * Register servlet for exact path.
     */
    public void addServlet(String path, HttpServlet servlet) throws ServletException {
        RegisteredServlet registration = new RegisteredServlet(path, servlet, servlet.getClass().getClassLoader(),
                null);
        exactMappings.put(path, registration);
        initializeServletIfNeeded(registration);
    }

    /**
     * Register servlet with wildcard pattern.
     * Patterns: "/api/*", "*.json", "/admin/.*"
     */
    public void addServletPattern(String pattern, HttpServlet servlet) throws ServletException {
        RegisteredServlet registration = new RegisteredServlet(
                pattern,
                servlet,
                servlet.getClass().getClassLoader(),
                null);
        patternMappings.add(new PathPattern<>(pattern, registration));
        initializeServletIfNeeded(registration);
    }

    /**
     * Register an annotation-based WebSocket endpoint.
     */
    public void addWebSocketEndpoint(Class<?> endpointClass) {
        WebSocketEndpointMetadata metadata = WebSocketEndpointMetadata.fromClass(endpointClass);
        WebSocketPathTemplate pathTemplate = WebSocketPathTemplate.compile(metadata.path());
        if (pathTemplate.isTemplate()) {
            webSocketTemplateMappings.add(new RegisteredWebSocketTemplate(pathTemplate, metadata));
        } else {
            webSocketMappings.put(metadata.path(), metadata);
        }
    }

    /**
     * Register filter for all request paths.
     */
    public void addFilter(Filter filter) throws ServletException {
        addFilterPattern("/*", filter);
    }

    /**
     * Registers an error page mapping.
     * Replaces any existing mapping for the same status code or exception type.
     */
    public void addErrorPage(ErrorPage errorPage) {
        if (errorPage.exceptionType() != null) {
            exceptionErrorPages.put(errorPage.exceptionType(), errorPage);
        } else {
            statusErrorPages.put(errorPage.statusCode(), errorPage);
        }
    }

    /**
     * Finds a registered error page for the given HTTP status code, or
     * {@code null}.
     */
    public ErrorPage findErrorPageForStatus(int statusCode) {
        return statusErrorPages.get(statusCode);
    }

    /**
     * Finds a registered error page for the given exception, walking up the class
     * hierarchy (exact match first, then superclasses up to {@link Throwable}).
     * Returns {@code null} if none is registered.
     */
    public ErrorPage findErrorPageForException(Throwable exception) {
        Class<?> type = exception.getClass();
        while (type != null && Throwable.class.isAssignableFrom(type)) {
            ErrorPage page = exceptionErrorPages.get(type);
            if (page != null) {
                return page;
            }
            type = type.getSuperclass();
        }
        return null;
    }

    /**
     * Register filter for an exact path.
     */
    public void addFilter(String path, Filter filter) throws ServletException {
        RegisteredFilter registration = new RegisteredFilter(path, filter, filter.getClass().getClassLoader(), null);
        filterMappings.add(new PathPattern<>(path, registration));
        initializeFilterIfNeeded(registration);
    }

    /**
     * Register filter using path pattern semantics.
     */
    public void addFilterPattern(String pattern, Filter filter) throws ServletException {
        RegisteredFilter registration = new RegisteredFilter(
                pattern,
                filter,
                filter.getClass().getClassLoader(),
                null);
        filterMappings.add(new PathPattern<>(pattern, registration));
        initializeFilterIfNeeded(registration);
    }

    public synchronized void deployWebApp(HotDeployedWebApp webApp) throws ServletException {
        Objects.requireNonNull(webApp, "webApp");
        undeployWebApp(webApp.appName());

        String contextPath = normalizeContextPath(webApp.contextPath());
        DeploymentRegistration deployment = new DeploymentRegistration(webApp.appName(), contextPath,
                webApp.classLoader());

        for (Map.Entry<String, HttpServlet> entry : webApp.servletMappings().entrySet()) {
            String mountedPath = joinContextAndPath(contextPath, entry.getKey());
            RegisteredServlet registration = new RegisteredServlet(
                    mountedPath,
                    entry.getValue(),
                    webApp.classLoader(),
                    webApp.appName());
            RegisteredServlet previous = exactMappings.put(mountedPath, registration);
            if (previous != null) {
                destroyServletIfNeeded(previous);
            }
            initializeServletIfNeeded(registration);
            deployment.servletPaths.add(mountedPath);
        }

        for (Map.Entry<String, Filter> entry : webApp.filterMappings().entrySet()) {
            String mountedPath = joinContextAndPath(contextPath, entry.getKey());
            RegisteredFilter registration = new RegisteredFilter(
                    mountedPath,
                    entry.getValue(),
                    webApp.classLoader(),
                    webApp.appName());
            PathPattern<RegisteredFilter> mapping = new PathPattern<>(mountedPath, registration);
            filterMappings.add(mapping);
            initializeFilterIfNeeded(registration);
            deployment.filterMappings.add(mapping);
        }

        deployments.put(webApp.appName(), deployment);
    }

    public synchronized boolean undeployWebApp(String appName) {
        if (appName == null || appName.isBlank()) {
            return false;
        }
        DeploymentRegistration deployment = deployments.remove(appName.trim());
        if (deployment == null) {
            return false;
        }

        for (PathPattern<RegisteredFilter> filterMapping : deployment.filterMappings) {
            filterMappings.remove(filterMapping);
            destroyFilterIfNeeded(filterMapping.target);
        }

        for (String path : deployment.servletPaths) {
            RegisteredServlet removed = exactMappings.remove(path);
            if (removed != null) {
                destroyServletIfNeeded(removed);
            }
        }
        return true;
    }

    public synchronized void initialize() throws ServletException {
        if (initialized) {
            return;
        }
        initialized = true;
        for (RegisteredServlet registration : uniqueServlets()) {
            initializeServletIfNeeded(registration);
        }
        for (RegisteredFilter registration : uniqueFilters()) {
            initializeFilterIfNeeded(registration);
        }
    }

    public synchronized void destroy() {
        if (!initialized) {
            return;
        }
        List<RegisteredFilter> filters = uniqueFilters();
        for (int index = filters.size() - 1; index >= 0; index--) {
            destroyFilterIfNeeded(filters.get(index));
        }

        List<RegisteredServlet> servlets = uniqueServlets();
        for (int index = servlets.size() - 1; index >= 0; index--) {
            destroyServletIfNeeded(servlets.get(index));
        }
        initialized = false;
    }

    /**
     * Resolve servlet and matching filters for request path.
     */
    public DispatchTarget resolve(String requestPath) {
        byte[] requestPathAscii = asciiBytes(requestPath);

        // Try exact match first (fastest)
        RegisteredServlet registration = exactMappings.get(requestPath);
        if (registration == null) {
            for (PathPattern<RegisteredServlet> pattern : patternMappings) {
                if (pattern.matches(requestPath, requestPathAscii)) {
                    registration = pattern.target;
                    break;
                }
            }
        }

        if (registration == null) {
            return null;
        }

        List<Filter> filters = new ArrayList<>();
        for (PathPattern<RegisteredFilter> pattern : filterMappings) {
            if (pattern.matches(requestPath, requestPathAscii)) {
                filters.add(pattern.target.filter);
            }
        }

        return new DispatchTarget(
                registration.servlet,
                Collections.unmodifiableList(filters),
                registration.contextClassLoader);
    }

    public WebSocketEndpointMatch resolveWebSocketEndpoint(String requestPath) {
        WebSocketEndpointMetadata exact = webSocketMappings.get(requestPath);
        if (exact != null) {
            return new WebSocketEndpointMatch(exact, Map.of());
        }

        for (RegisteredWebSocketTemplate registration : webSocketTemplateMappings) {
            Map<String, String> params = registration.template.match(requestPath);
            if (params != null) {
                return new WebSocketEndpointMatch(registration.metadata, params);
            }
        }

        return null;
    }

    private record RegisteredWebSocketTemplate(WebSocketPathTemplate template, WebSocketEndpointMetadata metadata) {
        private RegisteredWebSocketTemplate {
            Objects.requireNonNull(template, "template");
            Objects.requireNonNull(metadata, "metadata");
        }
    }

    private synchronized void initializeServletIfNeeded(RegisteredServlet registration) throws ServletException {
        if (!initialized || registration.initialized) {
            return;
        }
        registration.servlet.init(new BasicServletConfig(registration.name, Collections.emptyMap()));
        registration.initialized = true;
    }

    private synchronized void initializeFilterIfNeeded(RegisteredFilter registration) throws ServletException {
        if (!initialized || registration.initialized) {
            return;
        }
        registration.filter.init(new BasicFilterConfig(registration.name, Collections.emptyMap()));
        registration.initialized = true;
    }

    private synchronized void destroyServletIfNeeded(RegisteredServlet registration) {
        if (!registration.initialized) {
            return;
        }
        try {
            registration.servlet.destroy();
        } finally {
            registration.initialized = false;
        }
    }

    private synchronized void destroyFilterIfNeeded(RegisteredFilter registration) {
        if (!registration.initialized) {
            return;
        }
        try {
            registration.filter.destroy();
        } finally {
            registration.initialized = false;
        }
    }

    private List<RegisteredServlet> uniqueServlets() {
        List<RegisteredServlet> registrations = new ArrayList<>();
        Set<HttpServlet> seen = Collections.newSetFromMap(new IdentityHashMap<>());

        for (RegisteredServlet registration : exactMappings.values()) {
            if (seen.add(registration.servlet)) {
                registrations.add(registration);
            }
        }
        for (PathPattern<RegisteredServlet> pattern : patternMappings) {
            if (seen.add(pattern.target.servlet)) {
                registrations.add(pattern.target);
            }
        }
        return registrations;
    }

    private List<RegisteredFilter> uniqueFilters() {
        List<RegisteredFilter> registrations = new ArrayList<>();
        Set<Filter> seen = Collections.newSetFromMap(new IdentityHashMap<>());

        for (PathPattern<RegisteredFilter> pattern : filterMappings) {
            if (seen.add(pattern.target.filter)) {
                registrations.add(pattern.target);
            }
        }
        return registrations;
    }

    public record DispatchTarget(HttpServlet servlet, List<Filter> filters, ClassLoader contextClassLoader) {
    }

    private static String normalizeContextPath(String contextPath) {
        if (contextPath == null || contextPath.isBlank()) {
            return "/";
        }
        String normalized = contextPath.trim();
        if (!normalized.startsWith("/")) {
            normalized = "/" + normalized;
        }
        if (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private static String joinContextAndPath(String contextPath, String relativePath) {
        String normalizedPath = relativePath == null || relativePath.isBlank() ? "/" : relativePath.trim();
        if (!normalizedPath.startsWith("/")) {
            normalizedPath = "/" + normalizedPath;
        }
        if ("/".equals(contextPath)) {
            return normalizedPath;
        }
        if ("/".equals(normalizedPath)) {
            return contextPath;
        }
        return contextPath + normalizedPath;
    }

    public static boolean prefixMatchScalar(String path, String prefix) {
        return path.startsWith(prefix);
    }

    public static boolean suffixMatchScalar(String path, String suffix) {
        return path.endsWith(suffix);
    }

    public static boolean prefixMatchSimd(String path, String prefix) {
        if (path.length() < SIMD_MIN_PATH_LENGTH || prefix.length() < SIMD_MIN_PATTERN_LENGTH) {
            return prefixMatchScalar(path, prefix);
        }
        byte[] pathAscii = asciiBytes(path);
        byte[] prefixAscii = asciiBytes(prefix);
        if (pathAscii == null || prefixAscii == null) {
            return prefixMatchScalar(path, prefix);
        }
        return prefixMatchSimdAscii(pathAscii, prefixAscii);
    }

    public static boolean suffixMatchSimd(String path, String suffix) {
        if (path.length() < SIMD_MIN_PATH_LENGTH || suffix.length() < SIMD_MIN_PATTERN_LENGTH) {
            return suffixMatchScalar(path, suffix);
        }
        byte[] pathAscii = asciiBytes(path);
        byte[] suffixAscii = asciiBytes(suffix);
        if (pathAscii == null || suffixAscii == null) {
            return suffixMatchScalar(path, suffix);
        }
        return suffixMatchSimdAscii(pathAscii, suffixAscii);
    }

    public static boolean prefixMatchScalarAscii(byte[] pathAscii, byte[] prefixAscii) {
        if (prefixAscii.length > pathAscii.length) {
            return false;
        }
        for (int i = 0; i < prefixAscii.length; i++) {
            if (pathAscii[i] != prefixAscii[i]) {
                return false;
            }
        }
        return true;
    }

    public static boolean suffixMatchScalarAscii(byte[] pathAscii, byte[] suffixAscii) {
        if (suffixAscii.length > pathAscii.length) {
            return false;
        }
        int start = pathAscii.length - suffixAscii.length;
        for (int i = 0; i < suffixAscii.length; i++) {
            if (pathAscii[start + i] != suffixAscii[i]) {
                return false;
            }
        }
        return true;
    }

    public static boolean prefixMatchSimdAscii(byte[] pathAscii, byte[] prefixAscii) {
        return SIMDByteScanner.startsWith(pathAscii, 0, pathAscii.length, prefixAscii);
    }

    public static boolean suffixMatchSimdAscii(byte[] pathAscii, byte[] suffixAscii) {
        return SIMDByteScanner.endsWith(pathAscii, 0, pathAscii.length, suffixAscii);
    }

    private static byte[] asciiBytes(String value) {
        byte[] bytes = new byte[value.length()];
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current > 0x7F) {
                return null;
            }
            bytes[index] = (byte) current;
        }
        return bytes;
    }

    private static final class RegisteredServlet {
        private final String name;
        private final HttpServlet servlet;
        private final ClassLoader contextClassLoader;
        private final String ownerDeployment;
        private volatile boolean initialized;

        private RegisteredServlet(String mapping, HttpServlet servlet, ClassLoader contextClassLoader,
                String ownerDeployment) {
            this.name = servlet.getClass().getSimpleName() + "[" + mapping + "]";
            this.servlet = servlet;
            this.contextClassLoader = contextClassLoader;
            this.ownerDeployment = ownerDeployment;
        }
    }

    private static final class RegisteredFilter {
        private final String name;
        private final Filter filter;
        private final ClassLoader contextClassLoader;
        private final String ownerDeployment;
        private volatile boolean initialized;

        private RegisteredFilter(String mapping, Filter filter, ClassLoader contextClassLoader,
                String ownerDeployment) {
            this.name = filter.getClass().getSimpleName() + "[" + mapping + "]";
            this.filter = filter;
            this.contextClassLoader = contextClassLoader;
            this.ownerDeployment = ownerDeployment;
        }
    }

    private static final class DeploymentRegistration {
        private final String appName;
        private final String contextPath;
        private final ClassLoader classLoader;
        private final List<String> servletPaths;
        private final List<PathPattern<RegisteredFilter>> filterMappings;

        private DeploymentRegistration(String appName, String contextPath, ClassLoader classLoader) {
            this.appName = appName;
            this.contextPath = contextPath;
            this.classLoader = classLoader;
            this.servletPaths = new ArrayList<>();
            this.filterMappings = new ArrayList<>();
        }
    }

    private static final class BasicServletConfig implements ServletConfig {
        private final String servletName;
        private final Map<String, String> initParameters;

        private BasicServletConfig(String servletName, Map<String, String> initParameters) {
            this.servletName = servletName;
            this.initParameters = Map.copyOf(initParameters);
        }

        @Override
        public String getServletName() {
            return servletName;
        }

        @Override
        public String getInitParameter(String name) {
            return initParameters.get(name);
        }

        @Override
        public Enumeration<String> getInitParameterNames() {
            return Collections.enumeration(initParameters.keySet());
        }

        @Override
        public Map<String, String> getInitParameters() {
            return initParameters;
        }
    }

    private static final class BasicFilterConfig implements FilterConfig {
        private final String filterName;
        private final Map<String, String> initParameters;

        private BasicFilterConfig(String filterName, Map<String, String> initParameters) {
            this.filterName = filterName;
            this.initParameters = Map.copyOf(initParameters);
        }

        @Override
        public String getFilterName() {
            return filterName;
        }

        @Override
        public String getInitParameter(String name) {
            return initParameters.get(name);
        }

        @Override
        public Enumeration<String> getInitParameterNames() {
            return Collections.enumeration(initParameters.keySet());
        }

        @Override
        public Map<String, String> getInitParameters() {
            return initParameters;
        }
    }

    /**
     * Path pattern matching.
     */
    private static class PathPattern<T> {
        private final String pattern;
        private final T target;
        private final boolean isWildcard;
        private final boolean isPrefix;
        private final String prefix;
        private final byte[] patternAscii;
        private final byte[] prefixAscii;

        PathPattern(String pattern, T target) {
            this.pattern = pattern;
            this.target = target;
            this.patternAscii = asciiBytes(pattern);

            if (pattern.endsWith("/*")) {
                this.isWildcard = true;
                this.isPrefix = true;
                this.prefix = pattern.substring(0, pattern.length() - 2);
                this.prefixAscii = asciiBytes(this.prefix);
            } else if (pattern.startsWith("*.")) {
                this.isWildcard = true;
                this.isPrefix = false;
                this.prefix = pattern.substring(1);
                this.prefixAscii = asciiBytes(this.prefix);
            } else {
                this.isWildcard = false;
                this.isPrefix = false;
                this.prefix = null;
                this.prefixAscii = null;
            }
        }

        boolean matches(String path) {
            return matches(path, asciiBytes(path));
        }

        boolean matches(String path, byte[] pathAscii) {
            if (!isWildcard) {
                if (path.length() < SIMD_MIN_PATH_LENGTH || pattern.length() < SIMD_MIN_PATTERN_LENGTH) {
                    return pattern.equals(path);
                }
                if (pathAscii != null && patternAscii != null) {
                    return pathAscii.length == patternAscii.length
                            && SIMDByteScanner.startsWith(pathAscii, 0, pathAscii.length, patternAscii);
                }
                return pattern.equals(path);
            }

            if (isPrefix) {
                if (path.length() < SIMD_MIN_PATH_LENGTH || prefix.length() < SIMD_MIN_PATTERN_LENGTH) {
                    return path.startsWith(prefix);
                }
                if (pathAscii != null && prefixAscii != null) {
                    return prefixMatchSimdAscii(pathAscii, prefixAscii);
                }
                return path.startsWith(prefix);
            }

            if (path.length() < SIMD_MIN_PATH_LENGTH || prefix.length() < SIMD_MIN_PATTERN_LENGTH) {
                return path.endsWith(prefix);
            }
            if (pathAscii != null && prefixAscii != null) {
                return suffixMatchSimdAscii(pathAscii, prefixAscii);
            }
            return path.endsWith(prefix);
        }
    }
}
