package com.fastjava.server;

import com.fastjava.servlet.*;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * JDK HttpServer adapter providing FastJava servlet-style API compatibility.
 * 
 * This adapter wraps com.sun.net.httpserver.HttpServer to provide an alternative
 * HTTP backend that supports the same servlet-style routing as FastJava.
 * 
 * Purpose: Drop-in compatibility layer for developers preferring JDK's built-in 
 * HttpServer, or for reference/testing. Not recommended for production due to 
 * threading limitations of JDK HttpServer.
 * 
 * Performance note: This is a compatibility layer only - use FastJavaServer 
 * or FastJavaNioServer for best performance.
 */
public class JdkHttpServerAdapter {

    private static final Logger logger = LoggerFactory.getLogger(JdkHttpServerAdapter.class);
    private static final int DEFAULT_THREAD_POOL_SIZE = Math.max(8, Runtime.getRuntime().availableProcessors());

    private final HttpServer httpServer;
    private final Map<String, ServletEntry> servletRegistry = new LinkedHashMap<>();
    private volatile boolean running = false;

    private static class ServletEntry {
        final Pattern pattern;
        final HttpServlet servlet;
        final boolean isPattern;
        
        ServletEntry(String path, HttpServlet servlet) {
            this.pattern = Pattern.compile(Pattern.quote(path));
            this.servlet = servlet;
            this.isPattern = false;
        }
        
        ServletEntry(Pattern pattern, HttpServlet servlet) {
            this.pattern = pattern;
            this.servlet = servlet;
            this.isPattern = true;
        }
    }

    /**
     * Create adapter listening on specified port.
     */
    public JdkHttpServerAdapter(int port) throws IOException {
        this.httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 50);
        this.httpServer.setExecutor(Executors.newFixedThreadPool(DEFAULT_THREAD_POOL_SIZE));
        logger.info("JDK HttpServer adapter initialized on port {}", port);
    }

    /**
     * Register a servlet for a specific path (exact match).
     */
    public synchronized void addServlet(String path, HttpServlet servlet) throws ServletException {
        if (running) {
            throw new ServletException("Cannot register servlet while server is running");
        }
        
        ServletEntry entry = new ServletEntry(path, servlet);
        servlet.init(new ServletConfig() {
            @Override
            public String getServletName() {
                return servlet.getClass().getSimpleName();
            }

            @Override
            public String getInitParameter(String name) {
                return null;
            }

            @Override
            public Enumeration<String> getInitParameterNames() {
                return Collections.emptyEnumeration();
            }

            @Override
            public Map<String, String> getInitParameters() {
                return Map.of();
            }
        });
        
        servletRegistry.put(path, entry);
        logger.debug("Registered servlet for exact path: {}", path);
    }

    /**
     * Register a servlet using regex pattern matching.
     */
    public synchronized void addServletPattern(String patternStr, HttpServlet servlet) throws ServletException {
        if (running) {
            throw new ServletException("Cannot register servlet while server is running");
        }
        
        Pattern regex = Pattern.compile(patternStr);
        ServletEntry entry = new ServletEntry(regex, servlet);
        servlet.init(new ServletConfig() {
            @Override
            public String getServletName() {
                return servlet.getClass().getSimpleName();
            }

            @Override
            public String getInitParameter(String name) {
                return null;
            }

            @Override
            public Enumeration<String> getInitParameterNames() {
                return Collections.emptyEnumeration();
            }

            @Override
            public Map<String, String> getInitParameters() {
                return Map.of();
            }
        });
        
        String key = "PATTERN:" + patternStr;
        servletRegistry.put(key, entry);
        logger.debug("Registered servlet for pattern: {}", patternStr);
    }

    /**
     * Start the server.
     */
    public synchronized void start() {
        if (running) {
            return;
        }
        
        httpServer.createContext("/", new ServletDispatchHandler());
        httpServer.start();
        running = true;
        logger.info("JDK HttpServer adapter started on port {}", httpServer.getAddress().getPort());
    }

    /**
     * Stop the server.
     */
    public synchronized void stop() {
        if (!running) {
            return;
        }
        httpServer.stop(1);
        running = false;
        logger.info("JDK HttpServer adapter stopped");
    }

    /**
     * Get the port the server is bound to.
     */
    public int getPort() {
        return httpServer.getAddress().getPort();
    }

    /**
     * Internal handler that dispatches requests to registered servlets.
     */
    private class ServletDispatchHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            try {
                String path = exchange.getRequestURI().getPath();
                ServletEntry entry = null;

                // First try exact path match
                entry = servletRegistry.get(path);

                // If no exact match, try pattern matches
                if (entry == null) {
                    for (ServletEntry candidate : servletRegistry.values()) {
                        if (candidate.isPattern && candidate.pattern.matcher(path).matches()) {
                            entry = candidate;
                            break;
                        }
                    }
                }

                if (entry == null) {
                    // No matching servlet found - return 404
                    exchange.getResponseHeaders().set("Content-Type", "text/plain");
                    exchange.sendResponseHeaders(404, 0);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write("Not Found".getBytes(StandardCharsets.UTF_8));
                    }
                    return;
                }

                // Dispatch to servlet
                HttpServletRequest request = new JdkHttpServletRequest(exchange);
                HttpServletResponse response = new JdkHttpServletResponse(exchange);

                entry.servlet.service(request, response);
                ((JdkHttpServletResponse) response).completeIfNecessary();
            } catch (Exception e) {
                logger.error("Error handling request", e);
                try {
                    exchange.getResponseHeaders().set("Content-Type", "text/plain");
                    exchange.sendResponseHeaders(500, 0);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write("Internal Server Error".getBytes(StandardCharsets.UTF_8));
                    }
                } catch (IOException ignored) {
                }
            }
        }
    }

    /**
     * Adapter class converting JDK HttpExchange to FastJava HttpServletRequest.
     */
    private static class JdkHttpServletRequest implements HttpServletRequest {
        private final HttpExchange exchange;
        private final Map<String, String[]> parameters;
        private final Map<String, Object> attributes = new HashMap<>();

        JdkHttpServletRequest(HttpExchange exchange) {
            this.exchange = exchange;
            this.parameters = parseParameters(exchange.getRequestURI().getRawQuery());
        }

        private Map<String, String[]> parseParameters(String queryString) {
            Map<String, String[]> params = new HashMap<>();
            if (queryString == null || queryString.isEmpty()) {
                return params;
            }

            for (String pair : queryString.split("&")) {
                String[] kv = pair.split("=", 2);
                String key = URLDecoder.decode(kv[0], StandardCharsets.UTF_8);
                String value = kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "";
                
                params.computeIfAbsent(key, k -> new String[0]);
                String[] existing = params.get(key);
                String[] updated = new String[existing.length + 1];
                System.arraycopy(existing, 0, updated, 0, existing.length);
                updated[existing.length] = value;
                params.put(key, updated);
            }
            return params;
        }

        @Override
        public String getMethod() {
            return exchange.getRequestMethod();
        }

        @Override
        public String getRequestURI() {
            return exchange.getRequestURI().getPath();
        }

        @Override
        public String getQueryString() {
            return exchange.getRequestURI().getQuery();
        }

        @Override
        public String getProtocol() {
            return exchange.getProtocol();
        }

        @Override
        public String getHeader(String name) {
            return exchange.getRequestHeaders().getFirst(name);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            return Collections.enumeration(exchange.getRequestHeaders().keySet());
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            List<String> values = exchange.getRequestHeaders().get(name);
            return Collections.enumeration(values != null ? values : List.of());
        }

        @Override
        public int getIntHeader(String name) {
            String value = getHeader(name);
            return value != null ? Integer.parseInt(value) : -1;
        }

        @Override
        public long getDateHeader(String name) {
            String value = getHeader(name);
            return value != null ? Long.parseLong(value) : -1;
        }

        @Override
        public String getParameter(String name) {
            String[] values = parameters.get(name);
            return values != null && values.length > 0 ? values[0] : null;
        }

        @Override
        public Map<String, String[]> getParameterMap() {
            return parameters;
        }

        @Override
        public String getRemoteAddr() {
            return exchange.getRemoteAddress().getAddress().getHostAddress();
        }

        @Override
        public String getRemoteHost() {
            return exchange.getRemoteAddress().getHostName();
        }

        @Override
        public int getRemotePort() {
            return exchange.getRemoteAddress().getPort();
        }

        @Override
        public String getLocalAddr() {
            return exchange.getLocalAddress().getAddress().getHostAddress();
        }

        @Override
        public String getLocalName() {
            return exchange.getLocalAddress().getHostName();
        }

        @Override
        public int getLocalPort() {
            return exchange.getLocalAddress().getPort();
        }

        @Override
        public int getServerPort() {
            return exchange.getLocalAddress().getPort();
        }

        @Override
        public InputStream getInputStream() {
            return exchange.getRequestBody();
        }

        @Override
        public String getCharacterEncoding() {
            String contentType = getHeader("Content-Type");
            if (contentType != null && contentType.contains("charset=")) {
                return contentType.split("charset=")[1].split(";")[0].trim();
            }
            return "UTF-8";
        }

        @Override
        public int getContentLength() {
            String length = getHeader("Content-Length");
            return length != null ? Integer.parseInt(length) : -1;
        }

        @Override
        public String getContentType() {
            return getHeader("Content-Type");
        }

        @Override
        public Object getAttribute(String name) {
            return attributes.get(name);
        }

        @Override
        public void setAttribute(String name, Object object) {
            attributes.put(name, object);
        }

        @Override
        public HttpSession getSession(boolean create) {
            return null;
        }

        @Override
        public HttpSession getSession() {
            return getSession(true);
        }
    }

    /**
     * Adapter class converting FastJava HttpServletResponse to JDK HttpExchange.
     */
    static class JdkHttpServletResponse implements HttpServletResponse {
        private final HttpExchange exchange;
        private final Map<String, List<String>> headers = new LinkedHashMap<>();
        private int status = 200;
        private boolean committed = false;
        private OutputStream outputStream;
        private PrintWriter writer;

        JdkHttpServletResponse(HttpExchange exchange) {
            this.exchange = exchange;
        }

        @Override
        public void setStatus(int sc) {
            if (committed) {
                throw new IllegalStateException("Response already committed");
            }
            this.status = sc;
        }

        @Override
        public void setStatus(int sc, String sm) {
            setStatus(sc);
        }

        @Override
        public int getStatus() {
            return status;
        }

        @Override
        public void setHeader(String name, String value) {
            if (committed) {
                throw new IllegalStateException("Response already committed");
            }
            headers.put(name, new ArrayList<>(List.of(value)));
        }

        @Override
        public void addHeader(String name, String value) {
            if (committed) {
                throw new IllegalStateException("Response already committed");
            }
            headers.computeIfAbsent(name, ignored -> new ArrayList<>()).add(value);
        }

        @Override
        public void setIntHeader(String name, int value) {
            setHeader(name, String.valueOf(value));
        }

        @Override
        public void setDateHeader(String name, long date) {
            setHeader(name, String.valueOf(date));
        }

        @Override
        public void setContentType(String type) {
            setHeader("Content-Type", type);
        }

        @Override
        public void setContentLength(int len) {
            setHeader("Content-Length", String.valueOf(len));
        }

        @Override
        public void setCharacterEncoding(String charset) {
            String contentType = getContentType();
            if (contentType != null && !contentType.contains("charset=")) {
                setContentType(contentType + "; charset=" + charset);
            }
        }

        @Override
        public String getContentType() {
            return getHeader("Content-Type");
        }

        private String getHeader(String name) {
            List<String> values = headers.get(name);
            return values == null || values.isEmpty() ? null : values.get(0);
        }

        @Override
        public PrintWriter getWriter() {
            try {
                if (writer == null) {
                    writer = new PrintWriter(new OutputStreamWriter(getOutputStream(), StandardCharsets.UTF_8));
                }
                return writer;
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public byte[][] getOutputSegments() {
            return new byte[0][];
        }

        @Override
        public byte[] getOutputBuffer() {
            return new byte[0];
        }

        @Override
        public void setChunkedResponseEnabled(boolean enabled) {
            // JDK HttpServer handles chunking automatically
        }

        @Override
        public boolean isChunkedResponseEnabled() {
            return false;
        }

        @Override
        public void flushBuffer() {
            try {
                if (writer != null) {
                    writer.flush();
                }
                if (outputStream != null) {
                    outputStream.flush();
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public boolean isCommitted() {
            return committed;
        }

        @Override
        public void sendError(int sc) {
            try {
                if (committed) {
                    return;
                }
                this.status = sc;
                committed = true;
                exchange.getResponseHeaders().set("Content-Type", "text/plain");
                exchange.sendResponseHeaders(sc, 0);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(("Error: " + sc).getBytes(StandardCharsets.UTF_8));
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public void sendRedirect(String location) {
            try {
                if (committed) {
                    return;
                }
                this.status = 302;
                setHeader("Location", location);
                committed = true;

                applyHeaders();
                exchange.sendResponseHeaders(302, -1);
                exchange.getResponseBody().close();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        void completeIfNecessary() {
            try {
                if (!committed) {
                    applyHeaders();
                    exchange.sendResponseHeaders(status, -1);
                    committed = true;
                    exchange.getResponseBody().close();
                    return;
                }

                if (writer != null) {
                    writer.flush();
                    writer.close();
                    writer = null;
                    outputStream = null;
                } else if (outputStream != null) {
                    outputStream.flush();
                    outputStream.close();
                    outputStream = null;
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        private void applyHeaders() {
            for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                exchange.getResponseHeaders().put(entry.getKey(), new ArrayList<>(entry.getValue()));
            }
        }

        private OutputStream getOutputStream() throws IOException {
            if (outputStream != null) {
                return outputStream;
            }

            if (!committed) {
                applyHeaders();
                exchange.sendResponseHeaders(status, 0);
                committed = true;
            }

            outputStream = exchange.getResponseBody();
            return outputStream;
        }
    }
}
