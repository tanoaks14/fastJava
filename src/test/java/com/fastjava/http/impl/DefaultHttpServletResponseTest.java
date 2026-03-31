package com.fastjava.http.impl;

import com.fastjava.servlet.Cookie;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.junit.Assert.*;

public class DefaultHttpServletResponseTest {

    @Test
    public void testDefaultOutputBufferContainsBodyAndLength() {
        DefaultHttpServletResponse response = new DefaultHttpServletResponse(64);
        response.setContentType("text/plain");
        response.getWriter().print("hello");

        String payload = new String(response.getOutputBuffer(), StandardCharsets.US_ASCII);
        assertTrue(payload.contains("Content-Type: text/plain"));
        assertTrue(payload.contains("Content-Length: 5"));
        assertTrue(payload.endsWith("hello"));
    }

    @Test
    public void testChunkedOutputContainsTransferEncoding() {
        DefaultHttpServletResponse response = new DefaultHttpServletResponse(64);
        response.setChunkedResponseEnabled(true);
        response.getWriter().print("hello");

        String payload = new String(response.getOutputBuffer(), StandardCharsets.US_ASCII);
        assertTrue(response.isChunkedResponseEnabled());
        assertTrue(payload.contains("Transfer-Encoding: chunked"));
        assertFalse(payload.contains("Content-Length: 5"));
        assertTrue(payload.contains("\r\n5\r\nhello\r\n0\r\n\r\n"));
    }

    @Test
    public void testSendErrorCommitsResponse() {
        DefaultHttpServletResponse response = new DefaultHttpServletResponse(64);
        response.sendError(404);

        String payload = new String(response.getOutputBuffer(), StandardCharsets.US_ASCII);
        assertEquals(404, response.getStatus());
        assertTrue(response.isCommitted());
        assertTrue(payload.contains("HTTP/1.1 404 Not Found"));
        assertTrue(payload.contains("Error 404"));
    }

    @Test
    public void testSendRedirectSetsLocationAndCommits() {
        DefaultHttpServletResponse response = new DefaultHttpServletResponse(64);
        response.sendRedirect("/new-path");

        String payload = new String(response.getOutputBuffer(), StandardCharsets.US_ASCII);
        assertEquals(302, response.getStatus());
        assertTrue(response.isCommitted());
        assertTrue(payload.contains("HTTP/1.1 302 Found"));
        assertTrue(payload.contains("Location: /new-path"));
    }

    @Test
    public void testFlushBufferMarksCommitted() {
        DefaultHttpServletResponse response = new DefaultHttpServletResponse(64);
        assertFalse(response.isCommitted());

        response.flushBuffer();
        assertTrue(response.isCommitted());
    }

    @Test
    public void testAddCookieSerializesSetCookieHeader() {
        DefaultHttpServletResponse response = new DefaultHttpServletResponse(128);
        Cookie cookie = new Cookie("session", "abc123");
        cookie.setPath("/");
        cookie.setHttpOnly(true);
        cookie.setSecure(true);
        cookie.setSameSite("lax");
        cookie.setMaxAge(600);
        cookie.setExpires(Instant.parse("2030-01-01T00:00:00Z"));

        response.addCookie(cookie);
        response.getWriter().print("ok");

        String payload = new String(response.getOutputBuffer(), StandardCharsets.US_ASCII);
        assertTrue(payload.contains("Set-Cookie: session=abc123"));
        assertTrue(payload.contains("; Path=/"));
        assertTrue(payload.contains("; Max-Age=600"));
        assertTrue(payload.contains("; Expires=Tue, 1 Jan 2030 00:00:00 GMT"));
        assertTrue(payload.contains("; Secure"));
        assertTrue(payload.contains("; HttpOnly"));
        assertTrue(payload.contains("; SameSite=Lax"));
    }

    @Test
    public void testAddCookieAllowsMultipleSetCookieHeaders() {
        DefaultHttpServletResponse response = new DefaultHttpServletResponse(128);
        response.addCookie(new Cookie("a", "1"));
        response.addCookie(new Cookie("b", "2"));
        response.getWriter().print("ok");

        String payload = new String(response.getOutputBuffer(), StandardCharsets.US_ASCII);
        assertTrue(payload.contains("Set-Cookie: a=1"));
        assertTrue(payload.contains("Set-Cookie: b=2"));
    }
}