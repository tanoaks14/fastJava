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

        // Parse headers
        Map<String, String> headers = new HashMap<>();
        int headerStart = crlfPos + 2;
        int blankLinePos = -1;

        while (headerStart < length) {
            int nextCRLF = SIMDByteScanner.findCRLF(buffer, headerStart, length);
            if (nextCRLF == -1) {
                return null; // Incomplete headers
            }

            if (nextCRLF == headerStart) {
                // Blank line = end of headers
                blankLinePos = headerStart;
                break;
            }

            parseHeader(buffer, headerStart, nextCRLF, headers);
            headerStart = nextCRLF + 2;
        }

        if (blankLinePos == -1) {
            return null;
        }

        int bodyStart = blankLinePos + 2;
        String transferEncoding = headers.get("transfer-encoding");
        String contentLengthHeader = headers.get("content-length");

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
                    headers,
                    buffer,
                    bodyStart,
                    chunkDecodeResult.encodedLength,
                    chunkDecodeResult.bytesConsumed,
                    true);
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
                headers,
                buffer,
                bodyStart,
                bodyLength,
                bodyStart + bodyLength);
    }

    private static ChunkDecodeResult decodeChunkedBody(byte[] buffer, int bodyStart, int length) {
        int cursor = bodyStart;

        while (true) {
            int sizeLineEnd = SIMDByteScanner.findCRLF(buffer, cursor, length);
            if (sizeLineEnd == -1) {
                return null;
            }

            String rawSize = new String(buffer, cursor, sizeLineEnd - cursor, StandardCharsets.US_ASCII);
            int extensionIndex = rawSize.indexOf(';');
            String sizeToken = (extensionIndex >= 0 ? rawSize.substring(0, extensionIndex) : rawSize).trim();
            if (sizeToken.isEmpty()) {
                throw new IllegalArgumentException("Invalid chunk size");
            }

            final int chunkSize;
            try {
                chunkSize = Integer.parseInt(sizeToken, 16);
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("Invalid chunk size", exception);
            }

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

    private static void parseHeader(byte[] buffer, int start, int end, Map<String, String> headers) {
        if (start < end && (buffer[start] == ' ' || buffer[start] == '\t')) {
            throw new IllegalArgumentException("Obsolete folded headers are not supported");
        }
        int colonPos = SIMDByteScanner.indexOfByte(buffer, start, end, COLON);
        if (colonPos == -1)
            return;

        String name = canonicalHeaderName(normalizeHeaderName(buffer, start, colonPos));

        // Trim and parse value
        int valueStart = SIMDByteScanner.trimStart(buffer, colonPos + 1, end);
        int valueEnd = SIMDByteScanner.trimEnd(buffer, valueStart, end);
        String value = new String(buffer, valueStart, valueEnd - valueStart, StandardCharsets.US_ASCII);

        String existing = headers.get(name);
        if (existing != null) {
            if ("content-length".equals(name) || "transfer-encoding".equals(name)) {
                throw new IllegalArgumentException("Duplicate header not allowed: " + name);
            }
        }

        headers.put(name, value);
    }

    private static String normalizeHeaderName(byte[] buffer, int start, int end) {
        boolean hasUppercase = false;
        for (int i = start; i < end; i++) {
            byte current = buffer[i];
            if (current >= 'A' && current <= 'Z') {
                hasUppercase = true;
                break;
            }
        }
        if (!hasUppercase) {
            return new String(buffer, start, end - start, StandardCharsets.US_ASCII);
        }
        byte[] normalizedNameBytes = SIMDByteScanner.toLowercaseAscii(buffer, start, end);
        return new String(normalizedNameBytes, StandardCharsets.US_ASCII);
    }

    private static String canonicalHeaderName(String name) {
        return switch (name) {
            case HEADER_CONTENT_LENGTH -> HEADER_CONTENT_LENGTH;
            case HEADER_CONTENT_TYPE -> HEADER_CONTENT_TYPE;
            case HEADER_TRANSFER_ENCODING -> HEADER_TRANSFER_ENCODING;
            case HEADER_HOST -> HEADER_HOST;
            case HEADER_CONNECTION -> HEADER_CONNECTION;
            case HEADER_COOKIE -> HEADER_COOKIE;
            case HEADER_ACCEPT_ENCODING -> HEADER_ACCEPT_ENCODING;
            default -> name;
        };
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
