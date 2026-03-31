package com.fastjava.server;

import com.fastjava.http.parser.ParsedHttpRequest;
import com.fastjava.http.simd.SIMDByteScanner;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

public final class HttpRequestInspector {

    private static final int EXPECTATION_OK = 0;
    private static final int EXPECTATION_FAILED = 417;
    private static final byte[] HEADER_CONTENT_LENGTH = "content-length".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] HEADER_TRANSFER_ENCODING = "transfer-encoding".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] HEADER_EXPECT = "expect".getBytes(StandardCharsets.US_ASCII);

    private HttpRequestInspector() {
    }

    public static RequestValidationResult validateBufferedRequest(
            byte[] buffer,
            int totalBytes,
            RequestLimits limits) {
        int requestLineEnd = SIMDByteScanner.findCRLF(buffer, 0, totalBytes);
        if (requestLineEnd == -1) {
            if (totalBytes > limits.maxRequestLineBytes()) {
                return new RequestValidationResult(414, "URI Too Long");
            }
            return RequestValidationResult.none();
        }

        if (requestLineEnd > limits.maxRequestLineBytes()) {
            return new RequestValidationResult(414, "URI Too Long");
        }

        int headerEnd = SIMDByteScanner.findDoubleCRLF(buffer, 0, totalBytes);
        if (headerEnd == -1) {
            if (totalBytes > limits.maxHeaderBytes()) {
                return new RequestValidationResult(431, "Request Header Fields Too Large");
            }
            return RequestValidationResult.none();
        }

        int headerBytes = headerEnd + 4;
        if (headerBytes > limits.maxHeaderBytes()) {
            return new RequestValidationResult(431, "Request Header Fields Too Large");
        }

        final String contentLengthHeader;
        final String transferEncoding;
        try {
            contentLengthHeader = extractHeaderValue(buffer, headerEnd, HEADER_CONTENT_LENGTH, "Content-Length");
            transferEncoding = extractHeaderValue(buffer, headerEnd, HEADER_TRANSFER_ENCODING, "Transfer-Encoding");
        } catch (IllegalArgumentException malformedHeaders) {
            return new RequestValidationResult(400, "Bad Request");
        }

        if (contentLengthHeader != null && transferEncoding != null) {
            return new RequestValidationResult(400, "Bad Request");
        }

        if (transferEncoding != null && !isChunkedTransferEncoding(transferEncoding)) {
            return new RequestValidationResult(501, "Not Implemented");
        }

        if (contentLengthHeader != null) {
            try {
                int contentLength = Integer.parseInt(contentLengthHeader);
                if (contentLength < 0) {
                    return new RequestValidationResult(400, "Bad Request");
                }
                if (contentLength > limits.maxBodyBytes()) {
                    return new RequestValidationResult(413, "Payload Too Large");
                }
                if (headerBytes + contentLength > limits.maxRequestSize()) {
                    return new RequestValidationResult(413, "Payload Too Large");
                }
            } catch (NumberFormatException ignored) {
                return new RequestValidationResult(400, "Bad Request");
            }
        }

        if (transferEncoding != null) {
            final int decodedLength;
            try {
                decodedLength = decodeChunkedLength(buffer, headerEnd + 4, totalBytes, limits);
            } catch (IllegalArgumentException malformedChunking) {
                return new RequestValidationResult(400, "Bad Request");
            }
            if (decodedLength < 0) {
                return RequestValidationResult.none();
            }
            if (decodedLength > limits.maxBodyBytes()) {
                return new RequestValidationResult(413, "Payload Too Large");
            }
        }

        return RequestValidationResult.none();
    }

    public static boolean isHeaderComplete(byte[] buffer, int totalBytes) {
        return SIMDByteScanner.findDoubleCRLF(buffer, 0, totalBytes) != -1;
    }

    public static int expectationStatusCode(byte[] buffer, int totalBytes) {
        int headerEnd = SIMDByteScanner.findDoubleCRLF(buffer, 0, totalBytes);
        if (headerEnd == -1) {
            return EXPECTATION_OK;
        }

        String expectHeader = extractHeaderValue(buffer, headerEnd, HEADER_EXPECT, "Expect");
        if (expectHeader == null || expectHeader.isBlank()) {
            return EXPECTATION_OK;
        }

        if ("100-continue".equalsIgnoreCase(expectHeader.trim())) {
            return EXPECTATION_OK;
        }

        return EXPECTATION_FAILED;
    }

    public static boolean shouldSendContinue(byte[] buffer, int totalBytes) {
        return shouldSendContinue(buffer, totalBytes, RequestLimits.defaults(Math.max(1, totalBytes)));
    }

    public static boolean shouldSendContinue(byte[] buffer, int totalBytes, RequestLimits limits) {
        int headerEnd = SIMDByteScanner.findDoubleCRLF(buffer, 0, totalBytes);
        if (headerEnd == -1) {
            return false;
        }

        String expectHeader = extractHeaderValue(buffer, headerEnd, HEADER_EXPECT, "Expect");
        if (!"100-continue".equalsIgnoreCase(expectHeader == null ? null : expectHeader.trim())) {
            return false;
        }

        String transferEncoding = extractHeaderValue(buffer, headerEnd, HEADER_TRANSFER_ENCODING, "Transfer-Encoding");
        if (transferEncoding != null) {
            if (!isChunkedTransferEncoding(transferEncoding)) {
                return false;
            }
            try {
                return decodeChunkedLength(buffer, headerEnd + 4, totalBytes, limits) < 0;
            } catch (IllegalArgumentException malformedChunking) {
                return false;
            }
        }

        String contentLengthHeader = extractHeaderValue(buffer, headerEnd, HEADER_CONTENT_LENGTH, "Content-Length");
        if (contentLengthHeader == null) {
            return false;
        }

        try {
            int contentLength = Integer.parseInt(contentLengthHeader);
            if (contentLength <= 0) {
                return false;
            }
            int bodyStart = headerEnd + 4;
            return totalBytes - bodyStart < contentLength;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    public static boolean shouldKeepAlive(ParsedHttpRequest parsed) {
        String connection = parsed.getHeader("Connection");
        if (connection != null) {
            if ("close".equalsIgnoreCase(connection)) {
                return false;
            }
            if ("keep-alive".equalsIgnoreCase(connection)) {
                return true;
            }
        }
        return "HTTP/1.1".equalsIgnoreCase(parsed.getVersion());
    }

    public static boolean isWebSocketUpgradeAttempt(ParsedHttpRequest parsed) {
        if (parsed == null) {
            return false;
        }
        return hasToken(parsed.getHeader("Connection"), "upgrade")
                || "websocket".equalsIgnoreCase(trimToNull(parsed.getHeader("Upgrade")))
                || trimToNull(parsed.getHeader("Sec-WebSocket-Key")) != null;
    }

    public static boolean isValidWebSocketUpgradeRequest(ParsedHttpRequest parsed) {
        return extractWebSocketKeyIfValid(parsed) != null;
    }

    public static String extractWebSocketKeyIfValid(ParsedHttpRequest parsed) {
        if (parsed == null) {
            return null;
        }
        if (!"GET".equalsIgnoreCase(parsed.getMethod())) {
            return null;
        }
        if (!"HTTP/1.1".equalsIgnoreCase(parsed.getVersion())) {
            return null;
        }
        if (!"websocket".equalsIgnoreCase(trimToNull(parsed.getHeader("Upgrade")))) {
            return null;
        }
        if (!hasToken(parsed.getHeader("Connection"), "upgrade")) {
            return null;
        }
        if (!"13".equals(trimToNull(parsed.getHeader("Sec-WebSocket-Version")))) {
            return null;
        }

        String key = trimToNull(parsed.getHeader("Sec-WebSocket-Key"));
        if (key == null) {
            return null;
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(key);
        } catch (IllegalArgumentException malformedBase64) {
            return null;
        }
        if (decoded.length != 16) {
            return null;
        }
        return key;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static boolean hasToken(String headerValue, String token) {
        if (headerValue == null || token == null) {
            return false;
        }
        int start = 0;
        while (start < headerValue.length()) {
            int end = headerValue.indexOf(',', start);
            if (end < 0) {
                end = headerValue.length();
            }
            String candidate = headerValue.substring(start, end).trim();
            if (token.equalsIgnoreCase(candidate)) {
                return true;
            }
            start = end + 1;
        }
        return false;
    }

    private static String extractHeaderValue(byte[] buffer, int headerEnd, byte[] headerNameBytes, String headerName) {
        int scanLimit = Math.min(buffer.length, headerEnd + 2);
        int requestLineEnd = SIMDByteScanner.findCRLF(buffer, 0, scanLimit);
        if (requestLineEnd == -1) {
            return null;
        }

        int cursor = requestLineEnd + 2;
        String headerValue = null;
        while (cursor < headerEnd) {
            int lineEnd = SIMDByteScanner.findCRLF(buffer, cursor, scanLimit);
            if (lineEnd == -1 || lineEnd == cursor) {
                break;
            }
            if (buffer[cursor] == ' ' || buffer[cursor] == '\t') {
                throw new IllegalArgumentException("Obsolete folded headers are not supported");
            }
            int colon = SIMDByteScanner.indexOfByte(buffer, cursor, lineEnd, (byte) ':');
            if (colon > cursor && equalsHeaderNameIgnoreCase(buffer, cursor, colon, headerNameBytes)) {
                if (headerValue != null) {
                    throw new IllegalArgumentException("Duplicate header not allowed: " + headerName);
                }
                int valueStart = SIMDByteScanner.trimStart(buffer, colon + 1, lineEnd);
                int valueEnd = SIMDByteScanner.trimEnd(buffer, valueStart, lineEnd);
                headerValue = new String(buffer, valueStart, Math.max(0, valueEnd - valueStart),
                        StandardCharsets.US_ASCII);
            }
            cursor = lineEnd + 2;
        }
        return headerValue;
    }

    private static boolean equalsHeaderNameIgnoreCase(byte[] buffer, int start, int end, byte[] expectedLowercase) {
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

    private static boolean isChunkedTransferEncoding(String transferEncoding) {
        int start = 0;
        String onlyToken = null;
        while (start < transferEncoding.length()) {
            int end = transferEncoding.indexOf(',', start);
            if (end < 0) {
                end = transferEncoding.length();
            }
            String token = transferEncoding.substring(start, end).trim();
            if (!token.isEmpty()) {
                if (onlyToken != null) {
                    return false;
                }
                onlyToken = token;
            }
            start = end + 1;
        }
        return "chunked".equalsIgnoreCase(onlyToken);
    }

    private static int decodeChunkedLength(byte[] buffer, int cursor, int totalBytes, RequestLimits limits) {
        int decodedLength = 0;
        int chunkCount = 0;

        while (true) {
            int lineEnd = SIMDByteScanner.findCRLF(buffer, cursor, totalBytes);
            if (lineEnd == -1) {
                return -1;
            }

            final int chunkSize = parseChunkSize(buffer, cursor, lineEnd);

            if (chunkSize < 0 || chunkSize > limits.maxChunkSizeBytes()) {
                throw new IllegalArgumentException("Chunk size exceeds limits");
            }

            cursor = lineEnd + 2;
            if (chunkSize == 0) {
                while (true) {
                    int trailerEnd = SIMDByteScanner.findCRLF(buffer, cursor, totalBytes);
                    if (trailerEnd == -1) {
                        return -1;
                    }
                    if (trailerEnd == cursor) {
                        return decodedLength;
                    }
                    validateTrailerHeader(buffer, cursor, trailerEnd);
                    cursor = trailerEnd + 2;
                }
            }

            if (totalBytes - cursor < chunkSize + 2) {
                return -1;
            }
            cursor += chunkSize;
            if (buffer[cursor] != '\r' || buffer[cursor + 1] != '\n') {
                throw new IllegalArgumentException("Invalid chunk data terminator");
            }
            cursor += 2;

            decodedLength += chunkSize;
            chunkCount++;
            if (chunkCount > limits.maxChunkCount()) {
                throw new IllegalArgumentException("Chunk count exceeds limits");
            }
        }
    }

    private static void validateTrailerHeader(byte[] buffer, int start, int end) {
        if (start >= end) {
            throw new IllegalArgumentException("Invalid trailer header");
        }
        if (buffer[start] == ' ' || buffer[start] == '\t') {
            throw new IllegalArgumentException("Obsolete folded trailer headers are not supported");
        }
        int colonIndex = SIMDByteScanner.indexOfByte(buffer, start, end, (byte) ':');
        if (colonIndex <= start || colonIndex >= end - 1) {
            throw new IllegalArgumentException("Invalid trailer header");
        }
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
                throw new IllegalArgumentException("Chunk size exceeds limits");
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
}