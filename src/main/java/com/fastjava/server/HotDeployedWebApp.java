package com.fastjava.server;

import com.fastjava.servlet.Filter;
import com.fastjava.servlet.HttpServlet;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Defines an isolated web application deployment unit.
 *
 * A deployment mounts servlet/filter mappings under a context path and executes
 * requests with the provided classloader as TCCL.
 */
public final class HotDeployedWebApp {

    private final String appName;
    private final String contextPath;
    private final ClassLoader classLoader;
    private final Map<String, HttpServlet> servletMappings;
    private final Map<String, Filter> filterMappings;

    private HotDeployedWebApp(
            String appName,
            String contextPath,
            ClassLoader classLoader,
            Map<String, HttpServlet> servletMappings,
            Map<String, Filter> filterMappings) {
        this.appName = appName;
        this.contextPath = contextPath;
        this.classLoader = classLoader;
        this.servletMappings = servletMappings;
        this.filterMappings = filterMappings;
    }

    public String appName() {
        return appName;
    }

    public String contextPath() {
        return contextPath;
    }

    public ClassLoader classLoader() {
        return classLoader;
    }

    public Map<String, HttpServlet> servletMappings() {
        return servletMappings;
    }

    public Map<String, Filter> filterMappings() {
        return filterMappings;
    }

    public static Builder builder(String appName, String contextPath, ClassLoader classLoader) {
        return new Builder(appName, contextPath, classLoader);
    }

    public static final class Builder {
        private final String appName;
        private final String contextPath;
        private final ClassLoader classLoader;
        private final Map<String, HttpServlet> servletMappings;
        private final Map<String, Filter> filterMappings;

        private Builder(String appName, String contextPath, ClassLoader classLoader) {
            this.appName = normalizeAppName(appName);
            this.contextPath = normalizeContextPath(contextPath);
            this.classLoader = Objects.requireNonNull(classLoader, "classLoader");
            this.servletMappings = new LinkedHashMap<>();
            this.filterMappings = new LinkedHashMap<>();
        }

        public Builder addServlet(String path, HttpServlet servlet) {
            servletMappings.put(normalizeRelativePath(path), Objects.requireNonNull(servlet, "servlet"));
            return this;
        }

        public Builder addFilter(String path, Filter filter) {
            filterMappings.put(normalizeRelativePath(path), Objects.requireNonNull(filter, "filter"));
            return this;
        }

        public HotDeployedWebApp build() {
            if (servletMappings.isEmpty()) {
                throw new IllegalArgumentException("Hot deployment requires at least one servlet mapping");
            }
            return new HotDeployedWebApp(
                    appName,
                    contextPath,
                    classLoader,
                    Collections.unmodifiableMap(new LinkedHashMap<>(servletMappings)),
                    Collections.unmodifiableMap(new LinkedHashMap<>(filterMappings)));
        }

        private static String normalizeAppName(String appName) {
            if (appName == null || appName.isBlank()) {
                throw new IllegalArgumentException("appName must be non-blank");
            }
            return appName.trim();
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

        private static String normalizeRelativePath(String path) {
            if (path == null || path.isBlank()) {
                return "/";
            }
            String normalized = path.trim();
            if (!normalized.startsWith("/")) {
                normalized = "/" + normalized;
            }
            return normalized;
        }
    }
}