package com.fastjava.http.filter;

import com.fastjava.http.impl.DefaultHttpServletResponse;
import com.fastjava.servlet.*;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.zip.GZIPInputStream;

import static org.junit.Assert.*;

public class GzipFilterTest {

    /**
     * Minimum body length that triggers compression (must exceed
     * GzipFilter.DEFAULT_MIN_SIZE=256).
     */
    private static final String LARGE_BODY = "a".repeat(300);

    @Test
    public void compressesResponseWhenClientAcceptsGzip() throws Exception {
        GzipFilter filter = new GzipFilter();
        DefaultHttpServletResponse response = new DefaultHttpServletResponse(512);

        filter.doFilter(
                stubRequest("Accept-Encoding", "gzip, deflate"),
                response,
                new StubFilterChain((req, res) -> {
                    res.setContentType("text/plain");
                    res.getWriter().print(LARGE_BODY);
                }));

        String raw = new String(response.getOutputBuffer(), StandardCharsets.ISO_8859_1);
        assertTrue("Content-Encoding: gzip header expected", raw.contains("Content-Encoding: gzip"));
        assertTrue("Vary: Accept-Encoding header expected", raw.contains("Vary: Accept-Encoding"));

        // Decompress body and verify round-trip
        byte[] decompressed = decompressGzipBody(response.getOutputBuffer());
        assertEquals(LARGE_BODY, new String(decompressed, StandardCharsets.UTF_8));
    }

    @Test
    public void doesNotCompressWhenClientOmitsAcceptEncoding() throws Exception {
        GzipFilter filter = new GzipFilter();
        DefaultHttpServletResponse response = new DefaultHttpServletResponse(512);

        filter.doFilter(
                stubRequest(),
                response,
                new StubFilterChain((req, res) -> {
                    res.setContentType("text/plain");
                    res.getWriter().print(LARGE_BODY);
                }));

        String raw = new String(response.getOutputBuffer(), StandardCharsets.UTF_8);
        assertFalse("Must not compress when Accept-Encoding absent",
                raw.contains("Content-Encoding: gzip"));
        assertTrue("Body must be present uncompressed", raw.endsWith(LARGE_BODY));
    }

    @Test
    public void doesNotCompressSmallResponses() throws Exception {
        GzipFilter filter = new GzipFilter();
        DefaultHttpServletResponse response = new DefaultHttpServletResponse(512);
        String smallBody = "tiny"; // well below 256 byte min

        filter.doFilter(
                stubRequest("Accept-Encoding", "gzip"),
                response,
                new StubFilterChain((req, res) -> {
                    res.setContentType("text/plain");
                    res.getWriter().print(smallBody);
                }));

        String raw = new String(response.getOutputBuffer(), StandardCharsets.UTF_8);
        assertFalse("Must not compress small bodies", raw.contains("Content-Encoding: gzip"));
        assertTrue("Uncompressed body must be present", raw.endsWith(smallBody));
    }

    @Test
    public void doesNotCompressAlreadyCompressedMimeType() throws Exception {
        GzipFilter filter = new GzipFilter();
        DefaultHttpServletResponse response = new DefaultHttpServletResponse(512);

        filter.doFilter(
                stubRequest("Accept-Encoding", "gzip"),
                response,
                new StubFilterChain((req, res) -> {
                    res.setContentType("image/png");
                    res.getWriter().print(LARGE_BODY);
                }));

        String raw = new String(response.getOutputBuffer(), StandardCharsets.UTF_8);
        assertFalse("Must not compress image/* MIME type", raw.contains("Content-Encoding: gzip"));
    }

    @Test
    public void setsAccurateContentLengthAfterCompression() throws Exception {
        GzipFilter filter = new GzipFilter();
        DefaultHttpServletResponse response = new DefaultHttpServletResponse(512);

        filter.doFilter(
                stubRequest("Accept-Encoding", "gzip"),
                response,
                new StubFilterChain((req, res) -> {
                    res.setContentType("text/html");
                    res.getWriter().print(LARGE_BODY);
                }));

        // Extract Content-Length header value from raw response
        String raw = new String(response.getOutputBuffer(), StandardCharsets.ISO_8859_1);
        String contentLength = extractHeader(raw, "Content-Length");
        assertNotNull("Content-Length must be set", contentLength);

        // Verify the stated length matches the compressed body length
        byte[] compressed = extractBody(response.getOutputBuffer());
        assertEquals(Integer.parseInt(contentLength), compressed.length);
    }

    @Test
    public void passesErrorResponseThroughWithoutCompression() throws Exception {
        GzipFilter filter = new GzipFilter();
        DefaultHttpServletResponse response = new DefaultHttpServletResponse(512);

        filter.doFilter(
                stubRequest("Accept-Encoding", "gzip"),
                response,
                new StubFilterChain((req, res) -> res.sendError(404)));

        assertEquals(404, response.getStatus());
        String raw = new String(response.getOutputBuffer(), StandardCharsets.UTF_8);
        assertFalse("Error response must not be gzip-encoded", raw.contains("Content-Encoding: gzip"));
    }

    @Test
    public void varyHeaderPresent() throws Exception {
        GzipFilter filter = new GzipFilter();
        DefaultHttpServletResponse response = new DefaultHttpServletResponse(512);

        filter.doFilter(
                stubRequest("Accept-Encoding", "gzip"),
                response,
                new StubFilterChain((req, res) -> {
                    res.setContentType("text/plain");
                    res.getWriter().print(LARGE_BODY);
                }));

        String raw = new String(response.getOutputBuffer(), StandardCharsets.UTF_8);
        assertTrue("Vary header required for correct proxy caching", raw.contains("Vary: Accept-Encoding"));
    }

    @Test
    public void excludedMimeTypeSimdMatchesScalar() {
        String[] values = new String[] {
                "IMAGE/PNG",
                "image/png; charset=UTF-8",
                "  application/zip  ",
                "text/plain",
                "application/json; charset=utf-8",
                "video/mp4",
                "application/octet-stream",
                "application/x-custom"
        };

        for (String value : values) {
            assertEquals("SIMD/scalar MIME exclusion mismatch for: " + value,
                    GzipFilter.isExcludedMimeTypeScalar(value),
                    GzipFilter.isExcludedMimeTypeSimd(value));
        }
    }

    @Test
    public void binaryContentThresholdBoundaryBehavior() {
        byte[] thirtyPercent = new byte[100];
        for (int i = 0; i < 30; i++) {
            thirtyPercent[i] = (byte) 0xFF;
        }

        byte[] thirtyOnePercent = new byte[100];
        for (int i = 0; i < 31; i++) {
            thirtyOnePercent[i] = (byte) 0xFF;
        }

        assertFalse(GzipFilter.isBinaryContentSimd(thirtyPercent));
        assertTrue(GzipFilter.isBinaryContentSimd(thirtyOnePercent));
    }

    @Test
    public void binaryContentSimdMatchesScalarForRandomBodies() {
        Random random = new Random(7L);
        for (int i = 0; i < 128; i++) {
            byte[] body = new byte[512];
            random.nextBytes(body);
            assertEquals(GzipFilter.isBinaryContentScalar(body), GzipFilter.isBinaryContentSimd(body));
        }
    }

    // ---- helpers ----

    private static HttpServletRequest stubRequest(String... headerPairs) {
        Map<String, String> headers = new HashMap<>();
        for (int i = 0; i + 1 < headerPairs.length; i += 2) {
            headers.put(headerPairs[i], headerPairs[i + 1]);
        }
        return new StubRequest("GET", "/test", headers);
    }

    /** Decompress the gzip-encoded body from a raw HTTP response byte array. */
    private static byte[] decompressGzipBody(byte[] rawHttpResponse) throws Exception {
        byte[] body = extractBody(rawHttpResponse);
        try (GZIPInputStream gis = new GZIPInputStream(new ByteArrayInputStream(body))) {
            return gis.readAllBytes();
        }
    }

    /**
     * Extract the HTTP body (after the header-body separator \r\n\r\n) as raw
     * bytes.
     */
    private static byte[] extractBody(byte[] rawHttpResponse) {
        // Find \r\n\r\n
        for (int i = 0; i < rawHttpResponse.length - 3; i++) {
            if (rawHttpResponse[i] == '\r' && rawHttpResponse[i + 1] == '\n'
                    && rawHttpResponse[i + 2] == '\r' && rawHttpResponse[i + 3] == '\n') {
                int bodyStart = i + 4;
                byte[] body = new byte[rawHttpResponse.length - bodyStart];
                System.arraycopy(rawHttpResponse, bodyStart, body, 0, body.length);
                return body;
            }
        }
        return new byte[0];
    }

    private static String extractHeader(String httpResponse, String headerName) {
        for (String line : httpResponse.split("\r\n")) {
            if (line.regionMatches(true, 0, headerName + ":", 0, headerName.length() + 1)) {
                return line.substring(headerName.length() + 1).trim();
            }
        }
        return null;
    }

    // ---- stub ----

    @FunctionalInterface
    interface TestServlet {
        void serve(HttpServletRequest req, HttpServletResponse res) throws ServletException;
    }

    private static final class StubFilterChain implements FilterChain {
        private final TestServlet servlet;

        StubFilterChain(TestServlet servlet) {
            this.servlet = servlet;
        }

        @Override
        public void doFilter(HttpServletRequest request, HttpServletResponse response) throws ServletException {
            servlet.serve(request, response);
        }
    }

    private static final class StubRequest implements HttpServletRequest {
        private final String method;
        private final String uri;
        private final Map<String, String> headers;
        private final Map<String, Object> attributes = new HashMap<>();

        StubRequest(String method, String uri, Map<String, String> headers) {
            this.method = method;
            this.uri = uri;
            this.headers = headers;
        }

        @Override
        public String getMethod() {
            return method;
        }

        @Override
        public String getRequestURI() {
            return uri;
        }

        @Override
        public String getQueryString() {
            return null;
        }

        @Override
        public String getProtocol() {
            return "HTTP/1.1";
        }

        @Override
        public String getHeader(String name) {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                if (e.getKey().equalsIgnoreCase(name))
                    return e.getValue();
            }
            return null;
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            return Collections.enumeration(headers.keySet());
        }

        @Override
        public Enumeration<String> getHeaders(String n) {
            String v = getHeader(n);
            return v != null ? Collections.enumeration(Collections.singletonList(v)) : Collections.emptyEnumeration();
        }

        @Override
        public int getIntHeader(String n) {
            return -1;
        }

        @Override
        public long getDateHeader(String n) {
            return -1L;
        }

        @Override
        public String getParameter(String n) {
            return null;
        }

        @Override
        public Map<String, String[]> getParameterMap() {
            return Collections.emptyMap();
        }

        @Override
        public InputStream getInputStream() {
            return new ByteArrayInputStream(new byte[0]);
        }

        @Override
        public String getCharacterEncoding() {
            return StandardCharsets.UTF_8.name();
        }

        @Override
        public int getContentLength() {
            return 0;
        }

        @Override
        public String getContentType() {
            return null;
        }

        @Override
        public String getRemoteAddr() {
            return "127.0.0.1";
        }

        @Override
        public String getRemoteHost() {
            return "localhost";
        }

        @Override
        public int getRemotePort() {
            return 0;
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
            return 8080;
        }

        @Override
        public int getServerPort() {
            return 8080;
        }

        @Override
        public Object getAttribute(String n) {
            return attributes.get(n);
        }

        @Override
        public void setAttribute(String n, Object o) {
            attributes.put(n, o);
        }

        @Override
        public HttpSession getSession(boolean c) {
            return null;
        }

        @Override
        public HttpSession getSession() {
            return null;
        }
    }
}
