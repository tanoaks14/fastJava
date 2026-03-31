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
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class StaticFileServletTest {

    @Test
    public void servesStaticFileFromConfiguredRoot() throws Exception {
        Path tempDir = Files.createTempDirectory("fastjava-static");
        Files.writeString(tempDir.resolve("hello.txt"), "hello static", StandardCharsets.UTF_8);

        StaticFileServlet servlet = new StaticFileServlet(tempDir, "/static");
        DefaultHttpServletResponse response = new DefaultHttpServletResponse(512);

        servlet.service(new StubRequest("GET", "/static/hello.txt"), response);

        assertEquals(200, response.getStatus());
        String payload = new String(response.getOutputBuffer(), StandardCharsets.UTF_8);
        assertTrue(payload.contains("Content-Type: text/plain"));
        assertTrue(payload.endsWith("hello static"));
    }

    @Test
    public void servesIndexWhenRequestTargetsDirectory() throws Exception {
        Path tempDir = Files.createTempDirectory("fastjava-static-index");
        Files.writeString(tempDir.resolve("index.html"), "<h1>index</h1>", StandardCharsets.UTF_8);

        StaticFileServlet servlet = new StaticFileServlet(tempDir, "/static");
        DefaultHttpServletResponse response = new DefaultHttpServletResponse(512);

        servlet.service(new StubRequest("GET", "/static/"), response);

        assertEquals(200, response.getStatus());
        String payload = new String(response.getOutputBuffer(), StandardCharsets.UTF_8);
        assertTrue(payload.contains("Content-Type: text/html"));
        assertTrue(payload.endsWith("<h1>index</h1>"));
    }

    @Test
    public void returns404ForMissingStaticFile() throws Exception {
        Path tempDir = Files.createTempDirectory("fastjava-static-missing");
        StaticFileServlet servlet = new StaticFileServlet(tempDir, "/static");
        DefaultHttpServletResponse response = new DefaultHttpServletResponse(512);

        servlet.service(new StubRequest("GET", "/static/missing.txt"), response);

        assertEquals(404, response.getStatus());
        String payload = new String(response.getOutputBuffer(), StandardCharsets.UTF_8);
        assertTrue(payload.endsWith("Not Found"));
    }

    @Test
    public void blocksPathTraversalEscapes() throws Exception {
        Path tempDir = Files.createTempDirectory("fastjava-static-secure");
        StaticFileServlet servlet = new StaticFileServlet(tempDir, "/static");
        DefaultHttpServletResponse response = new DefaultHttpServletResponse(512);

        servlet.service(new StubRequest("GET", "/static/../secret.txt"), response);

        assertEquals(403, response.getStatus());
        String payload = new String(response.getOutputBuffer(), StandardCharsets.UTF_8);
        assertTrue(payload.endsWith("Forbidden"));
    }

    private static final class StubRequest implements HttpServletRequest {
        private final String method;
        private final String requestUri;
        private final Map<String, Object> attributes = new HashMap<>();

        private StubRequest(String method, String requestUri) {
            this.method = method;
            this.requestUri = requestUri;
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
            return null;
        }

        @Override
        public Enumeration<String> getHeaderNames() {
            return Collections.emptyEnumeration();
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            return Collections.emptyEnumeration();
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
