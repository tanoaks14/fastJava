package com.fastjava.examples;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

import com.fastjava.http.filter.CorsFilter;
import com.fastjava.http.filter.GzipFilter;
import com.fastjava.server.FastJavaNioServer;
import com.fastjava.server.RequestLimits;
import com.fastjava.servlet.AsyncContext;
import com.fastjava.servlet.AsyncEvent;
import com.fastjava.servlet.AsyncListener;
import com.fastjava.servlet.HttpServlet;
import com.fastjava.servlet.HttpServletRequest;
import com.fastjava.servlet.HttpServletResponse;
import com.fastjava.servlet.HttpSession;
import com.fastjava.servlet.Part;
import com.fastjava.servlet.ServletException;
import com.fastjava.sse.SseEmitter;
import com.fastjava.sse.SseEvent;
import com.fastjava.sse.SseSupport;
import com.fastjava.websocket.WebSocketSession;
import com.fastjava.websocket.annotation.OnClose;
import com.fastjava.websocket.annotation.OnError;
import com.fastjava.websocket.annotation.OnMessage;
import com.fastjava.websocket.annotation.OnOpen;
import com.fastjava.websocket.annotation.PathParam;
import com.fastjava.websocket.annotation.WebSocketEndpoint;

/**
 * Runnable demo backend that exercises the core FastJava server feature set.
 */
public final class DemoBackendApplication {

    private static final AtomicLong TODO_ID_SEQUENCE = new AtomicLong(0);
    private static final ConcurrentMap<Long, TodoItem> TODOS = new ConcurrentHashMap<>();

    private DemoBackendApplication() {
    }

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 9090;
        Path staticRoot = args.length > 1 ? Path.of(args[1]) : Path.of("static");

        FastJavaNioServer server = new FastJavaNioServer(port, RequestLimits.defaults(256 * 1024));
        server.addFilter(new CorsFilter(Set.of("*")));
        server.addFilter(new GzipFilter());

        server.addStaticPlainTextRoute("/healthz", "ok");
        server.addServlet("/", new DemoIndexServlet(port, staticRoot));
        server.addServlet("/api/info", new InfoServlet(port, staticRoot));
        server.addServlet("/api/todos", new TodoServlet());
        server.addServlet("/api/session", new SessionServlet());
        server.addServlet("/api/async/time", new AsyncTimeServlet());
        server.addServlet("/api/sse/ticks", new TickSseServlet());
        server.addServlet("/api/upload", new UploadServlet());
        server.addServletPattern("/ws/demo/*", new WebSocketLandingServlet());
        server.addServletPattern("/assets/*", new StaticFileServlet(staticRoot, "/assets"));
        server.addWebSocketEndpoint(DemoChatSocket.class);

        server.start();
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop));
        server.waitForStop();
    }

    private static final class DemoIndexServlet extends HttpServlet {

        private final int port;
        private final Path staticRoot;

        private DemoIndexServlet(int port, Path staticRoot) {
            this.port = port;
            this.staticRoot = staticRoot;
        }

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException {
            String body = """
                    {
                      "name": "fastjava-demo-backend",
                      "description": "Demo backend for FastJava NIO server features",
                      "port": %PORT%,
                      "staticRoot": %STATIC_ROOT%,
                      "routes": [
                        {"path":"/healthz","feature":"static fast route"},
                        {"path":"/api/info","feature":"json metadata"},
                        {"path":"/api/todos","feature":"REST CRUD"},
                        {"path":"/api/session","feature":"session state"},
                        {"path":"/api/async/time","feature":"async servlet"},
                        {"path":"/api/sse/ticks","feature":"server-sent events"},
                        {"path":"/api/upload","feature":"multipart form-data"},
                        {"path":"/assets/*","feature":"static file + range/etag support"},
                                                {"path":"/ws/demo/{room}","feature":"annotation websocket + browser tester page"}
                      ]
                    }
                    """
                    .replace("%PORT%", Integer.toString(port))
                    .replace("%STATIC_ROOT%", jsonString(staticRoot.toAbsolutePath().normalize().toString()));
            writeJson(response, 200, body);
        }
    }

    private static final class InfoServlet extends HttpServlet {

        private final int port;
        private final Path staticRoot;

        private InfoServlet(int port, Path staticRoot) {
            this.port = port;
            this.staticRoot = staticRoot;
        }

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException {
            String body = """
                    {
                      "server": "FastJava",
                      "mode": "nio",
                      "vectorApi": true,
                      "filters": ["cors", "gzip"],
                      "features": [
                        "static route",
                        "json rest",
                        "sessions",
                        "async servlet",
                        "sse",
                        "multipart",
                        "websocket",
                        "static file serving"
                      ],
                      "request": {
                        "method": %METHOD%,
                        "path": %PATH%,
                        "query": %QUERY%
                      },
                      "runtime": {
                        "port": %PORT%,
                        "staticRoot": %STATIC_ROOT%,
                        "timestamp": %TIMESTAMP%
                      }
                    }
                    """
                    .replace("%METHOD%", jsonString(request.getMethod()))
                    .replace("%PATH%", jsonString(request.getRequestURI()))
                    .replace("%QUERY%", nullableJsonString(request.getQueryString()))
                    .replace("%PORT%", Integer.toString(port))
                    .replace("%STATIC_ROOT%", jsonString(staticRoot.toAbsolutePath().normalize().toString()))
                    .replace("%TIMESTAMP%", jsonString(Instant.now().toString()));
            writeJson(response, 200, body);
        }
    }

    private static final class TodoServlet extends HttpServlet {

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException {
            List<TodoItem> snapshot = new ArrayList<>(TODOS.values());
            snapshot.sort((left, right) -> Long.compare(left.id(), right.id()));

            StringBuilder items = new StringBuilder();
            items.append('[');
            for (int index = 0; index < snapshot.size(); index++) {
                if (index > 0) {
                    items.append(',');
                }
                items.append(todoToJson(snapshot.get(index)));
            }
            items.append(']');

            String body = "{"
                    + "\"count\":" + snapshot.size() + ','
                    + "\"items\":" + items
                    + '}';
            writeJson(response, 200, body);
        }

        @Override
        protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException {
            String text = coalesceTodoText(request);
            long id = TODO_ID_SEQUENCE.incrementAndGet();
            TodoItem created = new TodoItem(id, text, false, Instant.now().toString());
            TODOS.put(id, created);
            writeJson(response, 201, todoToJson(created));
        }

        @Override
        protected void doPut(HttpServletRequest request, HttpServletResponse response) throws ServletException {
            long id = parseRequiredId(request);
            TodoItem current = TODOS.get(id);
            if (current == null) {
                writeJson(response, 404, "{\"error\":\"todo not found\"}");
                return;
            }

            String text = request.getParameter("text");
            if (text == null || text.isBlank()) {
                text = readBodyAsUtf8(request).trim();
            }
            String doneParam = request.getParameter("done");
            boolean done = doneParam != null ? Boolean.parseBoolean(doneParam) : current.done();
            TodoItem updated = new TodoItem(id, text == null || text.isBlank() ? current.text() : text, done,
                    current.createdAt());
            TODOS.put(id, updated);
            writeJson(response, 200, todoToJson(updated));
        }

        @Override
        protected void doDelete(HttpServletRequest request, HttpServletResponse response) throws ServletException {
            long id = parseRequiredId(request);
            TodoItem removed = TODOS.remove(id);
            if (removed == null) {
                writeJson(response, 404, "{\"error\":\"todo not found\"}");
                return;
            }
            writeJson(response, 200, "{\"deleted\":true,\"id\":" + id + '}');
        }

        private String coalesceTodoText(HttpServletRequest request) throws ServletException {
            String text = request.getParameter("text");
            if (text != null && !text.isBlank()) {
                return text;
            }
            String body = readBodyAsUtf8(request).trim();
            return body.isEmpty() ? "demo-task-" + (TODO_ID_SEQUENCE.get() + 1) : body;
        }

        private long parseRequiredId(HttpServletRequest request) throws ServletException {
            String value = request.getParameter("id");
            if (value == null || value.isBlank()) {
                throw new ServletException("Missing required query parameter: id");
            }
            try {
                return Long.parseLong(value.trim());
            } catch (NumberFormatException numberFormatException) {
                throw new ServletException("Invalid todo id", numberFormatException);
            }
        }
    }

    private static final class SessionServlet extends HttpServlet {

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException {
            HttpSession session = request.getSession(true);
            Integer visitCount = (Integer) session.getAttribute("visitCount");
            int nextVisit = visitCount == null ? 1 : visitCount + 1;
            session.setAttribute("visitCount", nextVisit);
            session.setAttribute("lastSeen", Instant.now().toString());

            String body = "{"
                    + "\"sessionId\":" + jsonString(session.getId()) + ','
                    + "\"isNew\":" + session.isNew() + ','
                    + "\"visitCount\":" + nextVisit + ','
                    + "\"lastSeen\":" + jsonString(String.valueOf(session.getAttribute("lastSeen")))
                    + '}';
            writeJson(response, 200, body);
        }
    }

    private static final class AsyncTimeServlet extends HttpServlet {

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException {
            AsyncContext async = request.startAsync();
            async.setTimeout(3_000);
            async.addListener(new AsyncListener() {
                @Override
                public void onTimeout(AsyncEvent event) {
                    HttpServletResponse asyncResponse = event.getSuppliedResponse();
                    asyncResponse.setStatus(503);
                    asyncResponse.setContentType("application/json");
                    asyncResponse.getWriter().write("{\"error\":\"async timeout\"}");
                    async.complete();
                }
            });

            Thread worker = new Thread(() -> {
                try {
                    Thread.sleep(150);
                    writeJson(response, 200, "{"
                            + "\"mode\":\"async\","
                            + "\"thread\":" + jsonString(Thread.currentThread().getName()) + ','
                            + "\"timestamp\":" + jsonString(Instant.now().toString())
                            + '}');
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    response.setStatus(500);
                    response.setContentType("application/json");
                    response.getWriter().write("{\"error\":\"async interrupted\"}");
                } finally {
                    async.complete();
                }
            }, "FastJava-Demo-Async");
            worker.setDaemon(true);
            worker.start();
        }
    }

    private static final class TickSseServlet extends HttpServlet {

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException {
            SseSupport.prepareResponse(response);
            SseEmitter emitter = SseSupport.from(request);
            if (emitter == null) {
                throw new ServletException("SSE emitter unavailable");
            }

            AsyncContext async = request.startAsync();
            async.setTimeout(10_000);
            Thread worker = new Thread(() -> {
                try {
                    for (int tick = 1; tick <= 5; tick++) {
                        emitter.send(SseEvent.builder()
                                .event("tick")
                                .id(Integer.toString(tick))
                                .data("{\"tick\":" + tick + ",\"timestamp\":"
                                        + jsonString(Instant.now().toString()) + "}")
                                .build());
                        pause(250);
                    }
                    emitter.send(SseEvent.builder().event("done").data("stream-complete").build());
                    emitter.close();
                } catch (IOException | InterruptedException exception) {
                    if (exception instanceof InterruptedException) {
                        Thread.currentThread().interrupt();
                    }
                    try {
                        if (emitter.isOpen()) {
                            emitter.sendComment("stream-error: " + exception.getMessage());
                            emitter.close();
                        }
                    } catch (IOException ignored) {
                    }
                } finally {
                    async.complete();
                }
            }, "FastJava-Demo-SSE");
            worker.setDaemon(true);
            worker.start();
        }
    }

    private static final class UploadServlet extends HttpServlet {

        @Override
        protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException {
            Collection<Part> parts = request.getParts();
            StringBuilder items = new StringBuilder();
            items.append('[');
            int index = 0;
            for (Part part : parts) {
                if (index++ > 0) {
                    items.append(',');
                }
                items.append('{')
                        .append("\"name\":").append(jsonString(part.getName())).append(',')
                        .append("\"fileName\":").append(nullableJsonString(part.getSubmittedFileName())).append(',')
                        .append("\"contentType\":").append(nullableJsonString(part.getContentType())).append(',')
                        .append("\"size\":").append(part.getSize())
                        .append('}');
            }
            items.append(']');

            String body = "{"
                    + "\"contentType\":" + nullableJsonString(request.getContentType()) + ','
                    + "\"partCount\":" + parts.size() + ','
                    + "\"parts\":" + items
                    + '}';
            writeJson(response, 200, body);
        }

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException {
            writeJson(response, 200,
                    "{\"hint\":\"POST multipart/form-data to this endpoint to inspect uploaded parts\"}");
        }
    }

    private static final class WebSocketLandingServlet extends HttpServlet {

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException {
            String room = extractRoom(request.getRequestURI());
            String websocketUrl = "ws://" + request.getHeader("Host") + "/ws/demo/" + room;
            String body = """
                                        <!DOCTYPE html>
                                        <html lang="en">
                                        <head>
                                            <meta charset="utf-8">
                                            <meta name="viewport" content="width=device-width, initial-scale=1">
                                            <title>FastJava WebSocket Demo</title>
                                            <style>
                                                :root { color-scheme: light; }
                                                body {
                                                    margin: 0;
                                                    padding: 32px;
                                                    font-family: "Segoe UI", sans-serif;
                                                    background: linear-gradient(135deg, #f7fbff, #eef6f2);
                                                    color: #17324d;
                                                }
                                                main {
                                                    max-width: 900px;
                                                    margin: 0 auto;
                                                    background: rgba(255,255,255,0.92);
                                                    border: 1px solid #d4e2ef;
                                                    border-radius: 20px;
                                                    padding: 28px;
                                                    box-shadow: 0 20px 60px rgba(23, 50, 77, 0.12);
                                                }
                                                h1 { margin-top: 0; font-size: 2rem; }
                                                p, li { line-height: 1.5; }
                                                code {
                                                    background: #eef4fb;
                                                    padding: 2px 6px;
                                                    border-radius: 6px;
                                                }
                                                .row {
                                                    display: flex;
                                                    gap: 12px;
                                                    flex-wrap: wrap;
                                                    margin: 16px 0;
                                                }
                                                input, button {
                                                    font: inherit;
                                                    padding: 12px 14px;
                                                    border-radius: 10px;
                                                    border: 1px solid #b8cade;
                                                }
                                                input { flex: 1 1 280px; }
                                                button {
                                                    background: #0b6aa2;
                                                    color: white;
                                                    border: none;
                                                    cursor: pointer;
                                                }
                                                button.secondary { background: #5b7288; }
                                                pre {
                                                    min-height: 260px;
                                                    background: #0f1720;
                                                    color: #d7e3ef;
                                                    padding: 16px;
                                                    border-radius: 14px;
                                                    overflow: auto;
                                                }
                                            </style>
                                        </head>
                                        <body>
                                            <main>
                                                <h1>FastJava WebSocket Demo</h1>
                                                <p>This page is the HTTP companion for the WebSocket endpoint. Opening <code>/ws/demo/{room}</code> in a browser tab does not perform a WebSocket upgrade by itself, so this tester opens the socket in JavaScript.</p>
                                                <p>Room: <strong>%ROOM%</strong></p>
                                                <p>Socket URL: <code>%WS_URL%</code></p>
                                                <div class="row">
                                                    <button id="connect">Connect</button>
                                                    <button id="disconnect" class="secondary">Disconnect</button>
                                                </div>
                                                <div class="row">
                                                    <input id="message" type="text" value="hello from browser" aria-label="message" />
                                                    <button id="send">Send</button>
                                                </div>
                                                <pre id="log"></pre>
                                            </main>
                                            <script>
                                                const wsUrl = %WS_URL_JSON%;
                                                const log = document.getElementById('log');
                                                const messageInput = document.getElementById('message');
                                                let socket;

                                                function append(line) {
                                                    log.textContent += line + '\\n';
                                                    log.scrollTop = log.scrollHeight;
                                                }

                                                function connect() {
                                                    if (socket && (socket.readyState === WebSocket.OPEN || socket.readyState === WebSocket.CONNECTING)) {
                                                        append('socket already active');
                                                        return;
                                                    }
                                                    socket = new WebSocket(wsUrl);
                                                    socket.onopen = () => append('connected');
                                                    socket.onmessage = event => append('recv: ' + event.data);
                                                    socket.onerror = () => append('socket error');
                                                    socket.onclose = event => append('closed code=' + event.code);
                                                }

                                                document.getElementById('connect').addEventListener('click', connect);
                                                document.getElementById('disconnect').addEventListener('click', () => {
                                                    if (socket) {
                                                        socket.close();
                                                    }
                                                });
                                                document.getElementById('send').addEventListener('click', () => {
                                                    if (!socket || socket.readyState !== WebSocket.OPEN) {
                                                        append('socket not open');
                                                        return;
                                                    }
                                                    socket.send(messageInput.value);
                                                    append('sent: ' + messageInput.value);
                                                });
                                            </script>
                                        </body>
                                        </html>
                                        """
                    .replace("%ROOM%", escapeHtml(room))
                    .replace("%WS_URL%", escapeHtml(websocketUrl))
                    .replace("%WS_URL_JSON%", jsonString(websocketUrl));

            response.setStatus(200);
            response.setContentType("text/html; charset=utf-8");
            response.getWriter().write(body);
        }

        private String extractRoom(String requestUri) {
            String prefix = "/ws/demo/";
            if (requestUri == null || !requestUri.startsWith(prefix) || requestUri.length() == prefix.length()) {
                return "demo";
            }
            String room = requestUri.substring(prefix.length());
            int nextSlash = room.indexOf('/');
            if (nextSlash >= 0) {
                room = room.substring(0, nextSlash);
            }
            return room.isBlank() ? "demo" : room;
        }
    }

    @WebSocketEndpoint(path = "/ws/demo/{room}")
    public static final class DemoChatSocket {

        private static final ConcurrentMap<String, Set<WebSocketSession>> ROOMS = new ConcurrentHashMap<>();

        @OnOpen
        public void onOpen(WebSocketSession session, @PathParam("room") String room) throws IOException {
            ROOMS.computeIfAbsent(room, ignored -> ConcurrentHashMap.newKeySet()).add(session);
            session.setAttribute("room", room);
            session.sendText("joined room=" + room + " session=" + session.getId());
            broadcast(room, "system: session " + session.getId() + " joined");
        }

        @OnMessage
        public void onMessage(WebSocketSession session, String message, @PathParam("room") String room)
                throws IOException {
            broadcast(room, "[" + room + "] " + session.getId() + ": " + message);
        }

        @OnClose
        public void onClose(WebSocketSession session, @PathParam("room") String room) throws IOException {
            remove(room, session);
            broadcast(room, "system: session " + session.getId() + " left");
        }

        @OnError
        public void onError(WebSocketSession session, Throwable throwable, @PathParam("room") String room)
                throws IOException {
            broadcast(room, "system-error: " + throwable.getMessage());
        }

        private static void broadcast(String room, String message) throws IOException {
            Set<WebSocketSession> sessions = ROOMS.get(room);
            if (sessions == null || sessions.isEmpty()) {
                return;
            }
            List<WebSocketSession> stale = new ArrayList<>();
            for (WebSocketSession candidate : sessions) {
                try {
                    candidate.sendText(message);
                } catch (IOException ioException) {
                    stale.add(candidate);
                }
            }
            for (WebSocketSession session : stale) {
                remove(room, session);
            }
        }

        private static void remove(String room, WebSocketSession session) {
            Set<WebSocketSession> sessions = ROOMS.get(room);
            if (sessions == null) {
                return;
            }
            sessions.remove(session);
            if (sessions.isEmpty()) {
                ROOMS.remove(room, sessions);
            }
        }
    }

    private static void writeJson(HttpServletResponse response, int status, String body) {
        response.setStatus(status);
        response.setContentType("application/json; charset=utf-8");
        response.getWriter().write(body);
    }

    private static String readBodyAsUtf8(HttpServletRequest request) throws ServletException {
        try (InputStream input = request.getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ioException) {
            throw new ServletException("Failed to read request body", ioException);
        }
    }

    private static void pause(long millis) throws InterruptedException {
        Thread.sleep(millis);
    }

    private static String todoToJson(TodoItem item) {
        return "{"
                + "\"id\":" + item.id() + ','
                + "\"text\":" + jsonString(item.text()) + ','
                + "\"done\":" + item.done() + ','
                + "\"createdAt\":" + jsonString(item.createdAt())
                + '}';
    }

    private static String nullableJsonString(String value) {
        return value == null ? "null" : jsonString(value);
    }

    private static String escapeHtml(String value) {
        String source = value == null ? "" : value;
        return source
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private static String jsonString(String value) {
        String source = value == null ? "" : value;
        StringBuilder escaped = new StringBuilder(source.length() + 8);
        escaped.append('"');
        for (int index = 0; index < source.length(); index++) {
            char ch = source.charAt(index);
            switch (ch) {
                case '\\' ->
                    escaped.append("\\\\");
                case '"' ->
                    escaped.append("\\\"");
                case '\n' ->
                    escaped.append("\\n");
                case '\r' ->
                    escaped.append("\\r");
                case '\t' ->
                    escaped.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) ch));
                    } else {
                        escaped.append(ch);
                    }
                }
            }
        }
        escaped.append('"');
        return escaped.toString();
    }

    private record TodoItem(long id, String text, boolean done, String createdAt) {

    }
}
