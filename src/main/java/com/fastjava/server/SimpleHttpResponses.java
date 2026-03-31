package com.fastjava.server;

import com.fastjava.http.response.HttpResponseBuilder;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public final class SimpleHttpResponses {

    private static final byte[] HTTP_100_CONTINUE = "HTTP/1.1 100 Continue\r\n\r\n"
            .getBytes(StandardCharsets.US_ASCII);
    private static final Map<ResponseKey, byte[]> CACHED_PLAIN_TEXT_RESPONSES = createCachedPlainTextResponses();

    private SimpleHttpResponses() {
    }

    public static byte[] plainText(int statusCode, String message) {
        byte[] cached = CACHED_PLAIN_TEXT_RESPONSES.get(new ResponseKey(statusCode, message));
        if (cached != null) {
            return cached;
        }
        HttpResponseBuilder builder = new HttpResponseBuilder(256);
        builder.setStatus(statusCode)
                .setContentType("text/plain")
                .setHeader("Connection", "close")
                .setBody(message.getBytes(StandardCharsets.US_ASCII));
        return builder.build();
    }

    public static byte[] provisionalContinue() {
        return HTTP_100_CONTINUE;
    }

    private static Map<ResponseKey, byte[]> createCachedPlainTextResponses() {
        Map<ResponseKey, byte[]> cache = new HashMap<>();
        cache.put(new ResponseKey(400, "Bad Request"), buildPlainText(400, "Bad Request"));
        cache.put(new ResponseKey(404, "Not Found"), buildPlainText(404, "Not Found"));
        cache.put(new ResponseKey(408, "Request Timeout"), buildPlainText(408, "Request Timeout"));
        cache.put(new ResponseKey(413, "Payload Too Large"), buildPlainText(413, "Payload Too Large"));
        cache.put(new ResponseKey(414, "URI Too Long"), buildPlainText(414, "URI Too Long"));
        cache.put(new ResponseKey(431, "Request Header Fields Too Large"),
                buildPlainText(431, "Request Header Fields Too Large"));
        cache.put(new ResponseKey(500, "Internal Server Error"), buildPlainText(500, "Internal Server Error"));
        cache.put(new ResponseKey(501, "Not Implemented"), buildPlainText(501, "Not Implemented"));
        cache.put(new ResponseKey(503, "Service Unavailable"), buildPlainText(503, "Service Unavailable"));
        return Map.copyOf(cache);
    }

    private static byte[] buildPlainText(int statusCode, String message) {
        HttpResponseBuilder builder = new HttpResponseBuilder(256);
        builder.setStatus(statusCode)
                .setContentType("text/plain")
                .setHeader("Connection", "close")
                .setBody(message.getBytes(StandardCharsets.US_ASCII));
        return builder.build();
    }

    private record ResponseKey(int statusCode, String message) {
    }
}