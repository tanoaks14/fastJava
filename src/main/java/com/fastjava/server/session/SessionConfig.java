package com.fastjava.server.session;

/**
 * Session and session-cookie settings.
 */
public record SessionConfig(
        String cookieName,
        String cookiePath,
        String cookieDomain,
        String sameSite,
        boolean httpOnly,
        boolean requireSecureCookie,
        int maxInactiveIntervalSeconds,
        int maxAttributesPerSession,
        int cleanupIntervalOperations,
        int cleanupBatchSize) {

    public SessionConfig {
        if (cookieName == null || cookieName.isBlank()) {
            throw new IllegalArgumentException("cookieName cannot be blank");
        }
        if (cookiePath == null || cookiePath.isBlank()) {
            throw new IllegalArgumentException("cookiePath cannot be blank");
        }
        if (sameSite == null || sameSite.isBlank()) {
            throw new IllegalArgumentException("sameSite cannot be blank");
        }
        if (maxInactiveIntervalSeconds <= 0) {
            throw new IllegalArgumentException("maxInactiveIntervalSeconds must be positive");
        }
        if (maxAttributesPerSession <= 0) {
            throw new IllegalArgumentException("maxAttributesPerSession must be positive");
        }
        if (cleanupIntervalOperations <= 0) {
            throw new IllegalArgumentException("cleanupIntervalOperations must be positive");
        }
        if (cleanupBatchSize <= 0) {
            throw new IllegalArgumentException("cleanupBatchSize must be positive");
        }
        cookieName = cookieName.trim();
        cookiePath = cookiePath.trim();
        cookieDomain = normalizeNullable(cookieDomain);
        sameSite = sameSite.trim();
    }

    public static SessionConfig defaults() {
        return new SessionConfig(
                "JSESSIONID",
                "/",
                null,
                "Lax",
                true,
                false,
                1_800,
                128,
                256,
                64);
    }

    private static String normalizeNullable(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
