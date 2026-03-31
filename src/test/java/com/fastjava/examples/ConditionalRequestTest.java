package com.fastjava.examples;

import com.fastjava.http.impl.DefaultHttpServletResponse;
import com.fastjava.servlet.HttpServletRequest;
import com.fastjava.servlet.HttpSession;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * Tests conditional GET semantics: ETag, If-None-Match, If-Modified-Since.
 */
public class ConditionalRequestTest {

    private static final DateTimeFormatter RFC_1123 = DateTimeFormatter.RFC_1123_DATE_TIME.withZone(ZoneOffset.UTC);

    @Test
    public void responseIncludesETagAndAcceptRanges() throws Exception {
        Path dir = Files.createTempDirectory("crt-etag");
        Files.writeString(dir.resolve("file.txt"), "hello", StandardCharsets.UTF_8);
        StaticFileServlet servlet = new StaticFileServlet(dir, "/static");
        DefaultHttpServletResponse response = new DefaultHttpServletResponse(512);

        servlet.service(stubGet("/static/file.txt"), response);

        assertEquals(200, response.getStatus());
        String payload = new String(response.getOutputBuffer(), StandardCharsets.UTF_8);
        assertTrue("ETag header must be present", payload.contains("\r\nETag: "));
        assertTrue("Accept-Ranges header must be present", payload.contains("\r\nAccept-Ranges: bytes"));
        assertTrue("Last-Modified header must be present", payload.contains("\r\nLast-Modified: "));
    }

    @Test
    public void ifNoneMatchHitReturns304() throws Exception {
        Path dir = Files.createTempDirectory("crt-inm");
        Files.writeString(dir.resolve("page.html"), "<h1>hi</h1>", StandardCharsets.UTF_8);
        StaticFileServlet servlet = new StaticFileServlet(dir, "/static");

        // First: unconditional GET to obtain ETag
        DefaultHttpServletResponse first = new DefaultHttpServletResponse(512);
        servlet.service(stubGet("/static/page.html"), first);
        assertEquals(200, first.getStatus());
        String etag = extractHeader(new String(first.getOutputBuffer(), StandardCharsets.UTF_8), "ETag");
        assertNotNull("ETag must be present in first response", etag);

        // Second: conditional GET with matching ETag
        DefaultHttpServletResponse second = new DefaultHttpServletResponse(512);
        servlet.service(stubGetWithHeaders("/static/page.html", Map.of("If-None-Match", etag)), second);
        assertEquals(304, second.getStatus());
    }

    @Test
    public void wildcardIfNoneMatchReturns304() throws Exception {
        Path dir = Files.createTempDirectory("crt-wild");
        Files.writeString(dir.resolve("x.txt"), "x", StandardCharsets.UTF_8);
        StaticFileServlet servlet = new StaticFileServlet(dir, "/static");

        DefaultHttpServletResponse response = new DefaultHttpServletResponse(512);
        servlet.service(stubGetWithHeaders("/static/x.txt", Map.of("If-None-Match", "*")), response);
        assertEquals(304, response.getStatus());
    }

    @Test
    public void mismatchedETagReturns200() throws Exception {
        Path dir = Files.createTempDirectory("crt-mismatch");
        Files.writeString(dir.resolve("y.txt"), "y content", StandardCharsets.UTF_8);
        StaticFileServlet servlet = new StaticFileServlet(dir, "/static");

        DefaultHttpServletResponse response = new DefaultHttpServletResponse(512);
        servlet.service(stubGetWithHeaders("/static/y.txt", Map.of("If-None-Match", "\"totally-wrong\"")), response);
        assertEquals(200, response.getStatus());
    }

    @Test
    public void ifModifiedSinceFutureDateReturns304() throws Exception {
        Path dir = Files.createTempDirectory("crt-ims-future");
        Files.writeString(dir.resolve("z.txt"), "z content", StandardCharsets.UTF_8);
        StaticFileServlet servlet = new StaticFileServlet(dir, "/static");

        // A date in the future guarantees the file has not been modified since then
        String futureDate = RFC_1123.format(Instant.now().plusSeconds(3600));
        DefaultHttpServletResponse response = new DefaultHttpServletResponse(512);
        servlet.service(stubGetWithHeaders("/static/z.txt", Map.of("If-Modified-Since", futureDate)), response);
        assertEquals(304, response.getStatus());
    }

    @Test
    public void ifModifiedSinceEpochReturns200() throws Exception {
        Path dir = Files.createTempDirectory("crt-ims-old");
        Files.writeString(dir.resolve("a.txt"), "a content", StandardCharsets.UTF_8);
        StaticFileServlet servlet = new StaticFileServlet(dir, "/static");

        // Epoch is before any real file modification time
        String epoch = RFC_1123.format(Instant.EPOCH);
        DefaultHttpServletResponse response = new DefaultHttpServletResponse(512);
        servlet.service(stubGetWithHeaders("/static/a.txt", Map.of("If-Modified-Since", epoch)), response);
        assertEquals(200, response.getStatus());
    }

    @Test
    public void ifNoneMatchTakesPrecedenceOverStaleIfModifiedSince() throws Exception {
        Path dir = Files.createTempDirectory("crt-precedence");
        Files.writeString(dir.resolve("b.txt"), "b content", StandardCharsets.UTF_8);
        StaticFileServlet servlet = new StaticFileServlet(dir, "/static");

        // Get ETag
        DefaultHttpServletResponse first = new DefaultHttpServletResponse(512);
        servlet.service(stubGet("/static/b.txt"), first);
        String etag = extractHeader(new String(first.getOutputBuffer(), StandardCharsets.UTF_8), "ETag");
        assertNotNull(etag);

        // If-None-Match matches but If-Modified-Since is very old — INM wins → 304
        Map<String, String> headers = new HashMap<>();
        headers.put("If-None-Match", etag);
        headers.put("If-Modified-Since", RFC_1123.format(Instant.EPOCH));
        DefaultHttpServletResponse second = new DefaultHttpServletResponse(512);
        servlet.service(stubGetWithHeaders("/static/b.txt", headers), second);
        assertEquals(304, second.getStatus());
    }

    @Test
    public void rangeWithExplicitStartAndEndReturns206AndSlice() throws Exception {
        Path dir = Files.createTempDirectory("crt-range-explicit");
        Files.writeString(dir.resolve("range.txt"), "0123456789", StandardCharsets.UTF_8);
        StaticFileServlet servlet = new StaticFileServlet(dir, "/static");

        DefaultHttpServletResponse response = new DefaultHttpServletResponse(512);
        servlet.service(stubGetWithHeaders("/static/range.txt", Map.of("Range", "bytes=2-5")), response);

        assertEquals(206, response.getStatus());
        String payload = new String(response.getOutputBuffer(), StandardCharsets.UTF_8);
        assertTrue(payload.contains("\r\nContent-Range: bytes 2-5/10\r\n"));
        assertTrue(payload.contains("\r\nContent-Length: 4\r\n"));
        assertTrue(payload.endsWith("2345"));
    }

    @Test
    public void rangeWithOpenEndedEndReturns206ToFileEnd() throws Exception {
        Path dir = Files.createTempDirectory("crt-range-open");
        Files.writeString(dir.resolve("open.txt"), "abcdefghij", StandardCharsets.UTF_8);
        StaticFileServlet servlet = new StaticFileServlet(dir, "/static");

        DefaultHttpServletResponse response = new DefaultHttpServletResponse(512);
        servlet.service(stubGetWithHeaders("/static/open.txt", Map.of("Range", "bytes=6-")), response);

        assertEquals(206, response.getStatus());
        String payload = new String(response.getOutputBuffer(), StandardCharsets.UTF_8);
        assertTrue(payload.contains("\r\nContent-Range: bytes 6-9/10\r\n"));
        assertTrue(payload.contains("\r\nContent-Length: 4\r\n"));
        assertTrue(payload.endsWith("ghij"));
    }

    @Test
    public void rangeWithSuffixReturns206FromTail() throws Exception {
        Path dir = Files.createTempDirectory("crt-range-suffix");
        Files.writeString(dir.resolve("tail.txt"), "abcdefghij", StandardCharsets.UTF_8);
        StaticFileServlet servlet = new StaticFileServlet(dir, "/static");

        DefaultHttpServletResponse response = new DefaultHttpServletResponse(512);
        servlet.service(stubGetWithHeaders("/static/tail.txt", Map.of("Range", "bytes=-3")), response);

        assertEquals(206, response.getStatus());
        String payload = new String(response.getOutputBuffer(), StandardCharsets.UTF_8);
        assertTrue(payload.contains("\r\nContent-Range: bytes 7-9/10\r\n"));
        assertTrue(payload.contains("\r\nContent-Length: 3\r\n"));
        assertTrue(payload.endsWith("hij"));
    }

    @Test
    public void unsatisfiableRangeReturns416WithWildcardContentRange() throws Exception {
        Path dir = Files.createTempDirectory("crt-range-416");
        Files.writeString(dir.resolve("tiny.txt"), "abc", StandardCharsets.UTF_8);
        StaticFileServlet servlet = new StaticFileServlet(dir, "/static");

        DefaultHttpServletResponse response = new DefaultHttpServletResponse(512);
        servlet.service(stubGetWithHeaders("/static/tiny.txt", Map.of("Range", "bytes=9-10")), response);

        assertEquals(416, response.getStatus());
        String payload = new String(response.getOutputBuffer(), StandardCharsets.UTF_8);
        assertTrue(payload.contains("\r\nContent-Range: bytes */3\r\n"));
        assertTrue(payload.endsWith("Range Not Satisfiable"));
    }

    @Test
    public void malformedRangeReturns416AndMultiRangeReturnsMultipart206() throws Exception {
        Path dir = Files.createTempDirectory("crt-range-invalid");
        Files.writeString(dir.resolve("tiny.txt"), "abcdef", StandardCharsets.UTF_8);
        StaticFileServlet servlet = new StaticFileServlet(dir, "/static");

        DefaultHttpServletResponse malformed = new DefaultHttpServletResponse(512);
        servlet.service(stubGetWithHeaders("/static/tiny.txt", Map.of("Range", "bytes=a-b")), malformed);
        assertEquals(416, malformed.getStatus());

        DefaultHttpServletResponse multiRange = new DefaultHttpServletResponse(2048);
        servlet.service(stubGetWithHeaders("/static/tiny.txt", Map.of("Range", "bytes=0-1,4-5")), multiRange);

        assertEquals(206, multiRange.getStatus());
        String payload = new String(multiRange.getOutputBuffer(), StandardCharsets.UTF_8);
        assertTrue(payload.contains("\r\nContent-Type: multipart/byteranges; boundary="));
        assertTrue(payload.contains("\r\nContent-Range: bytes 0-1/6\r\n"));
        assertTrue(payload.contains("\r\nContent-Range: bytes 4-5/6\r\n"));
        assertTrue(payload.contains("ab"));
        assertTrue(payload.contains("ef"));
        assertTrue(payload.contains("--\r\n"));
    }

    @Test
    public void adjacentOrOverlappingRangesAreCoalescedIntoSingleRange() throws Exception {
        Path dir = Files.createTempDirectory("crt-range-coalesce");
        Files.writeString(dir.resolve("tiny.txt"), "abcdef", StandardCharsets.UTF_8);
        StaticFileServlet servlet = new StaticFileServlet(dir, "/static");

        DefaultHttpServletResponse response = new DefaultHttpServletResponse(512);
        servlet.service(stubGetWithHeaders("/static/tiny.txt", Map.of("Range", "bytes=0-2,2-4")), response);

        assertEquals(206, response.getStatus());
        String payload = new String(response.getOutputBuffer(), StandardCharsets.UTF_8);
        assertTrue(payload.contains("\r\nContent-Range: bytes 0-4/6\r\n"));
        assertTrue(payload.contains("\r\nContent-Length: 5\r\n"));
        assertFalse(payload.contains("multipart/byteranges"));
        assertTrue(payload.endsWith("abcde"));
    }

    @Test
    public void unorderedRangesAreNormalizedToAscendingMultipartOrder() throws Exception {
        Path dir = Files.createTempDirectory("crt-range-normalize");
        Files.writeString(dir.resolve("tiny.txt"), "abcdef", StandardCharsets.UTF_8);
        StaticFileServlet servlet = new StaticFileServlet(dir, "/static");

        DefaultHttpServletResponse response = new DefaultHttpServletResponse(2048);
        servlet.service(stubGetWithHeaders("/static/tiny.txt", Map.of("Range", "bytes=4-5,0-1")), response);

        assertEquals(206, response.getStatus());
        String payload = new String(response.getOutputBuffer(), StandardCharsets.UTF_8);
        assertTrue(payload.contains("\r\nContent-Type: multipart/byteranges; boundary="));

        int firstIndex = payload.indexOf("Content-Range: bytes 0-1/6");
        int secondIndex = payload.indexOf("Content-Range: bytes 4-5/6");
        assertTrue(firstIndex >= 0);
        assertTrue(secondIndex > firstIndex);
    }

    @Test
    public void ifRangeMatchingETagReturns206() throws Exception {
        Path dir = Files.createTempDirectory("crt-if-range-etag-hit");
        Files.writeString(dir.resolve("slice.txt"), "0123456789", StandardCharsets.UTF_8);
        StaticFileServlet servlet = new StaticFileServlet(dir, "/static");

        DefaultHttpServletResponse first = new DefaultHttpServletResponse(512);
        servlet.service(stubGet("/static/slice.txt"), first);
        String etag = extractHeader(new String(first.getOutputBuffer(), StandardCharsets.UTF_8), "ETag");
        assertNotNull(etag);

        DefaultHttpServletResponse ranged = new DefaultHttpServletResponse(512);
        servlet.service(stubGetWithHeaders("/static/slice.txt", Map.of("Range", "bytes=2-4", "If-Range", etag)),
                ranged);

        assertEquals(206, ranged.getStatus());
        String payload = new String(ranged.getOutputBuffer(), StandardCharsets.UTF_8);
        assertTrue(payload.contains("\r\nContent-Range: bytes 2-4/10\r\n"));
        assertTrue(payload.endsWith("234"));
    }

    @Test
    public void ifRangeMismatchedETagIgnoresRangeAndReturns200() throws Exception {
        Path dir = Files.createTempDirectory("crt-if-range-etag-miss");
        Files.writeString(dir.resolve("slice.txt"), "0123456789", StandardCharsets.UTF_8);
        StaticFileServlet servlet = new StaticFileServlet(dir, "/static");

        DefaultHttpServletResponse response = new DefaultHttpServletResponse(512);
        servlet.service(
                stubGetWithHeaders("/static/slice.txt", Map.of("Range", "bytes=2-4", "If-Range", "\"wrong-etag\"")),
                response);

        assertEquals(200, response.getStatus());
        String payload = new String(response.getOutputBuffer(), StandardCharsets.UTF_8);
        assertFalse(payload.contains("\r\nContent-Range:"));
        assertTrue(payload.endsWith("0123456789"));
    }

    @Test
    public void ifRangeStaleDateIgnoresRangeAndReturns200() throws Exception {
        Path dir = Files.createTempDirectory("crt-if-range-date-miss");
        Path file = dir.resolve("slice.txt");
        Files.writeString(file, "abcdefghij", StandardCharsets.UTF_8);
        StaticFileServlet servlet = new StaticFileServlet(dir, "/static");

        String staleDate = RFC_1123.format(Instant.EPOCH);
        DefaultHttpServletResponse response = new DefaultHttpServletResponse(512);
        servlet.service(stubGetWithHeaders("/static/slice.txt", Map.of("Range", "bytes=4-7", "If-Range", staleDate)),
                response);

        assertEquals(200, response.getStatus());
        String payload = new String(response.getOutputBuffer(), StandardCharsets.UTF_8);
        assertFalse(payload.contains("\r\nContent-Range:"));
        assertTrue(payload.endsWith("abcdefghij"));
    }

    // ---- helpers ----

    private static HttpServletRequest stubGet(String uri) {
        return new HeaderStubRequest("GET", uri, Collections.emptyMap());
    }

    private static HttpServletRequest stubGetWithHeaders(String uri, Map<String, String> extraHeaders) {
        return new HeaderStubRequest("GET", uri, extraHeaders);
    }

    /**
     * Extract the value of the first matching header from a raw HTTP/1.1 response
     * string.
     */
    private static String extractHeader(String httpResponse, String headerName) {
        for (String line : httpResponse.split("\r\n")) {
            if (line.regionMatches(true, 0, headerName + ":", 0, headerName.length() + 1)) {
                return line.substring(headerName.length() + 1).trim();
            }
        }
        return null;
    }

    // ---- stub ----

    private static final class HeaderStubRequest implements HttpServletRequest {
        private final String method;
        private final String requestUri;
        private final Map<String, String> extraHeaders;
        private final Map<String, Object> attributes = new HashMap<>();

        HeaderStubRequest(String method, String requestUri, Map<String, String> extraHeaders) {
            this.method = method;
            this.requestUri = requestUri;
            this.extraHeaders = new HashMap<>(extraHeaders);
        }

        @Override
        public String getMethod() {
            return method;
        }

        @Override
        public String getRequestURI() {
            return requestUri;
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
            for (Map.Entry<String, String> e : extraHeaders.entrySet()) {
                if (e.getKey().equalsIgnoreCase(name)) {
                    return e.getValue();
                }
            }
            return null;
        }

        @Override
        public long getDateHeader(String name) {
            String value = getHeader(name);
            if (value == null)
                return -1L;
            try {
                return ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME)
                        .toInstant().toEpochMilli();
            } catch (DateTimeParseException ignored) {
                return -1L;
            }
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            return Collections.enumeration(extraHeaders.keySet());
        }

        @Override
        public Enumeration<String> getHeaders(String n) {
            String v = getHeader(n);
            return v != null ? Collections.enumeration(Collections.singletonList(v)) : Collections.emptyEnumeration();
        }

        @Override
        public int getIntHeader(String name) {
            return -1;
        }

        @Override
        public String getParameter(String name) {
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
        public Object getAttribute(String name) {
            return attributes.get(name);
        }

        @Override
        public void setAttribute(String name, Object o) {
            attributes.put(name, o);
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
