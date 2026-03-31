package com.fastjava.http.impl;

import com.fastjava.servlet.*;
import com.fastjava.http.response.HttpResponseBuilder;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Concrete HttpServletResponse implementation.
 */
public class DefaultHttpServletResponse implements HttpServletResponse {

    private static final DateTimeFormatter COOKIE_EXPIRES_FORMATTER = DateTimeFormatter.RFC_1123_DATE_TIME
            .withZone(ZoneOffset.UTC);

    private final HttpResponseBuilder builder;
    private final ByteArrayOutputStream outputBuffer;
    private final PrintWriter writer;
    private int statusCode = 200;
    private String contentType = "text/plain";
    private boolean committed = false;
    private boolean chunkedResponseEnabled = false;
    private boolean streamingChunkedResponseEnabled = false;
    private Path fileBodyPath;
    private long fileBodyOffset = 0;
    private long fileBodyLength = -1;

    public DefaultHttpServletResponse(int initialBufferSize) {
        this.builder = new HttpResponseBuilder(initialBufferSize);
        this.outputBuffer = new ByteArrayOutputStream(initialBufferSize);
        this.writer = new PrintWriter(
                new java.io.OutputStreamWriter(outputBuffer, StandardCharsets.UTF_8), true);
    }

    /**
     * Release the response builder's buffer back to the pool (if pooled).
     * Should be called after the response is fully sent.
     */
    public void releaseBuffer() {
        if (builder != null) {
            builder.release();
        }
    }

    @Override
    public void setStatus(int sc) {
        this.statusCode = sc;
        builder.setStatus(sc);
    }

    @Override
    public void setStatus(int sc, String sm) {
        setStatus(sc);
    }

    @Override
    public int getStatus() {
        return statusCode;
    }

    @Override
    public void setHeader(String name, String value) {
        builder.setHeader(name, value);
    }

    @Override
    public void addHeader(String name, String value) {
        builder.addHeader(name, value);
    }

    @Override
    public void addCookie(Cookie cookie) {
        if (cookie == null) {
            return;
        }
        validateCookieToken(cookie.getName(), "Cookie name");
        validateCookieToken(cookie.getValue(), "Cookie value");

        StringBuilder serialized = new StringBuilder();
        serialized.append(cookie.getName()).append("=").append(cookie.getValue());

        appendCookieAttribute(serialized, "Path", cookie.getPath(), true);
        appendCookieAttribute(serialized, "Domain", cookie.getDomain(), true);
        if (cookie.getMaxAge() != null) {
            serialized.append("; Max-Age=").append(cookie.getMaxAge());
        }
        if (cookie.getExpires() != null) {
            serialized.append("; Expires=").append(COOKIE_EXPIRES_FORMATTER.format(cookie.getExpires()));
        }
        if (cookie.isSecure()) {
            serialized.append("; Secure");
        }
        if (cookie.isHttpOnly()) {
            serialized.append("; HttpOnly");
        }
        if (cookie.getSameSite() != null) {
            serialized.append("; SameSite=").append(normalizeSameSite(cookie.getSameSite()));
        }

        builder.addHeader("Set-Cookie", serialized.toString());
    }

    @Override
    public void setIntHeader(String name, int value) {
        builder.setHeader(name, String.valueOf(value));
    }

    @Override
    public void setDateHeader(String name, long date) {
        builder.setHeader(name, String.valueOf(date));
    }

    public String getHeader(String name) {
        return builder.getHeader(name);
    }

    @Override
    public void setContentType(String type) {
        this.contentType = type;
        builder.setContentType(type);
    }

    @Override
    public void setContentLength(int len) {
        builder.setHeader("Content-Length", String.valueOf(len));
    }

    @Override
    public void setCharacterEncoding(String charset) {
        // UTF-8 is default
    }

    @Override
    public String getContentType() {
        return contentType;
    }

    @Override
    public PrintWriter getWriter() {
        if (fileBodyPath != null) {
            throw new IllegalStateException("Response body is already configured as a file");
        }
        return writer;
    }

    @Override
    public byte[] getOutputBuffer() {
        if (fileBodyPath != null) {
            byte[] headerBytes = HttpResponseBuilder.flattenSegments(getOutputSegments());
            try {
                byte[] fileBytes;
                long actualFileSize = java.nio.file.Files.size(fileBodyPath);
                if (fileBodyOffset == 0 && fileBodyLength == actualFileSize) {
                    fileBytes = java.nio.file.Files.readAllBytes(fileBodyPath);
                } else {
                    try (InputStream fileInput = java.nio.file.Files.newInputStream(fileBodyPath)) {
                        fileInput.skipNBytes(fileBodyOffset);
                        fileBytes = fileInput.readNBytes(Math.toIntExact(fileBodyLength));
                    }
                }
                byte[] merged = new byte[headerBytes.length + fileBytes.length];
                System.arraycopy(headerBytes, 0, merged, 0, headerBytes.length);
                System.arraycopy(fileBytes, 0, merged, headerBytes.length, fileBytes.length);
                return merged;
            } catch (IOException exception) {
                throw new IllegalStateException("Unable to materialize file-backed response", exception);
            }
        }
        return HttpResponseBuilder.flattenSegments(getOutputSegments());
    }

    @Override
    public byte[][] getOutputSegments() {
        writer.flush();
        byte[] body = fileBodyPath != null ? new byte[0] : outputBuffer.toByteArray();
        builder.setBody(body);
        if (contentType != null) {
            builder.setContentType(contentType);
        }
        if (streamingChunkedResponseEnabled) {
            return new byte[][] { builder.buildStreamingChunkedHeaders() };
        }
        if (fileBodyPath != null) {
            return builder.buildSegments(false, fileBodyLength);
        }
        return builder.buildSegments(chunkedResponseEnabled);
    }

    @Override
    public void setChunkedResponseEnabled(boolean enabled) {
        if (enabled && fileBodyPath != null) {
            throw new IllegalStateException("Chunked responses are not supported for file-backed bodies");
        }
        this.chunkedResponseEnabled = enabled;
        if (enabled) {
            builder.removeHeader("Content-Length");
            builder.setHeader("Transfer-Encoding", "chunked");
        }
    }

    @Override
    public boolean isChunkedResponseEnabled() {
        return chunkedResponseEnabled;
    }

    public void enableStreamingChunkedResponse() {
        if (fileBodyPath != null) {
            throw new IllegalStateException("Streaming responses are not supported for file-backed bodies");
        }
        this.streamingChunkedResponseEnabled = true;
        this.chunkedResponseEnabled = true;
        builder.removeHeader("Content-Length");
        builder.setHeader("Transfer-Encoding", "chunked");
    }

    public boolean isStreamingChunkedResponseEnabled() {
        return streamingChunkedResponseEnabled;
    }

    @Override
    public void flushBuffer() {
        writer.flush();
        committed = true;
    }

    @Override
    public boolean isCommitted() {
        return committed;
    }

    @Override
    public void sendError(int sc) {
        setStatus(sc);
        String errorMsg = "Error " + sc;
        writer.println("<html><body><h1>" + errorMsg + "</h1></body></html>");
        flushBuffer();
    }

    @Override
    public void sendRedirect(String location) {
        setStatus(302);
        setHeader("Location", location);
        flushBuffer();
    }

    /**
     * Replace the response body with raw bytes, bypassing the PrintWriter encoding
     * path.
     * Used by GzipResponseWrapper to inject compressed bytes after the filter chain
     * runs.
     */
    public void setRawBody(byte[] bytes) {
        writer.flush();
        outputBuffer.reset();
        outputBuffer.write(bytes, 0, bytes.length);
    }

    public void resetForForward() {
        writer.flush();
        outputBuffer.reset();
        builder.reset();
        statusCode = 200;
        contentType = "text/plain";
        committed = false;
        chunkedResponseEnabled = false;
        streamingChunkedResponseEnabled = false;
        fileBodyPath = null;
        fileBodyOffset = 0;
        fileBodyLength = -1;
    }

    public void setFileBody(Path filePath, long fileLength) {
        setFileBody(filePath, 0L, fileLength);
    }

    public void setFileBody(Path filePath, long fileOffset, long fileLength) {
        if (filePath == null) {
            throw new IllegalArgumentException("File path cannot be null");
        }
        if (fileOffset < 0) {
            throw new IllegalArgumentException("File offset cannot be negative");
        }
        if (fileLength < 0) {
            throw new IllegalArgumentException("File length cannot be negative");
        }
        if (outputBuffer.size() > 0) {
            throw new IllegalStateException("Response already contains buffered body data");
        }
        this.fileBodyPath = filePath;
        this.fileBodyOffset = fileOffset;
        this.fileBodyLength = fileLength;
        this.chunkedResponseEnabled = false;
        builder.removeHeader("Transfer-Encoding");
    }

    public Path getFileBodyPath() {
        return fileBodyPath;
    }

    public long getFileBodyLength() {
        return fileBodyLength;
    }

    public long getFileBodyOffset() {
        return fileBodyOffset;
    }

    private static void appendCookieAttribute(StringBuilder serialized, String name, String value,
            boolean validateToken) {
        if (value == null || value.isBlank()) {
            return;
        }
        if (validateToken) {
            validateCookieToken(value, name);
        }
        serialized.append("; ").append(name).append("=").append(value);
    }

    private static String normalizeSameSite(String sameSite) {
        validateCookieToken(sameSite, "SameSite");
        String normalized = sameSite.trim().toLowerCase();
        return switch (normalized) {
            case "lax" -> "Lax";
            case "strict" -> "Strict";
            case "none" -> "None";
            default -> throw new IllegalArgumentException("Unsupported SameSite value: " + sameSite);
        };
    }

    private static void validateCookieToken(String value, String fieldName) {
        if (value == null) {
            throw new IllegalArgumentException(fieldName + " cannot be null");
        }
        if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0) {
            throw new IllegalArgumentException(fieldName + " cannot contain CR/LF");
        }
        if (fieldName.equals("Cookie name") && value.indexOf(';') >= 0) {
            throw new IllegalArgumentException("Cookie name cannot contain ';'");
        }
    }

}
