package com.fastjava.server;

import com.fastjava.server.config.ServerConfig;
import com.fastjava.server.config.ServerConfigLoader;
import com.fastjava.servlet.Filter;
import com.fastjava.servlet.ServletException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * Main FastJava SIMD Web Server.
 * Listens for HTTP connections and dispatches to servlet processing.
 * 
 * Design principles:
 * - Minimal indirection
 * - Pre-allocated thread pool
 * - Direct socket handling
 * - SIMD optimizations in parsing
 */
public class FastJavaServer {

    private static final Logger logger = LoggerFactory.getLogger(FastJavaServer.class);
    private static final int WORKER_QUEUE_CAPACITY = 1000;

    private static final boolean VIRTUAL_THREADS = Boolean.getBoolean("fastjava.virtual.threads.enabled");

    private final int port;
    private final int threadPoolSize;
    private final RequestLimits requestLimits;
    private final ServletRouter router;
    private final ExecutorService threadPool;
    private final ExecutorService asyncDispatchExecutor;
    private final AtomicInteger liveAcceptedConnections;
    private final CountDownLatch stopLatch;
    private ServerSocket serverSocket;
    private volatile boolean running = false;

    public FastJavaServer(int port, int threadPoolSize, int maxRequestSize) {
        this(port, threadPoolSize, RequestLimits.defaults(maxRequestSize));
    }

    public FastJavaServer(int port, int threadPoolSize, RequestLimits requestLimits) {
        this.port = port;
        this.threadPoolSize = threadPoolSize;
        this.requestLimits = requestLimits;
        this.router = new ServletRouter();
        this.stopLatch = new CountDownLatch(1);
        this.liveAcceptedConnections = new AtomicInteger();
        this.threadPool = VIRTUAL_THREADS
                ? Executors.newVirtualThreadPerTaskExecutor()
                : new ThreadPoolExecutor(
                        threadPoolSize,
                        threadPoolSize,
                        60, TimeUnit.SECONDS,
                        new LinkedBlockingQueue<>(WORKER_QUEUE_CAPACITY),
                        r -> {
                            Thread t = new Thread(r, "FastJava-Worker");
                            t.setDaemon(false);
                            return t;
                        });
        this.asyncDispatchExecutor = VIRTUAL_THREADS
                ? Executors.newVirtualThreadPerTaskExecutor()
                : Executors.newCachedThreadPool(r -> {
                    Thread t = new Thread(r, "FastJava-Async-Dispatch");
                    t.setDaemon(true);
                    return t;
                });
    }

    /**
     * Register servlet for exact path.
     */
    public void addServlet(String path, com.fastjava.servlet.HttpServlet servlet) throws ServletException {
        router.addServlet(path, servlet);
    }

    /**
     * Register servlet with pattern.
     */
    public void addServletPattern(String pattern, com.fastjava.servlet.HttpServlet servlet) throws ServletException {
        router.addServletPattern(pattern, servlet);
    }

    /**
     * Register filter for all paths.
     */
    public void addFilter(Filter filter) throws ServletException {
        router.addFilter(filter);
    }

    /**
     * Register filter for exact path.
     */
    public void addFilter(String path, Filter filter) throws ServletException {
        router.addFilter(path, filter);
    }

    /**
     * Register filter with wildcard pattern semantics.
     */
    public void addFilterPattern(String pattern, Filter filter) throws ServletException {
        router.addFilterPattern(pattern, filter);
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

    /**
     * Start server and listen for connections.
     */
    public void start() throws IOException, ServletException {
        router.initialize();
        serverSocket = new ServerSocket(port);
        running = true;
        logger.info("FastJava Server started on port {} with {} threads", port, threadPoolSize);

        // Accept loop
        Thread acceptThread = new Thread(() -> {
            while (running) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    int queueDepth = threadPool instanceof ThreadPoolExecutor tpe ? tpe.getQueue().size() : 0;
                    ServerObservability.recordHandlerQueueDepth(queueDepth);

                    if (!reserveConnectionSlot()) {
                        ServerObservability.recordRejectedConnection();
                        sendServiceUnavailableAndClose(clientSocket);
                        continue;
                    }

                    BooleanSupplier shouldPreemptKeepAlive = () -> {
                        int threshold = requestLimits.keepAlivePressureQueueThreshold();
                        int depth = threadPool instanceof ThreadPoolExecutor tpe ? tpe.getQueue().size() : 0;
                        return threshold == 0 || depth >= threshold;
                    };

                    // Submit request handler to thread pool
                    ClientRequestHandler handler = new ClientRequestHandler(
                            clientSocket,
                            router,
                            requestLimits,
                            shouldPreemptKeepAlive,
                            asyncDispatchExecutor);
                    try {
                        threadPool.execute(() -> {
                            try {
                                handler.run();
                            } finally {
                                liveAcceptedConnections.updateAndGet(current -> Math.max(0, current - 1));
                            }
                        });
                        ServerObservability.recordHandlerQueueDepth(
                                threadPool instanceof ThreadPoolExecutor tpe ? tpe.getQueue().size() : 0);
                    } catch (RejectedExecutionException exception) {
                        liveAcceptedConnections.updateAndGet(current -> Math.max(0, current - 1));
                        ServerObservability.recordRejectedConnection();
                        ServerObservability.recordHandlerQueueDepth(
                                threadPool instanceof ThreadPoolExecutor tpe ? tpe.getQueue().size() : 0);
                        try {
                            sendServiceUnavailableAndClose(clientSocket);
                        } catch (IOException closeException) {
                            logger.debug("Error closing rejected client socket", closeException);
                        }
                        if (running) {
                            logger.error("Request handler rejected while server is running", exception);
                        }
                    }
                } catch (IOException e) {
                    if (running) {
                        logger.error("Accept error", e);
                    }
                }
            }
        }, "FastJava-Accept");

        acceptThread.setDaemon(false);
        acceptThread.start();
    }

    private boolean reserveConnectionSlot() {
        int maxConcurrentConnections = requestLimits.maxConcurrentConnections();
        while (true) {
            int current = liveAcceptedConnections.get();
            if (current >= maxConcurrentConnections) {
                return false;
            }
            if (liveAcceptedConnections.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    private void sendServiceUnavailableAndClose(Socket clientSocket) throws IOException {
        try (OutputStream output = clientSocket.getOutputStream()) {
            output.write(SimpleHttpResponses.plainText(503, "Service Unavailable"));
            output.flush();
        } finally {
            clientSocket.close();
        }
    }

    /**
     * Stop server.
     */
    public void stop() {
        running = false;
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (IOException e) {
            logger.error("Error closing server socket", e);
        }
        threadPool.shutdown();
        asyncDispatchExecutor.shutdown();
        try {
            if (!threadPool.awaitTermination(10, TimeUnit.SECONDS)) {
                threadPool.shutdownNow();
            }
            if (!asyncDispatchExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                asyncDispatchExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            threadPool.shutdownNow();
            asyncDispatchExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        try {
            router.destroy();
        } catch (RuntimeException exception) {
            logger.error("Error destroying servlet container", exception);
        }
        stopLatch.countDown();
        logger.info("FastJava Server stopped");
    }

    /**
     * Wait for server to stop.
     */
    public void waitForStop() throws InterruptedException {
        stopLatch.await();
    }

    public int getBoundPort() {
        if (serverSocket == null) {
            throw new IllegalStateException("Server has not been started");
        }
        return serverSocket.getLocalPort();
    }

    public static void main(String[] args) throws IOException, InterruptedException, ServletException {
        ServerConfig loadedConfig = loadStartupConfig(args);
        int port = args.length > 0 ? Integer.parseInt(args[0]) : loadedConfig.port();
        int threads = args.length > 1 ? Integer.parseInt(args[1]) : loadedConfig.threads();

        FastJavaServer server = new FastJavaServer(port, threads, loadedConfig.requestLimits());

        logger.info("Effective configuration: port={}, threads={}, maxRequestSize={}, keepAliveTimeoutMillis={}",
                port,
                threads,
                loadedConfig.requestLimits().maxRequestSize(),
                loadedConfig.requestLimits().keepAliveTimeoutMillis());

        // Register example servlets
        server.addServlet("/", new com.fastjava.examples.HelloWorldServlet());
        server.addServlet("/api/hello", new com.fastjava.examples.ApiServlet());
        server.addServletPattern("/static/*", new com.fastjava.examples.StaticFileServlet());

        server.start();

        // Keep running until interrupted
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
        server.waitForStop();
    }

    private static ServerConfig loadStartupConfig(String[] args) throws IOException {
        String configPath = null;
        if (args.length > 2) {
            configPath = args[2];
        }

        if (configPath == null || configPath.isBlank()) {
            configPath = System.getProperty("fastjava.config");
        }

        if (configPath == null || configPath.isBlank()) {
            return ServerConfig.defaults();
        }

        return ServerConfigLoader.load(Path.of(configPath));
    }
}
