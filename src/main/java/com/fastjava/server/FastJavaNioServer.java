package com.fastjava.server;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.FileChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Base64;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fastjava.http.h2.HpackCodec;
import com.fastjava.http.h2.Http2FrameCodec;
import com.fastjava.http.parser.HttpRequestParser;
import com.fastjava.http.parser.ParsedHttpRequest;
import com.fastjava.http.response.HttpResponseBuilder;
import com.fastjava.http.simd.SIMDByteScanner;
import com.fastjava.server.config.Http2Config;
import com.fastjava.server.tls.SslContextFactory;
import com.fastjava.server.tls.TlsChannelHandler;
import com.fastjava.server.tls.TlsConfig;
import com.fastjava.servlet.Filter;
import com.fastjava.servlet.HttpServlet;
import com.fastjava.servlet.ServletException;
import com.fastjava.sse.NioSseEmitter;
import com.fastjava.websocket.WebSocketEndpointBinding;
import com.fastjava.websocket.WebSocketEndpointMatch;
import com.fastjava.websocket.WebSocketEndpointMetadata;
import com.fastjava.websocket.WebSocketExtensions;
import com.fastjava.websocket.WebSocketFrame;
import com.fastjava.websocket.WebSocketFrameCodec;
import com.fastjava.websocket.WebSocketHandshake;
import com.fastjava.websocket.WebSocketSession;

public class FastJavaNioServer {

    private static final Logger logger = LoggerFactory.getLogger(FastJavaNioServer.class);
    // Configurable via -Dfastjava.sse.skip=true to disable SSE emitter creation
    // entirely.
    // Even when enabled, emitter setup is only done for SSE requests.
    private static final boolean SSE_EAGER = !Boolean.getBoolean("fastjava.sse.skip");
    // Configurable via -Dfastjava.inline.requests=true to execute servlet logic
    // on the selector thread and avoid worker handoff overhead.
    private static final boolean INLINE_REQUEST_EXECUTION = Boolean.getBoolean("fastjava.inline.requests");
    private static final boolean VIRTUAL_THREADS = Boolean.getBoolean("fastjava.virtual.threads.enabled");
    // 0 means unbounded (legacy behavior). Set >0 to cap per-loop completion drain.
    private static final int COMPLETION_DRAIN_BATCH
            = Integer.getInteger("fastjava.selector.completion.drain.batch", 0);
    private static final int GATHER_WRITE_BATCH_SIZE = 16;
    private static final int SMALL_RESPONSE_COALESCE_BYTES = 1024;
    private static final int MAX_WEBSOCKET_PAYLOAD_BYTES = 64 * 1024;
    private static final int HTTP2_ERROR_NO_ERROR = 0x0;
    private static final int HTTP2_ERROR_PROTOCOL_ERROR = 0x1;
    private static final int HTTP2_ERROR_INTERNAL_ERROR = 0x2;
    private static final int HTTP2_ERROR_FLOW_CONTROL_ERROR = 0x3;
    private static final int HTTP2_ERROR_STREAM_CLOSED = 0x5;
    private static final int HTTP2_ERROR_FRAME_SIZE_ERROR = 0x6;
    private static final int HTTP2_ERROR_REFUSED_STREAM = 0x7;
    private static final int HTTP2_ERROR_COMPRESSION_ERROR = 0x9;
    private static final int HTTP2_SETTINGS_HEADER_TABLE_SIZE = 0x1;
    private static final int HTTP2_SETTINGS_ENABLE_PUSH = 0x2;
    private static final int HTTP2_SETTINGS_MAX_CONCURRENT_STREAMS = 0x3;
    private static final int HTTP2_SETTINGS_INITIAL_WINDOW_SIZE = 0x4;
    private static final int HTTP2_SETTINGS_MAX_FRAME_SIZE = 0x5;
    private static final int HTTP2_SETTINGS_MAX_HEADER_LIST_SIZE = 0x6;
    private static final int HTTP2_DEFAULT_INITIAL_WINDOW_SIZE = 65_535;
    private static final int HTTP2_WINDOW_UPDATE_THRESHOLD = 32_768;
    private static final byte[] H2C_PREFACE = new byte[]{
        'P', 'R', 'I', ' ', '*', ' ', 'H', 'T', 'T', 'P', '/', '2', '.', '0', '\r', '\n',
        '\r', '\n', 'S', 'M', '\r', '\n', '\r', '\n'
    };

    private final int port;
    private final ServletRouter router;
    private final RequestLimits requestLimits;
    private final CountDownLatch stopLatch;
    private final AtomicBoolean stopRequested;
    private final ExecutorService workerExecutor;
    private final ScheduledExecutorService asyncTimeoutExecutor;
    private final ConcurrentLinkedQueue<NioCompletion> completionQueue;
    private final ConcurrentLinkedQueue<Runnable> selectorTasks;
    private final Map<String, StaticRouteResponse> staticResponses;
    private final int maxWriteBytesPerOperation;
    private final java.util.concurrent.atomic.AtomicLong writeTimeoutCloseCount;
    private final AtomicBoolean wakeupPending = new AtomicBoolean(false);
    private volatile boolean running;
    private final TlsConfig tlsConfig;
    private final Http2Config http2Config;
    private volatile SSLContext sslContext;
    private final ConcurrentLinkedQueue<TlsChannelHandler> pendingTlsRegistrations;
    private long tlsKeystoreLastModifiedMillis = -1L;
    private long tlsTruststoreLastModifiedMillis = -1L;
    private long lastTlsReloadCheckMillis = 0L;
    private long lastIdleCheckMillis = 0L;
    private long lastWriteTimeoutCheckMillis = 0L;
    // Keep-alive expiration can be coarse-grained and cheap at 1s cadence.
    private static final long IDLE_CHECK_INTERVAL_MS = 1_000L;
    // Write timeout enforcement must stay responsive for slow-reader protection.
    private static final long WRITE_TIMEOUT_CHECK_INTERVAL_MS = 200L;
    private static final int NUM_SELECTORS = Integer.getInteger("fastjava.num.selectors", 4);

    private Selector selector;
    private ServerSocketChannel serverSocketChannel;
    private Thread selectorThread;
    private SelectorGroup selectorGroup;
    private final Set<Thread> selectorThreads = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public FastJavaNioServer(int port, RequestLimits requestLimits) {
        this(port,
                requestLimits,
                Math.max(2, Runtime.getRuntime().availableProcessors()),
                Integer.MAX_VALUE,
                null,
                Http2Config.defaults());
    }

    public FastJavaNioServer(int port, RequestLimits requestLimits, int workerThreads) {
        this(port, requestLimits, workerThreads, Integer.MAX_VALUE, null, Http2Config.defaults());
    }

    public FastJavaNioServer(int port, RequestLimits requestLimits, TlsConfig tlsConfig) {
        this(port,
                requestLimits,
                Math.max(2, Runtime.getRuntime().availableProcessors()),
                Integer.MAX_VALUE,
                tlsConfig,
                Http2Config.defaults());
    }

    public FastJavaNioServer(int port, RequestLimits requestLimits, TlsConfig tlsConfig, Http2Config http2Config) {
        this(port,
                requestLimits,
                Math.max(2, Runtime.getRuntime().availableProcessors()),
                Integer.MAX_VALUE,
                tlsConfig,
                http2Config == null ? Http2Config.defaults() : http2Config);
    }

    FastJavaNioServer(int port, RequestLimits requestLimits, int workerThreads, int maxWriteBytesPerOperation) {
        this(port,
                requestLimits,
                workerThreads,
                maxWriteBytesPerOperation,
                null,
                Http2Config.defaults());
    }

    FastJavaNioServer(int port, RequestLimits requestLimits, int workerThreads, int maxWriteBytesPerOperation,
            TlsConfig tlsConfig) {
        this(port,
                requestLimits,
                workerThreads,
                maxWriteBytesPerOperation,
                tlsConfig,
                Http2Config.defaults());
    }

    FastJavaNioServer(int port, RequestLimits requestLimits, int workerThreads, int maxWriteBytesPerOperation,
            TlsConfig tlsConfig, Http2Config http2Config) {
        this.port = port;
        this.requestLimits = requestLimits;
        this.tlsConfig = tlsConfig;
        this.http2Config = http2Config == null ? Http2Config.defaults() : http2Config;
        this.router = new ServletRouter();
        this.stopLatch = new CountDownLatch(1);
        this.stopRequested = new AtomicBoolean(false);
        this.completionQueue = new ConcurrentLinkedQueue<>();
        this.selectorTasks = new ConcurrentLinkedQueue<>();
        this.staticResponses = new java.util.concurrent.ConcurrentHashMap<>();
        this.pendingTlsRegistrations = new ConcurrentLinkedQueue<>();
        this.maxWriteBytesPerOperation = maxWriteBytesPerOperation;
        this.writeTimeoutCloseCount = new java.util.concurrent.atomic.AtomicLong();
        this.workerExecutor = VIRTUAL_THREADS
                ? Executors.newVirtualThreadPerTaskExecutor()
                : new ThreadPoolExecutor(
                        workerThreads,
                        workerThreads,
                        60,
                        TimeUnit.SECONDS,
                        new LinkedBlockingQueue<>(),
                        r -> {
                            Thread t = new Thread(r, "FastJava-Nio-Worker");
                            t.setDaemon(false);
                            return t;
                        });
        this.asyncTimeoutExecutor = new ScheduledThreadPoolExecutor(1, runnable -> {
            Thread thread = new Thread(runnable, "FastJava-Nio-Async-Timeout");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void addServlet(String path, HttpServlet servlet) throws ServletException {
        router.addServlet(path, servlet);
    }

    public void addServletPattern(String pattern, HttpServlet servlet) throws ServletException {
        router.addServletPattern(pattern, servlet);
    }

    public void addFilter(Filter filter) throws ServletException {
        router.addFilter(filter);
    }

    public void addFilter(String path, Filter filter) throws ServletException {
        router.addFilter(path, filter);
    }

    public void addFilterPattern(String pattern, Filter filter) throws ServletException {
        router.addFilterPattern(pattern, filter);
    }

    /**
     * Registers an exact GET route with a prebuilt text/plain response. This
     * bypasses servlet/filter/async execution for matching requests.
     */
    public void addStaticPlainTextRoute(String path, String body) {
        byte[] payload = body == null ? new byte[0] : body.getBytes(StandardCharsets.US_ASCII);
        addStaticResponse("GET", path, "text/plain", payload);
    }

    public void addStaticResponse(String method, String path, String contentType, byte[] bodyBytes) {
        String normalizedPath = normalizeStaticPath(path);
        String normalizedMethod = method == null ? "GET" : method.trim().toUpperCase(java.util.Locale.ROOT);
        byte[] payload = bodyBytes == null ? new byte[0] : Arrays.copyOf(bodyBytes, bodyBytes.length);
        String type = contentType == null || contentType.isBlank() ? "application/octet-stream" : contentType;

        HttpResponseBuilder keepAliveBuilder = new HttpResponseBuilder(256);
        keepAliveBuilder.setStatus(200)
                .setContentType(type)
                .setHeader("Connection", "keep-alive")
                .setBody(payload);

        HttpResponseBuilder closeBuilder = new HttpResponseBuilder(256);
        closeBuilder.setStatus(200)
                .setContentType(type)
                .setHeader("Connection", "close")
                .setBody(payload);

        staticResponses.put(
                staticRouteKey(normalizedMethod, normalizedPath),
                new StaticRouteResponse(keepAliveBuilder.build(), closeBuilder.build()));
    }

    /**
     * Register an annotation-based WebSocket endpoint.
     */
    public void addWebSocketEndpoint(Class<?> endpointClass) {
        router.addWebSocketEndpoint(endpointClass);
    }

    /**
     * Deploy or redeploy an isolated web application.
     */
    public void deployWebApp(HotDeployedWebApp webApp) throws ServletException {
        router.deployWebApp(webApp);
    }

    /**
     * Undeploy a previously deployed web application.
     */
    public boolean undeployWebApp(String appName) {
        return router.undeployWebApp(appName);
    }

    public void start() throws IOException, ServletException {
        router.initialize();
        if (tlsConfig != null) {
            try {
                sslContext = SslContextFactory.create(tlsConfig);
                tlsKeystoreLastModifiedMillis = fileLastModifiedMillis(tlsConfig.keystoreFile());
                tlsTruststoreLastModifiedMillis = fileLastModifiedMillis(tlsConfig.truststoreFile());
            } catch (Exception exception) {
                throw new IOException("Failed to initialise TLS context", exception);
            }
        }
        serverSocketChannel = ServerSocketChannel.open();
        serverSocketChannel.configureBlocking(false);
        // Increase backlog to handle bursty connection acceptance
        serverSocketChannel.bind(new InetSocketAddress(port), 512);
        running = true;

        if (NUM_SELECTORS > 1) {
            selectorGroup = new SelectorGroup(NUM_SELECTORS, this, serverSocketChannel);
            selector = selectorGroup.getAcceptorSelector();
            selectorGroup.start();
        } else {
            selector = Selector.open();
            serverSocketChannel.register(selector, SelectionKey.OP_ACCEPT);
            selectorThread = new Thread(this::runLoop, "FastJava-Nio-Selector");
            selectorThread.setDaemon(false);
            selectorThread.start();
        }
        logger.info("FastJava NIO Server started on port {} (selectors: {})", getBoundPort(), NUM_SELECTORS);
    }

    public void stop() {
        if (!stopRequested.compareAndSet(false, true)) {
            return;
        }
        running = false;
        if (selectorGroup != null) {
            selectorGroup.stop();
        } else if (selector != null) {
            selector.wakeup();
        }
        try {
            if (serverSocketChannel != null) {
                serverSocketChannel.close();
            }
            workerExecutor.shutdownNow();
            asyncTimeoutExecutor.shutdownNow();
            if (selector != null && selector.isOpen()) {
                for (SelectionKey key : selector.keys()) {
                    closeKey(key);
                }
                selector.close();
            }
        } catch (ClosedSelectorException ignored) {
        } catch (IOException exception) {
            logger.debug("Error closing NIO server: {}", exception.getMessage());
        } finally {
            try {
                router.destroy();
            } catch (RuntimeException exception) {
                logger.error("Error destroying servlet container", exception);
            }
            stopLatch.countDown();
            logger.info("FastJava NIO Server stopped");
        }
    }

    public void waitForStop() throws InterruptedException {
        if (selectorGroup != null) {
            selectorGroup.waitForStop();
        } else if (selectorThread != null) {
            selectorThread.join();
        }
        stopLatch.await();
    }

    public int getBoundPort() {
        if (serverSocketChannel == null) {
            throw new IllegalStateException("Server has not been started");
        }
        try {
            return ((InetSocketAddress) serverSocketChannel.getLocalAddress()).getPort();
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to determine bound port", exception);
        }
    }

    long writeTimeoutCloseCount() {
        return writeTimeoutCloseCount.get();
    }

    private void runLoop() {
        try {
            while (running) {
                // Skip blocking select() when completions or tasks are already queued —
                // avoids the wakeup-overhead and latency of waiting unnecessarily.
                if (completionQueue.isEmpty() && selectorTasks.isEmpty() && pendingTlsRegistrations.isEmpty()) {
                    selector.select(100);
                } else {
                    selector.selectNow();
                }
                wakeupPending.set(false);
                maybeHotReloadTlsCertificates();
                drainPendingTlsRegistrations();
                drainSelectorTasks();
                applyCompletedExecutions(COMPLETION_DRAIN_BATCH);
                expireIdleConnections();
                Set<SelectionKey> selectedKeys = selector.selectedKeys();
                Iterator<SelectionKey> iterator = selectedKeys.iterator();
                while (iterator.hasNext()) {
                    SelectionKey key = iterator.next();
                    iterator.remove();
                    if (!key.isValid()) {
                        continue;
                    }
                    if (key.isAcceptable()) {
                        acceptConnection();
                    }
                    if (!key.isValid()) {
                        continue;
                    }
                    try {
                        if (key.isReadable()) {
                            handleRead(key);
                        }
                        if (!key.isValid()) {
                            continue;
                        }
                        if (key.isWritable()) {
                            handleWrite(key);
                        }
                    } catch (IOException ioException) {
                        logger.debug("Closing connection after I/O failure: {}", ioException.getMessage());
                        closeKey(key);
                    }
                }
            }
        } catch (ClosedSelectorException ignored) {
            if (running) {
                logger.debug("Selector closed unexpectedly");
            }
        } catch (IOException exception) {
            if (running) {
                logger.error("NIO event loop error", exception);
            }
        } finally {
            stop();
        }
    }

    void drainPendingTlsRegistrations() throws IOException {
        TlsChannelHandler handler;
        while ((handler = pendingTlsRegistrations.poll()) != null) {
            NioConnection.ProtocolMode protocolMode = resolveTlsProtocolMode(handler);
            if (protocolMode == NioConnection.ProtocolMode.REJECTED) {
                try {
                    handler.close();
                } catch (IOException ignored) {
                }
                try {
                    handler.channel().close();
                } catch (IOException ignored) {
                }
                continue;
            }
            SocketChannel ch = handler.channel();
            ch.configureBlocking(false);
            Selector targetSelector = getSelectorForConnection();
            SelectionKey key = ch.register(
                    targetSelector,
                    SelectionKey.OP_READ,
                    new NioConnection(ch, requestLimits, handler, protocolMode));
            ServerObservability.connectionOpened();
            registerConnectionForWriting(key);
        }
    }

    void drainSelectorTasks() {
        Runnable task;
        while ((task = selectorTasks.poll()) != null) {
            try {
                task.run();
            } catch (RuntimeException taskError) {
                logger.debug("Selector task failed: {}", taskError.getMessage());
            }
        }
    }

    private void submitSelectorTask(Runnable task) {
        selectorTasks.offer(task);
        requestSelectorWakeup();
    }

    private boolean isSelectorThread() {
        if (selectorGroup != null) {
            return selectorThreads.contains(Thread.currentThread());
        }
        return Thread.currentThread() == selectorThread;
    }

    void onSelectorThreadStart(Thread thread) {
        if (thread != null) {
            selectorThreads.add(thread);
        }
    }

    void onSelectorThreadStop(Thread thread) {
        if (thread != null) {
            selectorThreads.remove(thread);
        }
    }

    private void requestSelectorWakeup() {
        if (selectorGroup != null) {
            selectorGroup.wakeupAcceptor();
            return;
        }
        if (selector == null || isSelectorThread()) {
            return;
        }
        if (wakeupPending.compareAndSet(false, true)) {
            selector.wakeup();
        }
    }

    void acceptConnection() throws IOException {
        SocketChannel channel;
        while ((channel = serverSocketChannel.accept()) != null) {
            configureAcceptedChannel(channel);
            if (sslContext != null) {
                final SocketChannel tlsChannel = channel;
                try {
                    workerExecutor.execute(() -> performTlsHandshake(tlsChannel));
                } catch (RejectedExecutionException rejected) {
                    try {
                        tlsChannel.close();
                    } catch (IOException ignored) {
                    }
                }
            } else {
                channel.configureBlocking(false);
                Selector targetSelector = getSelectorForConnection();
                SelectionKey key = channel.register(
                        targetSelector,
                        SelectionKey.OP_READ,
                        new NioConnection(channel, requestLimits, null, NioConnection.ProtocolMode.HTTP1));
                ServerObservability.connectionOpened();
                registerConnectionForWriting(key);
            }
        }
    }

    private void configureAcceptedChannel(SocketChannel channel) {
        try {
            channel.setOption(StandardSocketOptions.TCP_NODELAY, Boolean.TRUE);
        } catch (IOException ignored) {
            // Keep serving even if a platform/channel refuses this hint.
        }
        try {
            channel.setOption(StandardSocketOptions.SO_KEEPALIVE, Boolean.TRUE);
        } catch (IOException ignored) {
            // Keep serving even if a platform/channel refuses this hint.
        }
    }

    private NioConnection.ProtocolMode resolveTlsProtocolMode(TlsChannelHandler handler) {
        String alpn = handler.negotiatedApplicationProtocol();
        if (alpn == null || alpn.isEmpty() || "http/1.1".equals(alpn)) {
            return NioConnection.ProtocolMode.HTTP1;
        }
        if ("h2".equals(alpn)) {
            if (!http2Config.enabled()) {
                logger.warn("ALPN negotiated h2 but HTTP/2 is disabled. Closing connection.");
                return NioConnection.ProtocolMode.REJECTED;
            }
            logger.info("ALPN negotiated h2; connection switched to HTTP/2 mode scaffold");
            return NioConnection.ProtocolMode.HTTP2;
        }
        if (http2Config.strictAlpn()) {
            logger.warn("ALPN negotiated unsupported protocol '{}'. strictAlpn=true, closing connection.", alpn);
            return NioConnection.ProtocolMode.REJECTED;
        }
        logger.warn("ALPN negotiated unsupported protocol '{}'; falling back to HTTP/1.1", alpn);
        return NioConnection.ProtocolMode.HTTP1;
    }

    private void performTlsHandshake(SocketChannel channel) {
        try {
            // Channel is still in blocking mode — safe for synchronous handshake.
            SSLEngine engine = sslContext.createSSLEngine();
            engine.setUseClientMode(false);
            SSLParameters params = new SSLParameters();
            params.setProtocols(tlsConfig.protocols());
            params.setApplicationProtocols(tlsConfig.applicationProtocols());
            switch (tlsConfig.clientAuthMode()) {
                case NEED ->
                    params.setNeedClientAuth(true);
                case WANT ->
                    params.setWantClientAuth(true);
                case NONE -> {
                    // default server-auth only mode
                }
            }
            engine.setSSLParameters(params);
            TlsChannelHandler handler = new TlsChannelHandler(channel, engine);
            handler.doHandshake();
            pendingTlsRegistrations.offer(handler);
            requestSelectorWakeup();
        } catch (Exception exception) {
            logger.warn("TLS handshake failed for {}: {}", channel, exception.getMessage());
            try {
                channel.close();
            } catch (IOException ignored) {
            }
        }
    }

    public boolean reloadTlsCertificates() {
        if (tlsConfig == null) {
            return false;
        }
        try {
            SSLContext reloaded = SslContextFactory.create(tlsConfig);
            sslContext = reloaded;
            tlsKeystoreLastModifiedMillis = fileLastModifiedMillis(tlsConfig.keystoreFile());
            tlsTruststoreLastModifiedMillis = fileLastModifiedMillis(tlsConfig.truststoreFile());
            logger.info("TLS certificate material reloaded successfully");
            return true;
        } catch (Exception exception) {
            logger.warn("TLS certificate reload failed: {}", exception.getMessage());
            return false;
        }
    }

    private void maybeHotReloadTlsCertificates() {
        if (tlsConfig == null || !tlsConfig.certificateHotReloadEnabled()) {
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastTlsReloadCheckMillis < tlsConfig.certificateHotReloadCheckIntervalMillis()) {
            return;
        }
        lastTlsReloadCheckMillis = now;

        long keystoreLastModified = fileLastModifiedMillis(tlsConfig.keystoreFile());
        long truststoreLastModified = fileLastModifiedMillis(tlsConfig.truststoreFile());
        boolean keystoreChanged = keystoreLastModified != tlsKeystoreLastModifiedMillis;
        boolean truststoreChanged = truststoreLastModified != tlsTruststoreLastModifiedMillis;

        if (!keystoreChanged && !truststoreChanged) {
            return;
        }

        reloadTlsCertificates();
    }

    private long fileLastModifiedMillis(Path path) {
        if (path == null) {
            return -1L;
        }
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException exception) {
            return -1L;
        }
    }

    void handleRead(SelectionKey key) throws IOException {
        NioConnection connection = (NioConnection) key.attachment();
        int bytesRead = connection.readFromChannel();
        if (bytesRead == -1) {
            closeKey(key);
            return;
        }
        connection.touch();
        if (connection.isSseStreamingEnabled()) {
            if (connection.bufferedBytes() > 0) {
                connection.consume(connection.bufferedBytes());
            }
            return;
        }
        processBufferedData(key, connection);
    }

    void handleWrite(SelectionKey key) throws IOException {
        NioConnection connection = (NioConnection) key.attachment();
        int bytesWritten;
        try (RequestTracing.SpanScope ignored = RequestTracing.startChildSpan("http.write")) {
            bytesWritten = connection.writeToChannel(maxWriteBytesPerOperation);
        }
        if (bytesWritten > 0) {
            connection.touch();
        }
        long now = System.currentTimeMillis();
        if (connection.hasPendingWrite()
                && connection.hasExceededWriteTimeout(now, requestLimits.writeTimeoutMillis())) {
            handleWriteTimeout(key, connection, now);
            return;
        }
        if (connection.hasPendingWrite()) {
            return;
        }
        if (connection.hasPendingH2cUpgrade()) {
            activatePendingH2cUpgrade(key, connection);
            return;
        }
        if (connection.shouldCloseAfterWrite()) {
            connection.markClosing();
            closeKey(key);
            return;
        }
        connection.markIdle();
        key.interestOps(SelectionKey.OP_READ);
        if (connection.bufferedBytes() > 0) {
            processBufferedData(key, connection);
        }
    }

    private void activatePendingH2cUpgrade(SelectionKey key, NioConnection connection) {
        byte[] settingsPayload = connection.consumePendingH2cUpgradeSettings();
        connection.setProtocolMode(NioConnection.ProtocolMode.HTTP2);
        connection.markHttp2PrefaceReceived();
        connection.markPeerSettingsReceived();
        if (settingsPayload != null && settingsPayload.length > 0) {
            if (!applyPeerSettingsPayload(key, connection, settingsPayload)) {
                return;
            }
        }
        queueHttp2InitialSettings(key, connection);
        key.interestOps(SelectionKey.OP_READ | SelectionKey.OP_WRITE);
        registerConnectionForWriting(key);
    }

    private void processBufferedData(SelectionKey key, NioConnection connection) throws IOException {
        if (connection.protocolMode() == NioConnection.ProtocolMode.HTTP2) {
            processHttp2Frames(key, connection);
            return;
        }
        if (http2Config.enabled()
                && http2Config.h2cEnabled()
                && connection.protocolMode() == NioConnection.ProtocolMode.HTTP1
                && maybeSwitchToH2cPriorKnowledge(connection)) {
            queueHttp2InitialSettings(key, connection);
            processHttp2Frames(key, connection);
            return;
        }
        if (connection.isSseStreamingEnabled()) {
            return;
        }
        if (connection.isWebSocketEnabled()) {
            processWebSocketFrames(key, connection);
            return;
        }
        processBufferedRequests(key, connection);
    }

    private boolean maybeSwitchToH2cPriorKnowledge(NioConnection connection) {
        if (connection.bufferedBytes() < H2C_PREFACE.length) {
            return false;
        }
        if (!SIMDByteScanner.bytesEqual(connection.buffer(), 0, H2C_PREFACE)) {
            return false;
        }
        connection.consume(H2C_PREFACE.length);
        connection.setProtocolMode(NioConnection.ProtocolMode.HTTP2);
        connection.markHttp2PrefaceReceived();
        logger.info("Detected h2c prior-knowledge preface; switched connection to HTTP/2 mode");
        return true;
    }

    private void processBufferedRequests(SelectionKey key, NioConnection connection) throws IOException {
        while (connection.canProcessBufferedRequest()) {
            int bufferedBefore = connection.bufferedBytes();
            int inFlightBefore = connection.inFlightRequestCount();
            long nextSequenceBefore = connection.nextRequestSequence();
            boolean pendingWriteBefore = connection.hasPendingWrite();
            processBufferedRequest(key, connection);
            boolean progressed = connection.bufferedBytes() != bufferedBefore
                    || connection.inFlightRequestCount() != inFlightBefore
                    || connection.nextRequestSequence() != nextSequenceBefore
                    || connection.hasPendingWrite() != pendingWriteBefore;
            if (!progressed) {
                return;
            }
            if (connection.shouldCloseAfterWrite()) {
                return;
            }
        }
    }

    private void processHttp2Frames(SelectionKey key, NioConnection connection) throws IOException {
        if (!connection.http2PrefaceReceived()) {
            if (connection.bufferedBytes() < H2C_PREFACE.length) {
                return;
            }
            if (!SIMDByteScanner.bytesEqual(connection.buffer(), 0, H2C_PREFACE)) {
                sendHttp2GoAway(key, connection, HTTP2_ERROR_PROTOCOL_ERROR, "Invalid HTTP/2 preface", true);
                return;
            }
            connection.consume(H2C_PREFACE.length);
            connection.markHttp2PrefaceReceived();
            queueHttp2InitialSettings(key, connection);
        }

        while (connection.bufferedBytes() > 0) {
            Http2FrameCodec.ParseResult parseResult = Http2FrameCodec.parseFrame(
                    connection.buffer(),
                    0,
                    connection.bufferedBytes(),
                    http2Config.maxFrameSize());
            if (parseResult.isIncomplete()) {
                return;
            }
            if (parseResult.isError()) {
                sendHttp2GoAway(key, connection, HTTP2_ERROR_FRAME_SIZE_ERROR, parseResult.error(), true);
                return;
            }

            Http2FrameCodec.Http2Frame frame = parseResult.frame();
            connection.consume(parseResult.bytesConsumed());
            if (!handleHttp2Frame(key, connection, frame)) {
                return;
            }
        }
    }

    private boolean handleHttp2Frame(SelectionKey key, NioConnection connection, Http2FrameCodec.Http2Frame frame)
            throws IOException {
        if (connection.hasPendingContinuation() && frame.type() != Http2FrameCodec.TYPE_CONTINUATION) {
            sendHttp2GoAway(key, connection, HTTP2_ERROR_PROTOCOL_ERROR,
                    "Expected CONTINUATION frame after fragmented HEADERS", true);
            return false;
        }
        return switch (frame.type()) {
            case Http2FrameCodec.TYPE_SETTINGS ->
                handleHttp2SettingsFrame(key, connection, frame);
            case Http2FrameCodec.TYPE_HEADERS ->
                handleHttp2HeadersFrame(key, connection, frame);
            case Http2FrameCodec.TYPE_DATA ->
                handleHttp2DataFrame(key, connection, frame);
            case Http2FrameCodec.TYPE_PING ->
                handleHttp2PingFrame(key, connection, frame);
            case Http2FrameCodec.TYPE_CONTINUATION ->
                handleHttp2ContinuationFrame(key, connection, frame);
            case Http2FrameCodec.TYPE_WINDOW_UPDATE ->
                handleHttp2WindowUpdateFrame(key, connection, frame);
            case Http2FrameCodec.TYPE_RST_STREAM -> {
                connection.removeHttp2Stream(frame.streamId());
                yield true;
            }
            case Http2FrameCodec.TYPE_GOAWAY -> {
                connection.markClosing();
                closeKey(key);
                yield false;
            }
            default -> {
                sendHttp2GoAway(
                        key,
                        connection,
                        HTTP2_ERROR_PROTOCOL_ERROR,
                        "Unsupported frame type: " + frame.type(),
                        true);
                yield false;
            }
        };
    }

    private boolean handleHttp2SettingsFrame(SelectionKey key, NioConnection connection,
            Http2FrameCodec.Http2Frame frame) {
        if (frame.streamId() != 0) {
            sendHttp2GoAway(key, connection, HTTP2_ERROR_PROTOCOL_ERROR, "SETTINGS must use stream 0", true);
            return false;
        }
        boolean ack = (frame.flags() & Http2FrameCodec.FLAG_ACK) != 0;
        if (ack && frame.length() != 0) {
            sendHttp2GoAway(key, connection, HTTP2_ERROR_FRAME_SIZE_ERROR, "SETTINGS ACK payload must be empty", true);
            return false;
        }
        if (ack) {
            connection.markPeerSettingsReceived();
            return true;
        }

        if ((frame.length() % 6) != 0) {
            sendHttp2GoAway(key, connection, HTTP2_ERROR_FRAME_SIZE_ERROR, "Invalid SETTINGS payload length", true);
            return false;
        }
        if (!applyPeerSettingsPayload(key, connection, frame.payload())) {
            return false;
        }
        queueHttp2Frame(key, connection, Http2FrameCodec.encodeFrame(
                Http2FrameCodec.TYPE_SETTINGS,
                Http2FrameCodec.FLAG_ACK,
                0,
                new byte[0]), false);
        connection.markPeerSettingsReceived();
        return true;
    }

    private boolean applyPeerSettingsPayload(SelectionKey key, NioConnection connection, byte[] payload) {
        for (int i = 0; i < payload.length; i += 6) {
            int settingId = ((payload[i] & 0xFF) << 8) | (payload[i + 1] & 0xFF);
            int value = ((payload[i + 2] & 0xFF) << 24)
                    | ((payload[i + 3] & 0xFF) << 16)
                    | ((payload[i + 4] & 0xFF) << 8)
                    | (payload[i + 5] & 0xFF);
            if (settingId == HTTP2_SETTINGS_ENABLE_PUSH && value != 0) {
                sendHttp2GoAway(key, connection, HTTP2_ERROR_PROTOCOL_ERROR, "Server does not support push", true);
                return false;
            }
            if (settingId == HTTP2_SETTINGS_INITIAL_WINDOW_SIZE) {
                if (value < 0) {
                    sendHttp2GoAway(key, connection, HTTP2_ERROR_FLOW_CONTROL_ERROR,
                            "Invalid peer initial window size", true);
                    return false;
                }
                connection.updatePeerInitialStreamWindowSize(value);
            }
            if (settingId == HTTP2_SETTINGS_MAX_FRAME_SIZE) {
                if (value < 16_384 || value > 16_777_215) {
                    sendHttp2GoAway(key, connection, HTTP2_ERROR_PROTOCOL_ERROR,
                            "Invalid peer max frame size", true);
                    return false;
                }
                connection.setPeerMaxFrameSize(value);
            }
        }
        return true;
    }

    private boolean handleHttp2PingFrame(SelectionKey key, NioConnection connection, Http2FrameCodec.Http2Frame frame) {
        if (frame.streamId() != 0 || frame.length() != 8) {
            sendHttp2GoAway(key, connection, HTTP2_ERROR_FRAME_SIZE_ERROR, "PING frame must be stream 0 length 8",
                    true);
            return false;
        }
        if ((frame.flags() & Http2FrameCodec.FLAG_ACK) != 0) {
            return true;
        }
        queueHttp2Frame(
                key,
                connection,
                Http2FrameCodec.encodeFrame(Http2FrameCodec.TYPE_PING, Http2FrameCodec.FLAG_ACK, 0, frame.payload()),
                false);
        return true;
    }

    private boolean handleHttp2HeadersFrame(SelectionKey key, NioConnection connection,
            Http2FrameCodec.Http2Frame frame) {
        if (frame.streamId() <= 0 || (frame.streamId() & 1) == 0) {
            sendHttp2GoAway(key, connection, HTTP2_ERROR_PROTOCOL_ERROR, "Client stream id must be odd and > 0", true);
            return false;
        }
        if ((frame.flags() & 0x28) != 0) {
            sendHttp2RstStream(key, connection, frame.streamId(), HTTP2_ERROR_PROTOCOL_ERROR);
            return false;
        }
        if (connection.http2Stream(frame.streamId()) != null && connection.http2Stream(frame.streamId()).dispatched()) {
            sendHttp2RstStream(key, connection, frame.streamId(), HTTP2_ERROR_STREAM_CLOSED);
            return false;
        }
        if (connection.http2ActiveStreams() >= http2Config.maxConcurrentStreams()) {
            sendHttp2RstStream(key, connection, frame.streamId(), HTTP2_ERROR_REFUSED_STREAM);
            return false;
        }
        Http2StreamState stream = connection.getOrCreateHttp2Stream(frame.streamId());
        stream.resetHeaderBlock();
        stream.appendHeaderBlock(frame.payload(), http2Config.maxHeaderListSize());
        boolean endStream = (frame.flags() & Http2FrameCodec.FLAG_END_STREAM) != 0;
        stream.setEndStreamAfterHeaders(endStream);
        if ((frame.flags() & Http2FrameCodec.FLAG_END_HEADERS) == 0) {
            connection.expectContinuation(frame.streamId());
        } else {
            if (!finalizeHttp2HeaderBlock(key, connection, stream)) {
                return false;
            }
        }
        if (!connection.hasPendingContinuation()) {
            connection.markHttp2RequestExecuting();
            if (stream.endStreamAfterHeaders()) {
                stream.markRemoteClosed();
                dispatchHttp2RequestAsync(key, connection, stream);
            } else {
                stream.markRemoteOpen();
            }
        }
        return true;
    }

    private boolean handleHttp2ContinuationFrame(SelectionKey key, NioConnection connection,
            Http2FrameCodec.Http2Frame frame) {
        if (!connection.hasPendingContinuation() || frame.streamId() != connection.pendingContinuationStreamId()) {
            sendHttp2GoAway(key, connection, HTTP2_ERROR_PROTOCOL_ERROR, "Unexpected CONTINUATION stream", true);
            return false;
        }
        Http2StreamState stream = connection.http2Stream(frame.streamId());
        if (stream == null) {
            sendHttp2GoAway(key, connection, HTTP2_ERROR_PROTOCOL_ERROR, "Missing stream for CONTINUATION", true);
            return false;
        }
        if (!stream.appendHeaderBlock(frame.payload(), http2Config.maxHeaderListSize())) {
            sendHttp2RstStream(key, connection, frame.streamId(), HTTP2_ERROR_COMPRESSION_ERROR);
            return false;
        }
        if ((frame.flags() & Http2FrameCodec.FLAG_END_HEADERS) != 0) {
            connection.clearPendingContinuation();
            if (!finalizeHttp2HeaderBlock(key, connection, stream)) {
                return false;
            }
            connection.markHttp2RequestExecuting();
            if (stream.endStreamAfterHeaders()) {
                stream.markRemoteClosed();
                dispatchHttp2RequestAsync(key, connection, stream);
            } else {
                stream.markRemoteOpen();
            }
            return true;
        }
        return true;
    }

    private boolean finalizeHttp2HeaderBlock(SelectionKey key, NioConnection connection, Http2StreamState stream) {
        Map<String, String> decodedHeaders;
        try {
            decodedHeaders = connection.hpackCodec().decode(stream.headerBlock());
        } catch (Exception decodeError) {
            sendHttp2RstStream(key, connection, stream.streamId(), HTTP2_ERROR_COMPRESSION_ERROR);
            return false;
        }
        stream.setHeaders(decodedHeaders);
        stream.clearHeaderBlock();
        return true;
    }

    private boolean handleHttp2WindowUpdateFrame(SelectionKey key, NioConnection connection,
            Http2FrameCodec.Http2Frame frame) {
        if (frame.length() != 4) {
            sendHttp2GoAway(key, connection, HTTP2_ERROR_FRAME_SIZE_ERROR, "WINDOW_UPDATE must be 4 bytes", true);
            return false;
        }
        byte[] payload = frame.payload();
        int increment = ((payload[0] & 0x7F) << 24)
                | ((payload[1] & 0xFF) << 16)
                | ((payload[2] & 0xFF) << 8)
                | (payload[3] & 0xFF);
        if (increment <= 0) {
            sendHttp2GoAway(key, connection, HTTP2_ERROR_PROTOCOL_ERROR, "Invalid WINDOW_UPDATE increment", true);
            return false;
        }

        if (frame.streamId() == 0) {
            if (!connection.incrementConnectionSendWindow(increment)) {
                sendHttp2GoAway(key, connection, HTTP2_ERROR_FLOW_CONTROL_ERROR,
                        "Connection flow-control window overflow", true);
                return false;
            }
            flushQueuedHttp2DataFrames(key, connection);
            return true;
        }

        Http2StreamState stream = connection.http2Stream(frame.streamId());
        if (stream == null) {
            return true;
        }
        if (!stream.incrementSendWindow(increment)) {
            sendHttp2RstStream(key, connection, frame.streamId(), HTTP2_ERROR_FLOW_CONTROL_ERROR);
            return false;
        }
        flushQueuedHttp2DataFrames(key, connection);
        return true;
    }

    private boolean handleHttp2DataFrame(SelectionKey key, NioConnection connection, Http2FrameCodec.Http2Frame frame) {
        if (frame.streamId() <= 0) {
            sendHttp2GoAway(key, connection, HTTP2_ERROR_PROTOCOL_ERROR, "DATA must target a stream", true);
            return false;
        }
        Http2StreamState stream = connection.http2Stream(frame.streamId());
        if (stream == null) {
            sendHttp2RstStream(key, connection, frame.streamId(), HTTP2_ERROR_PROTOCOL_ERROR);
            return false;
        }
        int dataLength = frame.payload() == null ? 0 : frame.payload().length;
        if (!connection.consumeConnectionReceiveWindow(dataLength)) {
            sendHttp2GoAway(key, connection, HTTP2_ERROR_FLOW_CONTROL_ERROR,
                    "Connection receive flow-control window exceeded", true);
            return false;
        }
        if (!stream.consumeReceiveWindow(dataLength)) {
            sendHttp2RstStream(key, connection, frame.streamId(), HTTP2_ERROR_FLOW_CONTROL_ERROR);
            return false;
        }
        if (!stream.appendBody(frame.payload(), requestLimits.maxBodyBytes())) {
            sendHttp2RstStream(key, connection, frame.streamId(), HTTP2_ERROR_FLOW_CONTROL_ERROR);
            return false;
        }

        maybeEmitHttp2WindowUpdate(key, connection, stream, dataLength);

        if ((frame.flags() & Http2FrameCodec.FLAG_END_STREAM) != 0) {
            stream.markRemoteClosed();
            dispatchHttp2RequestAsync(key, connection, stream);
        }
        return true;
    }

    private void maybeEmitHttp2WindowUpdate(
            SelectionKey key,
            NioConnection connection,
            Http2StreamState stream,
            int consumedBytes) {
        if (consumedBytes <= 0) {
            return;
        }
        if (connection.connectionReceiveWindow() <= HTTP2_WINDOW_UPDATE_THRESHOLD) {
            int increment = connection.restoreConnectionReceiveWindow(consumedBytes);
            byte[] payload = new byte[4];
            payload[0] = (byte) ((increment >>> 24) & 0x7F);
            payload[1] = (byte) ((increment >>> 16) & 0xFF);
            payload[2] = (byte) ((increment >>> 8) & 0xFF);
            payload[3] = (byte) (increment & 0xFF);
            queueHttp2Frame(
                    key,
                    connection,
                    Http2FrameCodec.encodeFrame(Http2FrameCodec.TYPE_WINDOW_UPDATE, 0, 0, payload),
                    false);
        }
        if (stream.receiveWindow() <= HTTP2_WINDOW_UPDATE_THRESHOLD) {
            int increment = stream.restoreReceiveWindow(consumedBytes);
            byte[] payload = new byte[4];
            payload[0] = (byte) ((increment >>> 24) & 0x7F);
            payload[1] = (byte) ((increment >>> 16) & 0xFF);
            payload[2] = (byte) ((increment >>> 8) & 0xFF);
            payload[3] = (byte) (increment & 0xFF);
            queueHttp2Frame(
                    key,
                    connection,
                    Http2FrameCodec.encodeFrame(Http2FrameCodec.TYPE_WINDOW_UPDATE, 0, stream.streamId(), payload),
                    false);
        }
    }

    private void dispatchHttp2RequestAsync(SelectionKey key, NioConnection connection, Http2StreamState stream) {
        if (stream.dispatched()) {
            return;
        }
        stream.markDispatched();
        ParsedHttpRequest parsed;
        try {
            parsed = buildParsedHttp2Request(stream);
        } catch (IllegalArgumentException invalidRequest) {
            sendHttp2RstStream(key, connection, stream.streamId(), HTTP2_ERROR_PROTOCOL_ERROR);
            connection.onHttp2RequestFinished(stream.streamId());
            return;
        }

        workerExecutor.execute(() -> executeHttp2RequestAsync(key, connection, stream.streamId(), parsed));
    }

    private ParsedHttpRequest buildParsedHttp2Request(Http2StreamState stream) {
        Map<String, String> decoded = stream.headers();
        String method = decoded.get(":method");
        String path = decoded.get(":path");
        if (method == null || path == null) {
            throw new IllegalArgumentException("Missing required pseudo headers");
        }

        Map<String, String> headers = new HashMap<>();
        for (Map.Entry<String, String> entry : decoded.entrySet()) {
            if (!entry.getKey().startsWith(":")) {
                headers.put(entry.getKey(), entry.getValue());
            }
        }
        String authority = decoded.get(":authority");
        if (authority != null && !authority.isBlank()) {
            headers.put("host", authority);
        }

        byte[] body = stream.body();
        HttpRequestParser.RequestLine requestLine = new HttpRequestParser.RequestLine(method, path, "HTTP/2");
        return new ParsedHttpRequest(requestLine, headers, body, 0, body.length, 0, false, null, false);
    }

    private void executeHttp2RequestAsync(
            SelectionKey key,
            NioConnection connection,
            int streamId,
            ParsedHttpRequest parsed) {
        try {
            HttpExecutionResult executionResult = HttpRequestExecutor.execute(
                    parsed,
                    router,
                    connection.remoteAddress(),
                    connection.remotePort(),
                    connection.localPort(),
                    requestLimits,
                    connection.isTlsEnabled(),
                    false,
                    null);

            submitSelectorTask(() -> {
                if (!key.isValid()) {
                    return;
                }
                NioConnection currentConnection = (NioConnection) key.attachment();
                if (currentConnection != connection
                        || currentConnection.protocolMode() != NioConnection.ProtocolMode.HTTP2) {
                    return;
                }
                try {
                    queueHttp2ExecutionResult(key, currentConnection, streamId, executionResult);
                } catch (IOException ioException) {
                    sendHttp2GoAway(key, currentConnection, HTTP2_ERROR_INTERNAL_ERROR, ioException.getMessage(), true);
                } finally {
                    currentConnection.onHttp2RequestFinished(streamId);
                }
            });
        } catch (Exception exception) {
            submitSelectorTask(() -> {
                if (!key.isValid()) {
                    return;
                }
                NioConnection currentConnection = (NioConnection) key.attachment();
                if (currentConnection != connection) {
                    return;
                }
                sendHttp2RstStream(key, currentConnection, streamId, HTTP2_ERROR_INTERNAL_ERROR);
                currentConnection.onHttp2RequestFinished(streamId);
            });
        }
    }

    private void queueHttp2ExecutionResult(
            SelectionKey key,
            NioConnection connection,
            int streamId,
            HttpExecutionResult executionResult) throws IOException {
        byte[] http1Bytes = executionResult.responseBytes();
        Http2ResponseParts parts = convertHttp1ResponseToHttp2(http1Bytes, executionResult.fileBody());

        Map<String, String> responseHeaders = new java.util.LinkedHashMap<>();
        responseHeaders.put(":status", Integer.toString(parts.statusCode()));
        for (Map.Entry<String, String> entry : parts.headers().entrySet()) {
            responseHeaders.put(entry.getKey(), entry.getValue());
        }
        if (parts.body().size() > 0) {
            responseHeaders.put("content-length", Integer.toString(parts.body().size()));
        }

        byte[] headerBlock = connection.hpackCodec().encode(responseHeaders);

        boolean hasBody = parts.body().size() > 0;
        byte[] headersFrame = Http2FrameCodec.encodeFrame(
                Http2FrameCodec.TYPE_HEADERS,
                hasBody ? Http2FrameCodec.FLAG_END_HEADERS
                        : (Http2FrameCodec.FLAG_END_HEADERS | Http2FrameCodec.FLAG_END_STREAM),
                streamId,
                headerBlock);
        queueHttp2Frame(key, connection, headersFrame, false);

        if (!hasBody) {
            return;
        }

        byte[] bodyBytes = parts.body().toByteArray();
        int maxChunk = Math.max(1, http2Config.maxFrameSize());
        int offset = 0;
        while (offset < bodyBytes.length) {
            int length = Math.min(maxChunk, bodyBytes.length - offset);
            byte[] chunk = Arrays.copyOfRange(bodyBytes, offset, offset + length);
            offset += length;
            int flags = (offset >= bodyBytes.length) ? Http2FrameCodec.FLAG_END_STREAM : 0;
            connection.enqueueHttp2DataChunk(streamId, chunk, flags);
        }
        flushQueuedHttp2DataFrames(key, connection);
    }

    private void flushQueuedHttp2DataFrames(SelectionKey key, NioConnection connection) {
        while (true) {
            Http2QueuedData nextFrame = connection.pollNextQueuedHttp2DataFrame();
            if (nextFrame == null) {
                return;
            }
            int payloadLength = nextFrame.payload().length;
            if (!connection.tryConsumeConnectionSendWindow(payloadLength)) {
                connection.requeueHttp2DataFrame(nextFrame);
                return;
            }
            Http2StreamState stream = connection.http2Stream(nextFrame.streamId());
            if (stream == null) {
                connection.restoreConnectionSendWindow(payloadLength);
                continue;
            }
            if (!stream.tryConsumeSendWindow(payloadLength)) {
                connection.restoreConnectionSendWindow(payloadLength);
                connection.requeueHttp2DataFrame(nextFrame);
                return;
            }
            byte[] encoded = Http2FrameCodec.encodeFrame(
                    Http2FrameCodec.TYPE_DATA,
                    nextFrame.flags(),
                    nextFrame.streamId(),
                    nextFrame.payload());
            queueHttp2Frame(key, connection, encoded, false);
        }
    }

    private Http2ResponseParts convertHttp1ResponseToHttp2(byte[] responseBytes, FileResponseBody fileBody)
            throws IOException {
        int headerEnd = findResponseHeaderEnd(responseBytes);
        if (headerEnd < 0) {
            throw new IOException("Malformed HTTP/1.1 response generated by executor");
        }

        String head = new String(responseBytes, 0, headerEnd, StandardCharsets.US_ASCII);
        int statusCode = 500;
        int firstLineEnd = head.indexOf("\r\n");
        if (firstLineEnd < 0) {
            firstLineEnd = head.length();
        }
        int statusSpace = head.indexOf(' ');
        if (statusSpace >= 0 && statusSpace < firstLineEnd - 1) {
            int statusEnd = head.indexOf(' ', statusSpace + 1);
            if (statusEnd < 0 || statusEnd > firstLineEnd) {
                statusEnd = firstLineEnd;
            }
            try {
                statusCode = Integer.parseInt(head.substring(statusSpace + 1, statusEnd));
            } catch (NumberFormatException ignored) {
                statusCode = 500;
            }
        }

        Map<String, String> headers = new HashMap<>();
        boolean chunked = false;
        int lineStart = firstLineEnd + 2;
        while (lineStart < head.length()) {
            int lineEnd = head.indexOf("\r\n", lineStart);
            if (lineEnd < 0) {
                lineEnd = head.length();
            }
            int colon = head.indexOf(':', lineStart);
            if (colon <= lineStart || colon >= lineEnd) {
                lineStart = lineEnd + 2;
                continue;
            }
            String name = head.substring(lineStart, colon).trim().toLowerCase();
            String value = head.substring(colon + 1, lineEnd).trim();
            if ("connection".equals(name) || "keep-alive".equals(name) || "transfer-encoding".equals(name)) {
                if ("transfer-encoding".equals(name) && value.toLowerCase().contains("chunked")) {
                    chunked = true;
                }
                lineStart = lineEnd + 2;
                continue;
            }
            headers.put(name, value);
            lineStart = lineEnd + 2;
        }

        int bodyOffset = headerEnd + 4;
        byte[] bodySection = (bodyOffset <= responseBytes.length)
                ? Arrays.copyOfRange(responseBytes, bodyOffset, responseBytes.length)
                : new byte[0];
        java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
        if (chunked) {
            body.write(decodeChunkedBody(bodySection));
        } else {
            body.write(bodySection);
        }
        if (fileBody != null && fileBody.length() > 0) {
            try (FileChannel fileChannel = FileChannel.open(fileBody.path())) {
                ByteBuffer fileBuffer = ByteBuffer.allocate((int) fileBody.length());
                fileChannel.position(fileBody.offset());
                while (fileBuffer.hasRemaining()) {
                    int read = fileChannel.read(fileBuffer);
                    if (read <= 0) {
                        break;
                    }
                }
                body.write(fileBuffer.array(), 0, fileBuffer.position());
            }
        }
        return new Http2ResponseParts(statusCode, headers, body);
    }

    private int findResponseHeaderEnd(byte[] responseBytes) {
        for (int i = 0; i < responseBytes.length - 3; i++) {
            if (responseBytes[i] == '\r'
                    && responseBytes[i + 1] == '\n'
                    && responseBytes[i + 2] == '\r'
                    && responseBytes[i + 3] == '\n') {
                return i;
            }
        }
        return -1;
    }

    private byte[] decodeChunkedBody(byte[] chunkedPayload) throws IOException {
        java.io.ByteArrayOutputStream decoded = new java.io.ByteArrayOutputStream();
        int cursor = 0;
        while (cursor < chunkedPayload.length) {
            int lineEnd = -1;
            for (int i = cursor; i < chunkedPayload.length - 1; i++) {
                if (chunkedPayload[i] == '\r' && chunkedPayload[i + 1] == '\n') {
                    lineEnd = i;
                    break;
                }
            }
            if (lineEnd < 0) {
                throw new IOException("Malformed chunked payload");
            }
            String chunkSizeHex = new String(chunkedPayload, cursor, lineEnd - cursor, StandardCharsets.US_ASCII)
                    .trim();
            int semicolon = chunkSizeHex.indexOf(';');
            if (semicolon >= 0) {
                chunkSizeHex = chunkSizeHex.substring(0, semicolon);
            }
            int size = Integer.parseInt(chunkSizeHex, 16);
            cursor = lineEnd + 2;
            if (size == 0) {
                break;
            }
            if (cursor + size > chunkedPayload.length) {
                throw new IOException("Invalid chunk size in payload");
            }
            decoded.write(chunkedPayload, cursor, size);
            cursor += size;
            if (cursor + 1 < chunkedPayload.length
                    && chunkedPayload[cursor] == '\r'
                    && chunkedPayload[cursor + 1] == '\n') {
                cursor += 2;
            }
        }
        return decoded.toByteArray();
    }

    private boolean maybeHandleH2cUpgradeRequest(
            SelectionKey key,
            NioConnection connection,
            ParsedHttpRequest parsed,
            long requestSequence) {
        String upgrade = parsed.getHeader("upgrade");
        if (upgrade == null || !"h2c".equalsIgnoreCase(upgrade.trim())) {
            return false;
        }

        String connectionHeader = parsed.getHeader("connection");
        if (!headerContainsToken(connectionHeader, "upgrade")
                || !headerContainsToken(connectionHeader, "http2-settings")) {
            enqueueImmediateCompletion(
                    key,
                    connection,
                    requestSequence,
                    SimpleHttpResponses.plainText(400, "Invalid h2c upgrade headers"),
                    true);
            return true;
        }

        String settingsHeader = parsed.getHeader("http2-settings");
        if (settingsHeader == null || settingsHeader.isBlank()) {
            enqueueImmediateCompletion(
                    key,
                    connection,
                    requestSequence,
                    SimpleHttpResponses.plainText(400, "Missing HTTP2-Settings header"),
                    true);
            return true;
        }

        byte[] settingsPayload;
        try {
            settingsPayload = Base64.getUrlDecoder().decode(settingsHeader.trim());
        } catch (IllegalArgumentException invalidBase64) {
            enqueueImmediateCompletion(
                    key,
                    connection,
                    requestSequence,
                    SimpleHttpResponses.plainText(400, "Invalid HTTP2-Settings header"),
                    true);
            return true;
        }
        if ((settingsPayload.length % 6) != 0) {
            enqueueImmediateCompletion(
                    key,
                    connection,
                    requestSequence,
                    SimpleHttpResponses.plainText(400, "Malformed HTTP2-Settings payload"),
                    true);
            return true;
        }

        connection.consume(parsed.bytesConsumed);
        byte[] upgradeResponse = ("HTTP/1.1 101 Switching Protocols\r\n"
                + "Connection: Upgrade\r\n"
                + "Upgrade: h2c\r\n"
                + "\r\n").getBytes(StandardCharsets.US_ASCII);
        connection.markPendingH2cUpgrade(settingsPayload); // must be set before queueResponse in case of inline write
        queueResponse(key, connection, upgradeResponse, false);
        return true;
    }

    private boolean headerContainsToken(String headerValue, String token) {
        if (headerValue == null || headerValue.isBlank()) {
            return false;
        }
        int length = headerValue.length();
        int tokenStart = 0;
        while (tokenStart < length) {
            int tokenEnd = headerValue.indexOf(',', tokenStart);
            if (tokenEnd < 0) {
                tokenEnd = length;
            }
            int start = tokenStart;
            while (start < tokenEnd && Character.isWhitespace(headerValue.charAt(start))) {
                start++;
            }
            int end = tokenEnd;
            while (end > start && Character.isWhitespace(headerValue.charAt(end - 1))) {
                end--;
            }
            if (equalsIgnoreCaseRange(headerValue, start, end, token)) {
                return true;
            }
            tokenStart = tokenEnd + 1;
        }
        return false;
    }

    private static boolean equalsIgnoreCaseRange(String source, int start, int end, String expected) {
        int length = end - start;
        if (length != expected.length()) {
            return false;
        }
        for (int i = 0; i < length; i++) {
            if (Character.toLowerCase(source.charAt(start + i)) != Character.toLowerCase(expected.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private void queueHttp2InitialSettings(SelectionKey key, NioConnection connection) {
        if (connection.http2InitialSettingsSent()) {
            return;
        }
        byte[] payload = new byte[6 * 5];
        writeSetting(payload, 0, HTTP2_SETTINGS_ENABLE_PUSH, 0);
        writeSetting(payload, 6, HTTP2_SETTINGS_MAX_CONCURRENT_STREAMS, http2Config.maxConcurrentStreams());
        writeSetting(payload, 12, HTTP2_SETTINGS_INITIAL_WINDOW_SIZE, http2Config.initialWindowSize());
        writeSetting(payload, 18, HTTP2_SETTINGS_MAX_FRAME_SIZE, http2Config.maxFrameSize());
        writeSetting(payload, 24, HTTP2_SETTINGS_MAX_HEADER_LIST_SIZE, http2Config.maxHeaderListSize());
        queueHttp2Frame(key, connection, Http2FrameCodec.encodeFrame(Http2FrameCodec.TYPE_SETTINGS, 0, 0, payload),
                false);
        connection.markHttp2InitialSettingsSent();
    }

    private void writeSetting(byte[] payload, int offset, int id, int value) {
        payload[offset] = (byte) ((id >>> 8) & 0xFF);
        payload[offset + 1] = (byte) (id & 0xFF);
        payload[offset + 2] = (byte) ((value >>> 24) & 0xFF);
        payload[offset + 3] = (byte) ((value >>> 16) & 0xFF);
        payload[offset + 4] = (byte) ((value >>> 8) & 0xFF);
        payload[offset + 5] = (byte) (value & 0xFF);
    }

    private void sendHttp2RstStream(SelectionKey key, NioConnection connection, int streamId, int errorCode) {
        byte[] payload = new byte[4];
        payload[0] = (byte) ((errorCode >>> 24) & 0xFF);
        payload[1] = (byte) ((errorCode >>> 16) & 0xFF);
        payload[2] = (byte) ((errorCode >>> 8) & 0xFF);
        payload[3] = (byte) (errorCode & 0xFF);
        queueHttp2Frame(key, connection,
                Http2FrameCodec.encodeFrame(Http2FrameCodec.TYPE_RST_STREAM, 0, streamId, payload), false);
    }

    private void sendHttp2GoAway(SelectionKey key, NioConnection connection, int errorCode, String debug,
            boolean closeAfterWrite) {
        byte[] debugData = debug == null ? new byte[0] : debug.getBytes(StandardCharsets.UTF_8);
        byte[] payload = new byte[8 + debugData.length];
        int lastStreamId = connection.lastHttp2StreamId();
        payload[0] = (byte) ((lastStreamId >>> 24) & 0x7F);
        payload[1] = (byte) ((lastStreamId >>> 16) & 0xFF);
        payload[2] = (byte) ((lastStreamId >>> 8) & 0xFF);
        payload[3] = (byte) (lastStreamId & 0xFF);
        payload[4] = (byte) ((errorCode >>> 24) & 0xFF);
        payload[5] = (byte) ((errorCode >>> 16) & 0xFF);
        payload[6] = (byte) ((errorCode >>> 8) & 0xFF);
        payload[7] = (byte) (errorCode & 0xFF);
        if (debugData.length > 0) {
            System.arraycopy(debugData, 0, payload, 8, debugData.length);
        }
        queueHttp2Frame(
                key,
                connection,
                Http2FrameCodec.encodeFrame(Http2FrameCodec.TYPE_GOAWAY, 0, 0, payload),
                closeAfterWrite);
    }

    private void queueHttp2Frame(SelectionKey key, NioConnection connection, byte[] frameBytes,
            boolean closeAfterWrite) {
        queueResponse(key, connection, new ByteBuffer[]{ByteBuffer.wrap(frameBytes)}, closeAfterWrite);
    }

    private void processBufferedRequest(SelectionKey key, NioConnection connection) throws IOException {
        if (!connection.canProcessBufferedRequest()) {
            return;
        }

        long requestSequence = connection.reserveRequestSequence();

        RequestValidationResult validation = HttpRequestInspector.validateBufferedRequest(
                connection.buffer(),
                connection.bufferedBytes(),
                requestLimits);
        if (validation.hasError()) {
            enqueueImmediateCompletion(
                    key,
                    connection,
                    requestSequence,
                    SimpleHttpResponses.plainText(validation.statusCode(), validation.message()),
                    true);
            return;
        }

        int expectationStatus = HttpRequestInspector.expectationStatusCode(connection.buffer(),
                connection.bufferedBytes());
        if (expectationStatus != 0) {
            enqueueImmediateCompletion(
                    key,
                    connection,
                    requestSequence,
                    SimpleHttpResponses.plainText(expectationStatus, "Expectation Failed"),
                    true);
            return;
        }

        ParsedHttpRequest parsed;
        try {
            try (RequestTracing.SpanScope ignored = RequestTracing.startChildSpan("http.parse")) {
                parsed = HttpRequestParser.parse(connection.buffer(), connection.bufferedBytes());
            }
        } catch (IllegalArgumentException malformedRequest) {
            enqueueImmediateCompletion(
                    key,
                    connection,
                    requestSequence,
                    SimpleHttpResponses.plainText(400, "Bad Request"),
                    true);
            return;
        }
        if (parsed == null) {
            connection.releaseReservedRequestSequence();
            if (!connection.continueSentForCurrentRequest()
                    && HttpRequestInspector.shouldSendContinue(connection.buffer(), connection.bufferedBytes(),
                            requestLimits)) {
                connection.markContinueSent(); // must be set before queueResponse in case of inline write
                queueResponse(key, connection, SimpleHttpResponses.provisionalContinue(), false);
            }
            return;
        }

        connection.resetContinueSent();

        if (http2Config.enabled()
                && http2Config.h2cEnabled()
                && maybeHandleH2cUpgradeRequest(key, connection, parsed, requestSequence)) {
            return;
        }

        String websocketKey = HttpRequestInspector.extractWebSocketKeyIfValid(parsed);
        if (websocketKey != null) {
            String requestPath = extractRequestPath(parsed.getURI());
            WebSocketEndpointBinding endpointBinding = null;
            String negotiatedSubprotocol = null;
            boolean perMessageDeflateEnabled = negotiatePerMessageDeflate(
                    parsed.getHeader("Sec-WebSocket-Extensions"));
            String negotiatedExtensions = WebSocketExtensions.perMessageDeflateResponseHeader(perMessageDeflateEnabled);
            WebSocketEndpointMatch endpointMatch = router.resolveWebSocketEndpoint(requestPath);
            WebSocketEndpointMetadata endpointMetadata = endpointMatch == null ? null : endpointMatch.metadata();
            if (endpointMetadata != null) {
                negotiatedSubprotocol = negotiateSubprotocol(
                        parsed.getHeader("Sec-WebSocket-Protocol"),
                        endpointMetadata);
                endpointBinding = createNioWebSocketEndpointBinding(
                        key,
                        connection,
                        endpointMetadata,
                        endpointMatch.pathParams(),
                        negotiatedSubprotocol,
                        perMessageDeflateEnabled);
            }
            ServerObservability.recordRequestBytesReceived(parsed.bytesConsumed);
            connection.consume(parsed.bytesConsumed);
            connection.enableWebSocket(endpointBinding, perMessageDeflateEnabled);
            queueResponse(
                    key,
                    connection,
                    WebSocketHandshake.buildSwitchingProtocolsResponse(
                            websocketKey,
                            negotiatedSubprotocol,
                            negotiatedExtensions),
                    false);
            if (endpointBinding != null) {
                try {
                    endpointBinding.onOpen();
                } catch (RuntimeException endpointOpenError) {
                    endpointBinding.onError(endpointOpenError);
                    sendWebSocketClose(key, connection, WebSocketFrameCodec.CLOSE_PROTOCOL_ERROR, true);
                }
            }
            return;
        }
        if (HttpRequestInspector.isWebSocketUpgradeAttempt(parsed)) {
            ServerObservability.recordRequestBytesReceived(parsed.bytesConsumed);
            connection.consume(parsed.bytesConsumed);
            enqueueImmediateCompletion(
                    key,
                    connection,
                    requestSequence,
                    SimpleHttpResponses.plainText(400, "Bad Request"),
                    true);
            return;
        }

        StaticRouteResponse staticResponse = resolveStaticResponse(parsed);
        if (staticResponse != null) {
            ServerObservability.recordRequestBytesReceived(parsed.bytesConsumed);
            connection.consume(parsed.bytesConsumed);
            boolean closeAfterWrite = parsed.closeAfterResponse();
            boolean canBypassCompletionOrdering = requestSequence == connection.nextResponseSequence()
                && connection.inFlightRequestCount() == 0
                && !connection.hasBufferedCompletions();
            if (canBypassCompletionOrdering) {
            connection.releaseReservedRequestSequence();
            queueResponse(
                key,
                connection,
                staticResponse.response(closeAfterWrite),
                closeAfterWrite);
            return;
            }
            enqueueImmediateCompletion(
                    key,
                    connection,
                    requestSequence,
                    staticResponse.response(closeAfterWrite),
                    closeAfterWrite);
            return;
        }

        ServerObservability.recordRequestBytesReceived(parsed.bytesConsumed);
        connection.consume(parsed.bytesConsumed);
        connection.markRequestReady();
        connection.markExecuting();
        if (INLINE_REQUEST_EXECUTION) {
            executeRequestAsync(key, connection, parsed, requestSequence);
            return;
        }
        try {
            workerExecutor.execute(() -> executeRequestAsync(key, connection, parsed, requestSequence));
        } catch (RejectedExecutionException exception) {
            enqueueCompletion(NioCompletion.failure(
                    key,
                    requestSequence,
                    SimpleHttpResponses.plainText(503, "Server shutting down"),
                    true));
        }
    }

    private void processWebSocketFrames(SelectionKey key, NioConnection connection) {
        while (connection.bufferedBytes() > 0) {
            WebSocketFrameCodec.ParseResult parseResult = WebSocketFrameCodec.parseClientFrame(
                    connection.buffer(),
                    0,
                    connection.bufferedBytes(),
                    MAX_WEBSOCKET_PAYLOAD_BYTES,
                    connection.webSocketCompressionEnabled());

            if (parseResult.status() == WebSocketFrameCodec.ParseStatus.INCOMPLETE) {
                return;
            }

            if (parseResult.status() == WebSocketFrameCodec.ParseStatus.ERROR) {
                logger.debug("Closing websocket connection after protocol error: {}", parseResult.errorMessage());
                connection.consume(connection.bufferedBytes());
                sendWebSocketClose(key, connection, parseResult.closeCode(), true);
                return;
            }

            connection.consume(parseResult.bytesConsumed());
            WebSocketFrame frame = parseResult.frame();
            if (frame == null) {
                return;
            }

            if (!handleWebSocketFrame(key, connection, frame)) {
                return;
            }
        }
    }

    private boolean handleWebSocketFrame(SelectionKey key, NioConnection connection, WebSocketFrame frame) {
        return switch (frame.opcode()) {
            case WebSocketFrameCodec.OPCODE_TEXT ->
                handleWebSocketTextFrame(key, connection, frame);
            case WebSocketFrameCodec.OPCODE_CONTINUATION ->
                handleWebSocketContinuationFrame(key, connection, frame);
            case WebSocketFrameCodec.OPCODE_BINARY -> {
                WebSocketEndpointBinding endpointBinding = connection.webSocketEndpointBinding();
                if (endpointBinding == null) {
                    sendWebSocketClose(key, connection, WebSocketFrameCodec.CLOSE_UNSUPPORTED_DATA, true);
                    yield false;
                }
                try {
                    endpointBinding.onBinary(frame.payload());
                    yield true;
                } catch (RuntimeException endpointException) {
                    endpointBinding.onError(endpointException);
                    sendWebSocketClose(key, connection, WebSocketFrameCodec.CLOSE_PROTOCOL_ERROR, true);
                    yield false;
                }
            }
            case WebSocketFrameCodec.OPCODE_PING -> {
                queueWebSocketFrame(key, connection, WebSocketFrameCodec.OPCODE_PONG, frame.payload(), false);
                yield true;
            }
            case WebSocketFrameCodec.OPCODE_PONG ->
                true;
            case WebSocketFrameCodec.OPCODE_CLOSE -> {
                WebSocketEndpointBinding endpointBinding = connection.webSocketEndpointBinding();
                if (endpointBinding != null) {
                    endpointBinding.onClose();
                }
                if (!connection.websocketCloseSent()) {
                    byte[] closePayload = frame.payload().length >= 2
                            ? Arrays.copyOf(frame.payload(), Math.min(frame.payload().length, 125))
                            : WebSocketFrameCodec.closePayload(WebSocketFrameCodec.CLOSE_NORMAL);
                    queueWebSocketFrame(key, connection, WebSocketFrameCodec.OPCODE_CLOSE, closePayload, true);
                    connection.markWebSocketCloseSent();
                } else {
                    connection.markClosing();
                }
                yield false;
            }
            default -> {
                sendWebSocketClose(key, connection, WebSocketFrameCodec.CLOSE_PROTOCOL_ERROR, true);
                yield false;
            }
        };
    }

    private boolean handleWebSocketTextFrame(SelectionKey key, NioConnection connection, WebSocketFrame frame) {
        if (!frame.fin()) {
            connection.beginWebSocketFragment(WebSocketFrameCodec.OPCODE_TEXT, frame.payload());
            return true;
        }
        if (connection.hasWebSocketFragment()) {
            sendWebSocketClose(key, connection, WebSocketFrameCodec.CLOSE_PROTOCOL_ERROR, true);
            return false;
        }
        WebSocketEndpointBinding endpointBinding = connection.webSocketEndpointBinding();
        if (endpointBinding == null) {
            queueWebSocketFrame(key, connection, WebSocketFrameCodec.OPCODE_TEXT, frame.payload(), false);
            return true;
        }

        try {
            endpointBinding.onText(new String(frame.payload(), java.nio.charset.StandardCharsets.UTF_8));
            return true;
        } catch (RuntimeException endpointException) {
            endpointBinding.onError(endpointException);
            sendWebSocketClose(key, connection, WebSocketFrameCodec.CLOSE_PROTOCOL_ERROR, true);
            return false;
        }
    }

    private boolean handleWebSocketContinuationFrame(SelectionKey key, NioConnection connection, WebSocketFrame frame) {
        if (!connection.hasWebSocketFragment()) {
            sendWebSocketClose(key, connection, WebSocketFrameCodec.CLOSE_PROTOCOL_ERROR, true);
            return false;
        }
        connection.appendWebSocketFragment(frame.payload());
        if (!frame.fin()) {
            return true;
        }

        int opcode = connection.websocketFragmentOpcode();
        byte[] payload = connection.consumeWebSocketFragment();
        if (opcode == WebSocketFrameCodec.OPCODE_TEXT) {
            WebSocketEndpointBinding endpointBinding = connection.webSocketEndpointBinding();
            if (endpointBinding == null) {
                queueWebSocketFrame(key, connection, opcode, payload, false);
                return true;
            }
            try {
                endpointBinding.onText(new String(payload, java.nio.charset.StandardCharsets.UTF_8));
                return true;
            } catch (RuntimeException endpointException) {
                endpointBinding.onError(endpointException);
                sendWebSocketClose(key, connection, WebSocketFrameCodec.CLOSE_PROTOCOL_ERROR, true);
                return false;
            }
        }
        queueWebSocketFrame(key, connection, opcode, payload, false);
        return true;
    }

    private WebSocketEndpointBinding createNioWebSocketEndpointBinding(
            SelectionKey key,
            NioConnection connection,
            WebSocketEndpointMetadata endpointMetadata,
            Map<String, String> pathParams,
            String negotiatedSubprotocol,
            boolean perMessageDeflateEnabled) {
        WebSocketSession session = new WebSocketSession(
                connection.remoteAddressSafely(),
                negotiatedSubprotocol,
                new WebSocketSession.Transport() {
            @Override
            public void sendText(String message) {
                queueWebSocketFrame(
                        key,
                        connection,
                        WebSocketFrameCodec.OPCODE_TEXT,
                        message.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        false,
                        perMessageDeflateEnabled);
            }

            @Override
            public void sendBinary(byte[] payload) {
                queueWebSocketFrame(
                        key,
                        connection,
                        WebSocketFrameCodec.OPCODE_BINARY,
                        payload,
                        false,
                        perMessageDeflateEnabled);
            }

            @Override
            public void close(int code) {
                sendWebSocketClose(key, connection, code, true);
            }
        });
        return new WebSocketEndpointBinding(endpointMetadata, session, pathParams);
    }

    private static boolean negotiatePerMessageDeflate(String requestedExtensionsHeader) {
        return WebSocketExtensions.requestIncludesPerMessageDeflate(requestedExtensionsHeader);
    }

    private static String negotiateSubprotocol(String requestedHeader, WebSocketEndpointMetadata endpointMetadata) {
        if (endpointMetadata == null) {
            return null;
        }
        String[] supported = endpointMetadata.subprotocols();
        if (supported.length == 0 || requestedHeader == null || requestedHeader.isBlank()) {
            return null;
        }

        Set<String> requested = new LinkedHashSet<>();
        int length = requestedHeader.length();
        int tokenStart = 0;
        while (tokenStart < length) {
            int tokenEnd = requestedHeader.indexOf(',', tokenStart);
            if (tokenEnd < 0) {
                tokenEnd = length;
            }
            int start = tokenStart;
            while (start < tokenEnd && Character.isWhitespace(requestedHeader.charAt(start))) {
                start++;
            }
            int end = tokenEnd;
            while (end > start && Character.isWhitespace(requestedHeader.charAt(end - 1))) {
                end--;
            }
            if (start < end) {
                requested.add(requestedHeader.substring(start, end));
            }
            tokenStart = tokenEnd + 1;
        }
        if (requested.isEmpty()) {
            return null;
        }

        for (String candidate : supported) {
            if (requested.contains(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static String extractRequestPath(String requestUri) {
        if (requestUri == null || requestUri.isEmpty()) {
            return "/";
        }
        int queryStart = requestUri.indexOf('?');
        return queryStart >= 0 ? requestUri.substring(0, queryStart) : requestUri;
    }

    private StaticRouteResponse resolveStaticResponse(ParsedHttpRequest parsed) {
        if (parsed == null) {
            return null;
        }
        if (parsed.bodyLength > 0) {
            return null;
        }
        if (parsed.getHeader("Upgrade") != null) {
            return null;
        }

        String method = parsed.getMethod();
        String path = extractRequestPath(parsed.getURI());
        return staticResponses.get(staticRouteKey(method, path));
    }

    private static String staticRouteKey(String method, String path) {
        String normalizedMethod;
        if (method == null || "GET".equals(method)) {
            normalizedMethod = "GET";
        } else if ("POST".equals(method)) {
            normalizedMethod = "POST";
        } else if ("HEAD".equals(method)) {
            normalizedMethod = "HEAD";
        } else {
            normalizedMethod = method.trim().toUpperCase(java.util.Locale.ROOT);
        }
        return normalizedMethod + " " + normalizeStaticPath(path);
    }

    private static String normalizeStaticPath(String path) {
        if (path == null || path.isBlank()) {
            return "/";
        }
        return path.charAt(0) == '/' ? path : "/" + path;
    }

    private record StaticRouteResponse(byte[] keepAliveResponse, byte[] closeResponse) {

        private byte[] response(boolean closeAfterWrite) {
            return closeAfterWrite ? closeResponse : keepAliveResponse;
        }
    }

    private void sendWebSocketClose(SelectionKey key, NioConnection connection, int closeCode,
            boolean closeAfterWrite) {
        byte[] closePayload = WebSocketFrameCodec.closePayload(closeCode);
        queueWebSocketFrame(
                key,
                connection,
                WebSocketFrameCodec.OPCODE_CLOSE,
                closePayload,
                closeAfterWrite,
                false);
        connection.markWebSocketCloseSent();
    }

    private void queueWebSocketFrame(SelectionKey key, NioConnection connection, int opcode, byte[] payload,
            boolean closeAfterWrite) {
        queueWebSocketFrame(
                key,
                connection,
                opcode,
                payload,
                closeAfterWrite,
                connection.webSocketCompressionEnabled());
    }

    private void queueWebSocketFrame(SelectionKey key, NioConnection connection, int opcode, byte[] payload,
            boolean closeAfterWrite, boolean perMessageDeflateEnabled) {
        byte[] frame = WebSocketFrameCodec.encodeServerFrame(opcode, true, payload, perMessageDeflateEnabled);
        queueResponse(key, connection, frame, closeAfterWrite);
    }

    private void enqueueImmediateCompletion(
            SelectionKey key,
            NioConnection connection,
            long sequence,
            byte[] responseBytes,
            boolean closeAfterWrite) {
        connection.markExecuting();
        enqueueCompletion(NioCompletion.failure(key, sequence, responseBytes, closeAfterWrite));
    }

    private void executeRequestAsync(
            SelectionKey key,
            NioConnection connection,
            ParsedHttpRequest parsed,
            long requestSequence) {
        try {
            final NioSseEmitter sseEmitter = SSE_EAGER ? createNioSseEmitter(key, connection) : null;
            final HttpRequestExecutor.AsyncResponseHandler asyncHandler = new HttpRequestExecutor.AsyncResponseHandler() {
                @Override
                public void onComplete(HttpExecutionResult result) {
                    enqueueCompletion(NioCompletion.success(key, requestSequence, result));
                }

                @Override
                public java.util.concurrent.ScheduledFuture<?> scheduleTimeout(Runnable task,
                        long timeoutMillis) {
                    return asyncTimeoutExecutor.schedule(task, timeoutMillis, TimeUnit.MILLISECONDS);
                }

                @Override
                public void executeAsync(Runnable task) {
                    workerExecutor.execute(task);
                }
            };
            final HttpExecutionResult executionResult;
            final String remoteAddr = connection.remoteAddress();
            final int remotePort = connection.remotePort();
            final int localPort = connection.localPort();
            final boolean tlsEnabled = connection.isTlsEnabled();
            if (sseEmitter != null) {
                executionResult = SseRuntime.callWithEmitter(sseEmitter, () -> HttpRequestExecutor.execute(
                        parsed, router, remoteAddr, remotePort,
                        localPort, requestLimits, tlsEnabled, false, asyncHandler));
            } else {
                executionResult = HttpRequestExecutor.execute(
                        parsed, router, remoteAddr, remotePort,
                        localPort, requestLimits, tlsEnabled, false, asyncHandler);
            }
            if (executionResult != null) {
                enqueueCompletion(NioCompletion.success(key, requestSequence, executionResult));
            }
        } catch (Exception exception) {
            logger.debug("Async request execution failed: {}", exception.getMessage());
            enqueueCompletion(NioCompletion.failure(
                    key,
                    requestSequence,
                    SimpleHttpResponses.plainText(500, "Internal Server Error"),
                    true));
        }
    }

    private void enqueueCompletion(NioCompletion completion) {
        if (completion == null) {
            return;
        }
        SelectionKey key = completion.key();
        if (selectorGroup != null && key != null) {
            selectorGroup.enqueueSelectorCompletion(key.selector(), completion);
            registerConnectionForWriting(key);
            return;
        }
        completionQueue.add(completion);
        requestSelectorWakeup();
    }

    void applyCompletion(NioCompletion completion) {
        if (completion == null) {
            return;
        }
        SelectionKey key = completion.key();
        if (key == null || !key.isValid()) {
            return;
        }
        NioConnection connection = (NioConnection) key.attachment();
        connection.markResponseReady();
        connection.enqueueCompletedResponse(completion);
        drainCompletedResponsesInOrder(key, connection);
    }

    private NioSseEmitter createNioSseEmitter(SelectionKey key, NioConnection connection) {
        return new NioSseEmitter((payload, closeAfterWrite) -> submitSelectorTask(() -> {
            if (!key.isValid()) {
                return;
            }
            if (closeAfterWrite) {
                connection.disableSseStreaming();
            }
            queueResponse(key, connection, new ByteBuffer[]{ByteBuffer.wrap(payload)}, closeAfterWrite);
        }));
    }

    void applyCompletedExecutions(int maxCompletions) {
        int processed = 0;
        NioCompletion completion;
        while ((maxCompletions <= 0 || processed < maxCompletions)
                && (completion = completionQueue.poll()) != null) {
            applyCompletion(completion);
            processed++;
        }
    }

    private void drainCompletedResponsesInOrder(SelectionKey key, NioConnection connection) {
        NioCompletion readyResponse;
        while ((readyResponse = connection.pollNextOrderedResponse()) != null) {
            NioSseEmitter sseEmitter = null;
            if (readyResponse.executionResult().sseStream()) {
                connection.enableSseStreaming();
                if (readyResponse.executionResult().sseEmitter() instanceof NioSseEmitter nioSseEmitter) {
                    sseEmitter = nioSseEmitter;
                }
            }
            queueResponse(key, connection, readyResponse.executionResult(), readyResponse.closeAfterWrite());
            if (sseEmitter != null) {
                try {
                    sseEmitter.markReady();
                } catch (IOException streamError) {
                    logger.debug("Failed to activate SSE stream: {}", streamError.getMessage());
                    connection.markClosing();
                    closeKey(key);
                    return;
                }
            }
            if (readyResponse.closeAfterWrite()) {
                return;
            }
        }
    }

    private void queueResponse(SelectionKey key, NioConnection connection, byte[] responseBytes,
            boolean closeAfterWrite) {
        queueResponse(key, connection, new ByteBuffer[]{ByteBuffer.wrap(responseBytes)}, closeAfterWrite);
    }

    private void queueResponse(SelectionKey key, NioConnection connection, ByteBuffer[] responseSegments,
            boolean closeAfterWrite) {
        connection.prepareWrite(responseSegments, null, closeAfterWrite);
        if (attemptInlineWrite(key, connection)) {
            return;
        }
        key.interestOps(SelectionKey.OP_WRITE);
        registerConnectionForWriting(key);
    }

    private void queueResponse(SelectionKey key, NioConnection connection, HttpExecutionResult executionResult,
            boolean closeAfterWrite) {
        connection.prepareWrite(executionResult.responseBuffers(), executionResult.fileBody(), closeAfterWrite);
        if (attemptInlineWrite(key, connection)) {
            return;
        }
        key.interestOps(SelectionKey.OP_WRITE);
        registerConnectionForWriting(key);
    }

    /**
     * Attempts a non-blocking write inline when already on the selector thread,
     * eliminating the OP_WRITE interest-registration + extra selector iteration
     * that would otherwise be needed for every response. For small keep-alive
     * responses, the TCP send buffer is almost always available, so the write
     * succeeds without a partial-write fall-through.
     *
     * @return true if a write attempt was made (caller must NOT arm OP_WRITE)
     * false if not on selector thread (caller should fall back to OP_WRITE)
     */
    private boolean attemptInlineWrite(SelectionKey key, NioConnection connection) {
        if (!isSelectorThread()) {
            return false;
        }
        try {
            int written = connection.writeToChannel(maxWriteBytesPerOperation);
            if (written > 0) {
                connection.touch();
            }
        } catch (IOException e) {
            closeKey(key);
            return true;
        }
        if (connection.hasPendingWrite()) {
            // Partial write: arm OP_WRITE but skip wakeup — selector thread will pick it up.
            key.interestOps(SelectionKey.OP_WRITE);
            return true;
        }
        if (connection.hasPendingH2cUpgrade()) {
            activatePendingH2cUpgrade(key, connection);
            return true;
        }
        if (connection.shouldCloseAfterWrite()) {
            connection.markClosing();
            closeKey(key);
            return true;
        }
        connection.markIdle();
        key.interestOps(SelectionKey.OP_READ);
        if (connection.bufferedBytes() > 0) {
            try {
                processBufferedData(key, connection);
            } catch (IOException e) {
                closeKey(key);
            }
        }
        return true;
    }

    void expireIdleConnections() {
        long now = System.currentTimeMillis();
        boolean doIdleCheck = now - lastIdleCheckMillis >= IDLE_CHECK_INTERVAL_MS;
        boolean doWriteTimeoutCheck = now - lastWriteTimeoutCheckMillis >= WRITE_TIMEOUT_CHECK_INTERVAL_MS;
        if (!doIdleCheck && !doWriteTimeoutCheck) {
            return;
        }
        if (doIdleCheck) {
            lastIdleCheckMillis = now;
        }
        if (doWriteTimeoutCheck) {
            lastWriteTimeoutCheckMillis = now;
        }
        expireIdleConnectionsOnSelector(selector, now, doIdleCheck, doWriteTimeoutCheck);
    }

    void expireIdleConnectionsOnSelector(Selector targetSelector, long now, boolean doIdleCheck, boolean doWriteTimeoutCheck) {
        if (targetSelector == null) {
            return;
        }
        for (SelectionKey key : targetSelector.keys()) {
            if (!(key.attachment() instanceof NioConnection connection)) {
                continue;
            }
            if (connection.hasPendingWrite()) {
                if (doWriteTimeoutCheck && connection.hasExceededWriteTimeout(now, requestLimits.writeTimeoutMillis())) {
                    handleWriteTimeout(key, connection, now);
                }
                continue;
            }
            if (!doIdleCheck) {
                continue;
            }
            if (connection.isExecuting()) {
                continue;
            }
            if (connection.isSseStreamingEnabled()) {
                continue;
            }
            if (now - connection.lastActivityTimeMillis() > requestLimits.keepAliveTimeoutMillis()) {
                connection.markClosing();
                closeKey(key);
            }
        }
    }

    private void handleWriteTimeout(SelectionKey key, NioConnection connection, long nowMillis) {
        writeTimeoutCloseCount.incrementAndGet();
        ServerObservability.recordWriteTimeout();
        connection.markClosing();
        logger.warn(
                "Closing NIO connection due to write timeout (remote={}, pendingBytes={}, elapsedMillis={}, timeoutMillis={})",
                connection.remoteAddressSafely(),
                connection.pendingWriteBytes(),
                connection.writeElapsedMillis(nowMillis),
                requestLimits.writeTimeoutMillis());
        closeKey(key);
    }

    void closeKey(SelectionKey key) {
        try {
            if (key.attachment() instanceof NioConnection connection) {
                connection.closeResources();
                ServerObservability.connectionClosed();
            }
            if (key.channel() != null) {
                key.channel().close();
            }
        } catch (IOException exception) {
            logger.debug("Error closing channel: {}", exception.getMessage());
        } finally {
            key.cancel();
        }
    }

    private static final class NioConnection {

        private enum ProtocolMode {
            HTTP1,
            HTTP2,
            REJECTED
        }

        private enum State {
            READING,
            REQUEST_READY,
            EXECUTING,
            RESPONSE_READY,
            WRITING,
            IDLE,
            CLOSING
        }

        private final SocketChannel channel;
        private final RequestLimits requestLimits;
        private final TlsChannelHandler tlsHandler;
        private ProtocolMode protocolMode;
        private final byte[] buffer;
        private final ByteBuffer readView;
        private Deque<ByteBuffer> pendingWrites;
        private ByteBuffer[] gatherWriteBatch;
        private ByteBuffer[] gatherWriteOriginals;
        private int[] gatherWriteOriginalLimits;
        private Map<Long, NioCompletion> completedResponses;
        private int bufferedBytes;
        private int inFlightRequests;
        private long nextRequestSequence;
        private long nextResponseSequence;
        private boolean closeAfterWrite;
        private Path pendingFilePath;
        private long pendingFilePosition;
        private long pendingFileRemaining;
        private FileChannel pendingFileChannel;
        /**
         * Lazily allocated 8 KiB chunk buffer used when sending files over TLS.
         */
        private ByteBuffer tlsFileChunk;
        private State state;
        private long lastActivityTimeMillis;
        private long writeStartedAtMillis;
        private boolean continueSentForCurrentRequest;
        private boolean webSocketEnabled;
        private boolean webSocketCloseSent;
        private boolean webSocketCompressionEnabled;
        private WebSocketEndpointBinding webSocketEndpointBinding;
        private boolean sseStreamingEnabled;
        private int websocketFragmentOpcode;
        private byte[] websocketFragmentBuffer;
        private boolean http2PrefaceReceived;
        private boolean http2InitialSettingsSent;
        private boolean peerSettingsReceived;
        private int lastHttp2StreamId;
        private Map<Integer, Http2StreamState> http2Streams;
        private int inFlightHttp2Requests;
        private Integer pendingContinuationStreamId;
        private int highestClientStreamId;
        private String cachedRemoteAddress;
        private int cachedRemotePort;
        private int connectionReceiveWindow;
        private int connectionSendWindow;
        private int peerInitialStreamWindowSize;
        private int peerMaxFrameSize;
        private HpackCodec hpackCodec;
        private Map<Integer, Deque<Http2QueuedData>> queuedHttp2DataByStream;
        private Deque<Integer> queuedHttp2StreamOrder;
        private Set<Integer> queuedHttp2StreamSet;
        private boolean pendingH2cUpgrade;
        private byte[] pendingH2cUpgradeSettings;

        private NioConnection(SocketChannel channel, RequestLimits requestLimits) throws IOException {
            this(channel, requestLimits, null, ProtocolMode.HTTP1);
        }

        private NioConnection(SocketChannel channel, RequestLimits requestLimits,
                TlsChannelHandler tlsHandler, ProtocolMode protocolMode) throws IOException {
            this.channel = channel;
            this.requestLimits = requestLimits;
            this.tlsHandler = tlsHandler;
            this.protocolMode = protocolMode;
            this.buffer = new byte[requestLimits.maxRequestSize()];
            this.readView = ByteBuffer.wrap(buffer);
            this.pendingWrites = null;
            this.gatherWriteBatch = null;
            this.gatherWriteOriginals = null;
            this.gatherWriteOriginalLimits = null;
            this.completedResponses = null;
            this.state = State.READING;
            this.lastActivityTimeMillis = System.currentTimeMillis();
            this.writeStartedAtMillis = 0;
            this.continueSentForCurrentRequest = false;
            this.inFlightRequests = 0;
            this.nextRequestSequence = 0;
            this.nextResponseSequence = 0;
            this.webSocketEnabled = false;
            this.webSocketCloseSent = false;
            this.webSocketCompressionEnabled = false;
            this.webSocketEndpointBinding = null;
            this.sseStreamingEnabled = false;
            this.websocketFragmentOpcode = -1;
            this.websocketFragmentBuffer = null;
            this.http2PrefaceReceived = false;
            this.http2InitialSettingsSent = false;
            this.peerSettingsReceived = false;
            this.lastHttp2StreamId = 0;
            this.http2Streams = null;
            this.inFlightHttp2Requests = 0;
            this.pendingContinuationStreamId = null;
            this.highestClientStreamId = 0;
            this.cachedRemoteAddress = null;
            this.cachedRemotePort = -1;
            this.connectionReceiveWindow = HTTP2_DEFAULT_INITIAL_WINDOW_SIZE;
            this.connectionSendWindow = HTTP2_DEFAULT_INITIAL_WINDOW_SIZE;
            this.peerInitialStreamWindowSize = HTTP2_DEFAULT_INITIAL_WINDOW_SIZE;
            this.peerMaxFrameSize = 16_384;
            this.hpackCodec = null;
            this.queuedHttp2DataByStream = null;
            this.queuedHttp2StreamOrder = null;
            this.queuedHttp2StreamSet = null;
            this.pendingH2cUpgrade = false;
            this.pendingH2cUpgradeSettings = null;
        }

        private ProtocolMode protocolMode() {
            return protocolMode;
        }

        private void setProtocolMode(ProtocolMode protocolMode) {
            this.protocolMode = protocolMode;
            if (protocolMode == ProtocolMode.HTTP2) {
                ensureHttp2State();
            }
        }

        private void ensureHttp2State() {
            if (http2Streams == null) {
                http2Streams = new HashMap<>();
            }
            if (hpackCodec == null) {
                hpackCodec = new HpackCodec(requestLimits.maxHeaderBytes(), 4096);
            }
            if (queuedHttp2DataByStream == null) {
                queuedHttp2DataByStream = new HashMap<>();
            }
            if (queuedHttp2StreamOrder == null) {
                queuedHttp2StreamOrder = new ArrayDeque<>();
            }
            if (queuedHttp2StreamSet == null) {
                queuedHttp2StreamSet = new HashSet<>();
            }
        }

        private Map<Long, NioCompletion> completedResponses() {
            if (completedResponses == null) {
                completedResponses = new TreeMap<>();
            }
            return completedResponses;
        }

        private Deque<ByteBuffer> pendingWrites() {
            if (pendingWrites == null) {
                pendingWrites = new ArrayDeque<>();
            }
            return pendingWrites;
        }

        private void ensureGatherWriteScratch() {
            if (gatherWriteBatch == null) {
                gatherWriteBatch = new ByteBuffer[GATHER_WRITE_BATCH_SIZE];
                gatherWriteOriginals = new ByteBuffer[GATHER_WRITE_BATCH_SIZE];
                gatherWriteOriginalLimits = new int[GATHER_WRITE_BATCH_SIZE];
            }
        }

        private boolean http2PrefaceReceived() {
            return http2PrefaceReceived;
        }

        private void markHttp2PrefaceReceived() {
            this.http2PrefaceReceived = true;
        }

        private boolean http2InitialSettingsSent() {
            return http2InitialSettingsSent;
        }

        private void markHttp2InitialSettingsSent() {
            this.http2InitialSettingsSent = true;
        }

        private void markPeerSettingsReceived() {
            this.peerSettingsReceived = true;
        }

        private boolean hasPendingContinuation() {
            return pendingContinuationStreamId != null;
        }

        private int pendingContinuationStreamId() {
            return pendingContinuationStreamId == null ? 0 : pendingContinuationStreamId;
        }

        private void expectContinuation(int streamId) {
            pendingContinuationStreamId = streamId;
        }

        private void clearPendingContinuation() {
            pendingContinuationStreamId = null;
        }

        private void markPendingH2cUpgrade(byte[] settingsPayload) {
            pendingH2cUpgrade = true;
            pendingH2cUpgradeSettings = settingsPayload == null ? new byte[0]
                    : Arrays.copyOf(settingsPayload,
                            settingsPayload.length);
        }

        private boolean hasPendingH2cUpgrade() {
            return pendingH2cUpgrade;
        }

        private byte[] consumePendingH2cUpgradeSettings() {
            pendingH2cUpgrade = false;
            byte[] settings = pendingH2cUpgradeSettings == null ? new byte[0]
                    : Arrays.copyOf(pendingH2cUpgradeSettings, pendingH2cUpgradeSettings.length);
            pendingH2cUpgradeSettings = null;
            return settings;
        }

        private HpackCodec hpackCodec() {
            ensureHttp2State();
            return hpackCodec;
        }

        private Http2StreamState getOrCreateHttp2Stream(int streamId) {
            ensureHttp2State();
            Http2StreamState stream = http2Streams.computeIfAbsent(
                    streamId,
                    ignored -> new Http2StreamState(streamId, peerInitialStreamWindowSize));
            if (streamId > lastHttp2StreamId) {
                lastHttp2StreamId = streamId;
            }
            if (streamId > highestClientStreamId) {
                highestClientStreamId = streamId;
            }
            return stream;
        }

        private Http2StreamState http2Stream(int streamId) {
            if (http2Streams == null) {
                return null;
            }
            return http2Streams.get(streamId);
        }

        private void removeHttp2Stream(int streamId) {
            if (http2Streams == null) {
                return;
            }
            http2Streams.remove(streamId);
            if (queuedHttp2DataByStream != null) {
                queuedHttp2DataByStream.remove(streamId);
            }
            if (queuedHttp2StreamSet != null) {
                queuedHttp2StreamSet.remove(streamId);
            }
            if (queuedHttp2StreamOrder != null) {
                queuedHttp2StreamOrder.removeIf(id -> id == streamId);
            }
            if (pendingContinuationStreamId != null && pendingContinuationStreamId == streamId) {
                pendingContinuationStreamId = null;
            }
        }

        private int http2ActiveStreams() {
            if (http2Streams == null) {
                return 0;
            }
            return http2Streams.size();
        }

        private int lastHttp2StreamId() {
            return lastHttp2StreamId;
        }

        private void markHttp2RequestExecuting() {
            inFlightHttp2Requests++;
            transitionTo(State.EXECUTING);
        }

        private void onHttp2RequestFinished(int streamId) {
            if (inFlightHttp2Requests > 0) {
                inFlightHttp2Requests--;
            }
            removeHttp2Stream(streamId);
            if (inFlightHttp2Requests == 0 && !hasPendingWrite()) {
                transitionTo(State.READING);
            }
        }

        private int connectionReceiveWindow() {
            return connectionReceiveWindow;
        }

        private boolean consumeConnectionReceiveWindow(int amount) {
            if (amount < 0 || amount > connectionReceiveWindow) {
                return false;
            }
            connectionReceiveWindow -= amount;
            return true;
        }

        private int restoreConnectionReceiveWindow(int amount) {
            long next = (long) connectionReceiveWindow + amount;
            if (next > Integer.MAX_VALUE) {
                connectionReceiveWindow = Integer.MAX_VALUE;
                return Integer.MAX_VALUE;
            }
            connectionReceiveWindow = (int) next;
            return amount;
        }

        private boolean incrementConnectionSendWindow(int increment) {
            long next = (long) connectionSendWindow + increment;
            if (next > Integer.MAX_VALUE) {
                return false;
            }
            connectionSendWindow = (int) next;
            return true;
        }

        private boolean tryConsumeConnectionSendWindow(int amount) {
            if (amount < 0 || amount > connectionSendWindow) {
                return false;
            }
            connectionSendWindow -= amount;
            return true;
        }

        private void restoreConnectionSendWindow(int amount) {
            connectionSendWindow += amount;
        }

        private void updatePeerInitialStreamWindowSize(int newSize) {
            int delta = newSize - peerInitialStreamWindowSize;
            peerInitialStreamWindowSize = newSize;
            if (http2Streams == null) {
                return;
            }
            for (Http2StreamState stream : http2Streams.values()) {
                stream.adjustSendWindow(delta);
            }
        }

        private void setPeerMaxFrameSize(int maxFrameSize) {
            peerMaxFrameSize = maxFrameSize;
        }

        private void enqueueHttp2DataChunk(int streamId, byte[] payload, int flags) {
            ensureHttp2State();
            Deque<Http2QueuedData> queue = queuedHttp2DataByStream.computeIfAbsent(streamId,
                    ignored -> new ArrayDeque<>());
            int offset = 0;
            int chunkSize = Math.max(1, peerMaxFrameSize);
            while (offset < payload.length) {
                int length = Math.min(chunkSize, payload.length - offset);
                byte[] chunk = Arrays.copyOfRange(payload, offset, offset + length);
                offset += length;
                int chunkFlags = (offset >= payload.length) ? flags : 0;
                queue.addLast(new Http2QueuedData(streamId, chunk, chunkFlags));
            }
            if (payload.length == 0) {
                queue.addLast(new Http2QueuedData(streamId, new byte[0], flags));
            }
            if (queuedHttp2StreamSet.add(streamId)) {
                queuedHttp2StreamOrder.addLast(streamId);
            }
        }

        private Http2QueuedData pollNextQueuedHttp2DataFrame() {
            if (queuedHttp2DataByStream == null || queuedHttp2StreamOrder == null || queuedHttp2StreamSet == null) {
                return null;
            }
            int streams = queuedHttp2StreamOrder.size();
            for (int i = 0; i < streams; i++) {
                Integer streamId = queuedHttp2StreamOrder.pollFirst();
                if (streamId == null) {
                    return null;
                }
                Deque<Http2QueuedData> queue = queuedHttp2DataByStream.get(streamId);
                if (queue == null || queue.isEmpty()) {
                    queuedHttp2StreamSet.remove(streamId);
                    queuedHttp2DataByStream.remove(streamId);
                    continue;
                }
                Http2QueuedData frame = queue.pollFirst();
                if (!queue.isEmpty()) {
                    queuedHttp2StreamOrder.addLast(streamId);
                } else {
                    queuedHttp2StreamSet.remove(streamId);
                    queuedHttp2DataByStream.remove(streamId);
                }
                return frame;
            }
            return null;
        }

        private void requeueHttp2DataFrame(Http2QueuedData frame) {
            ensureHttp2State();
            Deque<Http2QueuedData> queue = queuedHttp2DataByStream.computeIfAbsent(frame.streamId(),
                    ignored -> new ArrayDeque<>());
            queue.addFirst(frame);
            if (queuedHttp2StreamSet.add(frame.streamId())) {
                queuedHttp2StreamOrder.addFirst(frame.streamId());
            }
        }

        private byte bufferAt(int index) {
            return buffer[index];
        }

        private int readFromChannel() throws IOException {
            readView.position(bufferedBytes);
            readView.limit(buffer.length);
            int bytesRead = (tlsHandler != null)
                    ? tlsHandler.read(readView)
                    : channel.read(readView);
            if (bytesRead > 0) {
                bufferedBytes += bytesRead;
            }
            return bytesRead;
        }

        private int writeToChannel(int maxWriteBytesPerOperation) throws IOException {
            int remainingBudget = Math.max(1, maxWriteBytesPerOperation);
            int totalWritten = 0;

            Deque<ByteBuffer> writes = pendingWrites;
            if (remainingBudget > 0 && tlsHandler == null && writes != null && writes.size() > 1) {
                int gathered = writePendingBuffersGathered(remainingBudget);
                if (gathered > 0) {
                    remainingBudget -= gathered;
                    totalWritten += gathered;
                }
            }

            while (remainingBudget > 0 && writes != null && !writes.isEmpty()) {
                ByteBuffer current = writes.peekFirst();
                int allowed = Math.min(remainingBudget, current.remaining());
                int bytesWritten;
                if (tlsHandler != null) {
                    if (allowed == current.remaining()) {
                        bytesWritten = tlsHandler.write(current);
                    } else {
                        int originalLimit = current.limit();
                        current.limit(current.position() + allowed);
                        try {
                            bytesWritten = tlsHandler.write(current);
                        } finally {
                            current.limit(originalLimit);
                        }
                    }
                } else {
                    if (allowed == current.remaining()) {
                        bytesWritten = channel.write(current);
                    } else {
                        int originalLimit = current.limit();
                        current.limit(current.position() + allowed);
                        try {
                            bytesWritten = channel.write(current);
                        } finally {
                            current.limit(originalLimit);
                        }
                    }
                }

                if (bytesWritten <= 0) {
                    break;
                }

                remainingBudget -= bytesWritten;
                totalWritten += bytesWritten;
                if (!current.hasRemaining()) {
                    writes.removeFirst();
                }
            }

            if (remainingBudget > 0 && pendingFileRemaining > 0) {
                if (pendingFileChannel == null && pendingFilePath != null) {
                    pendingFileChannel = FileChannel.open(pendingFilePath);
                }
                if (tlsHandler != null) {
                    // FileChannel.transferTo() cannot be used over TLS — read then encrypt.
                    int chunkSize = (int) Math.min(Math.min((long) remainingBudget, pendingFileRemaining), 8192L);
                    if (tlsFileChunk == null) {
                        tlsFileChunk = ByteBuffer.allocate(8192);
                    }
                    tlsFileChunk.clear();
                    tlsFileChunk.limit(chunkSize);
                    int fileRead = pendingFileChannel.read(tlsFileChunk);
                    if (fileRead > 0) {
                        tlsFileChunk.flip();
                        int encrypted = tlsHandler.write(tlsFileChunk);
                        pendingFilePosition += encrypted;
                        pendingFileRemaining -= encrypted;
                        totalWritten += encrypted;
                    }
                } else {
                    long transferBudget = Math.min((long) remainingBudget, pendingFileRemaining);
                    long transferred = pendingFileChannel.transferTo(pendingFilePosition, transferBudget, channel);
                    if (transferred > 0) {
                        pendingFilePosition += transferred;
                        pendingFileRemaining -= transferred;
                        totalWritten += (int) transferred;
                    }
                }
                if (pendingFileRemaining == 0) {
                    clearPendingFileTransfer();
                }
            }
            return totalWritten;
        }

        private int writePendingBuffersGathered(int writeBudget) throws IOException {
            Deque<ByteBuffer> writes = pendingWrites;
            if (writes == null || writes.size() <= 1) {
                return 0;
            }
            ensureGatherWriteScratch();

            int count = 0;
            int remainingBudget = writeBudget;
            for (ByteBuffer pendingWrite : writes) {
                if (count == GATHER_WRITE_BATCH_SIZE || remainingBudget <= 0) {
                    break;
                }
                int allowed = Math.min(pendingWrite.remaining(), remainingBudget);
                if (allowed <= 0) {
                    break;
                }
                gatherWriteBatch[count] = pendingWrite;
                gatherWriteOriginals[count] = pendingWrite;
                gatherWriteOriginalLimits[count] = pendingWrite.limit();
                if (allowed < pendingWrite.remaining()) {
                    pendingWrite.limit(pendingWrite.position() + allowed);
                }
                remainingBudget -= allowed;
                count++;
            }

            if (count <= 1) {
                restoreGatherWriteLimits(count);
                return 0;
            }

            long writtenLong;
            try {
                writtenLong = channel.write(gatherWriteBatch, 0, count);
            } finally {
                restoreGatherWriteLimits(count);
            }

            if (writtenLong <= 0) {
                return 0;
            }

            int written = (int) Math.min(Integer.MAX_VALUE, writtenLong);

            for (int i = 0; i < count; i++) {
                gatherWriteBatch[i] = null;
                gatherWriteOriginals[i] = null;
                gatherWriteOriginalLimits[i] = 0;
            }

            while (!writes.isEmpty() && !writes.peekFirst().hasRemaining()) {
                writes.removeFirst();
            }
            return written;
        }

        private void prepareWrite(ByteBuffer[] responseSegments, FileResponseBody fileBody, boolean closeAfterWrite) {
            if (state != State.RESPONSE_READY && state != State.WRITING && state != State.READING
                    && state != State.EXECUTING
                    && state != State.IDLE) {
                throw new IllegalStateException("Cannot queue response while in state " + state);
            }
            if (fileBody == null && tlsHandler != null && shouldCoalesceSmallSegments(responseSegments)) {
                pendingWrites().addLast(ByteBuffer.wrap(coalesceSegments(responseSegments)));
            } else {
                for (ByteBuffer responseSegment : responseSegments) {
                    if (responseSegment != null && responseSegment.hasRemaining()) {
                        pendingWrites().addLast(responseSegment);
                    }
                }
            }
            if (fileBody != null && fileBody.length() > 0) {
                pendingFilePath = fileBody.path();
                pendingFilePosition = fileBody.offset();
                pendingFileRemaining = fileBody.length();
                if (pendingFileChannel != null) {
                    try {
                        pendingFileChannel.close();
                    } catch (IOException ignored) {
                        // Channel reset best-effort when switching to a new file transfer.
                    }
                    pendingFileChannel = null;
                }
            }
            this.closeAfterWrite = this.closeAfterWrite || closeAfterWrite;
            if (state != State.WRITING) {
                transitionTo(State.WRITING);
                writeStartedAtMillis = System.currentTimeMillis();
            }
        }

        private boolean shouldCoalesceSmallSegments(ByteBuffer[] responseSegments) {
            int nonEmptySegments = 0;
            int totalBytes = 0;
            for (ByteBuffer segment : responseSegments) {
                if (segment == null || !segment.hasRemaining()) {
                    continue;
                }
                nonEmptySegments++;
                totalBytes += segment.remaining();
                if (nonEmptySegments > 1 && totalBytes > SMALL_RESPONSE_COALESCE_BYTES) {
                    return false;
                }
            }
            return nonEmptySegments > 1 && totalBytes > 0;
        }

        private byte[] coalesceSegments(ByteBuffer[] responseSegments) {
            int totalBytes = 0;
            for (ByteBuffer segment : responseSegments) {
                if (segment != null) {
                    totalBytes += segment.remaining();
                }
            }
            byte[] merged = new byte[totalBytes];
            int position = 0;
            for (ByteBuffer segment : responseSegments) {
                if (segment == null || !segment.hasRemaining()) {
                    continue;
                }
                int length = segment.remaining();
                if (segment.hasArray()) {
                    int offset = segment.arrayOffset() + segment.position();
                    System.arraycopy(segment.array(), offset, merged, position, length);
                } else {
                    ByteBuffer duplicate = segment.duplicate();
                    duplicate.get(merged, position, length);
                }
                position += length;
            }
            return merged;
        }

        private void restoreGatherWriteLimits(int count) {
            for (int i = 0; i < count; i++) {
                ByteBuffer original = gatherWriteOriginals[i];
                if (original != null) {
                    original.limit(gatherWriteOriginalLimits[i]);
                }
            }
        }

        private void consume(int bytesConsumed) {
            int remaining = bufferedBytes - bytesConsumed;
            if (remaining > 0) {
                System.arraycopy(buffer, bytesConsumed, buffer, 0, remaining);
            }
            bufferedBytes = remaining;
            if (state == State.IDLE) {
                transitionTo(State.READING);
            }
        }

        private boolean hasPendingWrite() {
            return (pendingWrites != null && !pendingWrites.isEmpty()) || pendingFileRemaining > 0;
        }

        private boolean shouldCloseAfterWrite() {
            return closeAfterWrite;
        }

        private boolean isExecuting() {
            return inFlightRequests > 0;
        }

        private boolean canProcessBufferedRequest() {
            return !webSocketEnabled
                    && !sseStreamingEnabled
                    && state != State.CLOSING
                    && !shouldCloseAfterWrite()
                    && inFlightRequests < 16;
        }

        private int inFlightRequestCount() {
            return inFlightRequests;
        }

        private long nextResponseSequence() {
            return nextResponseSequence;
        }

        private boolean hasBufferedCompletions() {
            return completedResponses != null && !completedResponses.isEmpty();
        }

        private long nextRequestSequence() {
            return nextRequestSequence;
        }

        private long reserveRequestSequence() {
            return nextRequestSequence++;
        }

        private void releaseReservedRequestSequence() {
            if (nextRequestSequence > nextResponseSequence) {
                nextRequestSequence--;
            }
        }

        private void enqueueCompletedResponse(NioCompletion completion) {
            completedResponses().put(completion.sequence(), completion);
        }

        private NioCompletion pollNextOrderedResponse() {
            if (completedResponses == null) {
                return null;
            }
            NioCompletion response = completedResponses.remove(nextResponseSequence);
            if (response != null) {
                nextResponseSequence++;
            }
            return response;
        }

        private void markRequestReady() {
            transitionTo(State.REQUEST_READY);
        }

        private void markExecuting() {
            inFlightRequests++;
            transitionTo(State.EXECUTING);
        }

        private void markResponseReady() {
            if (inFlightRequests > 0) {
                inFlightRequests--;
            }
            if (inFlightRequests > 0) {
                transitionTo(State.EXECUTING);
            } else {
                transitionTo(State.RESPONSE_READY);
            }
        }

        private void markIdle() {
            if (state != State.IDLE) {
                transitionTo(State.IDLE);
                writeStartedAtMillis = 0;
            }
        }

        private void markClosing() {
            if (state != State.CLOSING) {
                transitionTo(State.CLOSING);
                writeStartedAtMillis = 0;
            }
        }

        private boolean hasExceededWriteTimeout(long nowMillis, int writeTimeoutMillis) {
            if (writeTimeoutMillis <= 0 || !hasPendingWrite() || writeStartedAtMillis == 0) {
                return false;
            }
            return nowMillis - writeStartedAtMillis > writeTimeoutMillis;
        }

        private long writeElapsedMillis(long nowMillis) {
            if (writeStartedAtMillis == 0) {
                return 0;
            }
            return Math.max(0, nowMillis - writeStartedAtMillis);
        }

        private long pendingWriteBytes() {
            long total = pendingFileRemaining;
            if (pendingWrites != null) {
                for (ByteBuffer pendingWrite : pendingWrites) {
                    total += pendingWrite.remaining();
                }
            }
            return total;
        }

        private void markContinueSent() {
            continueSentForCurrentRequest = true;
        }

        private void resetContinueSent() {
            continueSentForCurrentRequest = false;
        }

        private boolean continueSentForCurrentRequest() {
            return continueSentForCurrentRequest;
        }

        private void enableWebSocket(WebSocketEndpointBinding endpointBinding, boolean compressionEnabled) {
            webSocketEnabled = true;
            webSocketCompressionEnabled = compressionEnabled;
            webSocketEndpointBinding = endpointBinding;
            websocketFragmentOpcode = -1;
            websocketFragmentBuffer = null;
            inFlightRequests = 0;
        }

        private WebSocketEndpointBinding webSocketEndpointBinding() {
            return webSocketEndpointBinding;
        }

        private boolean isWebSocketEnabled() {
            return webSocketEnabled;
        }

        private boolean webSocketCompressionEnabled() {
            return webSocketCompressionEnabled;
        }

        private void enableSseStreaming() {
            sseStreamingEnabled = true;
            consume(bufferedBytes);
        }

        private void disableSseStreaming() {
            sseStreamingEnabled = false;
        }

        private boolean isSseStreamingEnabled() {
            return sseStreamingEnabled;
        }

        private boolean websocketCloseSent() {
            return webSocketCloseSent;
        }

        private void markWebSocketCloseSent() {
            webSocketCloseSent = true;
        }

        private boolean hasWebSocketFragment() {
            return websocketFragmentOpcode != -1;
        }

        private int websocketFragmentOpcode() {
            return websocketFragmentOpcode;
        }

        private void beginWebSocketFragment(int opcode, byte[] payload) {
            websocketFragmentOpcode = opcode;
            websocketFragmentBuffer = payload == null ? new byte[0] : Arrays.copyOf(payload, payload.length);
        }

        private void appendWebSocketFragment(byte[] payload) {
            if (websocketFragmentBuffer == null) {
                websocketFragmentBuffer = new byte[0];
            }
            byte[] appendPayload = payload == null ? new byte[0] : payload;
            byte[] combined = new byte[websocketFragmentBuffer.length + appendPayload.length];
            System.arraycopy(websocketFragmentBuffer, 0, combined, 0, websocketFragmentBuffer.length);
            System.arraycopy(appendPayload, 0, combined, websocketFragmentBuffer.length, appendPayload.length);
            websocketFragmentBuffer = combined;
        }

        private byte[] consumeWebSocketFragment() {
            byte[] combined = websocketFragmentBuffer == null ? new byte[0]
                    : Arrays.copyOf(websocketFragmentBuffer, websocketFragmentBuffer.length);
            websocketFragmentOpcode = -1;
            websocketFragmentBuffer = null;
            return combined;
        }

        private void transitionTo(State nextState) {
            if (!isValidTransition(nextState)) {
                throw new IllegalStateException("Invalid state transition from " + state + " to " + nextState);
            }
            state = nextState;
        }

        private boolean isValidTransition(State nextState) {
            if (state == State.CLOSING) {
                return nextState == State.CLOSING;
            }
            return true;
        }

        private void touch() {
            lastActivityTimeMillis = System.currentTimeMillis();
        }

        private long lastActivityTimeMillis() {
            return lastActivityTimeMillis;
        }

        private byte[] buffer() {
            return buffer;
        }

        private int bufferedBytes() {
            return bufferedBytes;
        }

        private String remoteAddress() throws IOException {
            if (cachedRemoteAddress != null) {
                return cachedRemoteAddress;
            }
            InetSocketAddress remote = (InetSocketAddress) channel.getRemoteAddress();
            if (remote == null || remote.getAddress() == null) {
                cachedRemoteAddress = "unavailable";
            } else {
                cachedRemoteAddress = remote.getAddress().getHostAddress();
            }
            return cachedRemoteAddress;
        }

        private int remotePort() throws IOException {
            if (cachedRemotePort >= 0) {
                return cachedRemotePort;
            }
            InetSocketAddress remote = (InetSocketAddress) channel.getRemoteAddress();
            cachedRemotePort = remote == null ? -1 : remote.getPort();
            return cachedRemotePort;
        }

        private int localPort() throws IOException {
            return ((InetSocketAddress) channel.getLocalAddress()).getPort();
        }

        private boolean isTlsEnabled() {
            return tlsHandler != null;
        }

        private String remoteAddressSafely() {
            try {
                int port = remotePort();
                return port >= 0 ? remoteAddress() + ":" + port : remoteAddress();
            } catch (IOException exception) {
                return "unavailable";
            }
        }

        private void clearPendingFileTransfer() throws IOException {
            pendingFilePath = null;
            pendingFilePosition = 0;
            pendingFileRemaining = 0;
            if (pendingFileChannel != null) {
                pendingFileChannel.close();
                pendingFileChannel = null;
            }
        }

        private void closeResources() throws IOException {
            clearPendingFileTransfer();
            if (completedResponses != null) {
                completedResponses.clear();
            }
            if (http2Streams != null) {
                http2Streams.clear();
            }
            if (queuedHttp2DataByStream != null) {
                queuedHttp2DataByStream.clear();
            }
            if (queuedHttp2StreamOrder != null) {
                queuedHttp2StreamOrder.clear();
            }
            if (queuedHttp2StreamSet != null) {
                queuedHttp2StreamSet.clear();
            }
            if (tlsHandler != null) {
                tlsHandler.close();
            }
        }
    }

    private static final class Http2StreamState {

        private final int streamId;
        private final java.io.ByteArrayOutputStream body;
        private final java.io.ByteArrayOutputStream headerBlock;
        private Map<String, String> headers;
        private boolean dispatched;
        private int receiveWindow;
        private int sendWindow;
        private boolean remoteClosed;
        private boolean endStreamAfterHeaders;

        private Http2StreamState(int streamId, int initialSendWindow) {
            this.streamId = streamId;
            this.body = new java.io.ByteArrayOutputStream();
            this.headerBlock = new java.io.ByteArrayOutputStream();
            this.headers = new HashMap<>();
            this.dispatched = false;
            this.receiveWindow = HTTP2_DEFAULT_INITIAL_WINDOW_SIZE;
            this.sendWindow = Math.max(0, initialSendWindow);
            this.remoteClosed = false;
            this.endStreamAfterHeaders = false;
        }

        private int streamId() {
            return streamId;
        }

        private void setHeaders(Map<String, String> headers) {
            this.headers = headers == null ? new HashMap<>() : new HashMap<>(headers);
        }

        private void resetHeaderBlock() {
            headerBlock.reset();
        }

        private boolean appendHeaderBlock(byte[] fragment, int maxHeaderListSize) {
            byte[] append = fragment == null ? new byte[0] : fragment;
            if ((long) headerBlock.size() + append.length > maxHeaderListSize) {
                return false;
            }
            headerBlock.write(append, 0, append.length);
            return true;
        }

        private byte[] headerBlock() {
            return headerBlock.toByteArray();
        }

        private void clearHeaderBlock() {
            headerBlock.reset();
        }

        private Map<String, String> headers() {
            return headers;
        }

        private boolean appendBody(byte[] chunk, int maxBodyBytes) {
            if (chunk == null || chunk.length == 0) {
                return true;
            }
            if ((long) body.size() + chunk.length > maxBodyBytes) {
                return false;
            }
            body.write(chunk, 0, chunk.length);
            return true;
        }

        private byte[] body() {
            return body.toByteArray();
        }

        private boolean dispatched() {
            return dispatched;
        }

        private void markDispatched() {
            this.dispatched = true;
        }

        private int receiveWindow() {
            return receiveWindow;
        }

        private boolean consumeReceiveWindow(int amount) {
            if (amount < 0 || amount > receiveWindow) {
                return false;
            }
            receiveWindow -= amount;
            return true;
        }

        private int restoreReceiveWindow(int amount) {
            long next = (long) receiveWindow + amount;
            if (next > Integer.MAX_VALUE) {
                receiveWindow = Integer.MAX_VALUE;
                return Integer.MAX_VALUE;
            }
            receiveWindow = (int) next;
            return amount;
        }

        private boolean incrementSendWindow(int increment) {
            long next = (long) sendWindow + increment;
            if (next > Integer.MAX_VALUE) {
                return false;
            }
            sendWindow = (int) next;
            return true;
        }

        private boolean tryConsumeSendWindow(int amount) {
            if (amount < 0 || amount > sendWindow) {
                return false;
            }
            sendWindow -= amount;
            return true;
        }

        private void adjustSendWindow(int delta) {
            long next = (long) sendWindow + delta;
            if (next < 0) {
                sendWindow = 0;
            } else if (next > Integer.MAX_VALUE) {
                sendWindow = Integer.MAX_VALUE;
            } else {
                sendWindow = (int) next;
            }
        }

        private void markRemoteOpen() {
            remoteClosed = false;
        }

        private void markRemoteClosed() {
            remoteClosed = true;
        }

        private boolean remoteClosed() {
            return remoteClosed;
        }

        private void setEndStreamAfterHeaders(boolean endStreamAfterHeaders) {
            this.endStreamAfterHeaders = endStreamAfterHeaders;
        }

        private boolean endStreamAfterHeaders() {
            return endStreamAfterHeaders;
        }
    }

    private record Http2QueuedData(int streamId, byte[] payload, int flags) {

    }

    private record Http2ResponseParts(int statusCode, Map<String, String> headers, java.io.ByteArrayOutputStream body) {

    }

    static record NioCompletion(
            SelectionKey key,
            long sequence,
            HttpExecutionResult executionResult,
            boolean closeAfterWrite) {

        private static NioCompletion success(SelectionKey key, long sequence, HttpExecutionResult executionResult) {
            return new NioCompletion(key, sequence, executionResult, !executionResult.keepAlive());
        }

        private static NioCompletion failure(
                SelectionKey key,
                long sequence,
                byte[] responseBytes,
                boolean closeAfterWrite) {
            int statusCode = parseStatusCode(responseBytes);
            return new NioCompletion(
                    key,
                    sequence,
                    new HttpExecutionResult(new byte[][]{responseBytes}, null, !closeAfterWrite, statusCode,
                    responseBytes == null ? 0 : responseBytes.length, false, null),
                    closeAfterWrite);
        }

        private static int parseStatusCode(byte[] responseBytes) {
            if (responseBytes == null || responseBytes.length < 12) {
                return 500;
            }
            int firstSpace = -1;
            for (int i = 0; i < responseBytes.length; i++) {
                if (responseBytes[i] == ' ') {
                    firstSpace = i;
                    break;
                }
            }
            if (firstSpace <= 0 || firstSpace + 4 > responseBytes.length) {
                return 500;
            }
            try {
                int hundreds = responseBytes[firstSpace + 1] - '0';
                int tens = responseBytes[firstSpace + 2] - '0';
                int ones = responseBytes[firstSpace + 3] - '0';
                if (hundreds < 0 || hundreds > 9 || tens < 0 || tens > 9 || ones < 0 || ones > 9) {
                    return 500;
                }
                return hundreds * 100 + tens * 10 + ones;
            } catch (RuntimeException ignored) {
                return 500;
            }
        }
    }

    // ===== Multi-Selector Architecture Support Methods =====
    Selector getSelectorForConnection() {
        if (selectorGroup != null) {
            return selectorGroup.getNextSelectorForConnection();
        }
        return selector;
    }

    boolean hasPendingWork() {
        return !completionQueue.isEmpty() || !selectorTasks.isEmpty() || !pendingTlsRegistrations.isEmpty();
    }

    void registerConnectionForWriting(SelectionKey key) {
        if (key == null || !key.isValid()) {
            return;
        }
        Selector targetSelector = key.selector();
        if (selectorGroup != null) {
            selectorGroup.requestSelectorWakeup(targetSelector);
            return;
        }
        if (selector != null && targetSelector != selector) {
            targetSelector.wakeup();
        }
    }
}
