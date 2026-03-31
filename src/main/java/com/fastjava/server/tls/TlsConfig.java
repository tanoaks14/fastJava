package com.fastjava.server.tls;

import java.nio.file.Path;

/**
 * Immutable TLS configuration for {@code FastJavaNioServer}.
 * <p>
 * Passwords are stored as {@code char[]} rather than {@code String} so the
 * caller can zero them out with {@code Arrays.fill(password, '\0')} after the
 * server has been started and the {@link javax.net.ssl.SSLContext} initialised.
 */
public record TlsConfig(
        Path keystoreFile,
        char[] keystorePassword,
        char[] keyPassword,
        String[] protocols,
        String[] applicationProtocols,
        Path truststoreFile,
        char[] truststorePassword,
        ClientAuthMode clientAuthMode,
        boolean certificateHotReloadEnabled,
        long certificateHotReloadCheckIntervalMillis) {

    public enum ClientAuthMode {
        NONE,
        WANT,
        NEED
    }

    /**
     * Creates a sensible default configuration using a PKCS12 keystore.
     * Advertises TLS 1.2 / 1.3 and ALPN {@code http/1.1}.
     *
     * @param keystoreFile path to the PKCS12 keystore
     * @param password     keystore and key password (same value)
     */
    public static TlsConfig defaults(Path keystoreFile, char[] password) {
        return new TlsConfig(
                keystoreFile,
                password,
                password,
                new String[] { "TLSv1.2", "TLSv1.3" },
                new String[] { "http/1.1" },
                null,
                null,
                ClientAuthMode.NONE,
                false,
                1_000L);
    }

    public TlsConfig withClientAuthRequired(Path truststoreFile, char[] truststorePassword) {
        return new TlsConfig(
                keystoreFile,
                keystorePassword,
                keyPassword,
                protocols,
                applicationProtocols,
                truststoreFile,
                truststorePassword,
                ClientAuthMode.NEED,
                certificateHotReloadEnabled,
                certificateHotReloadCheckIntervalMillis);
    }

    public TlsConfig withCertificateHotReload(long checkIntervalMillis) {
        long interval = Math.max(100L, checkIntervalMillis);
        return new TlsConfig(
                keystoreFile,
                keystorePassword,
                keyPassword,
                protocols,
                applicationProtocols,
                truststoreFile,
                truststorePassword,
                clientAuthMode,
                true,
                interval);
    }
}
