package com.fastjava.server;

import com.fastjava.servlet.HttpServlet;
import com.fastjava.servlet.HttpServletRequest;
import com.fastjava.servlet.HttpServletResponse;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class JdkHttpServerAdapterTest {

    @Test
    public void exactRouteShouldServeBodyAndHeaders() throws Exception {
        JdkHttpServerAdapter server = new JdkHttpServerAdapter(0);
        server.addServlet("/hello", new HttpServlet() {
            @Override
            public void service(HttpServletRequest req, HttpServletResponse res) {
                res.setStatus(200);
                res.setContentType("text/plain");
                res.getWriter().write("ok");
            }
        });
        server.start();

        try {
            HttpURLConnection connection = open(server.getPort(), "/hello");
            assertEquals(200, connection.getResponseCode());
            assertTrue(connection.getHeaderField("Content-Type").contains("text/plain"));
            assertEquals("ok", readBody(connection.getInputStream()));
        } finally {
            server.stop();
        }
    }

    @Test
    public void patternRouteShouldMatchAndDecodeParameters() throws Exception {
        JdkHttpServerAdapter server = new JdkHttpServerAdapter(0);
        server.addServletPattern("/items/\\d+", new HttpServlet() {
            @Override
            public void service(HttpServletRequest req, HttpServletResponse res) {
                String q = req.getParameter("q");
                String tag = req.getParameterMap().get("tag")[1];
                res.setStatus(200);
                res.getWriter().write(q + "|" + tag);
            }
        });
        server.start();

        try {
            HttpURLConnection connection = open(server.getPort(), "/items/42?q=hello%20world&tag=a&tag=b%2Bc");
            assertEquals(200, connection.getResponseCode());
            assertEquals("hello world|b+c", readBody(connection.getInputStream()));
        } finally {
            server.stop();
        }
    }

    @Test
    public void unknownRouteShouldReturn404() throws Exception {
        JdkHttpServerAdapter server = new JdkHttpServerAdapter(0);
        server.start();

        try {
            HttpURLConnection connection = open(server.getPort(), "/missing");
            assertEquals(404, connection.getResponseCode());
            assertEquals("Not Found", readBody(connection.getErrorStream()));
        } finally {
            server.stop();
        }
    }

    @Test
    public void addHeaderShouldPreserveMultipleValues() throws Exception {
        JdkHttpServerAdapter server = new JdkHttpServerAdapter(0);
        server.addServlet("/headers", new HttpServlet() {
            @Override
            public void service(HttpServletRequest req, HttpServletResponse res) {
                res.addHeader("Set-Cookie", "a=1");
                res.addHeader("Set-Cookie", "b=2");
                res.setStatus(204);
            }
        });
        server.start();

        try {
            HttpURLConnection connection = open(server.getPort(), "/headers");
            assertEquals(204, connection.getResponseCode());
            List<String> cookies = null;
            for (Map.Entry<String, List<String>> header : connection.getHeaderFields().entrySet()) {
                if (header.getKey() != null && "Set-Cookie".equalsIgnoreCase(header.getKey())) {
                    cookies = header.getValue();
                    break;
                }
            }
            assertNotNull(cookies);
            assertEquals(2, cookies.size());
            assertTrue(cookies.contains("a=1"));
            assertTrue(cookies.contains("b=2"));
        } finally {
            server.stop();
        }
    }

    @Test
    public void redirectShouldSetLocationHeader() throws Exception {
        JdkHttpServerAdapter server = new JdkHttpServerAdapter(0);
        server.addServlet("/go", new HttpServlet() {
            @Override
            public void service(HttpServletRequest req, HttpServletResponse res) {
                res.sendRedirect("/target");
            }
        });
        server.start();

        try {
            HttpURLConnection connection = open(server.getPort(), "/go");
            connection.setInstanceFollowRedirects(false);
            assertEquals(302, connection.getResponseCode());
            assertEquals("/target", connection.getHeaderField("Location"));
        } finally {
            server.stop();
        }
    }

    private static HttpURLConnection open(int port, String path) throws IOException {
        URL url = URI.create("http://127.0.0.1:" + port + path).toURL();
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(2000);
        connection.setReadTimeout(2000);
        return connection;
    }

    private static String readBody(InputStream in) throws IOException {
        if (in == null) {
            return "";
        }
        try (InputStream body = in) {
            return new String(body.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
