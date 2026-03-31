package com.fastjava.servlet;

import java.time.Instant;

/**
 * Minimal cookie model compatible with servlet-style request/response APIs.
 */
public class Cookie {
    private final String name;
    private String value;
    private String domain;
    private String path;
    private Integer maxAge;
    private Instant expires;
    private boolean secure;
    private boolean httpOnly;
    private String sameSite;

    public Cookie(String name, String value) {
        this.name = requireNonBlank(name, "Cookie name cannot be blank");
        this.value = value == null ? "" : value;
    }

    public String getName() {
        return name;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value == null ? "" : value;
    }

    public String getDomain() {
        return domain;
    }

    public void setDomain(String domain) {
        this.domain = normalizeNullable(domain);
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = normalizeNullable(path);
    }

    public Integer getMaxAge() {
        return maxAge;
    }

    public void setMaxAge(Integer maxAge) {
        this.maxAge = maxAge;
    }

    public Instant getExpires() {
        return expires;
    }

    public void setExpires(Instant expires) {
        this.expires = expires;
    }

    public boolean isSecure() {
        return secure;
    }

    public void setSecure(boolean secure) {
        this.secure = secure;
    }

    public boolean isHttpOnly() {
        return httpOnly;
    }

    public void setHttpOnly(boolean httpOnly) {
        this.httpOnly = httpOnly;
    }

    public String getSameSite() {
        return sameSite;
    }

    public void setSameSite(String sameSite) {
        this.sameSite = normalizeNullable(sameSite);
    }

    private static String requireNonBlank(String value, String message) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private static String normalizeNullable(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
