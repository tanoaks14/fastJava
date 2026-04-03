package com.fastjava.http.impl;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import com.fastjava.http.parser.MultipartFormDataParser;
import com.fastjava.http.parser.MultipartStreamingParser;
import com.fastjava.http.parser.ParsedHttpRequest;
import com.fastjava.http.simd.SIMDByteScanner;
import com.fastjava.server.RequestLimits;
import com.fastjava.server.ServletRouter;
import com.fastjava.server.session.SessionConfig;
import com.fastjava.server.session.SessionManager;
import com.fastjava.servlet.AsyncContext;
import com.fastjava.servlet.Cookie;
import com.fastjava.servlet.Filter;
import com.fastjava.servlet.FilterChain;
import com.fastjava.servlet.HttpServlet;
import com.fastjava.servlet.HttpServletRequest;
import com.fastjava.servlet.HttpServletResponse;
import com.fastjava.servlet.HttpSession;
import com.fastjava.servlet.Part;
import com.fastjava.servlet.RequestDispatcher;
import com.fastjava.servlet.ServletException;

/**
 * Concrete HttpServletRequest implementation. Wraps ParsedHttpRequest with
 * minimal overhead.
 */
public class DefaultHttpServletRequest implements HttpServletRequest {

    private final ParsedHttpRequest parsed;
    private String attributeKey1;
    private Object attributeValue1;
    private String attributeKey2;
    private Object attributeValue2;
    private Map<String, Object> attributes;
    private final String remoteAddr;
    private final int remotePort;
    private final int localPort;
    private final RequestLimits requestLimits;
    private final ServletRouter router;
    private String currentRequestUri;
    private String currentQueryString;
    private Map<String, String[]> parameterMap;
    private boolean multipartParsed;
    private MultipartFormDataParser.ParsedMultipart parsedMultipart;
    private List<Part> parts;
    private List<Cookie> cookies;
    private Map<String, Cookie> cookieByName;
    private final String cookieHeader;
    private final byte[] cookieHeaderBytes;
    private final boolean hasCookieHeader;
    private MultipartStreamingParser streamingMultipartParser;
    private boolean useStreamingMultipart = false;
    private final SessionManager sessionManager;
    private HttpSession session;
    private String requestedSessionId;
    private boolean sessionResolved;
    private boolean sessionCreated;
    private Supplier<AsyncContext> asyncContextFactory;
    private AsyncContext asyncContext;
    private volatile boolean asyncStarted;

    public DefaultHttpServletRequest(
            ParsedHttpRequest parsed,
            String remoteAddr,
            int remotePort,
            int localPort) {
        this(parsed, remoteAddr, remotePort, localPort,
                RequestLimits.defaults(Math.max(1, parsed == null ? 1 : parsed.bodyLength)));
    }

    public DefaultHttpServletRequest(
            ParsedHttpRequest parsed,
            String remoteAddr,
            int remotePort,
            int localPort,
            RequestLimits requestLimits) {
        this(parsed, remoteAddr, remotePort, localPort, requestLimits, null);
    }

    public DefaultHttpServletRequest(
            ParsedHttpRequest parsed,
            String remoteAddr,
            int remotePort,
            int localPort,
            RequestLimits requestLimits,
            SessionManager sessionManager) {
        this(parsed, remoteAddr, remotePort, localPort, requestLimits, sessionManager, null);
    }

    public DefaultHttpServletRequest(
            ParsedHttpRequest parsed,
            String remoteAddr,
            int remotePort,
            int localPort,
            RequestLimits requestLimits,
            SessionManager sessionManager,
            ServletRouter router) {
        this.parsed = parsed;
        this.remoteAddr = remoteAddr;
        this.remotePort = remotePort;
        this.localPort = localPort;
        this.requestLimits = requestLimits;
        this.router = router;
        this.sessionManager = sessionManager;
        this.cookieHeader = parsed == null ? null : parsed.getHeader("Cookie");
        this.hasCookieHeader = cookieHeader != null && !cookieHeader.isBlank();
        this.cookieHeaderBytes = hasCookieHeader ? cookieHeader.getBytes(StandardCharsets.US_ASCII) : null;
        String uri = parsed == null ? "/" : parsed.getURI();
        int query = uri.indexOf('?');
        this.currentRequestUri = query > 0 ? uri.substring(0, query) : uri;
        this.currentQueryString = query > 0 ? uri.substring(query + 1) : null;
    }

    @Override
    public String getMethod() {
        return parsed.getMethod();
    }

    @Override
    public String getRequestURI() {
        return currentRequestUri;
    }

    @Override
    public String getQueryString() {
        return currentQueryString;
    }

    @Override
    public String getProtocol() {
        return parsed.getVersion();
    }

    @Override
    public String getHeader(String name) {
        return parsed.getHeader(name);
    }

    @Override
    public Enumeration<String> getHeaderNames() {
        return Collections.enumeration(parsed.headerNames());
    }

    @Override
    public Enumeration<String> getHeaders(String name) {
        String value = getHeader(name);
        return value != null ? Collections.enumeration(Collections.singletonList(value))
                : Collections.emptyEnumeration();
    }

    @Override
    public int getIntHeader(String name) {
        try {
            return Integer.parseInt(getHeader(name));
        } catch (RuntimeException e) {
            return -1;
        }
    }

    private static final DateTimeFormatter[] DATE_FORMATS = {
        DateTimeFormatter.RFC_1123_DATE_TIME,
        DateTimeFormatter.ofPattern("EEEE, dd-MMM-yy HH:mm:ss zzz", java.util.Locale.US),
        DateTimeFormatter.ofPattern("EEE MMM d HH:mm:ss yyyy", java.util.Locale.US)
    };

    @Override
    public long getDateHeader(String name) {
        String value = getHeader(name);
        if (value == null) {
            return -1L;
        }
        for (DateTimeFormatter fmt : DATE_FORMATS) {
            try {
                return ZonedDateTime.parse(value, fmt).toInstant().toEpochMilli();
            } catch (DateTimeParseException ignored) {
            }
        }
        return -1L;
    }

    @Override
    public List<Cookie> getCookies() {
        ensureCookiesParsed();
        return cookies;
    }

    @Override
    public Cookie getCookie(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        ensureCookiesParsed();
        return cookieByName.get(name);
    }

    @Override
    public String getParameter(String name) {
        String[] values = getParameterMap().get(name);
        if (values == null || values.length == 0) {
            return null;
        }
        return values[0];
    }

    @Override
    public Map<String, String[]> getParameterMap() {
        if (parameterMap != null) {
            return parameterMap;
        }

        LinkedHashMap<String, List<String>> valuesByKey = new LinkedHashMap<>();

        String query = getQueryString();
        if (query != null) {
            parseParameterString(query, valuesByKey, false);
        }

        String contentType = getContentType();
        if (isFormUrlEncoded(contentType) && parsed.bodyLength > 0) {
            String bodyParams = new String(parsed.getBody(), StandardCharsets.UTF_8);
            // Query parameters take precedence over body parameters for duplicate keys.
            parseParameterString(bodyParams, valuesByKey, true);
        } else if (isMultipartFormData(contentType) && parsed.bodyLength > 0) {
            // Query parameters take precedence over body parameters for duplicate keys.
            parseMultipartIfNeeded();
            if (parsedMultipart.valid()) {
                for (MultipartFormDataParser.ParsedPart part : parsedMultipart.parts()) {
                    if (part.isFilePart() || part.name() == null || part.name().isBlank()) {
                        continue;
                    }
                    if (valuesByKey.containsKey(part.name())) {
                        continue;
                    }
                    String value = new String(part.getBytes(), StandardCharsets.UTF_8);
                    valuesByKey.computeIfAbsent(part.name(), key -> new ArrayList<>()).add(value);
                }
            }
        }

        Map<String, String[]> params = new HashMap<>(valuesByKey.size());
        for (Map.Entry<String, List<String>> entry : valuesByKey.entrySet()) {
            List<String> values = entry.getValue();
            params.put(entry.getKey(), values.toArray(new String[values.size()]));
        }
        parameterMap = params;
        return parameterMap;
    }

    @Override
    public Collection<Part> getParts() {
        if (!isMultipartFormData(getContentType()) || parsed.bodyLength <= 0) {
            return List.of();
        }
        if (parts != null) {
            return parts;
        }

        // Use streaming parser if enabled
        if (useStreamingMultipart) {
            return getPartsStreaming();
        }

        // Fall back to buffered parsing (original behavior)
        parseMultipartIfNeeded();
        if (!parsedMultipart.valid()) {
            parts = List.of();
            return parts;
        }

        List<Part> resolved = new ArrayList<>(parsedMultipart.parts().size());
        for (MultipartFormDataParser.ParsedPart parsedPart : parsedMultipart.parts()) {
            resolved.add(parsedPart);
        }
        parts = List.copyOf(resolved);
        return parts;
    }

    /**
     * Parses multipart data using streaming parser. This allows large uploads
     * to be processed incrementally.
     */
    private Collection<Part> getPartsStreaming() {
        List<Part> resolved = new ArrayList<>();
        MultipartFormDataParser.MultipartLimits multipartLimits = new MultipartFormDataParser.MultipartLimits(
                requestLimits.maxMultipartBytes(),
                requestLimits.maxMultipartPartBytes(),
                requestLimits.multipartMemoryThresholdBytes());

        try {
            streamingMultipartParser = new MultipartStreamingParser(
                    parsed.openBodyStream(),
                    getContentType(),
                    multipartLimits);

            MultipartStreamingParser.StreamingPart streamingPart;
            while ((streamingPart = streamingMultipartParser.nextPart()) != null) {
                resolved.add(streamingPart);
            }
        } catch (IOException exception) {
            // If streaming fails, return empty list
            resolved.clear();
        } finally {
            if (streamingMultipartParser != null) {
                try {
                    streamingMultipartParser.close();
                } catch (IOException ignored) {
                }
            }
        }
        parts = List.copyOf(resolved);
        return parts;
    }

    @Override
    public Part getPart(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        for (Part part : getParts()) {
            if (name.equals(part.getName())) {
                return part;
            }
        }
        return null;
    }

    private static boolean isFormUrlEncoded(String contentType) {
        if (contentType == null) {
            return false;
        }
        int semicolon = contentType.indexOf(';');
        String mediaType = (semicolon >= 0 ? contentType.substring(0, semicolon) : contentType).trim();
        return "application/x-www-form-urlencoded".equalsIgnoreCase(mediaType);
    }

    private static boolean isMultipartFormData(String contentType) {
        if (contentType == null) {
            return false;
        }
        int semicolon = contentType.indexOf(';');
        String mediaType = (semicolon >= 0 ? contentType.substring(0, semicolon) : contentType).trim();
        return "multipart/form-data".equalsIgnoreCase(mediaType);
    }

    private static void parseParameterString(String source, Map<String, List<String>> valuesByKey,
            boolean skipExistingKeys) {
        byte[] sourceBytes = source.getBytes(StandardCharsets.UTF_8);
        int cursor = 0;
        int limit = sourceBytes.length;
        while (cursor < limit) {
            int delimiter = SIMDByteScanner.indexOfByte(sourceBytes, cursor, limit, (byte) '&');
            if (delimiter < 0) {
                delimiter = limit;
            }
            int eq = SIMDByteScanner.indexOfByte(sourceBytes, cursor, delimiter, (byte) '=');
            if (eq <= cursor) {
                cursor = delimiter + 1;
                continue;
            }

            String key = decodeFormComponent(new String(sourceBytes, cursor, eq - cursor, StandardCharsets.UTF_8));
            if (skipExistingKeys && valuesByKey.containsKey(key)) {
                cursor = delimiter + 1;
                continue;
            }

            String value = decodeFormComponent(
                    new String(sourceBytes, eq + 1, delimiter - eq - 1, StandardCharsets.UTF_8));
            valuesByKey.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
            cursor = delimiter + 1;
        }
    }

    private static String decodeFormComponent(String component) {
        try {
            return URLDecoder.decode(component, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return component;
        }
    }

    private void ensureCookiesParsed() {
        if (cookies != null) {
            return;
        }

        if (!hasCookieHeader) {
            cookies = List.of();
            cookieByName = Map.of();
            return;
        }

        LinkedHashMap<String, Cookie> parsedCookies = new LinkedHashMap<>();
        byte[] headerBytes = cookieHeaderBytes;
        int start = 0;
        while (start < headerBytes.length) {
            int delimiter = SIMDByteScanner.indexOfByte(headerBytes, start, headerBytes.length, (byte) ';');
            if (delimiter < 0) {
                delimiter = headerBytes.length;
            }

            int tokenStart = SIMDByteScanner.trimStart(headerBytes, start, delimiter);
            int tokenEnd = SIMDByteScanner.trimEnd(headerBytes, tokenStart, delimiter);
            if (tokenStart < tokenEnd) {
                int eq = SIMDByteScanner.indexOfByte(headerBytes, tokenStart, tokenEnd, (byte) '=');
                if (eq > tokenStart) {
                    int nameStart = tokenStart;
                    int nameEnd = SIMDByteScanner.trimEnd(headerBytes, nameStart, eq);
                    int valueStart = SIMDByteScanner.trimStart(headerBytes, eq + 1, tokenEnd);
                    int valueEnd = SIMDByteScanner.trimEnd(headerBytes, valueStart, tokenEnd);

                    if (nameStart < nameEnd) {
                        String name = new String(headerBytes, nameStart, nameEnd - nameStart,
                                StandardCharsets.US_ASCII);
                        String value = new String(headerBytes, valueStart, Math.max(0, valueEnd - valueStart),
                                StandardCharsets.US_ASCII);
                        if (isValidCookieName(name)) {
                            Cookie cookie = new Cookie(name, unquoteCookieValue(value));
                            if (parsedCookies.containsKey(name)) {
                                parsedCookies.remove(name);
                            }
                            parsedCookies.put(name, cookie);
                        }
                    }
                }
            }

            start = delimiter + 1;
        }

        if (parsedCookies.isEmpty()) {
            cookies = List.of();
            cookieByName = Map.of();
            return;
        }

        cookieByName = Map.copyOf(parsedCookies);
        cookies = List.copyOf(parsedCookies.values());
    }

    private static String unquoteCookieValue(String value) {
        if (value.length() >= 2 && value.charAt(0) == '"' && value.charAt(value.length() - 1) == '"') {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static boolean isValidCookieName(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c <= 0x20 || c >= 0x7F) {
                return false;
            }
            if (c == '(' || c == ')' || c == '<' || c == '>' || c == '@' || c == ',' || c == ';' || c == ':'
                    || c == '\\' || c == '"' || c == '/' || c == '[' || c == ']' || c == '?' || c == '='
                    || c == '{' || c == '}') {
                return false;
            }
        }
        return true;
    }

    private void parseMultipartIfNeeded() {
        if (multipartParsed) {
            return;
        }
        multipartParsed = true;
        MultipartFormDataParser.MultipartLimits multipartLimits = new MultipartFormDataParser.MultipartLimits(
                requestLimits.maxMultipartBytes(),
                requestLimits.maxMultipartPartBytes(),
                requestLimits.multipartMemoryThresholdBytes());
        try (InputStream bodyStream = parsed.openBodyStream()) {
            parsedMultipart = MultipartFormDataParser.parse(bodyStream, getContentType(), multipartLimits);
        } catch (IOException exception) {
            parsedMultipart = MultipartFormDataParser.ParsedMultipart.invalid();
        }
    }

    /**
     * Enables streaming multipart parsing for this request. This allows large
     * file uploads to be processed incrementally from the network stream
     * without requiring full buffering before boundary parsing.
     *
     * Call this before getParts() to enable streaming behavior.
     */
    public void enableStreamingMultipart() {
        this.useStreamingMultipart = true;
    }

    @Override
    public InputStream getInputStream() {
        return parsed.openBodyStream();
    }

    @Override
    public String getCharacterEncoding() {
        return StandardCharsets.UTF_8.name();
    }

    @Override
    public int getContentLength() {
        String headerValue = parsed.getHeader("Content-Length");
        if (headerValue == null) {
            String transferEncoding = parsed.getHeader("Transfer-Encoding");
            if (containsTokenIgnoreCase(transferEncoding, "chunked")) {
                return -1;
            }
            return parsed.bodyLength;
        }
        try {
            return Integer.parseInt(headerValue);
        } catch (RuntimeException e) {
            return -1;
        }
    }

    @Override
    public String getContentType() {
        return parsed.getHeader("Content-Type");
    }

    private static boolean containsTokenIgnoreCase(String csv, String token) {
        if (csv == null || token == null) {
            return false;
        }
        int start = 0;
        while (start < csv.length()) {
            int end = csv.indexOf(',', start);
            if (end < 0) {
                end = csv.length();
            }
            String candidate = csv.substring(start, end).trim();
            if (token.equalsIgnoreCase(candidate)) {
                return true;
            }
            start = end + 1;
        }
        return false;
    }

    @Override
    public String getRemoteAddr() {
        return remoteAddr;
    }

    @Override
    public String getRemoteHost() {
        return remoteAddr;
    }

    @Override
    public int getRemotePort() {
        return remotePort;
    }

    @Override
    public String getLocalAddr() {
        return "127.0.0.1";
    }

    @Override
    public String getLocalName() {
        return "localhost";
    }

    @Override
    public int getLocalPort() {
        return localPort;
    }

    @Override
    public int getServerPort() {
        return localPort;
    }

    @Override
    public Object getAttribute(String name) {
        if (name == null) {
            return null;
        }
        if (name == attributeKey1 || name.equals(attributeKey1)) {
            return attributeValue1;
        }
        if (name == attributeKey2 || name.equals(attributeKey2)) {
            return attributeValue2;
        }
        return attributes == null ? null : attributes.get(name);
    }

    @Override
    public void setAttribute(String name, Object object) {
        if (name == null) {
            return;
        }
        if (name == attributeKey1 || name.equals(attributeKey1)) {
            attributeValue1 = object;
            return;
        }
        if (name == attributeKey2 || name.equals(attributeKey2)) {
            attributeValue2 = object;
            return;
        }
        if (attributeKey1 == null) {
            attributeKey1 = name;
            attributeValue1 = object;
            return;
        }
        if (attributeKey2 == null) {
            attributeKey2 = name;
            attributeValue2 = object;
            return;
        }
        if (attributes == null) {
            attributes = new HashMap<>(4);
        }
        attributes.put(name, object);
    }

    @Override
    public RequestDispatcher getRequestDispatcher(String path) {
        if (router == null || path == null || path.isBlank()) {
            return null;
        }
        if (path.charAt(0) != '/') {
            return null;
        }
        return new InternalRequestDispatcher(path);
    }

    private DispatchSnapshot applyDispatchTarget(String dispatchPath) {
        DispatchSnapshot snapshot = new DispatchSnapshot(currentRequestUri, currentQueryString);
        int query = dispatchPath.indexOf('?');
        currentRequestUri = query >= 0 ? dispatchPath.substring(0, query) : dispatchPath;
        currentQueryString = query >= 0 && query + 1 < dispatchPath.length() ? dispatchPath.substring(query + 1) : null;
        return snapshot;
    }

    private void restoreDispatchTarget(DispatchSnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        currentRequestUri = snapshot.requestUri();
        currentQueryString = snapshot.queryString();
    }

    private ServletRouter.DispatchTarget resolveDispatchTarget(String dispatchPath) {
        int query = dispatchPath.indexOf('?');
        String routingPath = query >= 0 ? dispatchPath.substring(0, query) : dispatchPath;
        return router.resolve(routingPath);
    }

    private void invokeDispatch(ServletRouter.DispatchTarget target, HttpServletResponse response)
            throws ServletException {
        if (target == null) {
            throw new ServletException("No servlet registered for dispatch path");
        }
        Thread currentThread = Thread.currentThread();
        ClassLoader previousClassLoader = currentThread.getContextClassLoader();
        ClassLoader targetClassLoader = target.contextClassLoader();
        boolean switchClassLoader = targetClassLoader != null && targetClassLoader != previousClassLoader;
        if (switchClassLoader) {
            currentThread.setContextClassLoader(targetClassLoader);
        }
        try {
            new LocalFilterChain(target.filters(), target.servlet()).doFilter(this, response);
        } finally {
            if (switchClassLoader) {
                currentThread.setContextClassLoader(previousClassLoader);
            }
        }
    }

    private static final class DispatchSnapshot {

        private final String requestUri;
        private final String queryString;

        private DispatchSnapshot(String requestUri, String queryString) {
            this.requestUri = requestUri;
            this.queryString = queryString;
        }

        private String requestUri() {
            return requestUri;
        }

        private String queryString() {
            return queryString;
        }
    }

    private final class InternalRequestDispatcher implements RequestDispatcher {

        private static final String FORWARD_REQUEST_URI = "javax.servlet.forward.request_uri";
        private static final String FORWARD_QUERY_STRING = "javax.servlet.forward.query_string";
        private static final String INCLUDE_REQUEST_URI = "javax.servlet.include.request_uri";
        private static final String INCLUDE_QUERY_STRING = "javax.servlet.include.query_string";

        private final String dispatchPath;

        private InternalRequestDispatcher(String dispatchPath) {
            this.dispatchPath = dispatchPath;
        }

        @Override
        public void forward(HttpServletRequest request, HttpServletResponse response) throws ServletException {
            if (request != DefaultHttpServletRequest.this) {
                throw new ServletException("Forward only supports the active request instance");
            }
            if (!(response instanceof DefaultHttpServletResponse defaultResponse)) {
                throw new ServletException("Forward requires DefaultHttpServletResponse");
            }
            if (defaultResponse.isCommitted()) {
                throw new ServletException("Cannot forward after response is committed");
            }

            setAttribute(FORWARD_REQUEST_URI, getRequestURI());
            setAttribute(FORWARD_QUERY_STRING, getQueryString());

            ServletRouter.DispatchTarget target = resolveDispatchTarget(dispatchPath);
            DispatchSnapshot snapshot = applyDispatchTarget(dispatchPath);
            try {
                defaultResponse.resetForForward();
                invokeDispatch(target, defaultResponse);
            } finally {
                restoreDispatchTarget(snapshot);
            }
        }

        @Override
        public void include(HttpServletRequest request, HttpServletResponse response) throws ServletException {
            if (request != DefaultHttpServletRequest.this) {
                throw new ServletException("Include only supports the active request instance");
            }

            setAttribute(INCLUDE_REQUEST_URI, dispatchPath);
            int query = dispatchPath.indexOf('?');
            setAttribute(INCLUDE_QUERY_STRING,
                    query >= 0 && query + 1 < dispatchPath.length() ? dispatchPath.substring(query + 1) : null);

            ServletRouter.DispatchTarget target = resolveDispatchTarget(dispatchPath);
            invokeDispatch(target, new IncludeResponseWrapper(response));
        }
    }

    private static final class IncludeResponseWrapper implements HttpServletResponse {

        private final HttpServletResponse delegate;

        private IncludeResponseWrapper(HttpServletResponse delegate) {
            this.delegate = delegate;
        }

        @Override
        public void setStatus(int sc) {
            // Includes must not alter the outer response status.
        }

        @Override
        public void setStatus(int sc, String sm) {
        }

        @Override
        public int getStatus() {
            return delegate.getStatus();
        }

        @Override
        public void setHeader(String name, String value) {
        }

        @Override
        public void addHeader(String name, String value) {
        }

        @Override
        public void setIntHeader(String name, int value) {
        }

        @Override
        public void setDateHeader(String name, long date) {
        }

        @Override
        public void setContentType(String type) {
        }

        @Override
        public void setContentLength(int len) {
        }

        @Override
        public void setCharacterEncoding(String charset) {
        }

        @Override
        public String getContentType() {
            return delegate.getContentType();
        }

        @Override
        public PrintWriter getWriter() {
            return delegate.getWriter();
        }

        @Override
        public byte[][] getOutputSegments() {
            return delegate.getOutputSegments();
        }

        @Override
        public byte[] getOutputBuffer() {
            return delegate.getOutputBuffer();
        }

        @Override
        public void setChunkedResponseEnabled(boolean enabled) {
        }

        @Override
        public boolean isChunkedResponseEnabled() {
            return delegate.isChunkedResponseEnabled();
        }

        @Override
        public void flushBuffer() {
            // Includes do not commit outer response.
        }

        @Override
        public boolean isCommitted() {
            return delegate.isCommitted();
        }

        @Override
        public void sendError(int sc) {
        }

        @Override
        public void sendRedirect(String location) {
        }
    }

    private static final class LocalFilterChain implements FilterChain {

        private final List<Filter> filters;
        private final HttpServlet servlet;
        private int index;

        private LocalFilterChain(List<Filter> filters, HttpServlet servlet) {
            this.filters = filters;
            this.servlet = servlet;
        }

        @Override
        public void doFilter(HttpServletRequest request, HttpServletResponse response) throws ServletException {
            if (index < filters.size()) {
                Filter filter = filters.get(index++);
                filter.doFilter(request, response, this);
                return;
            }
            servlet.service(request, response);
        }
    }

    @Override
    public HttpSession getSession(boolean create) {
        if (sessionManager == null) {
            return null;
        }
        if (!sessionResolved) {
            sessionResolved = true;
            String sessionId = findCookieValue(sessionManager.config().cookieName());
            if (sessionId != null) {
                requestedSessionId = sessionId;
                session = sessionManager.findSession(requestedSessionId);
            }
        }

        if (session == null && create) {
            session = sessionManager.createSession();
            sessionCreated = true;
        }
        return session;
    }

    @Override
    public HttpSession getSession() {
        return getSession(true);
    }

    @Override
    public AsyncContext startAsync() {
        Supplier<AsyncContext> factory = asyncContextFactory;
        if (factory == null) {
            throw new IllegalStateException("Async processing is not supported for this request");
        }
        if (asyncContext == null) {
            asyncContext = factory.get();
        }
        asyncStarted = true;
        return asyncContext;
    }

    @Override
    public boolean isAsyncStarted() {
        return asyncStarted;
    }

    public void configureAsyncContextFactory(Supplier<AsyncContext> factory) {
        this.asyncContextFactory = factory;
    }

    public void markAsyncCompleted() {
        asyncStarted = false;
    }

    public void dispatchAsync(String path, HttpServletResponse response) throws ServletException {
        if (path == null || path.isBlank() || path.charAt(0) != '/') {
            throw new ServletException("Async dispatch path must start with '/'");
        }
        if (!(response instanceof DefaultHttpServletResponse defaultResponse)) {
            throw new ServletException("Async dispatch requires DefaultHttpServletResponse");
        }
        if (defaultResponse.isCommitted()) {
            throw new ServletException("Cannot async-dispatch after response is committed");
        }

        ServletRouter.DispatchTarget target = resolveDispatchTarget(path);
        DispatchSnapshot snapshot = applyDispatchTarget(path);
        try {
            defaultResponse.resetForForward();
            invokeDispatch(target, defaultResponse);
        } finally {
            restoreDispatchTarget(snapshot);
        }
    }

    public void applySessionToResponse(DefaultHttpServletResponse response, boolean secureRequest) {
        if (sessionManager == null || response == null) {
            return;
        }

        // Fast path for stateless requests: no session API usage and no cookie header.
        if (!sessionCreated && !sessionResolved && !hasCookieHeader) {
            return;
        }

        SessionConfig config = sessionManager.config();
        HttpSession current = getSession(false);
        if (current != null && sessionManager.isSessionActive(current.getId())) {
            Cookie cookie = buildSessionCookie(config, current.getId(), secureRequest);
            if (sessionCreated || current.isNew()) {
                response.addCookie(cookie);
            }
            sessionManager.markSessionEstablished(current.getId());
            return;
        }

        if (requestedSessionId != null || sessionCreated) {
            Cookie expired = buildExpiredSessionCookie(config, secureRequest);
            response.addCookie(expired);
        }
    }

    private String findCookieValue(String cookieName) {
        if (!hasCookieHeader || cookieName == null || cookieName.isBlank()) {
            return null;
        }
        byte[] bytes = cookieHeaderBytes;
        String match = null;
        int start = 0;
        while (start < bytes.length) {
            int delimiter = SIMDByteScanner.indexOfByte(bytes, start, bytes.length, (byte) ';');
            if (delimiter < 0) {
                delimiter = bytes.length;
            }

            int tokenStart = SIMDByteScanner.trimStart(bytes, start, delimiter);
            int tokenEnd = SIMDByteScanner.trimEnd(bytes, tokenStart, delimiter);
            if (tokenStart < tokenEnd) {
                int eq = SIMDByteScanner.indexOfByte(bytes, tokenStart, tokenEnd, (byte) '=');
                if (eq > tokenStart) {
                    int nameStart = tokenStart;
                    int nameEnd = SIMDByteScanner.trimEnd(bytes, nameStart, eq);
                    if (asciiEqualsIgnoreCase(bytes, nameStart, nameEnd, cookieName)) {
                        int valueStart = SIMDByteScanner.trimStart(bytes, eq + 1, tokenEnd);
                        int valueEnd = SIMDByteScanner.trimEnd(bytes, valueStart, tokenEnd);
                        String value = new String(bytes, valueStart, Math.max(0, valueEnd - valueStart),
                                StandardCharsets.US_ASCII);
                        match = unquoteCookieValue(value);
                    }
                }
            }
            start = delimiter + 1;
        }
        return match;
    }

    private static boolean asciiEqualsIgnoreCase(byte[] bytes, int start, int end, String expected) {
        int length = end - start;
        if (length != expected.length() || length <= 0) {
            return false;
        }
        for (int i = 0; i < length; i++) {
            int c = bytes[start + i] & 0xFF;
            char e = expected.charAt(i);
            if (c >= 'A' && c <= 'Z') {
                c += 32;
            }
            if (e >= 'A' && e <= 'Z') {
                e = (char) (e + 32);
            }
            if (c != e) {
                return false;
            }
        }
        return true;
    }

    private static Cookie buildSessionCookie(SessionConfig config, String sessionId, boolean secureRequest) {
        Cookie cookie = new Cookie(config.cookieName(), sessionId);
        cookie.setPath(config.cookiePath());
        cookie.setDomain(config.cookieDomain());
        cookie.setHttpOnly(config.httpOnly());
        cookie.setSameSite(config.sameSite());
        cookie.setSecure(config.requireSecureCookie() || secureRequest);
        cookie.setMaxAge(config.maxInactiveIntervalSeconds());
        cookie.setExpires(Instant.now().plusSeconds(config.maxInactiveIntervalSeconds()));
        return cookie;
    }

    private static Cookie buildExpiredSessionCookie(SessionConfig config, boolean secureRequest) {
        Cookie cookie = new Cookie(config.cookieName(), "");
        cookie.setPath(config.cookiePath());
        cookie.setDomain(config.cookieDomain());
        cookie.setHttpOnly(config.httpOnly());
        cookie.setSameSite(config.sameSite());
        cookie.setSecure(config.requireSecureCookie() || secureRequest);
        cookie.setMaxAge(0);
        cookie.setExpires(Instant.EPOCH);
        return cookie;
    }
}
