package com.fastjava.server;

import com.fastjava.http.parser.HttpRequestParser;
import com.fastjava.http.parser.ParsedHttpRequest;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

public class HttpRequestInspectorTest {

    @Test
    public void testValidateRequestLineTooLong() {
        RequestLimits limits = new RequestLimits(512, 5_000, 8, 128, 64, 64, 8);
        byte[] request = "GET /this/path/is/too/long".getBytes(StandardCharsets.US_ASCII);

        RequestValidationResult result = HttpRequestInspector.validateBufferedRequest(request, request.length, limits);
        assertTrue(result.hasError());
        assertEquals(414, result.statusCode());
    }

    @Test
    public void testValidateHeadersTooLargeWhenIncomplete() {
        RequestLimits limits = new RequestLimits(128, 5_000, 64, 20, 64, 64, 8);
        byte[] request = "GET / HTTP/1.1\r\nHeader: value-without-ending".getBytes(StandardCharsets.US_ASCII);

        RequestValidationResult result = HttpRequestInspector.validateBufferedRequest(request, request.length, limits);
        assertTrue(result.hasError());
        assertEquals(431, result.statusCode());
    }

    @Test
    public void testValidateContentLengthAndTransferEncodingConflict() {
        RequestLimits limits = RequestLimits.defaults(2048);
        byte[] request = ("POST /upload HTTP/1.1\r\n"
                + "Host: localhost\r\n"
                + "Content-Length: 4\r\n"
                + "Transfer-Encoding: chunked\r\n"
                + "\r\n"
                + "test")
                .getBytes(StandardCharsets.US_ASCII);

        RequestValidationResult result = HttpRequestInspector.validateBufferedRequest(request, request.length, limits);
        assertTrue(result.hasError());
        assertEquals(400, result.statusCode());
    }

    @Test
    public void testValidateUnsupportedTransferEncoding() {
        RequestLimits limits = RequestLimits.defaults(2048);
        byte[] request = ("POST /upload HTTP/1.1\r\n"
                + "Host: localhost\r\n"
                + "Transfer-Encoding: gzip\r\n"
                + "\r\n")
                .getBytes(StandardCharsets.US_ASCII);

        RequestValidationResult result = HttpRequestInspector.validateBufferedRequest(request, request.length, limits);
        assertTrue(result.hasError());
        assertEquals(501, result.statusCode());
    }

    @Test
    public void testValidateRejectsTransferEncodingChainWithChunked() {
        RequestLimits limits = RequestLimits.defaults(2048);
        byte[] request = ("POST /upload HTTP/1.1\r\n"
                + "Host: localhost\r\n"
                + "Transfer-Encoding: gzip, chunked\r\n"
                + "\r\n")
                .getBytes(StandardCharsets.US_ASCII);

        RequestValidationResult result = HttpRequestInspector.validateBufferedRequest(request, request.length, limits);
        assertTrue(result.hasError());
        assertEquals(501, result.statusCode());
    }

    @Test
    public void testValidateRejectsDuplicateContentLengthHeaders() {
        RequestLimits limits = RequestLimits.defaults(2048);
        byte[] request = ("POST /upload HTTP/1.1\r\n"
                + "Host: localhost\r\n"
                + "Content-Length: 4\r\n"
                + "Content-Length: 4\r\n"
                + "\r\n"
                + "test")
                .getBytes(StandardCharsets.US_ASCII);

        RequestValidationResult result = HttpRequestInspector.validateBufferedRequest(request, request.length, limits);
        assertTrue(result.hasError());
        assertEquals(400, result.statusCode());
    }

    @Test
    public void testValidateRejectsDuplicateTransferEncodingHeaders() {
        RequestLimits limits = RequestLimits.defaults(2048);
        byte[] request = ("POST /upload HTTP/1.1\r\n"
                + "Host: localhost\r\n"
                + "Transfer-Encoding: chunked\r\n"
                + "Transfer-Encoding: chunked\r\n"
                + "\r\n")
                .getBytes(StandardCharsets.US_ASCII);

        RequestValidationResult result = HttpRequestInspector.validateBufferedRequest(request, request.length, limits);
        assertTrue(result.hasError());
        assertEquals(400, result.statusCode());
    }

    @Test
    public void testValidateRejectsObsFoldedHeaders() {
        RequestLimits limits = RequestLimits.defaults(2048);
        byte[] request = ("GET / HTTP/1.1\r\n"
                + "Host: localhost\r\n"
                + " X-Folded: value\r\n"
                + "\r\n")
                .getBytes(StandardCharsets.US_ASCII);

        RequestValidationResult result = HttpRequestInspector.validateBufferedRequest(request, request.length, limits);
        assertTrue(result.hasError());
        assertEquals(400, result.statusCode());
    }

    @Test
    public void testValidateRejectsMalformedChunkTrailerWithoutColon() {
        RequestLimits limits = RequestLimits.defaults(2048);
        byte[] request = ("POST /upload HTTP/1.1\r\n"
                + "Host: localhost\r\n"
                + "Transfer-Encoding: chunked\r\n"
                + "\r\n"
                + "4\r\n"
                + "test\r\n"
                + "0\r\n"
                + "BadTrailer\r\n"
                + "\r\n")
                .getBytes(StandardCharsets.US_ASCII);

        RequestValidationResult result = HttpRequestInspector.validateBufferedRequest(request, request.length, limits);
        assertTrue(result.hasError());
        assertEquals(400, result.statusCode());
    }

    @Test
    public void testValidateChunkedMalformedBody() {
        RequestLimits limits = RequestLimits.defaults(2048);
        byte[] request = ("POST /upload HTTP/1.1\r\n"
                + "Host: localhost\r\n"
                + "Transfer-Encoding: chunked\r\n"
                + "\r\n"
                + "zz\r\n"
                + "hello\r\n")
                .getBytes(StandardCharsets.US_ASCII);

        RequestValidationResult result = HttpRequestInspector.validateBufferedRequest(request, request.length, limits);
        assertTrue(result.hasError());
        assertEquals(400, result.statusCode());
    }

    @Test
    public void testValidateChunkedBodyTooLarge() {
        RequestLimits limits = new RequestLimits(1024, 5_000, 128, 256, 4, 8, 8);
        byte[] request = ("POST /upload HTTP/1.1\r\n"
                + "Host: localhost\r\n"
                + "Transfer-Encoding: chunked\r\n"
                + "\r\n"
                + "5\r\n"
                + "hello\r\n"
                + "0\r\n"
                + "\r\n")
                .getBytes(StandardCharsets.US_ASCII);

        RequestValidationResult result = HttpRequestInspector.validateBufferedRequest(request, request.length, limits);
        assertTrue(result.hasError());
        assertEquals(413, result.statusCode());
    }

    @Test
    public void testExpectationStatusCode() {
        byte[] badExpect = ("POST /upload HTTP/1.1\r\n"
                + "Host: localhost\r\n"
                + "Expect: weird\r\n"
                + "\r\n")
                .getBytes(StandardCharsets.US_ASCII);
        byte[] goodExpect = ("POST /upload HTTP/1.1\r\n"
                + "Host: localhost\r\n"
                + "Expect: 100-continue\r\n"
                + "\r\n")
                .getBytes(StandardCharsets.US_ASCII);

        assertEquals(417, HttpRequestInspector.expectationStatusCode(badExpect, badExpect.length));
        assertEquals(0, HttpRequestInspector.expectationStatusCode(goodExpect, goodExpect.length));
    }

    @Test
    public void testShouldSendContinueOnlyForIncompleteBody() {
        RequestLimits limits = RequestLimits.defaults(2048);
        byte[] incomplete = ("POST /upload HTTP/1.1\r\n"
                + "Host: localhost\r\n"
                + "Expect: 100-continue\r\n"
                + "Content-Length: 10\r\n"
                + "\r\n"
                + "abc")
                .getBytes(StandardCharsets.US_ASCII);
        byte[] complete = ("POST /upload HTTP/1.1\r\n"
                + "Host: localhost\r\n"
                + "Expect: 100-continue\r\n"
                + "Content-Length: 3\r\n"
                + "\r\n"
                + "abc")
                .getBytes(StandardCharsets.US_ASCII);

        assertTrue(HttpRequestInspector.shouldSendContinue(incomplete, incomplete.length, limits));
        assertFalse(HttpRequestInspector.shouldSendContinue(complete, complete.length, limits));
    }

    @Test
    public void testShouldKeepAliveDecisions() {
        ParsedHttpRequest implicitKeepAlive = parsed("HTTP/1.1", map());
        ParsedHttpRequest explicitClose = parsed("HTTP/1.1", map("Connection", "close"));
        ParsedHttpRequest explicitKeepAlive = parsed("HTTP/1.0", map("Connection", "keep-alive"));
        ParsedHttpRequest http10Default = parsed("HTTP/1.0", map());

        assertTrue(HttpRequestInspector.shouldKeepAlive(implicitKeepAlive));
        assertFalse(HttpRequestInspector.shouldKeepAlive(explicitClose));
        assertTrue(HttpRequestInspector.shouldKeepAlive(explicitKeepAlive));
        assertFalse(HttpRequestInspector.shouldKeepAlive(http10Default));
    }

    @Test
    public void testValidWebSocketUpgradeRequest() {
        ParsedHttpRequest request = parsed(
                "GET",
                "HTTP/1.1",
                map(
                        "Upgrade", "websocket",
                        "Connection", "keep-alive, Upgrade",
                        "Sec-WebSocket-Version", "13",
                        "Sec-WebSocket-Key", "dGhlIHNhbXBsZSBub25jZQ=="));

        assertTrue(HttpRequestInspector.isWebSocketUpgradeAttempt(request));
        assertTrue(HttpRequestInspector.isValidWebSocketUpgradeRequest(request));
        assertEquals("dGhlIHNhbXBsZSBub25jZQ==", HttpRequestInspector.extractWebSocketKeyIfValid(request));
    }

    @Test
    public void testInvalidWebSocketUpgradeRequest() {
        ParsedHttpRequest request = parsed(
                "GET",
                "HTTP/1.1",
                map(
                        "Upgrade", "websocket",
                        "Connection", "Upgrade",
                        "Sec-WebSocket-Version", "12",
                        "Sec-WebSocket-Key", "invalid"));

        assertTrue(HttpRequestInspector.isWebSocketUpgradeAttempt(request));
        assertFalse(HttpRequestInspector.isValidWebSocketUpgradeRequest(request));
        assertNull(HttpRequestInspector.extractWebSocketKeyIfValid(request));
    }

    private static ParsedHttpRequest parsed(String version, Map<String, String> headers) {
        return parsed("GET", version, headers);
    }

    private static ParsedHttpRequest parsed(String method, String version, Map<String, String> headers) {
        return new ParsedHttpRequest(
                new HttpRequestParser.RequestLine(method, "/", version),
                headers,
                new byte[0],
                0,
                0,
                0);
    }

    private static Map<String, String> map(String... pairs) {
        Map<String, String> result = new HashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            result.put(pairs[index], pairs[index + 1]);
        }
        return result;
    }
}