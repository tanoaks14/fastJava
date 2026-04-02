package com.fastjava.http.parser;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

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
    private String headerLookupKey1;
    private String headerLookupValue1;
    private String headerLookupKey2;
    private String headerLookupValue2;
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
        this(
                requestLine,
                headers,
                bodyBuffer,
                bodyOffset,
                bodyLength,
                bytesConsumed,
                chunkedBody,
                liveBodyStream,
                closeAfterResponse,
                headers.get(HEADER_CONTENT_LENGTH),
                headers.get(HEADER_CONTENT_TYPE),
                headers.get(HEADER_TRANSFER_ENCODING),
                headers.get(HEADER_HOST),
                headers.get(HEADER_CONNECTION),
                headers.get(HEADER_COOKIE),
                headers.get(HEADER_ACCEPT_ENCODING));
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
            boolean closeAfterResponse,
            String headerContentLength,
            String headerContentType,
            String headerTransferEncoding,
            String headerHost,
            String headerConnection,
            String headerCookie,
            String headerAcceptEncoding) {
        this.requestLine = requestLine;
        this.headers = headers;
        this.bodyBuffer = bodyBuffer;
        this.bodyOffset = bodyOffset;
        this.bodyLength = bodyLength;
        this.bytesConsumed = bytesConsumed;
        this.chunkedBody = chunkedBody;
        this.liveBodyStream = liveBodyStream;
        this.closeAfterResponse = closeAfterResponse;
        this.headerContentLength = headerContentLength;
        this.headerContentType = headerContentType;
        this.headerTransferEncoding = headerTransferEncoding;
        this.headerHost = headerHost;
        this.headerConnection = headerConnection;
        this.headerCookie = headerCookie;
        this.headerAcceptEncoding = headerAcceptEncoding;
    }

    public String getHeader(String name) {
        if (name == null) {
            return null;
        }
        if (name == headerLookupKey1 || name.equals(headerLookupKey1)) {
            return headerLookupValue1;
        }
        if (name == headerLookupKey2 || name.equals(headerLookupKey2)) {
            return headerLookupValue2;
        }

        String commonHeaderValue = commonHeaderValue(name);
        if (commonHeaderValue != null) {
            cacheHeaderLookup(name, commonHeaderValue);
            return commonHeaderValue;
        }

        if (isLowercaseAscii(name)) {
            String value = headers.get(name);
            cacheHeaderLookup(name, value);
            return value;
        }

        String exactCaseValue = headers.get(name);
        if (exactCaseValue != null) {
            cacheHeaderLookup(name, exactCaseValue);
            return exactCaseValue;
        }

        for (Entry<String, String> header : headers.entrySet()) {
            if (asciiEqualsIgnoreCase(name, header.getKey())) {
                String value = header.getValue();
                cacheHeaderLookup(name, value);
                return value;
            }
        }
        cacheHeaderLookup(name, null);
        return null;
    }

    private String commonHeaderValue(String name) {
        switch (name.length()) {
            case 4:
                if (asciiEqualsIgnoreCase(name, HEADER_HOST)) {
                    return headerHost;
                }
                break;
            case 6:
                if (asciiEqualsIgnoreCase(name, HEADER_COOKIE)) {
                    return headerCookie;
                }
                break;
            case 10:
                if (asciiEqualsIgnoreCase(name, HEADER_CONNECTION)) {
                    return headerConnection;
                }
                break;
            case 12:
                if (asciiEqualsIgnoreCase(name, HEADER_CONTENT_TYPE)) {
                    return headerContentType;
                }
                break;
            case 14:
                if (asciiEqualsIgnoreCase(name, HEADER_CONTENT_LENGTH)) {
                    return headerContentLength;
                }
                break;
            case 15:
                if (asciiEqualsIgnoreCase(name, HEADER_ACCEPT_ENCODING)) {
                    return headerAcceptEncoding;
                }
                break;
            case 17:
                if (asciiEqualsIgnoreCase(name, HEADER_TRANSFER_ENCODING)) {
                    return headerTransferEncoding;
                }
                break;
            default:
                break;
        }
        return null;
    }

    private void cacheHeaderLookup(String name, String value) {
        headerLookupKey2 = headerLookupKey1;
        headerLookupValue2 = headerLookupValue1;
        headerLookupKey1 = name;
        headerLookupValue1 = value;
    }

    private static boolean asciiEqualsIgnoreCase(String left, String right) {
        if (left == right) {
            return true;
        }
        if (left == null || right == null || left.length() != right.length()) {
            return false;
        }
        for (int i = 0; i < left.length(); i++) {
            char a = left.charAt(i);
            char b = right.charAt(i);
            if (a == b) {
                continue;
            }
            if (a >= 'A' && a <= 'Z') {
                a = (char) (a | 0x20);
            }
            if (b >= 'A' && b <= 'Z') {
                b = (char) (b | 0x20);
            }
            if (a != b) {
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

    public List<String> headerNames() {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        if (headerContentLength != null) {
            names.add("Content-Length");
        }
        if (headerContentType != null) {
            names.add("Content-Type");
        }
        if (headerTransferEncoding != null) {
            names.add("Transfer-Encoding");
        }
        if (headerHost != null) {
            names.add("Host");
        }
        if (headerConnection != null) {
            names.add("Connection");
        }
        if (headerCookie != null) {
            names.add("Cookie");
        }
        if (headerAcceptEncoding != null) {
            names.add("Accept-Encoding");
        }
        names.addAll(headers.keySet());
        return List.copyOf(names);
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

}
