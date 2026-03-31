package com.fastjava.server;

import com.fastjava.http.parser.HttpRequestParser;
import com.fastjava.http.parser.ParsedHttpRequest;
import com.fastjava.servlet.HttpServlet;
import com.fastjava.servlet.HttpServletRequest;
import com.fastjava.servlet.HttpServletResponse;
import com.fastjava.servlet.RequestDispatcher;
import com.fastjava.servlet.ServletException;
import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RequestDispatcherTest {

    private ServletRouter router;

    @Before
    public void setUp() throws ServletException {
        router = new ServletRouter();

        router.addServlet("/target", new HttpServlet() {
            @Override
            public void service(HttpServletRequest req, HttpServletResponse res) throws ServletException {
                String forwardedFrom = (String) req.getAttribute("javax.servlet.forward.request_uri");
                String forwardedQuery = (String) req.getAttribute("javax.servlet.forward.query_string");
                res.setStatus(200);
                res.getWriter().write("target uri=" + req.getRequestURI()
                        + " query=" + req.getQueryString()
                        + " from=" + forwardedFrom
                        + " fromQuery=" + forwardedQuery);
            }
        });

        router.addServlet("/start", new HttpServlet() {
            @Override
            public void service(HttpServletRequest req, HttpServletResponse res) throws ServletException {
                res.getWriter().write("before-forward");
                RequestDispatcher dispatcher = req.getRequestDispatcher("/target?next=true");
                dispatcher.forward(req, res);
            }
        });

        router.addServlet("/fragment", new HttpServlet() {
            @Override
            public void service(HttpServletRequest req, HttpServletResponse res) throws ServletException {
                String includeUri = (String) req.getAttribute("javax.servlet.include.request_uri");
                String includeQuery = (String) req.getAttribute("javax.servlet.include.query_string");
                res.setStatus(201);
                res.setHeader("X-Include", "blocked");
                res.getWriter().write("[fragment includeUri=" + includeUri
                        + " includeQuery=" + includeQuery
                        + " requestUri=" + req.getRequestURI() + "]");
            }
        });

        router.addServlet("/page", new HttpServlet() {
            @Override
            public void service(HttpServletRequest req, HttpServletResponse res) throws ServletException {
                res.setStatus(202);
                res.getWriter().write("A");
                RequestDispatcher dispatcher = req.getRequestDispatcher("/fragment?part=1");
                dispatcher.include(req, res);
                res.getWriter().write("B");
            }
        });

        router.addServlet("/committed", new HttpServlet() {
            @Override
            public void service(HttpServletRequest req, HttpServletResponse res) throws ServletException {
                res.getWriter().write("committed");
                res.flushBuffer();
                RequestDispatcher dispatcher = req.getRequestDispatcher("/target");
                dispatcher.forward(req, res);
            }
        });

        router.initialize();
    }

    @Test
    public void forward_replacesBufferedResponseAndUpdatesDispatchAttributes() {
        HttpExecutionResult result = execute(raw("GET /start?source=yes HTTP/1.1\r\nHost: localhost\r\n\r\n"));
        String response = joinSegments(result);

        assertTrue(response.startsWith("HTTP/1.1 200"));
        assertFalse(response.contains("before-forward"));
        assertTrue(response.contains("target uri=/target"));
        assertTrue(response.contains("query=next=true"));
        assertTrue(response.contains("from=/start"));
        assertTrue(response.contains("fromQuery=source=yes"));
    }

    @Test
    public void include_appendsOutputButCannotChangeOuterStatusOrHeaders() {
        HttpExecutionResult result = execute(raw("GET /page HTTP/1.1\r\nHost: localhost\r\n\r\n"));
        String response = joinSegments(result);

        assertTrue(response.startsWith("HTTP/1.1 202"));
        assertFalse(response.contains("X-Include"));
        assertTrue(response.contains("A[fragment includeUri=/fragment?part=1 includeQuery=part=1 requestUri=/page]B"));
    }

    @Test
    public void forward_afterCommitFallsBackToServerError() {
        HttpExecutionResult result = execute(raw("GET /committed HTTP/1.1\r\nHost: localhost\r\n\r\n"));
        String response = joinSegments(result);

        assertTrue(response.startsWith("HTTP/1.1 500"));
        assertEquals(500, result.statusCode());
    }

    private HttpExecutionResult execute(byte[] rawRequest) {
        ParsedHttpRequest parsed = HttpRequestParser.parse(rawRequest, rawRequest.length);
        return HttpRequestExecutor.execute(
                parsed,
                router,
                "127.0.0.1",
                12345,
                8080,
                RequestLimits.defaults(rawRequest.length));
    }

    private static String joinSegments(HttpExecutionResult result) {
        StringBuilder sb = new StringBuilder();
        for (byte[] segment : result.responseSegments()) {
            if (segment != null) {
                sb.append(new String(segment, StandardCharsets.ISO_8859_1));
            }
        }
        return sb.toString();
    }

    private static byte[] raw(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }
}
