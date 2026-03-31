package com.fastjava.http.parser;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Map;

/**
 * Represents a parsed HTTP request with minimal overhead.
 * Direct access to buffer for zero-copy operations when possible.
 */
public class ParsedHttpRequest {
    private static final String HEADER_CONTENT_LENGTH = "content-length";
    private static final String HEADER_CONTENT_TYPE = "content-type";
    private static final String HEADER_TRANSFER_ENCODING = "transfer-encoding";
    private static final String HEADER_HOST = "host";
    private static final String HEADER_CONNECTION = "connection";
    private static final String HEADER_COOKIE = "cookie";
    private static final String HEADER_ACCEPT_ENCODING = "accept-encoding";

    public final HttpRequestParser.RequestLine requestLine;
    public final Map<String, String> headers;
    public final byte[] bodyBuffer;
    public final int bodyOffset;
    public final int bodyLength;
    public final int bytesConsumed;
    public final boolean chunkedBody;
    private final InputStream liveBodyStream;
    private final boolean closeAfterResponse;
    private final String headerContentLength;
    private final String headerContentType;
    private final String headerTransferEncoding;
    private final String headerHost;
    private final String headerConnection;
    private final String headerCookie;
    private final String headerAcceptEncoding;
    private volatile byte[] decodedBodyCache;

    public ParsedHttpRequest(
            HttpRequestParser.RequestLine requestLine,
            Map<String, String> headers,
            byte[] bodyBuffer,
            int bodyOffset,
            int bodyLength,
            int bytesConsumed) {
        this(requestLine, headers, bodyBuffer, bodyOffset, bodyLength, bytesConsumed, false, null, false);
    }

    public ParsedHttpRequest(
            HttpRequestParser.RequestLine requestLine,
            Map<String, String> headers,
            byte[] bodyBuffer,
            int bodyOffset,
            int bodyLength,
            int bytesConsumed,
            boolean chunkedBody) {
        this(requestLine, headers, bodyBuffer, bodyOffset, bodyLength, bytesConsumed, chunkedBody, null, false);
    }

    public ParsedHttpRequest(
            HttpRequestParser.RequestLine requestLine,
            Map<String, String> headers,
            byte[] bodyBuffer,
            int bodyOffset,
            int bodyLength,
            int bytesConsumed,
            boolean chunkedBody,
            InputStream liveBodyStream,
            boolean closeAfterResponse) {
        this.requestLine = requestLine;
        this.headers = headers;
        this.bodyBuffer = bodyBuffer;
        this.bodyOffset = bodyOffset;
        this.bodyLength = bodyLength;
        this.bytesConsumed = bytesConsumed;
        this.chunkedBody = chunkedBody;
        this.liveBodyStream = liveBodyStream;
        this.closeAfterResponse = closeAfterResponse;
        this.headerContentLength = headers.get(HEADER_CONTENT_LENGTH);
        this.headerContentType = headers.get(HEADER_CONTENT_TYPE);
        this.headerTransferEncoding = headers.get(HEADER_TRANSFER_ENCODING);
        this.headerHost = headers.get(HEADER_HOST);
        this.headerConnection = headers.get(HEADER_CONNECTION);
        this.headerCookie = headers.get(HEADER_COOKIE);
        this.headerAcceptEncoding = headers.get(HEADER_ACCEPT_ENCODING);
    }

    public String getHeader(String name) {
        if (name == null) {
            return null;
        }
        String commonHeaderValue = commonHeaderValue(name);
        if (commonHeaderValue != null) {
            return commonHeaderValue;
        }

        if (isLowercaseAscii(name)) {
            return headers.get(name);
        }
        String exactCaseValue = headers.get(name);
        if (exactCaseValue != null) {
            return exactCaseValue;
        }
        return headers.get(lowercaseAscii(name));
    }

    private String commonHeaderValue(String name) {
        if (asciiEqualsIgnoreCase(name, HEADER_CONTENT_LENGTH)) {
            return headerContentLength;
        }
        if (asciiEqualsIgnoreCase(name, HEADER_CONTENT_TYPE)) {
            return headerContentType;
        }
        if (asciiEqualsIgnoreCase(name, HEADER_TRANSFER_ENCODING)) {
            return headerTransferEncoding;
        }
        if (asciiEqualsIgnoreCase(name, HEADER_HOST)) {
            return headerHost;
        }
        if (asciiEqualsIgnoreCase(name, HEADER_CONNECTION)) {
            return headerConnection;
        }
        if (asciiEqualsIgnoreCase(name, HEADER_COOKIE)) {
            return headerCookie;
        }
        if (asciiEqualsIgnoreCase(name, HEADER_ACCEPT_ENCODING)) {
            return headerAcceptEncoding;
        }
        return null;
    }

    private static boolean asciiEqualsIgnoreCase(String candidate, String lowercaseExpected) {
        if (candidate.length() != lowercaseExpected.length()) {
            return false;
        }
        for (int i = 0; i < candidate.length(); i++) {
            char ch = candidate.charAt(i);
            if (ch >= 'A' && ch <= 'Z') {
                ch = (char) (ch + ('a' - 'A'));
            }
            if (ch != lowercaseExpected.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isLowercaseAscii(String value) {
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch >= 'A' && ch <= 'Z') {
                return false;
            }
        }
        return true;
    }

    public String getMethod() {
        return requestLine.method;
    }

    public String getURI() {
        return requestLine.uri;
    }

    public String getVersion() {
        return requestLine.version;
    }

    public byte[] getBody() {
        if (!chunkedBody) {
            byte[] body = new byte[bodyLength];
            int availableBodyLength = Math.max(0, Math.min(bodyLength, bodyBuffer.length - bodyOffset));
            System.arraycopy(bodyBuffer, bodyOffset, body, 0, availableBodyLength);
            return body;
        }

        byte[] cached = decodedBodyCache;
        if (cached != null) {
            return java.util.Arrays.copyOf(cached, cached.length);
        }

        try (InputStream input = openBodyStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] copyBuffer = new byte[8192];
            int read;
            while ((read = input.read(copyBuffer)) != -1) {
                output.write(copyBuffer, 0, read);
            }
            byte[] decoded = output.toByteArray();
            decodedBodyCache = decoded;
            return java.util.Arrays.copyOf(decoded, decoded.length);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    public InputStream openBodyStream() {
        if (liveBodyStream != null) {
            byte[] cached = decodedBodyCache;
            if (cached != null) {
                return new ByteArrayInputStream(cached);
            }
            return liveBodyStream;
        }
        if (chunkedBody) {
            return new ChunkedDecodingInputStream(bodyBuffer, bodyOffset, bodyOffset + bodyLength);
        }
        int availableBodyLength = Math.max(0, Math.min(bodyLength, bodyBuffer.length - bodyOffset));
        return new ByteArrayInputStream(bodyBuffer, bodyOffset, availableBodyLength);
    }

    public void drainBodyStream() {
        if (liveBodyStream == null) {
            return;
        }
        byte[] discard = new byte[8192];
        try {
            while (liveBodyStream.read(discard) != -1) {
                // Drain unread body to keep connection framing aligned for keep-alive.
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    public boolean closeAfterResponse() {
        return closeAfterResponse;
    }

    private static String lowercaseAscii(String value) {
        int length = value.length();
        char[] normalized = null;
        for (int i = 0; i < length; i++) {
            char ch = value.charAt(i);
            if (ch >= 'A' && ch <= 'Z') {
                if (normalized == null) {
                    normalized = value.toCharArray();
                }
                normalized[i] = (char) (ch + ('a' - 'A'));
            }
        }
        return normalized == null ? value : new String(normalized);
    }
}
