package com.fastjava.http.parser;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

/**
 * Unit tests for HTTP request parsing.
 */
public class HttpRequestParserTest {

    @Test
    public void testParseSimpleGetRequest() {
        byte[] request = ("GET / HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "\r\n").getBytes();

        ParsedHttpRequest parsed = HttpRequestParser.parse(request, request.length);
        assertNotNull(parsed);
        assertEquals("GET", parsed.getMethod());
        assertEquals("/", parsed.getURI());
        assertEquals("HTTP/1.1", parsed.getVersion());
        assertEquals("localhost", parsed.getHeader("Host"));
    }

    @Test
    public void testParsePostWithBody() {
        byte[] request = ("POST /api/users HTTP/1.1\r\n" +
                "Host: api.example.com\r\n" +
                "Content-Type: application/json\r\n" +
                "Content-Length: 24\r\n" +
                "\r\n" +
                "{\"name\":\"John\",\"age\":30}").getBytes();

        ParsedHttpRequest parsed = HttpRequestParser.parse(request, request.length);
        assertNotNull(parsed);
        assertEquals("POST", parsed.getMethod());
        assertEquals("/api/users", parsed.getURI());
        assertEquals("application/json", parsed.getHeader("Content-Type"));
        assertEquals("24", parsed.getHeader("Content-Length"));
        assertEquals(24, parsed.bodyLength);
        assertEquals(request.length, parsed.bytesConsumed);
    }

    @Test
    public void testParseWithQueryString() {
        byte[] request = ("GET /search?q=java&sort=date HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "\r\n").getBytes();

        ParsedHttpRequest parsed = HttpRequestParser.parse(request, request.length);
        assertNotNull(parsed);
        assertEquals("/search?q=java&sort=date", parsed.getURI());
    }

    @Test
    public void testParseMultipleHeaders() {
        byte[] request = ("GET / HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Accept: text/html\r\n" +
                "User-Agent: Mozilla/5.0\r\n" +
                "Accept-Encoding: gzip, deflate\r\n" +
                "\r\n").getBytes();

        ParsedHttpRequest parsed = HttpRequestParser.parse(request, request.length);
        assertNotNull(parsed);
        assertEquals("localhost", parsed.getHeader("Host"));
        assertEquals("text/html", parsed.getHeader("Accept"));
        assertEquals("Mozilla/5.0", parsed.getHeader("User-Agent"));
    }

    @Test
    public void testParseIncompleteRequest() {
        byte[] request = "GET / HTTP/1.1\r\nHost: localhost".getBytes();
        ParsedHttpRequest parsed = HttpRequestParser.parse(request, request.length);
        assertNull(parsed); // Should be incomplete
    }

    @Test
    public void testParseIncompleteBodyReturnsNull() {
        byte[] request = ("POST /api/users HTTP/1.1\r\n" +
                "Host: api.example.com\r\n" +
                "Content-Length: 10\r\n" +
                "\r\n" +
                "12345").getBytes();

        ParsedHttpRequest parsed = HttpRequestParser.parse(request, request.length);
        assertNull(parsed);
    }

    @Test
    public void testParseLeavesNextRequestUnreadWhenNoBodyExists() {
        byte[] request = ("GET /first HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "\r\n" +
                "GET /second HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "\r\n").getBytes();

        ParsedHttpRequest parsed = HttpRequestParser.parse(request, request.length);
        assertNotNull(parsed);
        assertEquals("/first", parsed.getURI());
        assertTrue(parsed.bytesConsumed < request.length);
    }

    @Test
    public void testParsePutRequest() {
        byte[] request = ("PUT /api/users/123 HTTP/1.1\r\n" +
                "Host: api.example.com\r\n" +
                "\r\n").getBytes();

        ParsedHttpRequest parsed = HttpRequestParser.parse(request, request.length);
        assertNotNull(parsed);
        assertEquals("PUT", parsed.getMethod());
        assertEquals("/api/users/123", parsed.getURI());
    }

    @Test
    public void testParseDeleteRequest() {
        byte[] request = ("DELETE /api/users/123 HTTP/1.1\r\n" +
                "Host: api.example.com\r\n" +
                "\r\n").getBytes();

        ParsedHttpRequest parsed = HttpRequestParser.parse(request, request.length);
        assertNotNull(parsed);
        assertEquals("DELETE", parsed.getMethod());
    }

    @Test
    public void testHeaderValueTrimming() {
        byte[] request = ("GET / HTTP/1.1\r\n" +
                "X-Custom:   value with spaces   \r\n" +
                "\r\n").getBytes();

        ParsedHttpRequest parsed = HttpRequestParser.parse(request, request.length);
        assertNotNull(parsed);
        String value = parsed.getHeader("X-Custom");
        assertEquals("value with spaces", value);
    }

    @Test
    public void testMixedCaseHeaderLookupIsCaseInsensitive() {
        byte[] request = ("GET / HTTP/1.1\r\n" +
                "hOsT: localhost\r\n" +
                "cOnTeNt-LeNgTh: 0\r\n" +
                "\r\n").getBytes(StandardCharsets.US_ASCII);

        ParsedHttpRequest parsed = HttpRequestParser.parse(request, request.length);
        assertNotNull(parsed);
        assertEquals("localhost", parsed.getHeader("Host"));
        assertEquals("localhost", parsed.getHeader("HOST"));
        assertEquals("0", parsed.getHeader("Content-Length"));
    }

    @Test
    public void testDuplicateHeaderNamesWithDifferentCaseOverwriteByLastValue() {
        byte[] request = ("GET / HTTP/1.1\r\n" +
                "X-Trace: first\r\n" +
                "x-trace: second\r\n" +
                "\r\n").getBytes(StandardCharsets.US_ASCII);

        ParsedHttpRequest parsed = HttpRequestParser.parse(request, request.length);
        assertNotNull(parsed);
        assertEquals("second", parsed.getHeader("X-Trace"));
    }

    @Test
    public void testParseChunkedRequestBody() {
        byte[] request = ("POST /upload HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Transfer-Encoding: chunked\r\n" +
                "\r\n" +
                "5\r\n" +
                "hello\r\n" +
                "6\r\n" +
                " world\r\n" +
                "0\r\n" +
                "\r\n").getBytes(StandardCharsets.US_ASCII);

        ParsedHttpRequest parsed = HttpRequestParser.parse(request, request.length);
        assertNotNull(parsed);
        assertEquals("POST", parsed.getMethod());
        assertEquals("hello world", new String(parsed.getBody(), StandardCharsets.UTF_8));
        assertTrue(parsed.bodyLength > 11);
        assertEquals(request.length, parsed.bytesConsumed);
    }

    @Test
    public void testParseChunkedWithTrailersConsumesAllBytes() {
        byte[] request = ("POST /upload HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Transfer-Encoding: chunked\r\n" +
                "\r\n" +
                "4\r\n" +
                "test\r\n" +
                "0\r\n" +
                "X-Trailer: abc\r\n" +
                "\r\n").getBytes(StandardCharsets.US_ASCII);

        ParsedHttpRequest parsed = HttpRequestParser.parse(request, request.length);
        assertNotNull(parsed);
        assertEquals("test", new String(parsed.getBody(), StandardCharsets.UTF_8));
        assertEquals(request.length, parsed.bytesConsumed);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testParseRejectsObsFoldHeaders() {
        byte[] request = ("GET / HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                " X-Folded: value\r\n" +
                "\r\n").getBytes(StandardCharsets.US_ASCII);

        HttpRequestParser.parse(request, request.length);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testParseRejectsMalformedChunkedTrailerWithoutColon() {
        byte[] request = ("POST /upload HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Transfer-Encoding: chunked\r\n" +
                "\r\n" +
                "4\r\n" +
                "test\r\n" +
                "0\r\n" +
                "MalformedTrailer\r\n" +
                "\r\n").getBytes(StandardCharsets.US_ASCII);

        HttpRequestParser.parse(request, request.length);
    }

    @Test
    public void testParseIncompleteChunkedBodyReturnsNull() {
        byte[] request = ("POST /upload HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Transfer-Encoding: chunked\r\n" +
                "\r\n" +
                "5\r\n" +
                "hel").getBytes(StandardCharsets.US_ASCII);

        ParsedHttpRequest parsed = HttpRequestParser.parse(request, request.length);
        assertNull(parsed);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testParseMalformedChunkedBodyThrows() {
        byte[] request = ("POST /upload HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Transfer-Encoding: chunked\r\n" +
                "\r\n" +
                "zz\r\n" +
                "hello\r\n" +
                "0\r\n" +
                "\r\n").getBytes(StandardCharsets.US_ASCII);

        HttpRequestParser.parse(request, request.length);
    }

    @Test
    public void testParseChunkedBodySupportsExtensionsAndUppercaseHex() {
        byte[] request = ("POST /upload HTTP/1.1\r\n"
                + "Host: localhost\r\n"
                + "Transfer-Encoding: chunked\r\n"
                + "\r\n"
                + "A;foo=bar\r\n"
                + "0123456789\r\n"
                + "0\r\n"
                + "\r\n").getBytes(StandardCharsets.US_ASCII);

        ParsedHttpRequest parsed = HttpRequestParser.parse(request, request.length);
        assertNotNull(parsed);
        assertEquals("0123456789", new String(parsed.getBody(), StandardCharsets.US_ASCII));
    }

    @Test
    public void testParseChunkedBodyWithIncompleteTrailersReturnsNull() {
        byte[] request = ("POST /upload HTTP/1.1\r\n"
                + "Host: localhost\r\n"
                + "Transfer-Encoding: chunked\r\n"
                + "\r\n"
                + "4\r\n"
                + "test\r\n"
                + "0\r\n"
                + "X-Trailer: value\r\n").getBytes(StandardCharsets.US_ASCII);

        ParsedHttpRequest parsed = HttpRequestParser.parse(request, request.length);
        assertNull(parsed);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testParseRejectsDuplicateContentLengthHeaders() {
        byte[] request = ("POST /upload HTTP/1.1\r\n"
                + "Host: localhost\r\n"
                + "Content-Length: 4\r\n"
                + "Content-Length: 4\r\n"
                + "\r\n"
                + "test").getBytes(StandardCharsets.US_ASCII);

        HttpRequestParser.parse(request, request.length);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testParseRejectsDuplicateTransferEncodingHeaders() {
        byte[] request = ("POST /upload HTTP/1.1\r\n"
                + "Host: localhost\r\n"
                + "Transfer-Encoding: chunked\r\n"
                + "Transfer-Encoding: chunked\r\n"
                + "\r\n"
                + "0\r\n\r\n").getBytes(StandardCharsets.US_ASCII);

        HttpRequestParser.parse(request, request.length);
    }
}
