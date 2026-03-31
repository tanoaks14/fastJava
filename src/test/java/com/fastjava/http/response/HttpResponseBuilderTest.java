package com.fastjava.http.response;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

public class HttpResponseBuilderTest {

    @Test
    public void testBuildNonChunkedIncludesContentLengthAndBody() {
        HttpResponseBuilder builder = new HttpResponseBuilder(64)
                .setStatus(200)
                .setContentType("text/plain")
                .setBody("hello".getBytes(StandardCharsets.UTF_8));

        byte[] response = builder.build();
        String text = new String(response, StandardCharsets.US_ASCII);

        assertTrue(text.contains("HTTP/1.1 200 OK"));
        assertTrue(text.contains("Content-Type: text/plain"));
        assertTrue(text.contains("Content-Length: 5"));
        assertTrue(text.endsWith("hello"));
    }

    @Test
    public void testBuildChunkedSkipsContentLength() {
        HttpResponseBuilder builder = new HttpResponseBuilder(64)
                .setHeader("Content-Length", "999")
                .setBody("hello".getBytes(StandardCharsets.UTF_8));

        byte[][] segments = builder.buildSegments(true);
        String flattened = new String(HttpResponseBuilder.flattenSegments(segments), StandardCharsets.US_ASCII);

        assertTrue(flattened.contains("Transfer-Encoding: chunked"));
        assertFalse(flattened.contains("Content-Length: 999"));
        assertTrue(flattened.contains("\r\n5\r\nhello\r\n0\r\n\r\n"));
    }

    @Test
    public void testRemoveHeaderCaseInsensitive() {
        HttpResponseBuilder builder = new HttpResponseBuilder(64)
                .setHeader("X-Test", "1")
                .setHeader("Content-Length", "7")
                .setBody("abc".getBytes(StandardCharsets.UTF_8));

        builder.removeHeader("content-length");
        String flattened = new String(builder.build(), StandardCharsets.US_ASCII);

        assertTrue(flattened.contains("Content-Length: 3"));
        assertFalse(flattened.contains("Content-Length: 7"));
    }

    @Test
    public void testFlattenSegmentsHandlesNullSegment() {
        byte[] flattened = HttpResponseBuilder.flattenSegments(new byte[][] {
                "a".getBytes(StandardCharsets.US_ASCII),
                null,
                "b".getBytes(StandardCharsets.US_ASCII)
        });

        assertEquals("ab", new String(flattened, StandardCharsets.US_ASCII));
    }

    @Test
    public void testAddHeaderPreservesRepeatedSetCookieValues() {
        HttpResponseBuilder builder = new HttpResponseBuilder(128)
                .addHeader("Set-Cookie", "a=1")
                .addHeader("Set-Cookie", "b=2")
                .setBody("ok".getBytes(StandardCharsets.UTF_8));

        String payload = new String(builder.build(), StandardCharsets.US_ASCII);
        assertTrue(payload.contains("Set-Cookie: a=1"));
        assertTrue(payload.contains("Set-Cookie: b=2"));
    }
}