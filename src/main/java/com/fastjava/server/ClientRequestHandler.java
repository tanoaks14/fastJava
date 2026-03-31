package com.fastjava.server;

import com.fastjava.http.parser.HttpRequestParser;
import com.fastjava.http.parser.ParsedHttpRequest;
import com.fastjava.http.simd.SIMDByteScanner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.*;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import com.fastjava.websocket.WebSocketBlockingHandler;
import com.fastjava.websocket.WebSocketEndpointBinding;
import com.fastjava.websocket.WebSocketEndpointMatch;
import com.fastjava.websocket.WebSocketEndpointMetadata;
import com.fastjava.websocket.WebSocketExtensions;
import com.fastjava.websocket.WebSocketFrame;
import com.fastjava.websocket.WebSocketFrameCodec;
import com.fastjava.websocket.WebSocketHandshake;
import com.fastjava.websocket.WebSocketSession;
import com.fastjava.sse.BlockingSseEmitter;

/**
 * Handles individual HTTP request/response cycles.
 * Processes one request at a time with minimal allocations.
 */
public class ClientRequestHandler implements Runnable {

    private static final Logger logger = LoggerFactory.getLogger(ClientRequestHandler.class);
    private static final byte[] FAKE_EMPTY_CHUNK = "0\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
    private static final int INITIAL_RECEIVE_BUFFER_BYTES = 8 * 1024;
    private static final AtomicLong writeTimeoutCloseCount = new AtomicLong();
    private static final ScheduledExecutorService writeTimeoutScheduler = Executors
            .newSingleThreadScheduledExecutor(new ThreadFactory() {
                @Override
                public Thread newThread(Runnable runnable) {
                    Thread thread = new Thread(runnable, "FastJava-WriteTimeout-Guard");
                    thread.setDaemon(true);
                    return thread;
                }
            });

    private final Socket socket;
    private final ServletRouter router;
    private byte[] receiveBuffer;
    private final RequestLimits requestLimits;
    private final BooleanSupplier keepAlivePreemptionPolicy;
    private final Executor asyncDispatchExecutor;

    public ClientRequestHandler(
            Socket socket,
            ServletRouter router,
            RequestLimits requestLimits) {
        this(socket, router, requestLimits, () -> false, Runnable::run);
    }

    public ClientRequestHandler(
            Socket socket,
            ServletRouter router,
            RequestLimits requestLimits,
            BooleanSupplier keepAlivePreemptionPolicy) {
        this(socket, router, requestLimits, keepAlivePreemptionPolicy, Runnable::run);
    }

    public ClientRequestHandler(
            Socket socket,
            ServletRouter router,
            RequestLimits requestLimits,
            BooleanSupplier keepAlivePreemptionPolicy,
            Executor asyncDispatchExecutor) {
        this.socket = socket;
        this.router = router;
        this.requestLimits = requestLimits;
        this.receiveBuffer = new byte[Math.min(INITIAL_RECEIVE_BUFFER_BYTES, requestLimits.maxRequestSize())];
        this.keepAlivePreemptionPolicy = keepAlivePreemptionPolicy;
        this.asyncDispatchExecutor = asyncDispatchExecutor;
    }

    @Override
    public void run() {
        ServerObservability.connectionOpened();
        try {
            handleConnection();
        } catch (IOException e) {
            logger.debug("Connection error: {}", e.getMessage());
        } finally {
            closeSocket();
            ServerObservability.connectionClosed();
        }
    }

    private void handleConnection() throws IOException {
        socket.setSoTimeout(requestLimits.keepAliveTimeoutMillis());

        try (InputStream input = socket.getInputStream();
                OutputStream output = socket.getOutputStream()) {

            int bufferedBytes = 0;
            while (true) {
                RequestReadResult requestReadResult = readNextRequest(input, output, bufferedBytes);

                if (requestReadResult.errorStatusCode != 0) {
                    sendSimpleResponse(output, requestReadResult.errorStatusCode, requestReadResult.errorMessage);
                    return;
                }

                ParsedHttpRequest parsed = requestReadResult.parsed;

                if (parsed == null) {
                    if (bufferedBytes > 0) {
                        sendBadRequest(output);
                    }
                    return;
                }

                ServerObservability.recordRequestBytesReceived(parsed.bytesConsumed);

                // Check for WebSocket upgrade request
                if (HttpRequestInspector.isWebSocketUpgradeAttempt(parsed)) {
                    String websocketKey = HttpRequestInspector.extractWebSocketKeyIfValid(parsed);
                    if (websocketKey != null) {
                        String requestPath = extractRequestPath(parsed.getURI());
                        WebSocketEndpointMatch endpointMatch = router.resolveWebSocketEndpoint(requestPath);
                        WebSocketEndpointMetadata endpointMetadata = endpointMatch == null
                                ? null
                                : endpointMatch.metadata();
                        String negotiatedSubprotocol = negotiateSubprotocol(
                                parsed.getHeader("Sec-WebSocket-Protocol"),
                                endpointMetadata);
                        boolean perMessageDeflateEnabled = negotiatePerMessageDeflate(
                                parsed.getHeader("Sec-WebSocket-Extensions"));
                        String negotiatedExtensions = WebSocketExtensions
                                .perMessageDeflateResponseHeader(perMessageDeflateEnabled);
                        // Valid WebSocket upgrade request
                        byte[] upgradeResponse = WebSocketHandshake.buildSwitchingProtocolsResponse(
                                websocketKey,
                                negotiatedSubprotocol,
                                negotiatedExtensions);
                        output.write(upgradeResponse);
                        output.flush();
                        parsed.drainBodyStream();
                        handleWebSocketConnection(
                                input,
                                output,
                                endpointMetadata,
                                endpointMatch == null ? Map.of() : endpointMatch.pathParams(),
                                negotiatedSubprotocol,
                                perMessageDeflateEnabled);
                        return;
                    } else {
                        // Invalid WebSocket upgrade attempt
                        sendBadRequest(output);
                        return;
                    }
                }
                int remainingBytes = requestReadResult.totalBytes - parsed.bytesConsumed;
                if (remainingBytes > 0) {
                    System.arraycopy(receiveBuffer, parsed.bytesConsumed, receiveBuffer, 0, remainingBytes);
                }
                bufferedBytes = remainingBytes;

                boolean forceCloseConnection = keepAlivePreemptionPolicy.getAsBoolean();
                if (forceCloseConnection && HttpRequestInspector.shouldKeepAlive(parsed)
                        && !parsed.closeAfterResponse()) {
                    ServerObservability.recordKeepAlivePreemption();
                }

                CompletableFuture<HttpExecutionResult> deferredResult = new CompletableFuture<>();
                BlockingSseEmitter blockingSseEmitter = new BlockingSseEmitter(output);
                HttpExecutionResult executionResult = SseRuntime.callWithEmitter(
                        blockingSseEmitter,
                        () -> HttpRequestExecutor.execute(
                                parsed,
                                router,
                                socket.getInetAddress().getHostAddress(),
                                socket.getPort(),
                                socket.getLocalPort(),
                                requestLimits,
                                false,
                                forceCloseConnection,
                                new HttpRequestExecutor.AsyncResponseHandler() {
                                    @Override
                                    public void onComplete(HttpExecutionResult result) {
                                        deferredResult.complete(result);
                                    }

                                    @Override
                                    public ScheduledFuture<?> scheduleTimeout(Runnable task, long timeoutMillis) {
                                        return writeTimeoutScheduler.schedule(task, timeoutMillis,
                                                TimeUnit.MILLISECONDS);
                                    }

                                    @Override
                                    public void executeAsync(Runnable task) {
                                        try {
                                            asyncDispatchExecutor.execute(task);
                                        } catch (RejectedExecutionException rejectedExecutionException) {
                                            deferredResult.complete(new HttpExecutionResult(
                                                    new byte[][] {
                                                            SimpleHttpResponses.plainText(503,
                                                                    "Server shutting down") },
                                                    null,
                                                    false,
                                                    503,
                                                    0,
                                                    false,
                                                    null));
                                        }
                                    }
                                }));

                HttpExecutionResult resolvedResult = executionResult;
                if (resolvedResult == null) {
                    resolvedResult = awaitDeferredResult(deferredResult);
                    if (resolvedResult == null) {
                        return;
                    }
                }
                final HttpExecutionResult responseResult = resolvedResult;

                try (WriteTimeoutGuard writeTimeoutGuard = WriteTimeoutGuard.start(socket,
                        requestLimits.writeTimeoutMillis())) {
                    try (RequestTracing.SpanScope ignored = RequestTracing.startChildSpan("http.write")) {
                        for (ByteBuffer segment : responseResult.responseBuffers()) {
                            if (segment != null && segment.hasRemaining()) {
                                int length = segment.remaining();
                                if (segment.hasArray()) {
                                    int offset = segment.arrayOffset() + segment.position();
                                    byte[] source = segment.array();
                                    writeTimeoutGuard.execute(() -> output.write(source, offset, length));
                                } else {
                                    ByteBuffer duplicate = segment.duplicate();
                                    byte[] copy = new byte[length];
                                    duplicate.get(copy);
                                    writeTimeoutGuard.execute(() -> output.write(copy));
                                }
                            }
                        }
                        if (responseResult.fileBody() != null && responseResult.fileBody().length() > 0) {
                            try (InputStream fileInput = Files.newInputStream(responseResult.fileBody().path())) {
                                writeTimeoutGuard.execute(() -> {
                                    fileInput.skipNBytes(responseResult.fileBody().offset());
                                    byte[] copyBuffer = new byte[8192];
                                    long remaining = responseResult.fileBody().length();
                                    while (remaining > 0) {
                                        int read = fileInput.read(copyBuffer, 0,
                                                (int) Math.min(copyBuffer.length, remaining));
                                        if (read == -1) {
                                            break;
                                        }
                                        output.write(copyBuffer, 0, read);
                                        remaining -= read;
                                    }
                                });
                            }
                        }
                        writeTimeoutGuard.execute(output::flush);
                    }
                } catch (SocketTimeoutException timeoutException) {
                    logger.warn(
                            "Closing slow client after write timeout (remote={}, elapsedMillis={}, pendingRequestBytes={})",
                            socket.getRemoteSocketAddress(),
                            requestLimits.writeTimeoutMillis(),
                            bufferedBytes);
                    ServerObservability.recordWriteTimeout();
                    return;
                }

                if (responseResult.sseStream()) {
                    if (responseResult.sseEmitter() instanceof BlockingSseEmitter sseEmitter) {
                        sseEmitter.markReady();
                    }
                    try {
                        blockingSseEmitter.awaitClosed();
                    } catch (InterruptedException interruptedException) {
                        Thread.currentThread().interrupt();
                    }
                    return;
                }

                try {
                    parsed.drainBodyStream();
                } catch (UncheckedIOException streamError) {
                    logger.debug("Error draining request body stream: {}", streamError.getMessage());
                    return;
                }

                if (!responseResult.keepAlive()) {
                    return;
                }
            }
        }
    }

    private HttpExecutionResult awaitDeferredResult(CompletableFuture<HttpExecutionResult> deferredResult) {
        try {
            return deferredResult.get();
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException executionException) {
            logger.debug("Async request completion failed: {}", executionException.getMessage());
            byte[] response = SimpleHttpResponses.plainText(500, "Internal Server Error");
            return new HttpExecutionResult(new byte[][] { response }, null, false, 500, response.length, false,
                    null);
        }
    }

    private RequestReadResult readNextRequest(InputStream input, OutputStream output, int bufferedBytes)
            throws IOException {
        int totalBytes = bufferedBytes;
        boolean sentContinue = false;
        while (true) {
            RequestValidationResult validation = HttpRequestInspector.validateBufferedRequest(
                    receiveBuffer,
                    totalBytes,
                    requestLimits);
            if (validation.hasError()) {
                return RequestReadResult.error(validation.statusCode(), validation.message(), totalBytes);
            }

            int expectationStatus = HttpRequestInspector.expectationStatusCode(receiveBuffer, totalBytes);
            if (expectationStatus != 0) {
                return RequestReadResult.error(expectationStatus, "Expectation Failed", totalBytes);
            }

            ParsedHttpRequest parsed;
            try {
                try (RequestTracing.SpanScope ignored = RequestTracing.startChildSpan("http.parse")) {
                    parsed = HttpRequestParser.parse(receiveBuffer, totalBytes);
                }
            } catch (IllegalArgumentException malformedRequest) {
                return RequestReadResult.error(400, "Bad Request", totalBytes);
            }
            if (parsed != null) {
                return RequestReadResult.success(parsed, totalBytes);
            }

            ParsedHttpRequest streamingChunkedRequest;
            try {
                streamingChunkedRequest = tryBuildStreamingChunkedRequest(input, totalBytes);
            } catch (IllegalArgumentException malformedRequest) {
                return RequestReadResult.error(400, "Bad Request", totalBytes);
            }
            if (streamingChunkedRequest != null) {
                return RequestReadResult.success(streamingChunkedRequest, totalBytes);
            }

            if (!sentContinue && HttpRequestInspector.shouldSendContinue(receiveBuffer, totalBytes, requestLimits)) {
                output.write(SimpleHttpResponses.provisionalContinue());
                output.flush();
                sentContinue = true;
            }

            if (totalBytes == receiveBuffer.length) {
                if (!growReceiveBuffer(totalBytes + 1)) {
                    return RequestReadResult.error(413, "Payload Too Large", totalBytes);
                }
            }

            try {
                int readTimeoutMillis = totalBytes == 0
                        ? requestLimits.keepAliveTimeoutMillis()
                        : requestLimits.readTimeoutMillis();
                socket.setSoTimeout(readTimeoutMillis);
                int bytesReadable = Math.min(receiveBuffer.length - totalBytes,
                        requestLimits.maxRequestSize() - totalBytes);
                int bytesRead = input.read(receiveBuffer, totalBytes, bytesReadable);
                if (bytesRead == -1) {
                    return RequestReadResult.success(null, totalBytes);
                }
                totalBytes += bytesRead;
            } catch (SocketTimeoutException timeoutException) {
                if (totalBytes > 0) {
                    return RequestReadResult.error(408, "Request Timeout", totalBytes);
                }
                throw timeoutException;
            }
        }
    }

    private boolean growReceiveBuffer(int requiredCapacity) {
        int maxCapacity = requestLimits.maxRequestSize();
        if (requiredCapacity > maxCapacity || receiveBuffer.length >= maxCapacity) {
            return false;
        }
        int newCapacity = receiveBuffer.length;
        while (newCapacity < requiredCapacity && newCapacity < maxCapacity) {
            newCapacity = Math.min(maxCapacity, newCapacity << 1);
        }
        if (newCapacity < requiredCapacity) {
            return false;
        }
        receiveBuffer = Arrays.copyOf(receiveBuffer, newCapacity);
        return true;
    }

    private ParsedHttpRequest tryBuildStreamingChunkedRequest(InputStream input, int totalBytes) {
        int headerEnd = SIMDByteScanner.findDoubleCRLF(receiveBuffer, 0, totalBytes);
        if (headerEnd == -1) {
            return null;
        }

        int bodyStart = headerEnd + 4;
        int syntheticLength = bodyStart + FAKE_EMPTY_CHUNK.length;
        byte[] syntheticRequest = Arrays.copyOf(receiveBuffer, syntheticLength);
        System.arraycopy(FAKE_EMPTY_CHUNK, 0, syntheticRequest, bodyStart, FAKE_EMPTY_CHUNK.length);

        ParsedHttpRequest parsed = HttpRequestParser.parse(syntheticRequest, syntheticLength);
        if (parsed == null || !parsed.chunkedBody) {
            return null;
        }

        InputStream liveBodyStream = new LiveChunkedRequestBodyInputStream(
                receiveBuffer,
                bodyStart,
                Math.max(0, totalBytes - bodyStart),
                input,
                requestLimits);

        return new ParsedHttpRequest(
                parsed.requestLine,
                parsed.headers,
                receiveBuffer,
                bodyStart,
                0,
                totalBytes,
                true,
                liveBodyStream,
                false);
    }

    private static final class RequestReadResult {
        private final ParsedHttpRequest parsed;
        private final int totalBytes;
        private final int errorStatusCode;
        private final String errorMessage;

        private RequestReadResult(ParsedHttpRequest parsed, int totalBytes, int errorStatusCode, String errorMessage) {
            this.parsed = parsed;
            this.totalBytes = totalBytes;
            this.errorStatusCode = errorStatusCode;
            this.errorMessage = errorMessage;
        }

        private static RequestReadResult success(ParsedHttpRequest parsed, int totalBytes) {
            return new RequestReadResult(parsed, totalBytes, 0, null);
        }

        private static RequestReadResult error(int errorStatusCode, String errorMessage, int totalBytes) {
            return new RequestReadResult(null, totalBytes, errorStatusCode, errorMessage);
        }
    }

    private void sendBadRequest(OutputStream output) throws IOException {
        sendSimpleResponse(output, 400, "Bad Request");
    }

    private void sendSimpleResponse(OutputStream output, int statusCode, String message) throws IOException {
        output.write(SimpleHttpResponses.plainText(statusCode, message));
        output.flush();
    }

    private void closeSocket() {
        try {
            socket.close();
        } catch (IOException e) {
            logger.debug("Error closing socket: {}", e.getMessage());
        }
    }

    /**
     * Handle WebSocket frame-based communication after upgrade.
     * Runs in blocking mode until connection is closed or error occurs.
     */
    private void handleWebSocketConnection(
            InputStream input,
            OutputStream output,
            WebSocketEndpointMetadata endpointMetadata,
            Map<String, String> pathParams,
            String negotiatedSubprotocol,
            boolean perMessageDeflateEnabled) throws IOException {
        WebSocketBlockingHandler wsHandler = new WebSocketBlockingHandler(
                socket,
                input,
                output,
                perMessageDeflateEnabled);
        wsHandler.setFrameTimeoutMillis(requestLimits.readTimeoutMillis());
        WebSocketEndpointBinding endpointBinding = createWebSocketEndpointBinding(
                wsHandler,
                endpointMetadata,
                pathParams,
                negotiatedSubprotocol);

        if (endpointBinding != null) {
            try {
                endpointBinding.onOpen();
            } catch (RuntimeException endpointOpenError) {
                logger.debug("WebSocket endpoint onOpen error: {}", endpointOpenError.getMessage());
                try {
                    endpointBinding.onError(endpointOpenError);
                } catch (RuntimeException ignored) {
                    // Best effort endpoint error dispatch.
                }
                wsHandler.sendCloseFrame(WebSocketFrameCodec.CLOSE_PROTOCOL_ERROR);
                return;
            }
        }

        try {
            while (true) {
                WebSocketFrame frame = wsHandler.readClientFrame();
                if (frame == null) {
                    // Connection closed by peer
                    logger.debug("WebSocket connection closed by peer");
                    if (endpointBinding != null) {
                        endpointBinding.onClose();
                    }
                    break;
                }

                try {
                    if (!handleWebSocketFrame(wsHandler, frame, endpointBinding)) {
                        return;
                    }
                } catch (Exception e) {
                    logger.debug("WebSocket frame handling error: {}", e.getMessage());
                    if (endpointBinding != null) {
                        try {
                            endpointBinding.onError(e);
                        } catch (RuntimeException ignored) {
                            // Best effort endpoint error dispatch.
                        }
                    }
                    try {
                        wsHandler.sendCloseFrame(WebSocketFrameCodec.CLOSE_PROTOCOL_ERROR);
                    } catch (IOException ignored) {
                        // Already erroring
                    }
                    break;
                }
            }
        } catch (IOException e) {
            logger.debug("WebSocket I/O error: {}", e.getMessage());
        }
    }

    /**
     * Handle a single WebSocket frame.
     */
    private boolean handleWebSocketFrame(
            WebSocketBlockingHandler wsHandler,
            WebSocketFrame frame,
            WebSocketEndpointBinding endpointBinding) throws IOException {
        int opcode = frame.opcode();

        if (opcode == WebSocketFrameCodec.OPCODE_TEXT) {
            if (endpointBinding != null) {
                endpointBinding.onText(new String(frame.payload(), StandardCharsets.UTF_8));
            } else {
                // Echo text frames back to client
                wsHandler.sendServerFrame(WebSocketFrameCodec.OPCODE_TEXT, frame.fin(), frame.payload());
            }
        } else if (opcode == WebSocketFrameCodec.OPCODE_BINARY) {
            if (endpointBinding != null) {
                endpointBinding.onBinary(frame.payload());
            } else {
                // Echo binary frames back to client
                wsHandler.sendServerFrame(WebSocketFrameCodec.OPCODE_BINARY, frame.fin(), frame.payload());
            }
        } else if (opcode == WebSocketFrameCodec.OPCODE_PING) {
            // Respond to ping with pong
            wsHandler.sendPongFrame(frame.payload());
        } else if (opcode == WebSocketFrameCodec.OPCODE_PONG) {
            // Ignore pong frames
        } else if (opcode == WebSocketFrameCodec.OPCODE_CLOSE) {
            // Close connection gracefully
            if (endpointBinding != null) {
                endpointBinding.onClose();
            }
            if (frame.payload().length >= 2) {
                int closeCode = ((frame.payload()[0] & 0xFF) << 8) | (frame.payload()[1] & 0xFF);
                wsHandler.sendCloseFrame(closeCode);
            } else {
                wsHandler.sendCloseFrame(WebSocketFrameCodec.CLOSE_NORMAL);
            }
            return false;
        } else if (opcode == WebSocketFrameCodec.OPCODE_CONTINUATION) {
            // Echo continuation frames
            wsHandler.sendServerFrame(WebSocketFrameCodec.OPCODE_CONTINUATION, frame.fin(), frame.payload());
        }
        return true;
    }

    private WebSocketEndpointBinding createWebSocketEndpointBinding(
            WebSocketBlockingHandler wsHandler,
            WebSocketEndpointMetadata endpointMetadata,
            Map<String, String> pathParams,
            String negotiatedSubprotocol) {
        if (endpointMetadata == null) {
            return null;
        }
        WebSocketSession session = new WebSocketSession(
                socket.getRemoteSocketAddress().toString(),
                negotiatedSubprotocol,
                new WebSocketSession.Transport() {
                    @Override
                    public void sendText(String message) throws IOException {
                        wsHandler.sendServerFrame(
                                WebSocketFrameCodec.OPCODE_TEXT,
                                true,
                                message.getBytes(StandardCharsets.UTF_8));
                    }

                    @Override
                    public void sendBinary(byte[] payload) throws IOException {
                        wsHandler.sendServerFrame(WebSocketFrameCodec.OPCODE_BINARY, true, payload);
                    }

                    @Override
                    public void close(int code) throws IOException {
                        wsHandler.sendCloseFrame(code);
                    }
                });
        return new WebSocketEndpointBinding(endpointMetadata, session, pathParams);
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
        int cursor = 0;
        while (cursor < requestedHeader.length()) {
            int next = requestedHeader.indexOf(',', cursor);
            if (next < 0) {
                next = requestedHeader.length();
            }
            String trimmed = requestedHeader.substring(cursor, next).trim();
            if (!trimmed.isEmpty()) {
                requested.add(trimmed);
            }
            cursor = next + 1;
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

    private static boolean negotiatePerMessageDeflate(String requestedExtensionsHeader) {
        return WebSocketExtensions.requestIncludesPerMessageDeflate(requestedExtensionsHeader);
    }

    private static String extractRequestPath(String requestUri) {
        if (requestUri == null || requestUri.isEmpty()) {
            return "/";
        }
        int queryStart = requestUri.indexOf('?');
        return queryStart >= 0 ? requestUri.substring(0, queryStart) : requestUri;
    }

    static long writeTimeoutCloseCount() {
        return writeTimeoutCloseCount.get();
    }

    @FunctionalInterface
    private interface CheckedIoRunnable {
        void run() throws IOException;
    }

    private static final class WriteTimeoutGuard implements AutoCloseable {
        private final ScheduledFuture<?> closeFuture;
        private volatile boolean timedOut;

        private WriteTimeoutGuard(ScheduledFuture<?> closeFuture) {
            this.closeFuture = closeFuture;
        }

        private static WriteTimeoutGuard start(Socket socket, int writeTimeoutMillis) {
            if (writeTimeoutMillis <= 0) {
                return new WriteTimeoutGuard(null);
            }
            WriteTimeoutGuard[] holder = new WriteTimeoutGuard[1];
            ScheduledFuture<?> closeFuture = writeTimeoutScheduler.schedule(() -> {
                WriteTimeoutGuard guard = holder[0];
                if (guard != null) {
                    guard.timedOut = true;
                }
                writeTimeoutCloseCount.incrementAndGet();
                ServerObservability.recordWriteTimeout();
                try {
                    socket.close();
                } catch (IOException ignored) {
                    // Best-effort close to break blocked writes.
                }
            }, writeTimeoutMillis, TimeUnit.MILLISECONDS);
            WriteTimeoutGuard guard = new WriteTimeoutGuard(closeFuture);
            holder[0] = guard;
            return guard;
        }

        private void execute(CheckedIoRunnable runnable) throws IOException {
            try {
                runnable.run();
            } catch (IOException exception) {
                if (timedOut) {
                    SocketTimeoutException timeoutException = new SocketTimeoutException("Write timed out");
                    timeoutException.initCause(exception);
                    throw timeoutException;
                }
                throw exception;
            }
            if (timedOut) {
                throw new SocketTimeoutException("Write timed out");
            }
        }

        @Override
        public void close() {
            if (closeFuture != null) {
                closeFuture.cancel(false);
            }
        }
    }
}
