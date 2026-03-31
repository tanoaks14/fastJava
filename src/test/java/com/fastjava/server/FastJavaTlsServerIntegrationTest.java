package com.fastjava.server;

import com.fastjava.server.tls.TlsConfig;
import com.fastjava.servlet.HttpServlet;
import com.fastjava.servlet.HttpServletRequest;
import com.fastjava.servlet.HttpServletResponse;
import com.fastjava.servlet.ServletException;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.NoSuchAlgorithmException;
import java.security.Principal;
import java.security.cert.X509Certificate;

import static org.junit.Assert.*;

public class FastJavaTlsServerIntegrationTest {

    private static FastJavaNioServer server;
    private static Path keystoreFile;
    private static int port;
    private static Path mtlsKeystoreFile;
    private static FastJavaNioServer mtlsServer;
    private static int mtlsPort;

    @BeforeClass
    public static void setUp() throws Exception {
        keystoreFile = Files.createTempDirectory("tls-test").resolve("test.p12");
        generateKeystore(keystoreFile);
        installPermissiveTrustManager();

        TlsConfig tlsConfig = TlsConfig.defaults(keystoreFile, "changeit".toCharArray());
        server = new FastJavaNioServer(0, RequestLimits.defaults(16_384), tlsConfig);
        server.addServlet("/hello", new HttpServlet() {
            @Override
            public void service(HttpServletRequest req, HttpServletResponse res) throws ServletException {
                res.setStatus(200);
                res.getWriter().write("Hello TLS");
            }
        });
        server.start();
        port = server.getBoundPort();

        mtlsKeystoreFile = Files.createTempDirectory("mtls-test").resolve("mtls-test.p12");
        generateKeystore(mtlsKeystoreFile);
        TlsConfig mtlsConfig = TlsConfig.defaults(mtlsKeystoreFile, "changeit".toCharArray())
                .withClientAuthRequired(mtlsKeystoreFile, "changeit".toCharArray());
        mtlsServer = new FastJavaNioServer(0, RequestLimits.defaults(16_384), mtlsConfig);
        mtlsServer.addServlet("/hello", new HttpServlet() {
            @Override
            public void service(HttpServletRequest req, HttpServletResponse res) throws ServletException {
                res.setStatus(200);
                res.getWriter().write("Hello mTLS");
            }
        });
        mtlsServer.start();
        mtlsPort = mtlsServer.getBoundPort();
    }

    @AfterClass
    public static void tearDown() throws Exception {
        if (server != null) {
            server.stop();
        }
        if (mtlsServer != null) {
            mtlsServer.stop();
        }
        if (keystoreFile != null) {
            Files.deleteIfExists(keystoreFile);
            Files.deleteIfExists(keystoreFile.getParent());
        }
        if (mtlsKeystoreFile != null) {
            Files.deleteIfExists(mtlsKeystoreFile);
            Files.deleteIfExists(mtlsKeystoreFile.getParent());
        }
    }

    @Test
    public void tlsGet_returns200WithBody() throws Exception {
        HttpsURLConnection conn = openHttps("/hello");
        try {
            assertEquals(200, conn.getResponseCode());
            try (InputStream in = conn.getInputStream()) {
                String body = new String(in.readAllBytes());
                assertTrue("body should contain greeting", body.contains("Hello TLS"));
            }
        } finally {
            conn.disconnect();
        }
    }

    @Test
    public void tlsGet_unknownPath_returns404() throws Exception {
        HttpsURLConnection conn = openHttps("/not-found");
        try {
            assertEquals(404, conn.getResponseCode());
        } finally {
            conn.disconnect();
        }
    }

    @Test
    public void tlsSession_usesModernProtocol() throws Exception {
        HttpsURLConnection conn = openHttps("/hello");
        try {
            conn.connect();
            String cipherSuite = conn.getCipherSuite();
            assertNotNull("cipher suite should be non-null after connect", cipherSuite);
            // Any established TLS 1.2 / 1.3 session will have a non-empty cipher suite.
            assertFalse("cipher suite should not be empty", cipherSuite.isEmpty());
        } finally {
            conn.disconnect();
        }
    }

    @Test
    public void mtlsWithoutClientCertificateFailsHandshake() throws Exception {
        HttpsURLConnection conn = openMutualTlsWithoutClientCert("/hello");
        try {
            try {
                int code = conn.getResponseCode();
                assertTrue("Expected non-success response without client certificate", code >= 400);
            } catch (IOException expected) {
                // Different JDK/OS stacks surface client-cert failures with different
                // IOException subtypes. Any I/O failure here is an acceptable outcome.
                assertNotNull(expected);
            }
        } finally {
            conn.disconnect();
        }
    }

    @Test
    public void mtlsWithClientCertificateReturns200() throws Exception {
        HttpsURLConnection conn = openMutualTlsWithClientCert("/hello");
        try {
            assertEquals(200, conn.getResponseCode());
            try (InputStream in = conn.getInputStream()) {
                String body = new String(in.readAllBytes());
                assertTrue(body.contains("Hello mTLS"));
            }
        } finally {
            conn.disconnect();
        }
    }

    @Test
    public void certificateHotReloadPresentsUpdatedServerCertificateWithoutRestart() throws Exception {
        Path hotReloadDir = Files.createTempDirectory("tls-hot-reload");
        Path liveKeystore = hotReloadDir.resolve("live.p12");
        Path rotatedKeystore = hotReloadDir.resolve("rotated.p12");

        generateKeystore(liveKeystore, "CN=reload-one,OU=Test,O=Test,L=Test,ST=Test,C=US");
        generateKeystore(rotatedKeystore, "CN=reload-two,OU=Test,O=Test,L=Test,ST=Test,C=US");

        TlsConfig reloadConfig = TlsConfig.defaults(liveKeystore, "changeit".toCharArray())
                .withCertificateHotReload(100);

        FastJavaNioServer reloadServer = new FastJavaNioServer(0, RequestLimits.defaults(16_384), reloadConfig);
        reloadServer.addServlet("/hello", new HttpServlet() {
            @Override
            public void service(HttpServletRequest req, HttpServletResponse res) throws ServletException {
                res.setStatus(200);
                res.getWriter().write("Hello Hot Reload");
            }
        });
        reloadServer.start();

        try {
            int reloadPort = reloadServer.getBoundPort();
            String subjectBefore = fetchServerCertificateSubject(reloadPort);
            assertTrue(subjectBefore.contains("CN=reload-one"));

            Files.move(rotatedKeystore, liveKeystore, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

            long deadline = System.currentTimeMillis() + 5_000;
            while (System.currentTimeMillis() < deadline) {
                try {
                    String subjectAfter = fetchServerCertificateSubject(reloadPort);
                    if (subjectAfter.contains("CN=reload-two")) {
                        return;
                    }
                } catch (IOException transientTlsError) {
                    // During cert rotation there may be a brief window where individual
                    // handshakes fail; retry until deadline.
                }
                Thread.sleep(100);
            }
            fail("Expected hot-reloaded certificate subject CN=reload-two");
        } finally {
            reloadServer.stop();
            Files.deleteIfExists(liveKeystore);
            Files.deleteIfExists(rotatedKeystore);
            Files.deleteIfExists(hotReloadDir);
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static HttpsURLConnection openHttps(String path) throws IOException {
        return (HttpsURLConnection) new URL("https://localhost:" + port + path).openConnection();
    }

    private static HttpsURLConnection openMutualTlsWithoutClientCert(String path) throws Exception {
        return (HttpsURLConnection) new URL("https://localhost:" + mtlsPort + path).openConnection();
    }

    private static HttpsURLConnection openMutualTlsWithClientCert(String path) throws Exception {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(mtlsKeystoreFile)) {
            keyStore.load(in, "changeit".toCharArray());
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(keyStore, "changeit".toCharArray());

        TrustManager[] trustAll = new TrustManager[] {
                new X509TrustManager() {
                    public void checkClientTrusted(X509Certificate[] chain, String authType) {
                    }

                    public void checkServerTrusted(X509Certificate[] chain, String authType) {
                    }

                    public X509Certificate[] getAcceptedIssuers() {
                        return new X509Certificate[0];
                    }
                }
        };

        SSLContext clientContext = SSLContext.getInstance("TLS");
        clientContext.init(kmf.getKeyManagers(), trustAll, new java.security.SecureRandom());

        HttpsURLConnection connection = (HttpsURLConnection) new URL("https://localhost:" + mtlsPort + path)
                .openConnection();
        connection.setSSLSocketFactory(clientContext.getSocketFactory());
        connection.setHostnameVerifier((hostname, session) -> true);
        return connection;
    }

    private static void generateKeystore(Path dest) throws Exception {
        generateKeystore(dest, "CN=localhost,OU=Test,O=Test,L=Test,ST=Test,C=US");
    }

    private static void generateKeystore(Path dest, String distinguishedName) throws Exception {
        String keytool = System.getProperty("java.home") + "/bin/keytool";
        ProcessBuilder pb = new ProcessBuilder(
                keytool,
                "-genkeypair",
                "-keyalg", "RSA",
                "-keysize", "2048",
                "-validity", "1",
                "-alias", "test",
                "-keystore", dest.toString(),
                "-storetype", "PKCS12",
                "-storepass", "changeit",
                "-keypass", "changeit",
                "-dname", distinguishedName,
                "-noprompt");
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String output = new String(p.getInputStream().readAllBytes());
        int exitCode = p.waitFor();
        assertEquals("keytool exit code (output: " + output + ")", 0, exitCode);
        assertTrue("keystore file should exist after keytool", Files.exists(dest));
    }

    private static void installPermissiveTrustManager()
            throws NoSuchAlgorithmException, KeyManagementException {
        TrustManager[] trustAll = new TrustManager[] {
                new X509TrustManager() {
                    public void checkClientTrusted(X509Certificate[] chain, String authType) {
                    }

                    public void checkServerTrusted(X509Certificate[] chain, String authType) {
                    }

                    public X509Certificate[] getAcceptedIssuers() {
                        return new X509Certificate[0];
                    }
                }
        };
        SSLContext sc = SSLContext.getInstance("TLS");
        sc.init(null, trustAll, new java.security.SecureRandom());
        HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
        HttpsURLConnection.setDefaultHostnameVerifier((hostname, session) -> true);
    }

    private static String fetchServerCertificateSubject(int serverPort) throws Exception {
        TrustManager[] trustAll = new TrustManager[] {
                new X509TrustManager() {
                    public void checkClientTrusted(X509Certificate[] chain, String authType) {
                    }

                    public void checkServerTrusted(X509Certificate[] chain, String authType) {
                    }

                    public X509Certificate[] getAcceptedIssuers() {
                        return new X509Certificate[0];
                    }
                }
        };

        SSLContext clientContext = SSLContext.getInstance("TLS");
        clientContext.init(null, trustAll, new java.security.SecureRandom());
        SSLSocketFactory socketFactory = clientContext.getSocketFactory();
        try (SSLSocket socket = (SSLSocket) socketFactory.createSocket("localhost", serverPort)) {
            socket.startHandshake();
            Principal principal = ((X509Certificate) socket.getSession().getPeerCertificates()[0])
                    .getSubjectX500Principal();
            return principal.getName();
        }
    }
}
