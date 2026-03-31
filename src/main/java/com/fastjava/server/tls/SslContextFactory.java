package com.fastjava.server.tls;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.InputStream;
import java.nio.file.Files;
import java.security.KeyStore;

/**
 * Factory that builds an {@link SSLContext} from a {@link TlsConfig}.
 */
public final class SslContextFactory {

    private SslContextFactory() {
    }

    /**
     * Creates a server-side {@link SSLContext} from the given configuration.
     *
     * @param cfg TLS configuration (PKCS12 keystore)
     * @return initialised {@link SSLContext}
     * @throws Exception if the keystore cannot be loaded or the context cannot be
     *                   initialised
     */
    public static SSLContext create(TlsConfig cfg) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(cfg.keystoreFile())) {
            ks.load(in, cfg.keystorePassword());
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, cfg.keyPassword());

        TrustManagerFactory tmf = null;
        if (cfg.truststoreFile() != null) {
            KeyStore trustStore = KeyStore.getInstance("PKCS12");
            try (InputStream in = Files.newInputStream(cfg.truststoreFile())) {
                trustStore.load(in, cfg.truststorePassword());
            }
            tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(trustStore);
        }

        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), tmf == null ? null : tmf.getTrustManagers(), null);
        return ctx;
    }
}
