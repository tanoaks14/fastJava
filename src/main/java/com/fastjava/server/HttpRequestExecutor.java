package com.fastjava.server;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fastjava.http.impl.DefaultHttpServletRequest;
import com.fastjava.http.impl.DefaultHttpServletResponse;
import com.fastjava.http.parser.ParsedHttpRequest;
import com.fastjava.server.session.InMemorySessionManager;
import com.fastjava.server.session.SessionConfig;
import com.fastjava.server.session.SessionManager;
import com.fastjava.servlet.AsyncContext;
import com.fastjava.servlet.AsyncEvent;
import com.fastjava.servlet.AsyncListener;
import com.fastjava.servlet.Filter;
import com.fastjava.servlet.FilterChain;
import com.fastjava.servlet.HttpServlet;
import com.fastjava.servlet.HttpServletRequest;
import com.fastjava.servlet.HttpServletResponse;
import com.fastjava.sse.SseEmitter;
import com.fastjava.sse.SseSupport;

import io.opentelemetry.api.trace.Span;

public final class HttpRequestExecutor {

    // Configurable via -Dfastjava.response.buffer.size=<bytes> (default 256)
    private static final int RESPONSE_BUFFER_SIZE = Integer.getInteger("fastjava.response.buffer.size", 256);
    // Thread-local pool: reuses DefaultHttpServletResponse per selector thread
    // (eliminates per-request BAOS allocation)
    private static final ThreadLocal<DefaultHttpServletResponse> RESPONSE_POOL = new ThreadLocal<>();
    // Configurable via -Dfastjava.access.log.enabled=true (default false for
    // performance)
    private static final boolean ACCESS_LOG_ENABLED = Boolean.getBoolean("fastjava.access.log.enabled");
    private static final long DEFAULT_ASYNC_TIMEOUT_MILLIS = 30_000L;
    // Run explicit maintenance every 1024 requests instead of every request.
    private static final int MAINTENANCE_INTERVAL_MASK = 1023;
    private static final Logger accessLogger = LoggerFactory.getLogger("com.fastjava.access");
    private static final SessionManager SESSION_MANAGER = new InMemorySessionManager(SessionConfig.defaults());
    private static final AtomicLong REQUEST_COUNTER = new AtomicLong();

    private HttpRequestExecutor() {
    }

    public static HttpExecutionResult execute(
            ParsedHttpRequest parsed,
            ServletRouter router,
            String remoteAddr,
            int remotePort,
            int localPort,
            RequestLimits requestLimits) {
        return execute(parsed, router, remoteAddr, remotePort, localPort, requestLimits, false, false);
    }

    public static HttpExecutionResult execute(
            ParsedHttpRequest parsed,
            ServletRouter router,
            String remoteAddr,
            int remotePort,
            int localPort,
            RequestLimits requestLimits,
            boolean secureRequest) {
        return execute(parsed, router, remoteAddr, remotePort, localPort, requestLimits, secureRequest, false);
    }

    public static HttpExecutionResult execute(
            ParsedHttpRequest parsed,
            ServletRouter router,
            String remoteAddr,
            int remotePort,
            int localPort,
            RequestLimits requestLimits,
            boolean secureRequest,
            boolean forceCloseConnection) {
        return execute(parsed, router, remoteAddr, remotePort, localPort, requestLimits, secureRequest,
                forceCloseConnection, null);
    }

    public static HttpExecutionResult execute(
            ParsedHttpRequest parsed,
            ServletRouter router,
            String remoteAddr,
            int remotePort,
            int localPort,
            RequestLimits requestLimits,
            boolean secureRequest,
            boolean forceCloseConnection,
            AsyncResponseHandler asyncResponseHandler) {
        long startedAtNanos = System.nanoTime();
        if ((REQUEST_COUNTER.incrementAndGet() & MAINTENANCE_INTERVAL_MASK) == 0) {
            SESSION_MANAGER.runMaintenance();
        }
        DefaultHttpServletRequest request = new DefaultHttpServletRequest(
                parsed,
                remoteAddr,
                remotePort,
                localPort,
                requestLimits,
                SESSION_MANAGER,
                router);
        DefaultHttpServletResponse response = RESPONSE_POOL.get();
        if (response != null) {
            RESPONSE_POOL.set(null); // check out from pool
        } else {
            response = new DefaultHttpServletResponse(RESPONSE_BUFFER_SIZE);
        }
        final DefaultHttpServletResponse requestResponse = response;
        SseEmitter requestSseEmitter = SseRuntime.currentEmitter();
        if (requestSseEmitter != null) {
            request.setAttribute(SseSupport.REQUEST_ATTRIBUTE, requestSseEmitter);
        }
        final boolean tracingEnabled = RequestTracing.isEnabled();
        RequestTracing.SpanScope requestSpan = RequestTracing.startServerSpan("http.request");
        if (tracingEnabled) {
            requestSpan.setAttribute("http.method", parsed.getMethod());
            requestSpan.setAttribute("http.target", parsed.getURI());
        }

        AsyncExecutionState[] asyncStateRef = null;
        if (asyncResponseHandler != null) {
            asyncStateRef = new AsyncExecutionState[1];
            AsyncExecutionState[] finalAsyncStateRef = asyncStateRef;
            request.configureAsyncContextFactory(() -> {
                AsyncExecutionState current = finalAsyncStateRef[0];
                if (current == null) {
                    current = new AsyncExecutionState(
                            request,
                            requestResponse,
                            parsed,
                            requestSseEmitter,
                            secureRequest,
                            forceCloseConnection,
                            startedAtNanos,
                            remoteAddr,
                            remotePort,
                            requestSpan,
                            asyncResponseHandler);
                    finalAsyncStateRef[0] = current;
                }
                return current.startAsync();
            });
        }

        try {
            String requestPath = request.getRequestURI();
            if ("/metrics".equals(requestPath)) {
                response.setStatus(200);
                response.setContentType("text/plain; version=0.0.4");
                response.getWriter().write(ServerObservability.renderPrometheus());
            } else {
                ServletRouter.DispatchTarget dispatchTarget;
                if (tracingEnabled) {
                    try (RequestTracing.SpanScope ignored = RequestTracing.startChildSpan("http.route.resolve")) {
                        dispatchTarget = router.resolve(requestPath);
                    }
                } else {
                    dispatchTarget = router.resolve(requestPath);
                }
                if (dispatchTarget == null) {
                    ErrorPage notFoundPage = router.findErrorPageForStatus(404);
                    if (notFoundPage != null) {
                        HttpErrorDispatcher.dispatch(request, response, null, 404, notFoundPage, router);
                    } else {
                        response.sendError(404);
                    }
                } else {
                    if (tracingEnabled) {
                        try (RequestTracing.SpanScope ignored = RequestTracing.startChildSpan("http.filter.chain")) {
                            invokeDispatchTarget(dispatchTarget, request, response);
                        }
                    } else {
                        invokeDispatchTarget(dispatchTarget, request, response);
                    }
                }
            }
        } catch (Exception exception) {
            RequestTracing.recordError(requestSpan.span(), exception);
            ErrorPage errorPage = router.findErrorPageForException(exception);
            if (errorPage == null) {
                errorPage = router.findErrorPageForStatus(500);
            }
            if (errorPage != null) {
                try {
                    HttpErrorDispatcher.dispatch(request, response, exception, 500, errorPage, router);
                } catch (Exception dispatchException) {
                    RequestTracing.recordError(requestSpan.span(), dispatchException);
                    response.sendError(500);
                }
            } else {
                response.sendError(500);
            }
        }

        AsyncExecutionState asyncState = asyncStateRef == null ? null : asyncStateRef[0];
        if (asyncState != null && asyncState.hasStarted()) {
            return null;
        }

        try {
            return finalizeExecutionResult(
                    parsed,
                    request,
                    response,
                    requestSseEmitter,
                    secureRequest,
                    forceCloseConnection,
                    startedAtNanos,
                    requestSpan,
                    remoteAddr,
                    remotePort);
        } finally {
            requestSpan.close();
            // Recycle response into thread-local pool for reuse by next request on this
            // thread
            response.resetForForward();
            RESPONSE_POOL.set(response);
        }
    }

    private static HttpExecutionResult finalizeExecutionResult(
            ParsedHttpRequest parsed,
            DefaultHttpServletRequest request,
            DefaultHttpServletResponse response,
            SseEmitter requestSseEmitter,
            boolean secureRequest,
            boolean forceCloseConnection,
            long startedAtNanos,
            RequestTracing.SpanScope requestSpan,
            String remoteAddr,
            int remotePort) {
        request.applySessionToResponse(response, secureRequest);

        boolean keepAlive = HttpRequestInspector.shouldKeepAlive(parsed)
                && !parsed.closeAfterResponse()
                && !forceCloseConnection;
        boolean sseStream = isSseResponse(response);
        if (sseStream) {
            keepAlive = true;
            response.enableStreamingChunkedResponse();
            response.setHeader("Connection", "keep-alive");
        } else {
            response.setHeader("Connection", keepAlive ? "keep-alive" : "close");
        }
        FileResponseBody fileBody = null;
        if (response.getFileBodyPath() != null) {
            fileBody = new FileResponseBody(
                    response.getFileBodyPath(),
                    response.getFileBodyOffset(),
                    response.getFileBodyLength());
        }

        int statusCode = response.getStatus();
        ByteBuffer[] responseSegments = response.getOutputByteBuffers();
        HttpExecutionResult result = new HttpExecutionResult(
                responseSegments,
                fileBody,
                keepAlive,
                statusCode,
                estimateBytesSent(responseSegments, fileBody),
                sseStream,
                sseStream ? requestSseEmitter : null);

        long durationNanos = System.nanoTime() - startedAtNanos;
        ServerObservability.recordCompletedRequest(statusCode, durationNanos, result.bytesSent());
        Span span = requestSpan.span();
        span.setAttribute("http.status_code", statusCode);
        span.setAttribute("http.response_content_length", result.bytesSent());
        logAccess(request, statusCode, durationNanos, result.bytesSent(), keepAlive, remoteAddr, remotePort);
        return result;
    }

    private static void invokeDispatchTarget(
            ServletRouter.DispatchTarget dispatchTarget,
            HttpServletRequest request,
            HttpServletResponse response) throws Exception {
        Thread currentThread = Thread.currentThread();
        ClassLoader previousClassLoader = currentThread.getContextClassLoader();
        ClassLoader targetClassLoader = dispatchTarget.contextClassLoader();
        boolean switchClassLoader = targetClassLoader != null && targetClassLoader != previousClassLoader;
        if (switchClassLoader) {
            currentThread.setContextClassLoader(targetClassLoader);
        }
        try {
            List<Filter> filters = dispatchTarget.filters();
            if (filters.isEmpty()) {
                invokeServlet(dispatchTarget.servlet(), request, response);
                return;
            }
            new DefaultFilterChain(filters, dispatchTarget.servlet()).doFilter(request, response);
        } finally {
            if (switchClassLoader) {
                currentThread.setContextClassLoader(previousClassLoader);
            }
        }
    }

    private static void invokeServlet(HttpServlet servlet, HttpServletRequest request, HttpServletResponse response)
            throws com.fastjava.servlet.ServletException {
        if (RequestTracing.isEnabled()) {
            try (RequestTracing.SpanScope ignored = RequestTracing.startChildSpan("http.servlet.service")) {
                servlet.service(request, response);
            }
            return;
        }
        servlet.service(request, response);
    }

    private static boolean isSseResponse(DefaultHttpServletResponse response) {
        if (response.getStatus() >= 400) {
            return false;
        }
        String contentType = response.getContentType();
        if (contentType == null) {
            return false;
        }
        int separator = contentType.indexOf(';');
        String mediaType = separator >= 0 ? contentType.substring(0, separator) : contentType;
        return "text/event-stream".equalsIgnoreCase(mediaType.trim());
    }

    public interface AsyncResponseHandler {

        void onComplete(HttpExecutionResult result);

        ScheduledFuture<?> scheduleTimeout(Runnable task, long timeoutMillis);

        void executeAsync(Runnable task);
    }

    private static final class AsyncExecutionState {

        private final DefaultHttpServletRequest request;
        private final DefaultHttpServletResponse response;
        private final ParsedHttpRequest parsed;
        private final SseEmitter requestSseEmitter;
        private final boolean secureRequest;
        private final boolean forceCloseConnection;
        private final long startedAtNanos;
        private final String remoteAddr;
        private final int remotePort;
        private final RequestTracing.SpanScope requestSpan;
        private final AsyncResponseHandler responseHandler;
        private final CopyOnWriteArrayList<AsyncListener> listeners;
        private final AtomicBoolean started;
        private final AtomicBoolean completed;
        private volatile String dispatchPath;
        private volatile long timeoutMillis;
        private volatile ScheduledFuture<?> timeoutFuture;

        private AsyncExecutionState(
                DefaultHttpServletRequest request,
                DefaultHttpServletResponse response,
                ParsedHttpRequest parsed,
                SseEmitter requestSseEmitter,
                boolean secureRequest,
                boolean forceCloseConnection,
                long startedAtNanos,
                String remoteAddr,
                int remotePort,
                RequestTracing.SpanScope requestSpan,
                AsyncResponseHandler responseHandler) {
            this.request = request;
            this.response = response;
            this.parsed = parsed;
            this.requestSseEmitter = requestSseEmitter;
            this.secureRequest = secureRequest;
            this.forceCloseConnection = forceCloseConnection;
            this.startedAtNanos = startedAtNanos;
            this.remoteAddr = remoteAddr;
            this.remotePort = remotePort;
            this.requestSpan = requestSpan;
            this.responseHandler = responseHandler;
            this.listeners = new CopyOnWriteArrayList<>();
            this.started = new AtomicBoolean(false);
            this.completed = new AtomicBoolean(false);
            this.timeoutMillis = DEFAULT_ASYNC_TIMEOUT_MILLIS;
            this.dispatchPath = request.getRequestURI();
        }

        private AsyncContext startAsync() {
            if (response.isCommitted()) {
                throw new IllegalStateException("Cannot start async after response has been committed");
            }
            if (started.compareAndSet(false, true)) {
                scheduleTimeout();
                notifyStartAsync();
            }
            return new AsyncContextImpl(this);
        }

        private boolean hasStarted() {
            return started.get();
        }

        private boolean isCompleted() {
            return completed.get();
        }

        private void setTimeout(long timeoutMillis) {
            if (timeoutMillis <= 0) {
                throw new IllegalArgumentException("Async timeout must be greater than zero");
            }
            this.timeoutMillis = timeoutMillis;
            if (started.get() && !completed.get()) {
                scheduleTimeout();
            }
        }

        private long getTimeout() {
            return timeoutMillis;
        }

        private void scheduleTimeout() {
            ScheduledFuture<?> previous = timeoutFuture;
            if (previous != null) {
                previous.cancel(false);
            }
            timeoutFuture = responseHandler.scheduleTimeout(this::onTimeout, timeoutMillis);
        }

        private void onTimeout() {
            if (!completed.compareAndSet(false, true)) {
                return;
            }
            notifyTimeout();
            if (!response.isCommitted()) {
                response.setStatus(503);
                response.setContentType("text/plain");
                response.getWriter().print("Async request timeout");
            }
            completeNow();
        }

        private void dispatch(String path) {
            if (completed.get()) {
                return;
            }
            if (path != null && !path.isBlank()) {
                dispatchPath = path;
            }
            responseHandler.executeAsync(this::dispatchInternal);
        }

        private void dispatchInternal() {
            try {
                String path = dispatchPath;
                request.markAsyncCompleted();
                request.dispatchAsync(path, response);
                if (!request.isAsyncStarted()) {
                    complete();
                }
            } catch (Exception exception) {
                RequestTracing.recordError(requestSpan.span(), exception);
                notifyError(exception);
                if (!response.isCommitted()) {
                    response.sendError(500);
                }
                complete();
            }
        }

        private void addListener(AsyncListener listener) {
            if (listener == null) {
                throw new IllegalArgumentException("AsyncListener cannot be null");
            }
            listeners.add(listener);
            if (started.get() && !completed.get()) {
                try {
                    listener.onStartAsync(new AsyncEvent(request, response, null));
                } catch (RuntimeException ignored) {
                    // Listener failures must not abort request processing.
                }
            }
        }

        private void complete() {
            if (!completed.compareAndSet(false, true)) {
                return;
            }
            completeNow();
        }

        private void completeNow() {
            ScheduledFuture<?> scheduled = timeoutFuture;
            if (scheduled != null) {
                scheduled.cancel(false);
            }
            request.markAsyncCompleted();
            HttpExecutionResult result;
            try {
                result = finalizeExecutionResult(
                        parsed,
                        request,
                        response,
                        requestSseEmitter,
                        secureRequest,
                        forceCloseConnection,
                        startedAtNanos,
                        requestSpan,
                        remoteAddr,
                        remotePort);
            } finally {
                requestSpan.close();
                response.releaseBuffer();
            }
            notifyComplete();
            responseHandler.onComplete(result);
        }

        private void notifyStartAsync() {
            AsyncEvent event = new AsyncEvent(request, response, null);
            for (AsyncListener listener : listeners) {
                try {
                    listener.onStartAsync(event);
                } catch (RuntimeException ignored) {
                    // Listener failures must not abort request processing.
                }
            }
        }

        private void notifyComplete() {
            AsyncEvent event = new AsyncEvent(request, response, null);
            for (AsyncListener listener : listeners) {
                try {
                    listener.onComplete(event);
                } catch (RuntimeException ignored) {
                    // Listener failures must not abort request processing.
                }
            }
        }

        private void notifyTimeout() {
            AsyncEvent event = new AsyncEvent(request, response, null);
            for (AsyncListener listener : listeners) {
                try {
                    listener.onTimeout(event);
                } catch (RuntimeException ignored) {
                    // Listener failures must not abort request processing.
                }
            }
        }

        private void notifyError(Throwable throwable) {
            AsyncEvent event = new AsyncEvent(request, response, throwable);
            for (AsyncListener listener : listeners) {
                try {
                    listener.onError(event);
                } catch (RuntimeException ignored) {
                    // Listener failures must not abort request processing.
                }
            }
        }
    }

    private static final class AsyncContextImpl implements AsyncContext {

        private final AsyncExecutionState state;

        private AsyncContextImpl(AsyncExecutionState state) {
            this.state = state;
        }

        @Override
        public void complete() {
            state.complete();
        }

        @Override
        public void dispatch() {
            state.dispatch(null);
        }

        @Override
        public void dispatch(String path) {
            state.dispatch(path);
        }

        @Override
        public void addListener(AsyncListener listener) {
            state.addListener(listener);
        }

        @Override
        public void setTimeout(long timeoutMillis) {
            state.setTimeout(timeoutMillis);
        }

        @Override
        public long getTimeout() {
            return state.getTimeout();
        }
    }

    private static long estimateBytesSent(ByteBuffer[] responseSegments, FileResponseBody fileBody) {
        long total = 0;
        if (responseSegments != null) {
            for (ByteBuffer segment : responseSegments) {
                if (segment != null) {
                    total += segment.remaining();
                }
            }
        }
        if (fileBody != null) {
            total += fileBody.length();
        }
        return total;
    }

    private static void logAccess(
            DefaultHttpServletRequest request,
            int statusCode,
            long durationNanos,
            long bytesSent,
            boolean keepAlive,
            String remoteAddr,
            int remotePort) {
        if (!ACCESS_LOG_ENABLED || !accessLogger.isInfoEnabled()) {
            return;
        }
        double durationMillis = durationNanos / 1_000_000.0;
        accessLogger.info(
                "{\"remote\":\"{}:{}\",\"method\":\"{}\",\"path\":\"{}\",\"status\":{},\"bytesSent\":{},\"durationMs\":{},\"keepAlive\":{}}",
                remoteAddr,
                remotePort,
                sanitize(request.getMethod()),
                sanitize(request.getRequestURI()),
                statusCode,
                bytesSent,
                String.format(Locale.ROOT, "%.3f", durationMillis),
                keepAlive);
    }

    private static String sanitize(String value) {
        if (value == null) {
            return "";
        }
        if (value.indexOf('\\') < 0 && value.indexOf('"') < 0) {
            return value;
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static final class DefaultFilterChain implements FilterChain {

        private final List<Filter> filters;
        private final HttpServlet servlet;
        private int index;

        private DefaultFilterChain(List<Filter> filters, HttpServlet servlet) {
            this.filters = filters;
            this.servlet = servlet;
        }

        @Override
        public void doFilter(HttpServletRequest request, HttpServletResponse response)
                throws com.fastjava.servlet.ServletException {
            if (index < filters.size()) {
                Filter filter = filters.get(index++);
                filter.doFilter(request, response, this);
                return;
            }
            invokeServlet(servlet, request, response);
        }
    }
}
