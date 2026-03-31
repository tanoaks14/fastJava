package com.fastjava.http.filter;

import com.fastjava.http.impl.DefaultHttpServletResponse;
import com.fastjava.servlet.HttpServletRequest;
import com.fastjava.servlet.HttpSession;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class CorsFilterTest {

    @Test
    public void allowsSimpleRequestForAllowedOrigin() throws Exception {
        CorsFilter filter = new CorsFilter(Set.of("https://app.example"));
        DefaultHttpServletResponse response = new DefaultHttpServletResponse(512);

        filter.doFilter(
                stubRequest("GET", "/hello", "Origin", "https://app.example"),
                response,
                (req, res) -> {
                    res.setContentType("text/plain");
                    res.getWriter().print("ok");
                });

        assertEquals(200, response.getStatus());
        assertEquals("https://app.example", response.getHeader("Access-Control-Allow-Origin"));
        assertEquals("Origin", response.getHeader("Vary"));
    }

    @Test
    public void skipsCorsHeadersForDisallowedOrigin() throws Exception {
        CorsFilter filter = new CorsFilter(Set.of("https://app.example"));
        DefaultHttpServletResponse response = new DefaultHttpServletResponse(512);
        AtomicBoolean chainCalled = new AtomicBoolean(false);

        filter.doFilter(
                stubRequest("GET", "/hello", "Origin", "https://evil.example"),
                response,
                (req, res) -> {
                    chainCalled.set(true);
                    res.getWriter().print("ok");
                });

        assertTrue(chainCalled.get());
        assertNull(response.getHeader("Access-Control-Allow-Origin"));
        assertNull(response.getHeader("Vary"));
    }

    @Test
    public void handlesValidPreflightWithoutInvokingChain() throws Exception {
        CorsFilter filter = new CorsFilter(
                Set.of("https://app.example"),
                Set.of("GET", "POST", "OPTIONS"),
                Set.of("content-type", "x-request-id"),
                Set.of("x-response-id"),
                false,
                1200);
        DefaultHttpServletResponse response = new DefaultHttpServletResponse(512);
        AtomicBoolean chainCalled = new AtomicBoolean(false);

        filter.doFilter(
                stubRequest(
                        "OPTIONS",
                        "/api/hello",
                        "Origin", "https://app.example",
                        "Access-Control-Request-Method", "POST",
                        "Access-Control-Request-Headers", "Content-Type, X-Request-Id"),
                response,
                (req, res) -> chainCalled.set(true));

        assertFalse(chainCalled.get());
        assertEquals(204, response.getStatus());
        assertEquals("https://app.example", response.getHeader("Access-Control-Allow-Origin"));
        assertEquals("GET, OPTIONS, POST", response.getHeader("Access-Control-Allow-Methods"));
        assertEquals("content-type, x-request-id", response.getHeader("Access-Control-Allow-Headers"));
        assertEquals("1200", response.getHeader("Access-Control-Max-Age"));
        assertTrue(response.getHeader("Vary").contains("Origin"));
        assertTrue(response.getHeader("Vary").contains("Access-Control-Request-Method"));
        assertTrue(response.getHeader("Vary").contains("Access-Control-Request-Headers"));
    }

    @Test
    public void rejectsPreflightWithDisallowedMethod() throws Exception {
        CorsFilter filter = new CorsFilter(
                Set.of("https://app.example"),
                Set.of("GET", "POST"),
                Set.of("*"),
                Set.of(),
                false,
                600);
        DefaultHttpServletResponse response = new DefaultHttpServletResponse(512);
        AtomicBoolean chainCalled = new AtomicBoolean(false);

        filter.doFilter(
                stubRequest(
                        "OPTIONS",
                        "/api/hello",
                        "Origin", "https://app.example",
                        "Access-Control-Request-Method", "PUT"),
                response,
                (req, res) -> chainCalled.set(true));

        assertFalse(chainCalled.get());
        assertEquals(403, response.getStatus());
        assertNull(response.getHeader("Access-Control-Allow-Origin"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsWildcardOriginWhenCredentialsEnabled() {
        new CorsFilter(
                Set.of("*"),
                Set.of("GET", "POST"),
                Set.of("*"),
                Set.of(),
                true,
                600);
    }

    private static HttpServletRequest stubRequest(String method, String uri, String... headerPairs) {
        Map<String, String> headers = new HashMap<>();
        for (int i = 0; i + 1 < headerPairs.length; i += 2) {
            headers.put(headerPairs[i], headerPairs[i + 1]);
        }
        return new StubRequest(method, uri, headers);
    }

    private static final class StubRequest implements HttpServletRequest {
        private final String method;
        private final String uri;
        private final Map<String, String> headers;
        private final Map<String, Object> attributes = new HashMap<>();

        private StubRequest(String method, String uri, Map<String, String> headers) {
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
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(name)) {
                    return entry.getValue();
                }
            }
            return null;
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            return Collections.enumeration(headers.keySet());
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            String value = getHeader(name);
            return value == null
                    ? Collections.emptyEnumeration()
                    : Collections.enumeration(Collections.singletonList(value));
        }

        @Override
        public int getIntHeader(String name) {
            return -1;
        }

        @Override
        public long getDateHeader(String name) {
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
        public void setAttribute(String name, Object object) {
            attributes.put(name, object);
        }

        @Override
        public HttpSession getSession(boolean create) {
            return null;
        }

        @Override
        public HttpSession getSession() {
            return null;
        }
    }
}
