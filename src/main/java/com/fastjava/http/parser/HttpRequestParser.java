package com.fastjava.http.parser;

import com.fastjava.http.simd.SIMDByteScanner;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Fast HTTP request parser with minimal allocations.
 * Parses raw bytes into HTTP request components.
 */
public class HttpRequestParser {

    private static final byte[] GET_METHOD = "GET".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] POST_METHOD = "POST".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] PUT_METHOD = "PUT".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] DELETE_METHOD = "DELETE".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] HEAD_METHOD = "HEAD".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] OPTIONS_METHOD = "OPTIONS".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] TRACE_METHOD = "TRACE".getBytes(StandardCharsets.US_ASCII);

    private static final byte SPACE = ' ';
    private static final byte COLON = ':';
    private static final String HEADER_CONTENT_LENGTH = "content-length";
    private static final String HEADER_CONTENT_TYPE = "content-type";
    private static final String HEADER_TRANSFER_ENCODING = "transfer-encoding";
    private static final String HEADER_HOST = "host";
    private static final String HEADER_CONNECTION = "connection";
    private static final String HEADER_COOKIE = "cookie";
    private static final String HEADER_ACCEPT_ENCODING = "accept-encoding";
    private static final byte[] HEADER_CONTENT_LENGTH_BYTES = HEADER_CONTENT_LENGTH.getBytes(StandardCharsets.US_ASCII);
    private static final byte[] HEADER_CONTENT_TYPE_BYTES = HEADER_CONTENT_TYPE.getBytes(StandardCharsets.US_ASCII);
    private static final byte[] HEADER_TRANSFER_ENCODING_BYTES = HEADER_TRANSFER_ENCODING
            .getBytes(StandardCharsets.US_ASCII);
    private static final byte[] HEADER_HOST_BYTES = HEADER_HOST.getBytes(StandardCharsets.US_ASCII);
    private static final byte[] HEADER_CONNECTION_BYTES = HEADER_CONNECTION.getBytes(StandardCharsets.US_ASCII);
    private static final byte[] HEADER_COOKIE_BYTES = HEADER_COOKIE.getBytes(StandardCharsets.US_ASCII);
    private static final byte[] HEADER_ACCEPT_ENCODING_BYTES = HEADER_ACCEPT_ENCODING
            .getBytes(StandardCharsets.US_ASCII);

    /**
     * Parse HTTP request from raw bytes.
     * Returns parsed request or null if incomplete.
     */
    public static ParsedHttpRequest parse(byte[] buffer, int length) {
        // Find first line ending (CRLF)
        int crlfPos = SIMDByteScanner.findCRLF(buffer, 0, length);
        if (crlfPos == -1) {
            return null; // Incomplete request
        }

        // Parse request line
        RequestLine requestLine = parseRequestLine(buffer, 0, crlfPos);
        if (requestLine == null) {
            return null;
        }

        // Parse headers using a single forward pass over header lines.
        HeaderAccumulator headers = new HeaderAccumulator();
        int headerStart = crlfPos + 2;
        int headerEnd = SIMDByteScanner.findDoubleCRLF(buffer, headerStart, length);
        if (headerEnd == -1) {
            return null;
        }

        int cursor = headerStart;
        while (cursor < headerEnd) {
            int lineEnd = findNextCRLF(buffer, cursor, headerEnd + 2);
            if (lineEnd == -1 || lineEnd > headerEnd) {
                return null;
            }
            parseHeader(buffer, cursor, lineEnd, headers);
            cursor = lineEnd + 2;
        }

        int bodyStart = headerEnd + 4;
        String transferEncoding = headers.transferEncoding;
        String contentLengthHeader = headers.contentLength;

        if (transferEncoding != null) {
            if (contentLengthHeader != null) {
                throw new IllegalArgumentException("Content-Length and Transfer-Encoding cannot both be present");
            }
            if (!"chunked".equalsIgnoreCase(transferEncoding.trim())) {
                throw new IllegalArgumentException("Unsupported Transfer-Encoding: " + transferEncoding);
            }

            ChunkDecodeResult chunkDecodeResult = decodeChunkedBody(buffer, bodyStart, length);
            if (chunkDecodeResult == null) {
                return null;
            }

            return new ParsedHttpRequest(
                    requestLine,
                    headers.toMap(),
                    buffer,
                    bodyStart,
                    chunkDecodeResult.encodedLength,
                    chunkDecodeResult.bytesConsumed,
                    true,
                    null,
                    false,
                    headers.contentLength,
                    headers.contentType,
                    headers.transferEncoding,
                    headers.host,
                    headers.connection,
                    headers.cookie,
                    headers.acceptEncoding);
        }

        int bodyLength = 0;
        if (contentLengthHeader != null) {
            try {
                int expectedBodyLength = Integer.parseInt(contentLengthHeader);
                if (expectedBodyLength < 0) {
                    return null;
                }
                if (length - bodyStart < expectedBodyLength) {
                    return null;
                }
                bodyLength = expectedBodyLength;
            } catch (NumberFormatException ignored) {
                return null;
            }
        }

        return new ParsedHttpRequest(
                requestLine,
                headers.toMap(),
                buffer,
                bodyStart,
                bodyLength,
                bodyStart + bodyLength,
                false,
                null,
                false,
                headers.contentLength,
                headers.contentType,
                headers.transferEncoding,
                headers.host,
                headers.connection,
                headers.cookie,
                headers.acceptEncoding);
    }

    private static ChunkDecodeResult decodeChunkedBody(byte[] buffer, int bodyStart, int length) {
        int cursor = bodyStart;

        while (true) {
            int sizeLineEnd = SIMDByteScanner.findCRLF(buffer, cursor, length);
            if (sizeLineEnd == -1) {
                return null;
            }

            final int chunkSize = parseChunkSize(buffer, cursor, sizeLineEnd);

            if (chunkSize < 0) {
                throw new IllegalArgumentException("Negative chunk size");
            }

            cursor = sizeLineEnd + 2;
            if (chunkSize == 0) {
                while (true) {
                    int trailerEnd = SIMDByteScanner.findCRLF(buffer, cursor, length);
                    if (trailerEnd == -1) {
                        return null;
                    }
                    if (trailerEnd == cursor) {
                        cursor += 2;
                        return new ChunkDecodeResult(cursor, cursor - bodyStart);
                    }
                    validateTrailerLine(buffer, cursor, trailerEnd);
                    cursor = trailerEnd + 2;
                }
            }

            if (length - cursor < chunkSize + 2) {
                return null;
            }

            cursor += chunkSize;
            if (buffer[cursor] != '\r' || buffer[cursor + 1] != '\n') {
                throw new IllegalArgumentException("Invalid chunk data terminator");
            }
            cursor += 2;
        }
    }

    private static RequestLine parseRequestLine(byte[] buffer, int start, int end) {
        // Find spaces: "METHOD /path HTTP/1.1"
        int firstSpace = SIMDByteScanner.indexOfByte(buffer, start, end, SPACE);
        if (firstSpace == -1)
            return null;

        String method = extractMethod(buffer, start, firstSpace);
        if (method == null)
            return null;

        int secondSpace = SIMDByteScanner.indexOfByte(buffer, firstSpace + 1, end, SPACE);
        if (secondSpace == -1)
            return null;

        String uri = new String(buffer, firstSpace + 1, secondSpace - firstSpace - 1, StandardCharsets.US_ASCII);
        String version = new String(buffer, secondSpace + 1, end - secondSpace - 1, StandardCharsets.US_ASCII);

        return new RequestLine(method, uri, version);
    }

    private static String extractMethod(byte[] buffer, int start, int end) {
        int len = end - start;

        if (len == 3) {
            if (SIMDByteScanner.bytesEqual(buffer, start, GET_METHOD))
                return "GET";
            if (SIMDByteScanner.bytesEqual(buffer, start, PUT_METHOD))
                return "PUT";
        } else if (len == 4) {
            if (SIMDByteScanner.bytesEqual(buffer, start, HEAD_METHOD))
                return "HEAD";
            if (SIMDByteScanner.bytesEqual(buffer, start, POST_METHOD))
                return "POST";
        } else if (len == 6) {
            if (SIMDByteScanner.bytesEqual(buffer, start, DELETE_METHOD))
                return "DELETE";
        } else if (len == 7) {
            if (SIMDByteScanner.bytesEqual(buffer, start, OPTIONS_METHOD))
                return "OPTIONS";
        } else if (len == 5) {
            if (SIMDByteScanner.bytesEqual(buffer, start, TRACE_METHOD))
                return "TRACE";
        }

        return new String(buffer, start, len, StandardCharsets.US_ASCII);
    }

    private static void parseHeader(byte[] buffer, int start, int end, HeaderAccumulator headers) {
        if (start < end && (buffer[start] == ' ' || buffer[start] == '\t')) {
            throw new IllegalArgumentException("Obsolete folded headers are not supported");
        }
        int colonPos = SIMDByteScanner.indexOfByte(buffer, start, end, COLON);
        if (colonPos == -1)
            return;

        String name = canonicalHeaderName(buffer, start, colonPos);

        // Trim and parse value
        int valueStart = SIMDByteScanner.trimStart(buffer, colonPos + 1, end);
        int valueEnd = SIMDByteScanner.trimEnd(buffer, valueStart, end);
        String value = new String(buffer, valueStart, valueEnd - valueStart, StandardCharsets.US_ASCII);

        headers.put(name, value);
    }

    private static String normalizeHeaderName(byte[] buffer, int start, int end) {
        int length = end - start;
        byte[] normalizedNameBytes = null;
        for (int i = 0; i < length; i++) {
            byte current = buffer[start + i];
            if (current >= 'A' && current <= 'Z') {
                if (normalizedNameBytes == null) {
                    normalizedNameBytes = new byte[length];
                    System.arraycopy(buffer, start, normalizedNameBytes, 0, length);
                }
                normalizedNameBytes[i] = (byte) (current + 32);
            }
        }
        if (normalizedNameBytes == null) {
            return new String(buffer, start, length, StandardCharsets.US_ASCII);
        }
        return new String(normalizedNameBytes, StandardCharsets.US_ASCII);
    }

    private static String canonicalHeaderName(byte[] buffer, int start, int end) {
        if (equalsAsciiIgnoreCase(buffer, start, end, HEADER_CONTENT_LENGTH_BYTES)) {
            return HEADER_CONTENT_LENGTH;
        }
        if (equalsAsciiIgnoreCase(buffer, start, end, HEADER_CONTENT_TYPE_BYTES)) {
            return HEADER_CONTENT_TYPE;
        }
        if (equalsAsciiIgnoreCase(buffer, start, end, HEADER_TRANSFER_ENCODING_BYTES)) {
            return HEADER_TRANSFER_ENCODING;
        }
        if (equalsAsciiIgnoreCase(buffer, start, end, HEADER_HOST_BYTES)) {
            return HEADER_HOST;
        }
        if (equalsAsciiIgnoreCase(buffer, start, end, HEADER_CONNECTION_BYTES)) {
            return HEADER_CONNECTION;
        }
        if (equalsAsciiIgnoreCase(buffer, start, end, HEADER_COOKIE_BYTES)) {
            return HEADER_COOKIE;
        }
        if (equalsAsciiIgnoreCase(buffer, start, end, HEADER_ACCEPT_ENCODING_BYTES)) {
            return HEADER_ACCEPT_ENCODING;
        }
        return normalizeHeaderName(buffer, start, end);
    }

    private static boolean equalsAsciiIgnoreCase(byte[] buffer, int start, int end, byte[] expectedLowercase) {
        int length = end - start;
        if (length != expectedLowercase.length) {
            return false;
        }
        for (int i = 0; i < length; i++) {
            byte value = buffer[start + i];
            if (value >= 'A' && value <= 'Z') {
                value = (byte) (value + 32);
            }
            if (value != expectedLowercase[i]) {
                return false;
            }
        }
        return true;
    }

    private static int parseChunkSize(byte[] buffer, int start, int endExclusive) {
        int cursor = start;
        while (cursor < endExclusive && (buffer[cursor] == ' ' || buffer[cursor] == '\t')) {
            cursor++;
        }

        long value = 0;
        boolean hasDigit = false;

        while (cursor < endExclusive) {
            byte current = buffer[cursor];
            if (current == ';') {
                break;
            }
            if (current == ' ' || current == '\t') {
                while (cursor < endExclusive) {
                    byte trailing = buffer[cursor];
                    if (trailing == ';') {
                        break;
                    }
                    if (trailing != ' ' && trailing != '\t') {
                        throw new IllegalArgumentException("Invalid chunk size");
                    }
                    cursor++;
                }
                break;
            }

            int hex = hexValue(current);
            if (hex < 0) {
                throw new IllegalArgumentException("Invalid chunk size");
            }
            hasDigit = true;
            value = (value << 4) + hex;
            if (value > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("Chunk size overflow");
            }
            cursor++;
        }

        if (!hasDigit) {
            throw new IllegalArgumentException("Invalid chunk size");
        }
        return (int) value;
    }

    private static int hexValue(byte value) {
        if (value >= '0' && value <= '9') {
            return value - '0';
        }
        if (value >= 'a' && value <= 'f') {
            return value - 'a' + 10;
        }
        if (value >= 'A' && value <= 'F') {
            return value - 'A' + 10;
        }
        return -1;
    }

    private static int findNextCRLF(byte[] buffer, int start, int limit) {
        for (int i = start; i < limit - 1; i++) {
            if (buffer[i] == '\r' && buffer[i + 1] == '\n') {
                return i;
            }
        }
        return -1;
    }

    private static final class HeaderAccumulator {
        private String contentLength;
        private String contentType;
        private String transferEncoding;
        private String host;
        private String connection;
        private String cookie;
        private String acceptEncoding;
        private String uncommonName;
        private String uncommonValue;
        private Map<String, String> uncommon;

        private void put(String name, String value) {
            if (HEADER_CONTENT_LENGTH.equals(name)) {
                if (contentLength != null) {
                    throw new IllegalArgumentException("Duplicate header not allowed: " + name);
                }
                contentLength = value;
                return;
            }
            if (HEADER_TRANSFER_ENCODING.equals(name)) {
                if (transferEncoding != null) {
                    throw new IllegalArgumentException("Duplicate header not allowed: " + name);
                }
                transferEncoding = value;
                return;
            }
            if (HEADER_CONTENT_TYPE.equals(name)) {
                contentType = value;
                return;
            }
            if (HEADER_HOST.equals(name)) {
                host = value;
                return;
            }
            if (HEADER_CONNECTION.equals(name)) {
                connection = value;
                return;
            }
            if (HEADER_COOKIE.equals(name)) {
                cookie = value;
                return;
            }
            if (HEADER_ACCEPT_ENCODING.equals(name)) {
                acceptEncoding = value;
                return;
            }

            if (uncommonName == null) {
                uncommonName = name;
                uncommonValue = value;
                return;
            }

            if (uncommonName.equals(name)) {
                uncommonValue = value;
                return;
            }

            if (uncommon == null) {
                uncommon = new HashMap<>(4);
                uncommon.put(uncommonName, uncommonValue);
            }
            uncommon.put(name, value);
        }

        private Map<String, String> toMap() {
            if (uncommon != null) {
                return uncommon;
            }
            if (uncommonName == null) {
                return Collections.emptyMap();
            }
            return Collections.singletonMap(uncommonName, uncommonValue);
        }
    }

    private static void validateTrailerLine(byte[] buffer, int start, int end) {
        if (start >= end) {
            throw new IllegalArgumentException("Invalid trailer header");
        }
        if (buffer[start] == ' ' || buffer[start] == '\t') {
            throw new IllegalArgumentException("Obsolete folded trailer headers are not supported");
        }
        int colonPos = SIMDByteScanner.indexOfByte(buffer, start, end, COLON);
        if (colonPos <= start || colonPos >= end - 1) {
            throw new IllegalArgumentException("Invalid trailer header");
        }
    }

    private static final class ChunkDecodeResult {
        private final int bytesConsumed;
        private final int encodedLength;

        private ChunkDecodeResult(int bytesConsumed, int encodedLength) {
            this.bytesConsumed = bytesConsumed;
            this.encodedLength = encodedLength;
        }
    }

    /**
     * Request line component (method, URI, version)
     */
    public static class RequestLine {
        public final String method;
        public final String uri;
        public final String version;

        public RequestLine(String method, String uri, String version) {
            this.method = method;
            this.uri = uri;
            this.version = version;
        }
    }
}
