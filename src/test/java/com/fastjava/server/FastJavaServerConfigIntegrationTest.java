package com.fastjava.server;

import com.fastjava.examples.HelloWorldServlet;
import com.fastjava.server.config.ServerConfig;
import com.fastjava.server.config.ServerConfigLoader;
import org.junit.After;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class FastJavaServerConfigIntegrationTest {

    private FastJavaServer server;

    @After
    public void tearDown() {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    public void bootsFromPropertiesConfiguration() throws Exception {
        Path configPath = Files.createTempFile("fastjava-config-", ".properties");
        try {
            Files.writeString(configPath,
                    "server.port=0\n"
                            + "server.threads=2\n"
                            + "request.maxRequestSize=16384\n"
                            + "request.keepAliveTimeoutMillis=500\n"
                            + "request.maxRequestLineBytes=4096\n"
                            + "request.maxHeaderBytes=8192\n"
                            + "request.maxBodyBytes=16384\n"
                            + "request.maxChunkSizeBytes=16384\n"
                            + "request.maxChunkCount=16384\n"
                            + "request.writeTimeoutMillis=5000\n"
                            + "request.maxMultipartBytes=16384\n"
                            + "request.maxMultipartPartBytes=16384\n"
                            + "request.multipartMemoryThresholdBytes=4096\n");

            ServerConfig config = ServerConfigLoader.load(configPath);
            server = new FastJavaServer(config.port(), config.threads(), config.requestLimits());
            server.addServlet("/", new HelloWorldServlet());
            server.start();
            int boundPort = server.getBoundPort();
            waitForServerReady(boundPort);

            RawHttpResponse response = sendRequest(boundPort,
                    "GET / HTTP/1.1\r\n"
                            + "Host: localhost\r\n"
                            + "Connection: close\r\n"
                            + "\r\n");

            assertEquals(200, response.statusCode);
            assertTrue(response.body.contains("Hello from FastJava SIMD Server!"));
        } finally {
            Files.deleteIfExists(configPath);
        }
    }

    private static void waitForServerReady(int targetPort) throws Exception {
        long deadline = System.currentTimeMillis() + 2_000;
        while (System.currentTimeMillis() < deadline) {
            try (Socket ignored = new Socket()) {
                ignored.connect(new InetSocketAddress("127.0.0.1", targetPort), 100);
                return;
            } catch (IOException ignored) {
                Thread.sleep(25);
            }
        }
        throw new IllegalStateException("Server did not become ready in time");
    }

    private static RawHttpResponse sendRequest(int port, String rawRequest) throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 1_000);
            socket.setSoTimeout(2_000);

            OutputStream output = socket.getOutputStream();
            output.write(rawRequest.getBytes(StandardCharsets.US_ASCII));
            output.flush();

            return RawHttpResponse.readFrom(socket.getInputStream());
        }
    }

    private static final class RawHttpResponse {
        private final int statusCode;
        private final String body;

        private RawHttpResponse(int statusCode, String body) {
            this.statusCode = statusCode;
            this.body = body;
        }

        private static RawHttpResponse readFrom(InputStream input) throws IOException {
            String headers = readHeaders(input);
            int contentLength = contentLength(headers);
            byte[] bodyBytes = input.readNBytes(contentLength);
            if (bodyBytes.length != contentLength) {
                throw new IllegalArgumentException("Incomplete HTTP response body");
            }

            String[] lines = headers.split("\\r\\n");
            int statusCode = Integer.parseInt(lines[0].split(" ")[1]);
            return new RawHttpResponse(statusCode, new String(bodyBytes, StandardCharsets.UTF_8));
        }

        private static String readHeaders(InputStream input) throws IOException {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            int current;
            int matched = 0;
            byte[] terminator = new byte[] { '\r', '\n', '\r', '\n' };

            while ((current = input.read()) != -1) {
                output.write(current);
                if (current == terminator[matched]) {
                    matched++;
                    if (matched == terminator.length) {
                        byte[] raw = output.toByteArray();
                        return new String(raw, 0, raw.length - terminator.length, StandardCharsets.UTF_8);
                    }
                } else {
                    matched = current == terminator[0] ? 1 : 0;
                }
            }

            throw new IllegalArgumentException("Incomplete HTTP response headers");
        }

        private static int contentLength(String headers) {
            String[] lines = headers.split("\\r\\n");
            for (int index = 1; index < lines.length; index++) {
                String line = lines[index];
                int separatorIndex = line.indexOf(':');
                if (separatorIndex < 0) {
                    continue;
                }
                String name = line.substring(0, separatorIndex).trim();
                if ("Content-Length".equalsIgnoreCase(name)) {
                    return Integer.parseInt(line.substring(separatorIndex + 1).trim());
                }
            }
            return 0;
        }
    }
}
