package com.fastjava.server;

import com.fastjava.examples.ApiServlet;
import com.fastjava.examples.HelloWorldServlet;
import com.fastjava.examples.StaticFileServlet;
import com.fastjava.http.filter.CorsFilter;
import com.fastjava.sse.SseEmitter;
import com.fastjava.sse.SseEvent;
import com.fastjava.sse.SseSupport;
import com.fastjava.servlet.Filter;
import com.fastjava.servlet.FilterChain;
import com.fastjava.servlet.FilterConfig;
import com.fastjava.servlet.AsyncContext;
import com.fastjava.servlet.AsyncEvent;
import com.fastjava.servlet.AsyncListener;
import com.fastjava.servlet.HttpServlet;
import com.fastjava.servlet.HttpServletRequest;
import com.fastjava.servlet.HttpServletResponse;
import com.fastjava.servlet.HttpSession;
import com.fastjava.servlet.ServletConfig;
import com.fastjava.servlet.ServletException;
import com.fastjava.websocket.WebSocketSession;
import com.fastjava.websocket.WebSocketPerMessageDeflate;
import com.fastjava.websocket.annotation.OnClose;
import com.fastjava.websocket.annotation.OnMessage;
import com.fastjava.websocket.annotation.OnOpen;
import com.fastjava.websocket.annotation.PathParam;
import com.fastjava.websocket.annotation.WebSocketEndpoint;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.SecureRandom;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class FastJavaNioServerIntegrationTest {

    private static final String LARGE_BODY = "0123456789abcdef".repeat(8192);
    private static final String VERY_LARGE_BODY = "0123456789abcdef".repeat(1_048_576);

    private FastJavaNioServer server;
    private int port;
    private CountDownLatch slowServletStarted;
    private CountDownLatch slowServletRelease;
    private LifecycleServlet lifecycleServlet;
    private HeaderFilter headerFilter;
    private PipelineObserveServlet pipelineObserveServlet;
    private Path staticRoot;
    private String staticPayload;

    @Before
    public void setUp() throws Exception {
        server = new FastJavaNioServer(0, new RequestLimits(16384, 500, 128, 512, 512), 4, 1024);
        slowServletStarted = new CountDownLatch(1);
        slowServletRelease = new CountDownLatch(1);
        lifecycleServlet = new LifecycleServlet();
        headerFilter = new HeaderFilter();
        pipelineObserveServlet = new PipelineObserveServlet();
        server.addFilter(new CorsFilter(Set.of("https://app.example")));
        staticRoot = Files.createTempDirectory("fastjava-nio-static");
        staticPayload = "static-body-" + "abc123".repeat(4096);
        Files.writeString(staticRoot.resolve("asset.txt"), staticPayload, StandardCharsets.UTF_8);
        server.addServlet("/", new HelloWorldServlet());
        server.addServlet("/api/hello", new ApiServlet());
        server.addServlet("/search", new QueryEchoServlet());
        server.addServlet("/slow", new SlowServlet());
        server.addServlet("/large", new LargeBodyServlet());
        server.addServlet("/chunked", new ChunkedServlet());
        server.addServlet("/pipeline", new PipelineServlet());
        server.addServlet("/pipeline-observe", pipelineObserveServlet);
        server.addServlet("/async", new AsyncPipelineServlet());
        server.addServlet("/async-timeout", new AsyncTimeoutServlet());
        server.addServlet("/async-dispatch", new AsyncDispatchServlet());
        server.addServlet("/async-listener", new AsyncListenerServlet());
        server.addServlet("/sse", new SseServlet());
        server.addServletPattern("/static/*", new StaticFileServlet(staticRoot, "/static"));
        server.addWebSocketEndpoint(AnnotatedEchoEndpoint.class);
        server.addServlet("/echo-body", new EchoBodyServlet());
        server.addServlet("/session-counter", new SessionCounterServlet());
        server.addServlet("/lifecycle", lifecycleServlet);
        server.addFilter("/lifecycle", headerFilter);
        server.addWebSocketEndpoint(TemplatePathEndpoint.class);
        server.start();
        port = server.getBoundPort();
        waitForServerReady();
    }

    @After
    public void tearDown() {
        slowServletRelease.countDown();
        if (server != null) {
            server.stop();
        }
    }

    @Test
    public void servesSimpleGetRequest() throws Exception {
        RawHttpResponse response = sendRequest(
                "GET / HTTP/1.1\r\n"
                + "Host: localhost\r\n"
                + "Connection: close\r\n"
                + "\r\n");

        assertEquals(200, response.statusCode);
        assertEquals("text/html", response.header("Content-Type"));
        assertTrue(response.body.contains("Hello from FastJava SIMD Server!"));
    }

    @Test
    public void streamsSseResponseThroughNioLifecycle() throws Exception {
        RawHttpResponse response = sendRequest(
                "GET /sse HTTP/1.1\r\n"
                + "Host: localhost\r\n"
                + "Connection: close\r\n"
                + "\r\n");

        assertEquals(200, response.statusCode);
        assertTrue(response.header("Content-Type").startsWith("text/event-stream"));
        assertEquals("chunked", response.header("Transfer-Encoding"));
        assertTrue(response.body.contains("event: greeting"));
        assertTrue(response.body.contains("data: hello-sse"));
    }

    @Test
    public void exposesPrometheusMetricsEndpoint() throws Exception {
        RawHttpResponse response = sendRequest(
                "GET /metrics HTTP/1.1\r\n"
                + "Host: localhost\r\n"
                + "Connection: close\r\n"
                + "\r\n");

        assertEquals(200, response.statusCode);
        assertTrue(response.header("Content-Type").startsWith("text/plain"));
        assertTrue(response.body.contains("fastjava_requests_total"));
        assertTrue(response.body.contains("fastjava_active_connections"));
        assertTrue(response.body.contains("fastjava_request_duration_ms_bucket"));
    }

    @Test
    public void corsSimpleRequestAddsAllowOriginHeader() throws Exception {
        RawHttpResponse response = sendRequest(
                "GET / HTTP/1.1\r\n"
                + "Host: localhost\r\n"
                + "Origin: https://app.example\r\n"
                + "Connection: close\r\n"
                + "\r\n");

        assertEquals(200, response.statusCode);
        assertEquals("https://app.example", response.header("Access-Control-Allow-Origin"));
        assertEquals("Origin", response.header("Vary"));
    }

    @Test
    public void supportsFragmentedReads() throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 1_000);
            socket.setSoTimeout(2_000);

            OutputStream output = socket.getOutputStream();
            output.write("GET /search?q=frag".getBytes(StandardCharsets.US_ASCII));
            output.flush();

            Thread.sleep(25);

            output.write(("mented HTTP/1.1\r\n"
                    + "Host: localhost\r\n"
                    + "Connection: close\r\n"
                    + "\r\n").getBytes(StandardCharsets.US_ASCII));
            output.flush();

            RawHttpResponse response = RawHttpResponse.readFrom(socket.getInputStream());
            assertEquals(200, response.statusCode);
            assertEquals("q=fragmented", response.body.trim());
        }
    }

    @Test
    public void supportsKeepAliveAcrossSequentialRequests() throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 1_000);
            socket.setSoTimeout(2_000);

            OutputStream output = socket.getOutputStream();
            InputStream input = socket.getInputStream();

            output.write(("GET / HTTP/1.1\r\n"
                    + "Host: localhost\r\n"
                    + "Connection: keep-alive\r\n"
                    + "\r\n").getBytes(StandardCharsets.US_ASCII));
            output.flush();

            RawHttpResponse firstResponse = RawHttpResponse.readFrom(input);
            assertEquals(200, firstResponse.statusCode);
            assertEquals("keep-alive", firstResponse.header("Connection"));

            output.write(("GET /search?q=nio HTTP/1.1\r\n"
                    + "Host: localhost\r\n"
                    + "Connection: close\r\n"
                    + "\r\n").getBytes(StandardCharsets.US_ASCII));
            output.flush();

            RawHttpResponse secondResponse = RawHttpResponse.readFrom(input);
            assertEquals(200, secondResponse.statusCode);
            assertEquals("close", secondResponse.header("Connection"));
            assertEquals("q=nio", secondResponse.body.trim());
        }
    }

    @Test
    public void closesIdleKeepAliveConnectionsAfterConfiguredTimeout() throws Exception {
        FastJavaNioServer timeoutServer = new FastJavaNioServer(0,
                new RequestLimits(16384, 250, 128, 512, 512, 1_000),
                4,
                1024);
        timeoutServer.addServlet("/", new HelloWorldServlet());
        timeoutServer.start();
        int timeoutServerPort = timeoutServer.getBoundPort();
        waitForServerReady(timeoutServerPort);

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", timeoutServerPort), 1_000);
            socket.setSoTimeout(1_500);

            OutputStream output = socket.getOutputStream();
            InputStream input = socket.getInputStream();

            output.write(("GET / HTTP/1.1\r\n"
                    + "Host: localhost\r\n"
                    + "Connection: keep-alive\r\n"
                    + "\r\n").getBytes(StandardCharsets.US_ASCII));
            output.flush();

            RawHttpResponse firstResponse = RawHttpResponse.readFrom(input);
            assertEquals(200, firstResponse.statusCode);
            assertEquals("keep-alive", firstResponse.header("Connection"));

            Thread.sleep(700);

            int closed = input.read();
            assertEquals(-1, closed);
        } catch (java.net.SocketTimeoutException timeout) {
            fail("Expected idle keep-alive connection to close before read timeout");
        } finally {
            timeoutServer.stop();
        }
    }

    @Test
    public void upgradesConnectionToWebSocketAndEchoesTextFrames() throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 1_000);
            socket.setSoTimeout(2_000);

            OutputStream output = socket.getOutputStream();
            InputStream input = socket.getInputStream();

            output.write(("GET / HTTP/1.1\r\n"
                    + "Host: localhost\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Version: 13\r\n"
                    + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"
                    + "\r\n").getBytes(StandardCharsets.US_ASCII));
            output.flush();

            String handshake = RawHttpResponse.readHeaders(input);
            assertTrue(handshake.startsWith("HTTP/1.1 101 Switching Protocols"));
            assertEquals("websocket", RawHttpResponse.headerValue(handshake, "Upgrade"));
            assertEquals("Upgrade", RawHttpResponse.headerValue(handshake, "Connection"));
            assertEquals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=",
                    RawHttpResponse.headerValue(handshake, "Sec-WebSocket-Accept"));
            assertNull(RawHttpResponse.headerValue(handshake, "Sec-WebSocket-Protocol"));

            output.write(WebSocketTestFrameCodec.maskedClientText("hello-websocket"));
            output.flush();

            WebSocketTestFrame frame = WebSocketTestFrameCodec.readServerFrame(input);
            assertEquals(0x1, frame.opcode);
            assertTrue(frame.fin);
            assertEquals("hello-websocket", new String(frame.payload, StandardCharsets.UTF_8));
        }
    }

    @Test
    public void handlesWebSocketPingPongAndCloseHandshake() throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 1_000);
            socket.setSoTimeout(2_000);

            OutputStream output = socket.getOutputStream();
            InputStream input = socket.getInputStream();

            output.write(("GET / HTTP/1.1\r\n"
                    + "Host: localhost\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Version: 13\r\n"
                    + "Sec-WebSocket-Key: YWJjZGVmZ2hpamtsbW5vcA==\r\n"
                    + "\r\n").getBytes(StandardCharsets.US_ASCII));
            output.flush();

            String handshake = RawHttpResponse.readHeaders(input);
            assertTrue(handshake.startsWith("HTTP/1.1 101 Switching Protocols"));

            byte[] pingPayload = "ping-data".getBytes(StandardCharsets.UTF_8);
            output.write(WebSocketTestFrameCodec.maskedClientFrame(0x9, true, pingPayload));
            output.flush();

            WebSocketTestFrame pong = WebSocketTestFrameCodec.readServerFrame(input);
            assertEquals(0xA, pong.opcode);
            assertTrue(pong.fin);
            assertEquals("ping-data", new String(pong.payload, StandardCharsets.UTF_8));

            output.write(WebSocketTestFrameCodec.maskedClientClose(1000));
            output.flush();

            WebSocketTestFrame close = WebSocketTestFrameCodec.readServerFrame(input);
            assertEquals(0x8, close.opcode);
            assertTrue(close.fin);
            assertTrue(close.payload.length >= 2);
            int closeCode = ((close.payload[0] & 0xFF) << 8) | (close.payload[1] & 0xFF);
            assertEquals(1000, closeCode);

            assertTrue(input.read() == -1 || input.read() == -1);
        }
    }

    @Test
    public void rejectsInvalidWebSocketUpgradeRequest() throws Exception {
        RawHttpResponse response = sendRequest(
                "GET / HTTP/1.1\r\n"
                + "Host: localhost\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Version: 12\r\n"
                + "Sec-WebSocket-Key: invalid\r\n"
                + "\r\n");

        assertEquals(400, response.statusCode);
    }

    @Test
    public void dispatchesWebSocketFramesToAnnotatedEndpoint() throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 1_000);
            socket.setSoTimeout(2_000);

            OutputStream output = socket.getOutputStream();
            InputStream input = socket.getInputStream();

            output.write(("GET /ws/annotated HTTP/1.1\r\n"
                    + "Host: localhost\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Version: 13\r\n"
                    + "Sec-WebSocket-Key: MDEyMzQ1Njc4OWFiY2RlZg==\r\n"
                    + "\r\n").getBytes(StandardCharsets.US_ASCII));
            output.flush();

            String handshake = RawHttpResponse.readHeaders(input);
            assertTrue(handshake.startsWith("HTTP/1.1 101 Switching Protocols"));

            output.write(WebSocketTestFrameCodec.maskedClientText("hello"));
            output.flush();

            WebSocketTestFrame frame = WebSocketTestFrameCodec.readServerFrame(input);
            assertEquals(0x1, frame.opcode);
            assertTrue(frame.fin);
            assertEquals("annotated:hello", new String(frame.payload, StandardCharsets.UTF_8));
        }
    }

    @Test
    public void negotiatesWebSocketSubprotocolForAnnotatedEndpoint() throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 1_000);
            socket.setSoTimeout(2_000);

            OutputStream output = socket.getOutputStream();
            InputStream input = socket.getInputStream();

            output.write(("GET /ws/annotated HTTP/1.1\r\n"
                    + "Host: localhost\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Version: 13\r\n"
                    + "Sec-WebSocket-Key: MDEyMzQ1Njc4OWFiY2RlZg==\r\n"
                    + "Sec-WebSocket-Protocol: unknown, chat.v2\r\n"
                    + "\r\n").getBytes(StandardCharsets.US_ASCII));
            output.flush();

            String handshake = RawHttpResponse.readHeaders(input);
            assertTrue(handshake.startsWith("HTTP/1.1 101 Switching Protocols"));
            assertEquals("chat.v2", RawHttpResponse.headerValue(handshake, "Sec-WebSocket-Protocol"));

            output.write(WebSocketTestFrameCodec.maskedClientText("hello"));
            output.flush();

            WebSocketTestFrame frame = WebSocketTestFrameCodec.readServerFrame(input);
            assertEquals("annotated:hello", new String(frame.payload, StandardCharsets.UTF_8));
        }
    }

    @Test
    public void negotiatesPerMessageDeflateAndHandlesCompressedFrames() throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 1_000);
            socket.setSoTimeout(2_000);

            OutputStream output = socket.getOutputStream();
            InputStream input = socket.getInputStream();

            output.write(("GET /ws/annotated HTTP/1.1\r\n"
                    + "Host: localhost\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Version: 13\r\n"
                    + "Sec-WebSocket-Key: MDEyMzQ1Njc4OWFiY2RlZg==\r\n"
                    + "Sec-WebSocket-Extensions: permessage-deflate\r\n"
                    + "\r\n").getBytes(StandardCharsets.US_ASCII));
            output.flush();

            String handshake = RawHttpResponse.readHeaders(input);
            assertTrue(handshake.startsWith("HTTP/1.1 101 Switching Protocols"));
            assertEquals(
                    "permessage-deflate; server_no_context_takeover; client_no_context_takeover",
                    RawHttpResponse.headerValue(handshake, "Sec-WebSocket-Extensions"));

            output.write(WebSocketTestFrameCodec.maskedClientCompressedText("hello-compressed"));
            output.flush();

            WebSocketTestFrame frame = WebSocketTestFrameCodec.readServerFrame(input);
            assertEquals(0x1, frame.opcode);
            assertTrue(frame.compressed);
            byte[] inflated = WebSocketPerMessageDeflate.inflateMessage(frame.payload, 64 * 1024);
            assertEquals("annotated:hello-compressed", new String(inflated, StandardCharsets.UTF_8));
        }
    }

    @Test
    public void dispatchesWebSocketFramesToTemplateEndpointWithPathParams() throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 1_000);
            socket.setSoTimeout(2_000);

            OutputStream output = socket.getOutputStream();
            InputStream input = socket.getInputStream();

            output.write(("GET /ws/template/general/alice HTTP/1.1\r\n"
                    + "Host: localhost\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Version: 13\r\n"
                    + "Sec-WebSocket-Key: MDEyMzQ1Njc4OWFiY2RlZg==\r\n"
                    + "\r\n").getBytes(StandardCharsets.US_ASCII));
            output.flush();

            String handshake = RawHttpResponse.readHeaders(input);
            assertTrue(handshake.startsWith("HTTP/1.1 101 Switching Protocols"));

            output.write(WebSocketTestFrameCodec.maskedClientText("hello"));
            output.flush();

            WebSocketTestFrame frame = WebSocketTestFrameCodec.readServerFrame(input);
            assertEquals(0x1, frame.opcode);
            assertEquals("general/alice:hello", new String(frame.payload, StandardCharsets.UTF_8));
        }
    }

    @Test
    public void hotDeploysAndRedeploysIsolatedWebAppOnNioPath() throws Exception {
        URLClassLoader classLoaderV1 = new URLClassLoader(new java.net.URL[0], getClass().getClassLoader());
        URLClassLoader classLoaderV2 = new URLClassLoader(new java.net.URL[0], getClass().getClassLoader());
        try {
            server.deployWebApp(HotDeployedWebApp.builder("app-nio", "/apps/chat", classLoaderV1)
                    .addServlet("/status", new ContextClassLoaderServlet("v1", classLoaderV1))
                    .build());

            RawHttpResponse first = sendRequest(
                    "GET /apps/chat/status HTTP/1.1\r\n"
                    + "Host: localhost\r\n"
                    + "Connection: close\r\n"
                    + "\r\n");
            assertEquals(200, first.statusCode);
            assertEquals("v1:true", first.body.trim());

            server.deployWebApp(HotDeployedWebApp.builder("app-nio", "/apps/chat", classLoaderV2)
                    .addServlet("/status", new ContextClassLoaderServlet("v2", classLoaderV2))
                    .build());

            RawHttpResponse second = sendRequest(
                    "GET /apps/chat/status HTTP/1.1\r\n"
                    + "Host: localhost\r\n"
                    + "Connection: close\r\n"
                    + "\r\n");
            assertEquals(200, second.statusCode);
            assertEquals("v2:true", second.body.trim());

            assertTrue(server.undeployWebApp("app-nio"));
            RawHttpResponse afterUndeploy = sendRequest(
                    "GET /apps/chat/status HTTP/1.1\r\n"
                    + "Host: localhost\r\n"
                    + "Connection: close\r\n"
                    + "\r\n");
            assertEquals(404, afterUndeploy.statusCode);
        } finally {
            classLoaderV1.close();
            classLoaderV2.close();
        }
    }

    @Test
    public void executesServletsOffSelectorThread() throws Exception {
        try (Socket slowSocket = new Socket()) {
            slowSocket.connect(new InetSocketAddress("127.0.0.1", port), 1_000);
            slowSocket.setSoTimeout(2_000);

            OutputStream slowOutput = slowSocket.getOutputStream();
            slowOutput.write(("GET /slow HTTP/1.1\r\n"
                    + "Host: localhost\r\n"
                    + "Connection: close\r\n"
                    + "\r\n").getBytes(StandardCharsets.US_ASCII));
            slowOutput.flush();

            assertTrue(slowServletStarted.await(1, TimeUnit.SECONDS));

            RawHttpResponse fastResponse = sendRequest(
                    "GET /search?q=parallel HTTP/1.1\r\n"
                    + "Host: localhost\r\n"
                    + "Connection: close\r\n"
                    + "\r\n");
            assertEquals(200, fastResponse.statusCode);
            assertEquals("q=parallel", fastResponse.body.trim());

            slowServletRelease.countDown();
            RawHttpResponse slowResponse = RawHttpResponse.readFrom(slowSocket.getInputStream());
            assertEquals(200, slowResponse.statusCode);
            assertTrue(slowResponse.body.contains("slow-done"));
        }
    }

    @Test
    public void supportsLargeResponsesAcrossMultipleWriteCycles() throws Exception {
        RawHttpResponse response = sendRequest(
                "GET /large HTTP/1.1\r\n"
                + "Host: localhost\r\n"
                + "Connection: close\r\n"
                + "\r\n");

        assertEquals(200, response.statusCode);
        assertEquals(String.valueOf(LARGE_BODY.length()), response.header("Content-Length"));
        assertEquals(LARGE_BODY.length(), response.body.length());
        assertEquals(LARGE_BODY, response.body);
    }

    @Test
    public void enforcesWriteTimeoutForSlowReaders() throws Exception {

        FastJavaNioServer timeoutServer = new FastJavaNioServer(0,
                new RequestLimits(16384, 1_000, 128, 512, 512, 400),
                4,
                1024);
        timeoutServer.addServlet("/very-large", new VeryLargeBodyServlet());
        timeoutServer.start();
        int timeoutServerPort = timeoutServer.getBoundPort();
        waitForServerReady(timeoutServerPort);

        long baselineTimeoutCount = timeoutServer.writeTimeoutCloseCount();
        try {
            try (Socket socket = new Socket()) {
                socket.setReceiveBufferSize(1_024);
                socket.connect(new InetSocketAddress("127.0.0.1", timeoutServerPort), 1_000);

                OutputStream output = socket.getOutputStream();
                output.write(("GET /very-large HTTP/1.1\r\n"
                        + "Host: localhost\r\n"
                        + "Connection: keep-alive\r\n"
                        + "\r\n").getBytes(StandardCharsets.US_ASCII));
                output.flush();

                Thread.sleep(900);
            }
        } finally {
            timeoutServer.stop();
        }

        long deadlineMillis = System.currentTimeMillis() + 2_000;
        while (System.currentTimeMillis() < deadlineMillis) {
            if (timeoutServer.writeTimeoutCloseCount() > baselineTimeoutCount) {
                return;
            }
            Thread.sleep(25);
        }

        throw new AssertionError("Expected NIO server write timeout counter to increase");
    }

    @Test
    public void servesStaticFilesOverNioPath() throws Exception {
        RawHttpResponse response = sendRequest(
                "GET /static/asset.txt HTTP/1.1\r\n"
                + "Host: localhost\r\n"
                + "Connection: close\r\n"
                + "\r\n");

        assertEquals(200, response.statusCode);
        assertEquals("text/plain", response.header("Content-Type"));
        assertEquals(String.valueOf(staticPayload.getBytes(StandardCharsets.UTF_8).length),
                response.header("Content-Length"));
        assertEquals(staticPayload, response.body);
    }

    @Test
    public void supportsChunkedResponsesAndKeepAliveReuse() throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 1_000);
            socket.setSoTimeout(2_000);

            OutputStream output = socket.getOutputStream();
            InputStream input = socket.getInputStream();

            output.write(("GET /chunked HTTP/1.1\r\n"
                    + "Host: localhost\r\n"
                    + "Connection: keep-alive\r\n"
                    + "\r\n").getBytes(StandardCharsets.US_ASCII));
            output.flush();

            RawHttpResponse firstResponse = RawHttpResponse.readFrom(input);
            assertEquals(200, firstResponse.statusCode);
            assertEquals("chunked", firstResponse.header("Transfer-Encoding"));
            assertNull(firstResponse.header("Content-Length"));
            assertEquals("nio-chunked-body", firstResponse.body.trim());

            output.write(("GET /search?q=nio-after-chunked HTTP/1.1\r\n"
                    + "Host: localhost\r\n"
                    + "Connection: close\r\n"
                    + "\r\n").getBytes(StandardCharsets.US_ASCII));
            output.flush();

            RawHttpResponse secondResponse = RawHttpResponse.readFrom(input);
            assertEquals(200, secondResponse.statusCode);
            assertEquals("q=nio-after-chunked", secondResponse.body.trim());
            assertEquals("close", secondResponse.header("Connection"));
        }
    }

    @Test
    public void keepsPipelinedResponsesInRequestOrder() throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 1_000);
            socket.setSoTimeout(2_000);

            OutputStream output = socket.getOutputStream();
            InputStream input = socket.getInputStream();

            String pipelinedRequest = "GET /pipeline?id=1&delay=120 HTTP/1.1\r\n"
                    + "Host: localhost\r\n"
                    + "Connection: keep-alive\r\n"
                    + "\r\n"
                    + "GET /pipeline?id=2&delay=0 HTTP/1.1\r\n"
                    + "Host: localhost\r\n"
                    + "Connection: keep-alive\r\n"
                    + "\r\n"
                    + "GET /pipeline?id=3&delay=0 HTTP/1.1\r\n"
                    + "Host: localhost\r\n"
                    + "Connection: close\r\n"
                    + "\r\n";
            output.write(pipelinedRequest.getBytes(StandardCharsets.US_ASCII));
            output.flush();

            RawHttpResponse first = RawHttpResponse.readFrom(input);
            RawHttpResponse second = RawHttpResponse.readFrom(input);
            RawHttpResponse third = RawHttpResponse.readFrom(input);

            assertEquals(200, first.statusCode);
            assertEquals(200, second.statusCode);
            assertEquals(200, third.statusCode);
            assertEquals("id=1", first.body.trim());
            assertEquals("id=2", second.body.trim());
            assertEquals("id=3", third.body.trim());
            assertEquals("close", third.header("Connection"));
        }
    }

    @Test
    public void processesPipelinedRequestsConcurrentlyWhilePreservingOrder() throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 1_000);
            socket.setSoTimeout(2_000);

            OutputStream output = socket.getOutputStream();
            InputStream input = socket.getInputStream();

            String pipelinedRequest = "GET /pipeline-observe?id=1 HTTP/1.1\r\n"
                    + "Host: localhost\r\n"
                    + "Connection: keep-alive\r\n"
                    + "\r\n"
                    + "GET /pipeline-observe?id=2 HTTP/1.1\r\n"
                    + "Host: localhost\r\n"
                    + "Connection: close\r\n"
                    + "\r\n";
            output.write(pipelinedRequest.getBytes(StandardCharsets.US_ASCII));
            output.flush();

            RawHttpResponse first = RawHttpResponse.readFrom(input);
            RawHttpResponse second = RawHttpResponse.readFrom(input);

            assertEquals(200, first.statusCode);
            assertEquals(200, second.statusCode);
            assertEquals("first-sees-second=true", first.body.trim());
            assertEquals("second", second.body.trim());
            assertEquals("close", second.header("Connection"));
        }
    }

    @Test
    public void processesAsyncRequestsAndPreservesPipelinedResponseOrder() throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 1_000);
            socket.setSoTimeout(2_000);

            OutputStream output = socket.getOutputStream();
            InputStream input = socket.getInputStream();

            String pipelinedRequest = "GET /async?id=1&delay=120 HTTP/1.1\r\n"
                    + "Host: localhost\r\n"
                    + "Connection: keep-alive\r\n"
                    + "\r\n"
                    + "GET /async?id=2&delay=0 HTTP/1.1\r\n"
                    + "Host: localhost\r\n"
                    + "Connection: close\r\n"
                    + "\r\n";

            output.write(pipelinedRequest.getBytes(StandardCharsets.US_ASCII));
            output.flush();

            RawHttpResponse first = RawHttpResponse.readFrom(input);
            RawHttpResponse second = RawHttpResponse.readFrom(input);

            assertEquals(200, first.statusCode);
            assertEquals(200, second.statusCode);
            assertEquals("async-id=1", first.body.trim());
            assertEquals("async-id=2", second.body.trim());
            assertEquals("close", second.header("Connection"));
        }
    }

    @Test
    public void timesOutAsyncRequestsWhenNotCompleted() throws Exception {
        RawHttpResponse response = sendRequest(
                "GET /async-timeout HTTP/1.1\r\n"
                + "Host: localhost\r\n"
                + "Connection: close\r\n"
                + "\r\n");

        assertEquals(503, response.statusCode);
        assertEquals("Async request timeout", response.body.trim());
    }

    @Test
    public void dispatchesAsyncRequestToAnotherServletPath() throws Exception {
        RawHttpResponse response = sendRequest(
                "GET /async-dispatch HTTP/1.1\r\n"
                + "Host: localhost\r\n"
                + "Connection: close\r\n"
                + "\r\n");

        assertEquals(200, response.statusCode);
        assertEquals("q=redispatched", response.body.trim());
    }

    @Test
    public void invokesAsyncListenerCallbacksForLifecycleEvents() throws Exception {
        RawHttpResponse response = sendRequest(
                "GET /async-listener HTTP/1.1\r\n"
                + "Host: localhost\r\n"
                + "Connection: close\r\n"
                + "\r\n");

        assertEquals(200, response.statusCode);
        assertEquals("listener-ok", response.body.trim());
        assertTrue(AsyncListenerServlet.startNotified.get());
        assertTrue(AsyncListenerServlet.completeNotified.get());
    }

    @Test
    public void decodesChunkedRequestBodies() throws Exception {
        RawHttpResponse response = sendRequest(
                "POST /echo-body HTTP/1.1\r\n"
                + "Host: localhost\r\n"
                + "Transfer-Encoding: chunked\r\n"
                + "Connection: close\r\n"
                + "\r\n"
                + "5\r\n"
                + "hello\r\n"
                + "6\r\n"
                + " world\r\n"
                + "0\r\n"
                + "\r\n");

        assertEquals(200, response.statusCode);
        assertEquals("len=11 body=hello world", response.body.trim());
    }

    @Test
    public void supportsExpectContinueHandshake() throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 1_000);
            socket.setSoTimeout(2_000);

            OutputStream output = socket.getOutputStream();
            InputStream input = socket.getInputStream();

            output.write(("POST /echo-body HTTP/1.1\r\n"
                    + "Host: localhost\r\n"
                    + "Expect: 100-continue\r\n"
                    + "Content-Length: 11\r\n"
                    + "Connection: close\r\n"
                    + "\r\n").getBytes(StandardCharsets.US_ASCII));
            output.flush();

            String provisionalHeaders = RawHttpResponse.readHeaders(input);
            assertTrue(provisionalHeaders.startsWith("HTTP/1.1 100 Continue"));

            output.write("hello world".getBytes(StandardCharsets.US_ASCII));
            output.flush();

            RawHttpResponse finalResponse = RawHttpResponse.readFrom(input);
            assertEquals(200, finalResponse.statusCode);
            assertEquals("len=11 body=hello world", finalResponse.body.trim());
        }
    }

    @Test
    public void rejectsUnsupportedExpectations() throws Exception {
        RawHttpResponse response = sendRequest(
                "POST /echo-body HTTP/1.1\r\n"
                + "Host: localhost\r\n"
                + "Expect: unsupported-token\r\n"
                + "Content-Length: 5\r\n"
                + "Connection: close\r\n"
                + "\r\n"
                + "hello");

        assertEquals(417, response.statusCode);
    }

    @Test
    public void initializesServletsAndRunsFilters() throws Exception {
        RawHttpResponse response = sendRequest(
                "GET /lifecycle HTTP/1.1\r\n"
                + "Host: localhost\r\n"
                + "Connection: close\r\n"
                + "\r\n");

        assertEquals(200, response.statusCode);
        assertEquals("applied", response.header("X-Filter"));
        assertEquals("init=true filter=true", response.body.trim());
        assertTrue(lifecycleServlet.initCalled);
        assertTrue(headerFilter.initCalled);
    }

    @Test
    public void maintainsSessionAcrossNioRequestsUsingCookie() throws Exception {
        RawHttpResponse first = sendRequest(
                "GET /session-counter HTTP/1.1\r\n"
                + "Host: localhost\r\n"
                + "Connection: close\r\n"
                + "\r\n");

        assertEquals(200, first.statusCode);
        assertEquals("counter=1", first.body.trim());
        String setCookie = first.header("Set-Cookie");
        assertTrue(setCookie != null && setCookie.startsWith("JSESSIONID="));

        String sessionCookie = setCookie.split(";", 2)[0];
        RawHttpResponse second = sendRequest(
                "GET /session-counter HTTP/1.1\r\n"
                + "Host: localhost\r\n"
                + "Cookie: " + sessionCookie + "\r\n"
                + "Connection: close\r\n"
                + "\r\n");

        assertEquals(200, second.statusCode);
        assertEquals("counter=2", second.body.trim());
    }

    @Test
    public void destroysServletsAndFiltersOnStop() {
        server.stop();

        assertTrue(lifecycleServlet.destroyCalled);
        assertTrue(headerFilter.destroyCalled);
        server = null;
    }

    private void waitForServerReady() throws Exception {
        waitForServerReady(port);
    }

    private void waitForServerReady(int targetPort) throws Exception {
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

    private RawHttpResponse sendRequest(String rawRequest) throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 1_000);
            socket.setSoTimeout(2_000);

            OutputStream output = socket.getOutputStream();
            output.write(rawRequest.getBytes(StandardCharsets.US_ASCII));
            output.flush();

            return RawHttpResponse.readFrom(socket.getInputStream());
        }
    }

    private static final class QueryEchoServlet extends HttpServlet {

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException {
            response.setContentType("text/plain");
            response.getWriter().print(request.getQueryString());
        }
    }

    private final class SlowServlet extends HttpServlet {

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException {
            slowServletStarted.countDown();
            try {
                if (!slowServletRelease.await(2, TimeUnit.SECONDS)) {
                    throw new ServletException("Slow servlet release latch timed out");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new ServletException("Interrupted while waiting", exception);
            }
            response.setContentType("text/plain");
            response.getWriter().print("slow-done");
        }
    }

    private static final class LargeBodyServlet extends HttpServlet {

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException {
            response.setContentType("text/plain");
            response.getWriter().print(LARGE_BODY);
        }
    }

    private static final class VeryLargeBodyServlet extends HttpServlet {

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException {
            response.setContentType("text/plain");
            response.getWriter().print(VERY_LARGE_BODY);
        }
    }

    private static final class ChunkedServlet extends HttpServlet {

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException {
            response.setContentType("text/plain");
            response.setChunkedResponseEnabled(true);
            response.getWriter().print("nio-chunked-body");
        }
    }

    private static final class EchoBodyServlet extends HttpServlet {

        @Override
        protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException {
            try {
                byte[] body = request.getInputStream().readAllBytes();
                response.setContentType("text/plain");
                response.getWriter().print("len=" + body.length + " body=" + new String(body, StandardCharsets.UTF_8));
            } catch (IOException exception) {
                throw new ServletException("Unable to read request body", exception);
            }
        }
    }

    private static final class SessionCounterServlet extends HttpServlet {

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException {
            HttpSession session = request.getSession(true);
            Integer counter = (Integer) session.getAttribute("counter");
            int next = counter == null ? 1 : counter + 1;
            session.setAttribute("counter", next);
            response.setContentType("text/plain");
            response.getWriter().print("counter=" + next);
        }
    }

    private static final class PipelineServlet extends HttpServlet {

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException {
            String id = request.getParameter("id");
            String delayParam = request.getParameter("delay");
            if (delayParam != null && !delayParam.isBlank()) {
                try {
                    long delayMillis = Long.parseLong(delayParam);
                    if (delayMillis > 0) {
                        Thread.sleep(delayMillis);
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new ServletException("Pipeline delay interrupted", interrupted);
                } catch (NumberFormatException ignored) {
                    // Ignore malformed delay and continue with immediate response.
                }
            }
            response.setContentType("text/plain");
            response.getWriter().print("id=" + id);
        }
    }

    private static final class AsyncPipelineServlet extends HttpServlet {

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException {
            AsyncContext asyncContext = request.startAsync();
            String id = request.getParameter("id");
            long delayMillis = 0L;
            String delayParam = request.getParameter("delay");
            if (delayParam != null && !delayParam.isBlank()) {
                try {
                    delayMillis = Long.parseLong(delayParam);
                } catch (NumberFormatException ignored) {
                    delayMillis = 0L;
                }
            }

            final long sleepDuration = delayMillis;
            new Thread(() -> {
                try {
                    if (sleepDuration > 0) {
                        Thread.sleep(sleepDuration);
                    }
                    response.setContentType("text/plain");
                    response.getWriter().print("async-id=" + id);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    response.setStatus(500);
                    response.getWriter().print("async-interrupted");
                } finally {
                    asyncContext.complete();
                }
            }, "FastJava-Async-Pipeline").start();
        }
    }

    private static final class AsyncTimeoutServlet extends HttpServlet {

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException {
            AsyncContext asyncContext = request.startAsync();
            asyncContext.setTimeout(100);
        }
    }

    private static final class AsyncDispatchServlet extends HttpServlet {

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException {
            AsyncContext asyncContext = request.startAsync();
            new Thread(() -> asyncContext.dispatch("/search?q=redispatched"), "FastJava-Async-Dispatch").start();
        }
    }

    private static final class AsyncListenerServlet extends HttpServlet {

        private static final AtomicBoolean startNotified = new AtomicBoolean();
        private static final AtomicBoolean completeNotified = new AtomicBoolean();

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException {
            startNotified.set(false);
            completeNotified.set(false);
            AsyncContext asyncContext = request.startAsync();
            asyncContext.addListener(new AsyncListener() {
                @Override
                public void onStartAsync(AsyncEvent event) {
                    startNotified.set(true);
                }

                @Override
                public void onComplete(AsyncEvent event) {
                    completeNotified.set(true);
                }
            });
            new Thread(() -> {
                response.setContentType("text/plain");
                response.getWriter().print("listener-ok");
                asyncContext.complete();
            }, "FastJava-Async-Listener").start();
        }
    }

    private final class PipelineObserveServlet extends HttpServlet {

        private final CountDownLatch secondArrived = new CountDownLatch(1);
        private final AtomicInteger secondObserved = new AtomicInteger();

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException {
            String id = request.getParameter("id");
            if ("2".equals(id)) {
                secondObserved.incrementAndGet();
                secondArrived.countDown();
                response.setContentType("text/plain");
                response.getWriter().print("second");
                return;
            }

            boolean seenSecond;
            try {
                seenSecond = secondArrived.await(800, TimeUnit.MILLISECONDS) && secondObserved.get() > 0;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new ServletException("Interrupted while waiting for pipelined request", interrupted);
            }
            response.setContentType("text/plain");
            response.getWriter().print("first-sees-second=" + seenSecond);
        }
    }

    private static final class LifecycleServlet extends HttpServlet {

        private volatile boolean initCalled;
        private volatile boolean destroyCalled;

        @Override
        public void init(ServletConfig config) throws ServletException {
            super.init(config);
            initCalled = true;
        }

        @Override
        public void destroy() {
            destroyCalled = true;
        }

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException {
            response.setContentType("text/plain");
            response.getWriter().print(
                    "init=" + (initCalled && getServletConfig() != null)
                    + " filter=" + Boolean.TRUE.equals(request.getAttribute("filter.applied")));
        }
    }

    private static final class HeaderFilter implements Filter {

        private volatile boolean initCalled;
        private volatile boolean destroyCalled;

        @Override
        public void init(FilterConfig filterConfig) throws ServletException {
            initCalled = filterConfig != null;
        }

        @Override
        public void doFilter(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException {
            request.setAttribute("filter.applied", true);
            chain.doFilter(request, response);
            response.setHeader("X-Filter", "applied");
        }

        @Override
        public void destroy() {
            destroyCalled = true;
        }
    }

    private static final class SseServlet extends HttpServlet {

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException {
            SseSupport.prepareResponse(response);
            SseEmitter emitter = SseSupport.from(request);
            if (emitter == null) {
                throw new ServletException("Missing SSE emitter in request context");
            }
            try {
                emitter.send(SseEvent.builder().event("greeting").data("hello-sse").build());
                emitter.close();
            } catch (IOException ioException) {
                throw new ServletException("Failed to emit SSE data", ioException);
            }
        }
    }

    @WebSocketEndpoint(path = "/ws/annotated", subprotocols = {"chat.v1", "chat.v2"})
    public static final class AnnotatedEchoEndpoint {

        @OnOpen
        public void onOpen(WebSocketSession session) {
            session.setAttribute("prefix", "annotated:");
        }

        @OnMessage
        public void onMessage(WebSocketSession session, String message) throws IOException {
            String prefix = String.valueOf(session.getAttribute("prefix"));
            session.sendText(prefix + message);
        }

        @OnClose
        public void onClose(WebSocketSession session) {
            session.removeAttribute("prefix");
        }
    }

    @WebSocketEndpoint(path = "/ws/template/{room}/{user}")
    public static final class TemplatePathEndpoint {

        @OnOpen
        public void onOpen(WebSocketSession session, @PathParam("room") String room, @PathParam("user") String user) {
            session.setAttribute("prefix", room + "/" + user + ":");
        }

        @OnMessage
        public void onMessage(WebSocketSession session, String message) throws IOException {
            session.sendText(String.valueOf(session.getAttribute("prefix")) + message);
        }
    }

    public static final class ContextClassLoaderServlet extends HttpServlet {

        private final String version;
        private final ClassLoader expectedClassLoader;

        public ContextClassLoaderServlet(String version, ClassLoader expectedClassLoader) {
            this.version = version;
            this.expectedClassLoader = expectedClassLoader;
        }

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response)
                throws ServletException {
            boolean isolated = Thread.currentThread().getContextClassLoader() == expectedClassLoader;
            response.setStatus(200);
            response.setContentType("text/plain");
            response.getWriter().write(version + ":" + isolated);
        }
    }

    private static final class RawHttpResponse {

        private final int statusCode;
        private final String headers;
        private final String body;

        private RawHttpResponse(int statusCode, String headers, String body) {
            this.statusCode = statusCode;
            this.headers = headers;
            this.body = body;
        }

        private static RawHttpResponse readFrom(InputStream input) throws IOException {
            String headers = readHeaders(input);
            byte[] bodyBytes = isChunked(headers)
                    ? readChunkedBody(input)
                    : readFixedLengthBody(input, contentLength(headers));
            return parse(headers, bodyBytes);
        }

        private static byte[] readFixedLengthBody(InputStream input, int contentLength) throws IOException {
            byte[] bodyBytes = input.readNBytes(contentLength);
            if (bodyBytes.length != contentLength) {
                throw new IllegalArgumentException("Incomplete HTTP response body");
            }
            return bodyBytes;
        }

        private static byte[] readChunkedBody(InputStream input) throws IOException {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            while (true) {
                String sizeLine = readLine(input);
                int separatorIndex = sizeLine.indexOf(';');
                String sizeToken = separatorIndex >= 0 ? sizeLine.substring(0, separatorIndex) : sizeLine;

                final int chunkSize;
                try {
                    chunkSize = Integer.parseInt(sizeToken.trim(), 16);
                } catch (NumberFormatException exception) {
                    throw new IllegalArgumentException("Invalid chunk size: " + sizeLine, exception);
                }

                if (chunkSize == 0) {
                    String trailerLine;
                    do {
                        trailerLine = readLine(input);
                    } while (!trailerLine.isEmpty());
                    break;
                }

                byte[] chunk = input.readNBytes(chunkSize);
                if (chunk.length != chunkSize) {
                    throw new IllegalArgumentException("Incomplete chunked body");
                }
                body.write(chunk);

                int cr = input.read();
                int lf = input.read();
                if (cr != '\r' || lf != '\n') {
                    throw new IllegalArgumentException("Invalid chunk terminator");
                }
            }
            return body.toByteArray();
        }

        private static RawHttpResponse parse(String headers, byte[] bodyBytes) {
            String response = headers + "\r\n\r\n" + new String(bodyBytes, StandardCharsets.UTF_8);
            int headerEnd = response.indexOf("\r\n\r\n");
            if (headerEnd < 0) {
                throw new IllegalArgumentException("Invalid HTTP response: missing header terminator");
            }

            String headerBlock = response.substring(0, headerEnd);
            String body = response.substring(headerEnd + 4);
            String[] lines = headerBlock.split("\\r\\n");
            int statusCode = Integer.parseInt(lines[0].split(" ")[1]);
            return new RawHttpResponse(statusCode, headerBlock, body);
        }

        private static String readHeaders(InputStream input) throws IOException {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            int current;
            int matched = 0;
            byte[] terminator = new byte[]{'\r', '\n', '\r', '\n'};

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
                int colon = line.indexOf(':');
                if (colon > 0 && line.substring(0, colon).equalsIgnoreCase("Content-Length")) {
                    return Integer.parseInt(line.substring(colon + 1).trim());
                }
            }
            return 0;
        }

        private static boolean isChunked(String headers) {
            String transferEncoding = headerValue(headers, "Transfer-Encoding");
            return transferEncoding != null && transferEncoding.toLowerCase().contains("chunked");
        }

        private static String readLine(InputStream input) throws IOException {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            while (true) {
                int current = input.read();
                if (current == -1) {
                    throw new IllegalArgumentException("Unexpected EOF while reading chunk metadata");
                }
                if (current == '\r') {
                    int next = input.read();
                    if (next != '\n') {
                        throw new IllegalArgumentException("Malformed CRLF sequence in chunk metadata");
                    }
                    return output.toString(StandardCharsets.US_ASCII);
                }
                output.write(current);
            }
        }

        private static String headerValue(String headers, String name) {
            String[] lines = headers.split("\\r\\n");
            for (int index = 1; index < lines.length; index++) {
                String line = lines[index];
                int colon = line.indexOf(':');
                if (colon > 0 && line.substring(0, colon).equalsIgnoreCase(name)) {
                    return line.substring(colon + 1).trim();
                }
            }
            return null;
        }

        private String header(String name) {
            return headerValue(headers, name);
        }
    }

    private static final class WebSocketTestFrame {

        private final int opcode;
        private final boolean fin;
        private final boolean compressed;
        private final byte[] payload;

        private WebSocketTestFrame(int opcode, boolean fin, boolean compressed, byte[] payload) {
            this.opcode = opcode;
            this.fin = fin;
            this.compressed = compressed;
            this.payload = payload;
        }
    }

    private static final class WebSocketTestFrameCodec {

        private static final SecureRandom RANDOM = new SecureRandom();

        private static byte[] maskedClientText(String text) {
            return maskedClientFrame(0x1, true, text.getBytes(StandardCharsets.UTF_8));
        }

        private static byte[] maskedClientCompressedText(String text) {
            byte[] compressedPayload = WebSocketPerMessageDeflate.deflateMessage(text.getBytes(StandardCharsets.UTF_8));
            return maskedClientFrame(0x1, true, compressedPayload, true);
        }

        private static byte[] maskedClientClose(int closeCode) {
            byte[] payload = new byte[]{
                (byte) ((closeCode >>> 8) & 0xFF),
                (byte) (closeCode & 0xFF)
            };
            return maskedClientFrame(0x8, true, payload);
        }

        private static byte[] maskedClientFrame(int opcode, boolean fin, byte[] payload) {
            return maskedClientFrame(opcode, fin, payload, false);
        }

        private static byte[] maskedClientFrame(int opcode, boolean fin, byte[] payload, boolean compressed) {
            assertNotNull(payload);
            assertFalse(payload.length > 125);

            byte[] mask = new byte[4];
            RANDOM.nextBytes(mask);

            byte[] frame = new byte[2 + 4 + payload.length];
            int firstByte = (fin ? 0x80 : 0) | (opcode & 0x0F);
            if (compressed) {
                firstByte |= 0x40;
            }
            frame[0] = (byte) firstByte;
            frame[1] = (byte) (0x80 | payload.length);
            System.arraycopy(mask, 0, frame, 2, 4);

            for (int index = 0; index < payload.length; index++) {
                frame[6 + index] = (byte) (payload[index] ^ mask[index & 3]);
            }
            return frame;
        }

        private static WebSocketTestFrame readServerFrame(InputStream input) throws IOException {
            int b0 = input.read();
            int b1 = input.read();
            if (b0 < 0 || b1 < 0) {
                throw new IllegalArgumentException("Unexpected EOF while reading websocket frame");
            }

            boolean fin = (b0 & 0x80) != 0;
            boolean compressed = (b0 & 0x40) != 0;
            int opcode = b0 & 0x0F;
            boolean masked = (b1 & 0x80) != 0;
            int payloadIndicator = b1 & 0x7F;
            if (masked) {
                throw new IllegalArgumentException("Server frames must not be masked");
            }

            long payloadLength;
            if (payloadIndicator <= 125) {
                payloadLength = payloadIndicator;
            } else if (payloadIndicator == 126) {
                int b2 = input.read();
                int b3 = input.read();
                if (b2 < 0 || b3 < 0) {
                    throw new IllegalArgumentException(
                            "Unexpected EOF while reading websocket extended payload length");
                }
                payloadLength = ((b2 & 0xFFL) << 8) | (b3 & 0xFFL);
            } else {
                payloadLength = 0L;
                for (int i = 0; i < 8; i++) {
                    int next = input.read();
                    if (next < 0) {
                        throw new IllegalArgumentException(
                                "Unexpected EOF while reading websocket extended payload length");
                    }
                    payloadLength = (payloadLength << 8) | (next & 0xFFL);
                }
            }

            if (payloadLength > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("Payload too large for test decoder");
            }
            byte[] payload = input.readNBytes((int) payloadLength);
            if (payload.length != (int) payloadLength) {
                throw new IllegalArgumentException("Incomplete websocket frame payload");
            }
            return new WebSocketTestFrame(opcode, fin, compressed, payload);
        }
    }
}
