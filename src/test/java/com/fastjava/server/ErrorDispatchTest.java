package com.fastjava.server;

import com.fastjava.http.parser.HttpRequestParser;
import com.fastjava.http.parser.ParsedHttpRequest;
import com.fastjava.servlet.HttpServlet;
import com.fastjava.servlet.HttpServletRequest;
import com.fastjava.servlet.HttpServletResponse;
import com.fastjava.servlet.ServletException;
import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

/**
 * Unit tests for error-page dispatch via {@link HttpRequestExecutor}.
 * <p>
 * Tests call {@link HttpRequestExecutor#execute} directly — no network
 * required.
 */
public class ErrorDispatchTest {

    private static final byte[] GET_HELLO = raw("GET /hello HTTP/1.1\r\nHost: localhost\r\n\r\n");
    private static final byte[] GET_ERROR = raw("GET /error HTTP/1.1\r\nHost: localhost\r\n\r\n");
    private static final byte[] GET_UNKNOWN = raw("GET /unknown HTTP/1.1\r\nHost: localhost\r\n\r\n");
    private static final byte[] GET_ISE = raw("GET /ise HTTP/1.1\r\nHost: localhost\r\n\r\n");

    private ServletRouter router;

    @Before
    public void setUp() throws ServletException {
        router = new ServletRouter();

        // Normal servlet
        router.addServlet("/hello", new HttpServlet() {
            @Override
            public void service(HttpServletRequest req, HttpServletResponse res) throws ServletException {
                res.setStatus(200);
                res.getWriter().write("OK");
            }
        });

        // Servlet that always throws
        router.addServlet("/error", new HttpServlet() {
            @Override
            public void service(HttpServletRequest req, HttpServletResponse res) throws ServletException {
                throw new IllegalArgumentException("bad input");
            }
        });

        // Servlet that throws a subclass of RuntimeException
        router.addServlet("/ise", new HttpServlet() {
            @Override
            public void service(HttpServletRequest req, HttpServletResponse res) throws ServletException {
                throw new IllegalStateException("illegal state");
            }
        });

        // Error page servlet for 500
        router.addServlet("/err/500", new HttpServlet() {
            @Override
            public void service(HttpServletRequest req, HttpServletResponse res) throws ServletException {
                res.setStatus(500);
                String msg = (String) req.getAttribute(HttpErrorDispatcher.ERROR_MESSAGE);
                res.getWriter().write("ERROR: " + msg);
            }
        });

        // Error page servlet for 404
        router.addServlet("/err/404", new HttpServlet() {
            @Override
            public void service(HttpServletRequest req, HttpServletResponse res) throws ServletException {
                res.setStatus(404);
                res.getWriter().write("NOT FOUND");
            }
        });

        router.initialize();
    }

    @Test
    public void normalRequest_notAffected() {
        HttpExecutionResult result = execute(GET_HELLO);
        String response = joinSegments(result);
        assertTrue("Response should start with HTTP/1.1 200", response.startsWith("HTTP/1.1 200"));
        assertTrue("Body should contain OK", response.contains("OK"));
    }

    @Test
    public void noErrorPage_exceptionCausesPlain500() {
        // No error pages registered — should fall back to default sendError(500)
        HttpExecutionResult result = execute(GET_ERROR);
        String response = joinSegments(result);
        assertTrue("Response should be 500", response.startsWith("HTTP/1.1 500"));
    }

    @Test
    public void exceptionErrorPage_registered_dispatchesToErrorServlet() throws ServletException {
        router.addErrorPage(ErrorPage.forException(IllegalArgumentException.class, "/err/500"));

        HttpExecutionResult result = execute(GET_ERROR);
        String response = joinSegments(result);
        assertTrue("Response should be 500", response.contains("HTTP/1.1 500"));
        assertTrue("Body should include error message", response.contains("ERROR: bad input"));
    }

    @Test
    public void exceptionErrorPage_hierarchyMatch_usesSupertypeMapping() throws ServletException {
        // Register for RuntimeException — IllegalStateException is a subtype
        router.addErrorPage(ErrorPage.forException(RuntimeException.class, "/err/500"));

        HttpExecutionResult result = execute(GET_ISE);
        String response = joinSegments(result);
        assertTrue("Response should be 500", response.contains("HTTP/1.1 500"));
        assertTrue("Body should contain error message", response.contains("illegal state"));
    }

    @Test
    public void statusErrorPage_404_dispatchesToErrorServlet() throws ServletException {
        router.addErrorPage(ErrorPage.forStatus(404, "/err/404"));

        HttpExecutionResult result = execute(GET_UNKNOWN);
        String response = joinSegments(result);
        assertTrue("Response should be 404", response.contains("HTTP/1.1 404"));
        assertTrue("Body should contain NOT FOUND", response.contains("NOT FOUND"));
    }

    @Test
    public void errorAttributes_statusCode_setCorrectly() throws ServletException {
        // Capture request attributes during error dispatch
        int[] capturedStatus = { 0 };
        router.addServlet("/err/check", new HttpServlet() {
            @Override
            public void service(HttpServletRequest req, HttpServletResponse res) throws ServletException {
                capturedStatus[0] = (Integer) req.getAttribute(HttpErrorDispatcher.ERROR_STATUS_CODE);
                res.setStatus(500);
                res.getWriter().write("done");
            }
        });
        router.addErrorPage(ErrorPage.forException(RuntimeException.class, "/err/check"));

        execute(GET_ISE);
        assertEquals("ERROR_STATUS_CODE should be 500", 500, capturedStatus[0]);
    }

    @Test
    public void errorAttributes_requestUri_htmlEncoded() throws ServletException {
        // Simulate a request URI with injected HTML characters
        byte[] xssRequest = raw("GET /evil?q=<script> HTTP/1.1\r\nHost: localhost\r\n\r\n");
        router.addServlet("/evil", new HttpServlet() {
            @Override
            public void service(HttpServletRequest req, HttpServletResponse res) throws ServletException {
                throw new RuntimeException("xss");
            }
        });

        String[] capturedUri = { "" };
        router.addServlet("/err/xss", new HttpServlet() {
            @Override
            public void service(HttpServletRequest req, HttpServletResponse res) throws ServletException {
                capturedUri[0] = (String) req.getAttribute(HttpErrorDispatcher.ERROR_REQUEST_URI);
                res.setStatus(500);
                res.getWriter().write("done");
            }
        });
        router.addErrorPage(ErrorPage.forException(RuntimeException.class, "/err/xss"));

        execute(xssRequest);
        assertFalse("Raw '<' must not appear in error request URI attribute", capturedUri[0].contains("<"));
        assertTrue("HTML-encoded '<' should be present", capturedUri[0].contains("&lt;"));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

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

    private static byte[] raw(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }
}
