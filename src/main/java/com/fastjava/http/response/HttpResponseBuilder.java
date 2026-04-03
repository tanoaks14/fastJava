package com.fastjava.http.response;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import com.fastjava.http.simd.SIMDVectorOps;

/**
 * Fast HTTP response builder with minimal allocations. Pre-allocates buffers
 * and uses efficient byte operations.
 */
public class HttpResponseBuilder {

    private static final byte[] EMPTY_BYTES = new byte[0];
    private static final byte[] HTTP_11 = "HTTP/1.1 ".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] CRLF = "\r\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] CHUNK_TERMINATOR = "0\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
    private static final String HEADER_CONTENT_TYPE = "Content-Type";
    private static final String HEADER_CONTENT_LENGTH = "Content-Length";
    private static final String HEADER_TRANSFER_ENCODING = "Transfer-Encoding";
    private static final byte[][] CACHED_STATUS_LINES = createCachedStatusLines();

    // Pre-encoded "HeaderName: " bytes (name + colon + space) to avoid per-call
    // String.getBytes() allocations for common HTTP header names.
    private static final Map<String, byte[]> HEADER_PREFIX_BYTES;

    static {
        String[] common = {
            "Content-Type", "Content-Length", "Transfer-Encoding", "Connection",
            "Cache-Control", "Server", "Date", "Accept-Ranges", "ETag",
            "Last-Modified", "Location", "Set-Cookie", "Vary", "Allow",
            "Access-Control-Allow-Origin", "Access-Control-Allow-Headers",
            "Access-Control-Allow-Methods", "Access-Control-Max-Age",
            "Content-Encoding", "Keep-Alive", "Content-Range", "WWW-Authenticate",
            "Retry-After", "Upgrade", "X-Content-Type-Options", "X-Frame-Options"
        };
        Map<String, byte[]> m = new HashMap<>(common.length * 2);
        for (String h : common) {
            m.put(h, (h + ": ").getBytes(StandardCharsets.US_ASCII));
        }
        HEADER_PREFIX_BYTES = m;
    }

    // Pre-encoded full header line for Transfer-Encoding: chunked (zero alloc).
    private static final byte[] HEADER_LINE_TE_CHUNKED = "Transfer-Encoding: chunked\r\n"
            .getBytes(StandardCharsets.US_ASCII);

    private byte[] buffer;
    private int position;
    private final HeaderStorage headers;
    private int statusCode;
    private String contentType;
    private byte[] body;
    private boolean pooledBuffer = false;
    private final byte[] decimalScratch = new byte[20];

    public HttpResponseBuilder(int initialCapacity) {
        if (initialCapacity == 8_192) {
            // Try to acquire from pool for standard size
            this.buffer = ResponseBufferPool.acquire();
            this.pooledBuffer = true;
        } else {
            this.buffer = new byte[initialCapacity];
            this.pooledBuffer = false;
        }
        this.position = 0;
        this.headers = new HeaderStorage();
        this.statusCode = 200;
    }

    public HttpResponseBuilder setStatus(int code) {
        this.statusCode = code;
        return this;
    }

    public HttpResponseBuilder setContentType(String type) {
        this.contentType = type;
        return this;
    }

    public HttpResponseBuilder setHeader(String name, String value) {
        headers.set(name, value);
        return this;
    }

    public HttpResponseBuilder addHeader(String name, String value) {
        headers.add(name, value);
        return this;
    }

    public HttpResponseBuilder setBody(byte[] body) {
        this.body = body;
        return this;
    }

    public void reset() {
        headers.clear();
        statusCode = 200;
        contentType = null;
        body = null;
        position = 0;
    }

    /**
     * Release the buffer back to the pool if it was pooled. Called after
     * response is sent.
     */
    public void release() {
        if (pooledBuffer && buffer != null) {
            ResponseBufferPool.release(buffer);
            pooledBuffer = false;
        }
    }

    public String getHeader(String name) {
        if (name == null) {
            return null;
        }
        return headers.get(name);
    }

    public byte[] build() {
        return flattenSegments(buildSegments(false));
    }

    public byte[] buildStreamingChunkedHeaders() {
        byte[][] segments = buildSegments(false, null, true);
        return segments.length == 0 ? new byte[0] : segments[0];
    }

    public byte[][] buildSegments(boolean chunkedResponse) {
        return buildSegments(chunkedResponse, null);
    }

    public byte[][] buildSegments(boolean chunkedResponse, Long explicitContentLength) {
        return buildSegments(chunkedResponse, explicitContentLength, false);
    }

    public byte[] buildHeaderBytes(boolean chunkedResponse, Long explicitContentLength) {
        return buildHeaderBytes(chunkedResponse, explicitContentLength, false);
    }

    public byte[] buildHeaderBytes(boolean chunkedResponse, Long explicitContentLength, boolean streamingChunked) {
        return buildHeaderBytesInternal(chunkedResponse, explicitContentLength, streamingChunked);
    }

    private byte[][] buildSegments(boolean chunkedResponse, Long explicitContentLength, boolean streamingChunked) {
        byte[] bodyBytes = body != null ? body : EMPTY_BYTES;
        boolean chunked = streamingChunked || chunkedResponse
                || hasHeaderIgnoreCase(HEADER_TRANSFER_ENCODING, "chunked");
        byte[] headerBytes = buildHeaderBytesInternal(chunkedResponse, explicitContentLength, streamingChunked);

        if (!chunked) {
            if (bodyBytes.length == 0) {
                return new byte[][]{headerBytes};
            }
            return new byte[][]{headerBytes, bodyBytes};
        }

        if (streamingChunked) {
            return new byte[][]{headerBytes};
        }

        if (bodyBytes.length == 0) {
            return new byte[][]{headerBytes, CHUNK_TERMINATOR};
        }

        byte[] chunkPrefix = createChunkPrefix(bodyBytes.length);

        return new byte[][]{
            headerBytes,
            chunkPrefix,
            bodyBytes,
            CRLF,
            CHUNK_TERMINATOR
        };
    }

    private byte[] buildHeaderBytesInternal(boolean chunkedResponse, Long explicitContentLength,
            boolean streamingChunked) {
        position = 0;

        // Status line: HTTP/1.1 200 OK
        appendStatusLine(statusCode);

        boolean chunked = streamingChunked || chunkedResponse
                || hasHeaderIgnoreCase(HEADER_TRANSFER_ENCODING, "chunked");

        if (contentType != null && !hasHeaderIgnoreCase(HEADER_CONTENT_TYPE, null)) {
            appendHeader(HEADER_CONTENT_TYPE, contentType);
        }

        int headerCount = headers.size();
        for (int i = 0; i < headerCount; i++) {
            HeaderStorage.HeaderValue header = headers.get(i);
            if (header == null) {
                continue;
            }
            if (isHeader(header.name(), HEADER_CONTENT_TYPE)) {
                continue;
            }
            if (chunked && isHeader(header.name(), HEADER_CONTENT_LENGTH)) {
                continue;
            }
            if (chunked && isHeader(header.name(), HEADER_TRANSFER_ENCODING)) {
                continue;
            }
            if (!chunked && isHeader(header.name(), HEADER_CONTENT_LENGTH)) {
                continue;
            }
            appendHeader(header.name(), header.value());
        }

        byte[] bodyBytes = body != null ? body : EMPTY_BYTES;
        long contentLength = explicitContentLength != null ? explicitContentLength : bodyBytes.length;
        if (chunked) {
            append(HEADER_LINE_TE_CHUNKED);
        } else {
            appendContentLengthHeader(contentLength);
        }

        // Blank line between headers and body
        append(CRLF);
        return Arrays.copyOf(buffer, position);
    }

    private byte[] createChunkPrefix(int contentLength) {
        byte[] scratch = new byte[8];
        int cursor = scratch.length;
        int value = contentLength;
        do {
            int digit = value & 0xF;
            scratch[--cursor] = (byte) (digit < 10 ? ('0' + digit) : ('a' + (digit - 10)));
            value >>>= 4;
        } while (value != 0);

        int hexLength = scratch.length - cursor;
        byte[] chunkPrefix = new byte[hexLength + CRLF.length];
        System.arraycopy(scratch, cursor, chunkPrefix, 0, hexLength);
        System.arraycopy(CRLF, 0, chunkPrefix, hexLength, CRLF.length);
        return chunkPrefix;
    }

    public static byte[] flattenSegments(byte[][] segments) {
        if (segments == null || segments.length == 0) {
            return new byte[0];
        }

        int totalLength = 0;
        for (byte[] segment : segments) {
            if (segment != null) {
                totalLength += segment.length;
            }
        }

        byte[] flattened = new byte[totalLength];
        SIMDVectorOps.batchCopy(flattened, 0, segments);
        return flattened;
    }

    public void removeHeader(String name) {
        headers.removeByName(name);
    }

    private void appendHeader(String name, String value) {
        byte[] prefix = HEADER_PREFIX_BYTES.get(name);
        if (prefix != null) {
            append(prefix);
        } else {
            append(name.getBytes(StandardCharsets.US_ASCII));
            append((byte) ':');
            append((byte) ' ');
        }
        append(value.getBytes(StandardCharsets.US_ASCII));
        append(CRLF);
    }

    /**
     * Write "Content-Length: <n>\r\n" directly as ASCII bytes, avoiding
     * String.valueOf(n) and subsequent getBytes() allocations.
     */
    private void appendContentLengthHeader(long length) {
        append(HEADER_PREFIX_BYTES.get(HEADER_CONTENT_LENGTH));
        appendDecimalLong(length);
        append(CRLF);
    }

    /**
     * Write a non-negative long as ASCII decimal bytes directly into the
     * buffer.
     */
    private void appendDecimalLong(long value) {
        if (value == 0) {
            append((byte) '0');
            return;
        }
        // Collect digits in reverse order into reusable scratch storage.
        int i = decimalScratch.length;
        long v = value;
        while (v != 0) {
            decimalScratch[--i] = (byte) ('0' + (v % 10));
            v /= 10;
        }
        int count = decimalScratch.length - i;
        ensureCapacity(position + count);
        System.arraycopy(decimalScratch, i, buffer, position, count);
        position += count;
    }

    private void append(byte[] data) {
        ensureCapacity(position + data.length);
        System.arraycopy(data, 0, buffer, position, data.length);
        position += data.length;
    }

    private void append(byte b) {
        ensureCapacity(position + 1);
        buffer[position++] = b;
    }

    private boolean hasHeaderIgnoreCase(String name, String containsValueIgnoreCase) {
        for (HeaderStorage.HeaderValue entry : headers) {
            if (!isHeader(entry.name(), name)) {
                continue;
            }
            if (containsValueIgnoreCase == null) {
                return true;
            }
            String value = entry.value();
            if (containsIgnoreCase(value, containsValueIgnoreCase)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsIgnoreCase(String source, String target) {
        if (source == null || target == null) {
            return false;
        }
        int sourceLength = source.length();
        int targetLength = target.length();
        if (targetLength == 0) {
            return true;
        }
        if (targetLength > sourceLength) {
            return false;
        }
        int maxStart = sourceLength - targetLength;
        for (int i = 0; i <= maxStart; i++) {
            if (source.regionMatches(true, i, target, 0, targetLength)) {
                return true;
            }
        }
        return false;
    }

    private boolean isHeader(String candidate, String expected) {
        return candidate != null && expected != null && candidate.equalsIgnoreCase(expected);
    }

    private void ensureCapacity(int required) {
        if (required > buffer.length) {
            int newCapacity = Math.max(buffer.length * 2, required);
            byte[] newBuffer = new byte[newCapacity];
            System.arraycopy(buffer, 0, newBuffer, 0, position);
            buffer = newBuffer;
        }
    }

    private void appendStatusLine(int code) {
        byte[] cached = code >= 0 && code < CACHED_STATUS_LINES.length ? CACHED_STATUS_LINES[code] : null;
        if (cached != null) {
            append(cached);
            return;
        }
        append(HTTP_11);
        append(String.valueOf(code).getBytes(StandardCharsets.US_ASCII));
        append((byte) ' ');
        append(getStatusMessage(code).getBytes(StandardCharsets.US_ASCII));
        append(CRLF);
    }

    private static byte[][] createCachedStatusLines() {
        byte[][] statusLines = new byte[600][];
        cacheStatusLine(statusLines, 200, "OK");
        cacheStatusLine(statusLines, 201, "Created");
        cacheStatusLine(statusLines, 204, "No Content");
        cacheStatusLine(statusLines, 206, "Partial Content");
        cacheStatusLine(statusLines, 301, "Moved Permanently");
        cacheStatusLine(statusLines, 302, "Found");
        cacheStatusLine(statusLines, 304, "Not Modified");
        cacheStatusLine(statusLines, 400, "Bad Request");
        cacheStatusLine(statusLines, 401, "Unauthorized");
        cacheStatusLine(statusLines, 403, "Forbidden");
        cacheStatusLine(statusLines, 404, "Not Found");
        cacheStatusLine(statusLines, 405, "Method Not Allowed");
        cacheStatusLine(statusLines, 408, "Request Timeout");
        cacheStatusLine(statusLines, 413, "Payload Too Large");
        cacheStatusLine(statusLines, 416, "Range Not Satisfiable");
        cacheStatusLine(statusLines, 414, "URI Too Long");
        cacheStatusLine(statusLines, 431, "Request Header Fields Too Large");
        cacheStatusLine(statusLines, 500, "Internal Server Error");
        cacheStatusLine(statusLines, 501, "Not Implemented");
        cacheStatusLine(statusLines, 503, "Service Unavailable");
        return statusLines;
    }

    private static void cacheStatusLine(byte[][] target, int code, String reasonPhrase) {
        target[code] = ("HTTP/1.1 " + code + " " + reasonPhrase + "\r\n").getBytes(StandardCharsets.US_ASCII);
    }

    private String getStatusMessage(int code) {
        return switch (code) {
            case 200 ->
                "OK";
            case 201 ->
                "Created";
            case 204 ->
                "No Content";
            case 206 ->
                "Partial Content";
            case 301 ->
                "Moved Permanently";
            case 302 ->
                "Found";
            case 304 ->
                "Not Modified";
            case 400 ->
                "Bad Request";
            case 401 ->
                "Unauthorized";
            case 403 ->
                "Forbidden";
            case 404 ->
                "Not Found";
            case 405 ->
                "Method Not Allowed";
            case 408 ->
                "Request Timeout";
            case 413 ->
                "Payload Too Large";
            case 416 ->
                "Range Not Satisfiable";
            case 414 ->
                "URI Too Long";
            case 431 ->
                "Request Header Fields Too Large";
            case 500 ->
                "Internal Server Error";
            case 501 ->
                "Not Implemented";
            case 503 ->
                "Service Unavailable";
            default ->
                "Unknown";
        };
    }

    private record HeaderValue(String name, String value) {

    }
}
