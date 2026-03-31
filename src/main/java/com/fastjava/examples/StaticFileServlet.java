package com.fastjava.examples;

import com.fastjava.http.impl.DefaultHttpServletResponse;
import com.fastjava.http.simd.SIMDByteScanner;
import com.fastjava.servlet.*;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Static file servlet with path traversal protection.
 */
public class StaticFileServlet extends HttpServlet {

    private static final String DEFAULT_INDEX_FILE = "index.html";
    private static final DateTimeFormatter RFC_1123 = DateTimeFormatter.RFC_1123_DATE_TIME.withZone(ZoneOffset.UTC);

    private final Path rootDirectory;
    private final String mountPath;

    public StaticFileServlet() {
        this(Path.of("static"), "/static");
    }

    public StaticFileServlet(Path rootDirectory) {
        this(rootDirectory, "/static");
    }

    public StaticFileServlet(Path rootDirectory, String mountPath) {
        this.rootDirectory = rootDirectory.toAbsolutePath().normalize();
        this.mountPath = normalizeMountPath(mountPath);
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException {
        Path targetFile = resolveRequestPath(request.getRequestURI());
        if (targetFile == null) {
            sendPlainError(response, 403, "Forbidden");
            return;
        }

        if (!Files.isRegularFile(targetFile)) {
            sendPlainError(response, 404, "Not Found");
            return;
        }

        try {
            long lastModified = Files.getLastModifiedTime(targetFile).toMillis();
            long fileLength = Files.size(targetFile);
            String etag = '"' + Long.toHexString(lastModified) + '-' + Long.toHexString(fileLength) + '"';

            // Conditional request checks (RFC 7232)
            String ifNoneMatch = request.getHeader("If-None-Match");
            if (ifNoneMatch != null && (ifNoneMatch.equals(etag) || "*".equals(ifNoneMatch))) {
                response.setStatus(304);
                response.setHeader("ETag", etag);
                response.flushBuffer();
                return;
            }
            // If-Modified-Since only checked when If-None-Match is absent (RFC 7232 §6)
            if (ifNoneMatch == null) {
                long ims = request.getDateHeader("If-Modified-Since");
                if (ims != -1L && lastModified / 1000L <= ims / 1000L) {
                    response.setStatus(304);
                    response.flushBuffer();
                    return;
                }
            }

            response.setStatus(200);
            response.setContentType(detectMimeType(targetFile));
            response.setHeader("Cache-Control", "public, max-age=60");
            response.setHeader("Last-Modified", RFC_1123.format(java.time.Instant.ofEpochMilli(lastModified)));
            response.setHeader("ETag", etag);
            response.setHeader("Accept-Ranges", "bytes");

            String rawRange = request.getHeader("Range");
            RangeSelection rangeSelection = RangeSelection.none();
            if (shouldApplyRange(request, rawRange, etag, lastModified)) {
                rangeSelection = resolveRequestedRanges(rawRange, fileLength);
            }
            if (rangeSelection.unsatisfiable()) {
                response.setStatus(416);
                response.setHeader("Content-Range", "bytes */" + fileLength);
                sendPlainError(response, 416, "Range Not Satisfiable");
                return;
            }

            if (!rangeSelection.hasRanges()) {
                response.setHeader("Content-Length", String.valueOf(fileLength));
                if (response instanceof DefaultHttpServletResponse defaultResponse) {
                    defaultResponse.setFileBody(targetFile, 0L, fileLength);
                    return;
                }
                writeTextRange(response, targetFile, 0L, fileLength);
                return;
            }

            if (rangeSelection.isSingleRange()) {
                ByteRange range = rangeSelection.singleRange();
                response.setStatus(206);
                response.setHeader("Content-Range",
                        "bytes " + range.start() + "-" + range.endInclusive() + "/" + fileLength);
                response.setHeader("Content-Length", String.valueOf(range.length()));

                if (response instanceof DefaultHttpServletResponse defaultResponse) {
                    defaultResponse.setFileBody(targetFile, range.start(), range.length());
                    return;
                }
                writeTextRange(response, targetFile, range.start(), range.length());
                return;
            }

            response.setStatus(206);
            String boundary = "fastjava-" + Long.toHexString(System.nanoTime());
            byte[] multipartBody = buildMultipartBody(targetFile, detectMimeType(targetFile), fileLength,
                    rangeSelection.ranges(), boundary);
            response.setContentType("multipart/byteranges; boundary=" + boundary);
            response.setHeader("Content-Length", String.valueOf(multipartBody.length));

            if (response instanceof DefaultHttpServletResponse defaultResponse) {
                defaultResponse.setRawBody(multipartBody);
                return;
            }
            response.getWriter().write(new String(multipartBody, StandardCharsets.ISO_8859_1));
            response.getWriter().flush();
        } catch (IOException exception) {
            throw new ServletException("Failed to serve static file", exception);
        }
    }

    private boolean shouldApplyRange(HttpServletRequest request, String rawRange, String etag, long lastModified) {
        if (rawRange == null || rawRange.isBlank()) {
            return false;
        }

        String ifRange = request.getHeader("If-Range");
        if (ifRange == null || ifRange.isBlank()) {
            return true;
        }

        String normalized = ifRange.trim();
        if (normalized.startsWith("\"")) {
            // If-Range requires strong validator comparison for entity tags.
            return normalized.equals(etag);
        }
        if (normalized.startsWith("W/")) {
            return false;
        }

        long ifRangeDate = request.getDateHeader("If-Range");
        if (ifRangeDate < 0L) {
            return false;
        }
        return lastModified / 1000L <= ifRangeDate / 1000L;
    }

    private Path resolveRequestPath(String requestUri) {
        if (requestUri == null || !requestUri.startsWith(mountPath)) {
            return null;
        }

        String relative = requestUri.substring(mountPath.length());
        if (relative.isEmpty() || "/".equals(relative)) {
            relative = "/" + DEFAULT_INDEX_FILE;
        }

        if (relative.startsWith("/")) {
            relative = relative.substring(1);
        }

        String decoded = URLDecoder.decode(relative, StandardCharsets.UTF_8);
        byte[] normalizedBytes = SIMDByteScanner.replaceByte(decoded.getBytes(StandardCharsets.UTF_8),
                (byte) '\\',
                (byte) '/');
        String normalizedSeparators = new String(normalizedBytes, StandardCharsets.UTF_8);
        Path resolved = rootDirectory.resolve(normalizedSeparators).normalize();

        if (!resolved.startsWith(rootDirectory)) {
            return null;
        }
        return resolved;
    }

    private String detectMimeType(Path targetFile) {
        try {
            String mimeType = Files.probeContentType(targetFile);
            if (mimeType != null && !mimeType.isBlank()) {
                return mimeType;
            }
        } catch (IOException ignored) {
            // Fall back to extension-based defaults.
        }

        String fileName = targetFile.getFileName().toString().toLowerCase();
        if (fileName.endsWith(".html") || fileName.endsWith(".htm")) {
            return "text/html";
        }
        if (fileName.endsWith(".css")) {
            return "text/css";
        }
        if (fileName.endsWith(".js")) {
            return "application/javascript";
        }
        if (fileName.endsWith(".json")) {
            return "application/json";
        }
        if (fileName.endsWith(".svg")) {
            return "image/svg+xml";
        }
        if (fileName.endsWith(".txt")) {
            return "text/plain";
        }
        return "application/octet-stream";
    }

    private RangeSelection resolveRequestedRanges(String rawRange, long fileLength) {
        if (rawRange == null || rawRange.isBlank()) {
            return RangeSelection.none();
        }
        if (!rawRange.startsWith("bytes=")) {
            return RangeSelection.unsatisfiableSelection();
        }

        String expression = rawRange.substring("bytes=".length()).trim();
        if (expression.isEmpty()) {
            return RangeSelection.unsatisfiableSelection();
        }

        byte[] expressionBytes = expression.getBytes(StandardCharsets.US_ASCII);
        int[] commas = SIMDByteScanner.findAllOccurrences(expressionBytes, (byte) ',', 0, expressionBytes.length);

        List<ByteRange> ranges = new ArrayList<>(Math.max(2, commas.length + 1));
        int tokenStart = 0;
        for (int index = 0; index <= commas.length; index++) {
            int tokenEnd = index < commas.length ? commas[index] : expressionBytes.length;
            String token = new String(expressionBytes, tokenStart, tokenEnd - tokenStart, StandardCharsets.US_ASCII)
                    .trim();
            if (token.isEmpty()) {
                return RangeSelection.unsatisfiableSelection();
            }
            ByteRange parsedRange = parseSingleRange(token, fileLength);
            if (parsedRange == ByteRange.UNSATISFIABLE) {
                return RangeSelection.unsatisfiableSelection();
            }
            ranges.add(parsedRange);
            tokenStart = tokenEnd + 1;
        }

        if (ranges.isEmpty()) {
            return RangeSelection.unsatisfiableSelection();
        }
        return RangeSelection.of(normalizeRanges(ranges));
    }

    private List<ByteRange> normalizeRanges(List<ByteRange> ranges) {
        if (ranges.size() <= 1) {
            return ranges;
        }

        List<ByteRange> sorted = new ArrayList<>(ranges);
        sorted.sort(Comparator.comparingLong(ByteRange::start));

        List<ByteRange> merged = new ArrayList<>(sorted.size());
        ByteRange current = sorted.getFirst();
        for (int index = 1; index < sorted.size(); index++) {
            ByteRange candidate = sorted.get(index);
            if (candidate.start() <= current.endInclusive() + 1) {
                current = ByteRange.of(current.start(), Math.max(current.endInclusive(), candidate.endInclusive()));
                continue;
            }
            merged.add(current);
            current = candidate;
        }
        merged.add(current);
        return merged;
    }

    private ByteRange parseSingleRange(String expression, long fileLength) {
        byte[] expressionBytes = expression.getBytes(StandardCharsets.US_ASCII);
        int dashIndex = SIMDByteScanner.indexOfByte(expressionBytes, 0, expressionBytes.length, (byte) '-');
        if (dashIndex <= -1) {
            return ByteRange.UNSATISFIABLE;
        }

        String startToken = new String(expressionBytes, 0, dashIndex, StandardCharsets.US_ASCII).trim();
        String endToken = new String(expressionBytes, dashIndex + 1, expressionBytes.length - dashIndex - 1,
                StandardCharsets.US_ASCII).trim();

        try {
            if (startToken.isEmpty()) {
                if (endToken.isEmpty()) {
                    return ByteRange.UNSATISFIABLE;
                }
                long suffixLength = parseUnsignedLong(endToken);
                if (suffixLength <= 0) {
                    return ByteRange.UNSATISFIABLE;
                }
                long effectiveLength = Math.min(suffixLength, fileLength);
                if (effectiveLength == 0) {
                    return ByteRange.UNSATISFIABLE;
                }
                long start = fileLength - effectiveLength;
                long end = fileLength - 1;
                return ByteRange.of(start, end);
            }

            long start = parseUnsignedLong(startToken);
            if (start >= fileLength) {
                return ByteRange.UNSATISFIABLE;
            }
            if (endToken.isEmpty()) {
                return ByteRange.of(start, fileLength - 1);
            }

            long end = parseUnsignedLong(endToken);
            if (end < start) {
                return ByteRange.UNSATISFIABLE;
            }

            long normalizedEnd = Math.min(end, fileLength - 1);
            return ByteRange.of(start, normalizedEnd);
        } catch (NumberFormatException ignored) {
            return ByteRange.UNSATISFIABLE;
        }
    }

    private byte[] buildMultipartBody(Path targetFile, String contentType, long fileLength, List<ByteRange> ranges,
            String boundary) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] dashBoundary = ("--" + boundary + "\r\n").getBytes(StandardCharsets.US_ASCII);
        byte[] contentTypeHeader = ("Content-Type: " + contentType + "\r\n").getBytes(StandardCharsets.US_ASCII);

        for (ByteRange range : ranges) {
            out.write(dashBoundary);
            out.write(contentTypeHeader);
            out.write(("Content-Range: bytes " + range.start() + "-" + range.endInclusive() + "/" + fileLength + "\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
            out.write(readRangeBytes(targetFile, range.start(), range.length()));
            out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
        }
        out.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII));
        return out.toByteArray();
    }

    private byte[] readRangeBytes(Path targetFile, long offset, long length) throws IOException {
        try (InputStream in = Files.newInputStream(targetFile)) {
            in.skipNBytes(offset);
            return in.readNBytes(Math.toIntExact(length));
        }
    }

    private long parseUnsignedLong(String token) {
        if (token.startsWith("+")) {
            throw new NumberFormatException("leading plus not allowed");
        }
        return Long.parseLong(token);
    }

    private void writeTextRange(HttpServletResponse response, Path targetFile, long offset, long length)
            throws IOException {
        byte[] rangeBytes;
        if (length <= 0) {
            rangeBytes = new byte[0];
        } else if (offset == 0 && length == Files.size(targetFile)) {
            rangeBytes = Files.readAllBytes(targetFile);
        } else {
            rangeBytes = readRangeBytes(targetFile, offset, length);
        }
        PrintWriter out = response.getWriter();
        out.print(new String(rangeBytes, StandardCharsets.UTF_8));
        out.flush();
    }

    private void sendPlainError(HttpServletResponse response, int statusCode, String message) {
        response.setStatus(statusCode);
        response.setContentType("text/plain");
        PrintWriter writer = response.getWriter();
        writer.print(message);
        writer.flush();
    }

    private String normalizeMountPath(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return "/static";
        }
        String normalized = candidate.startsWith("/") ? candidate : "/" + candidate;
        if (normalized.length() > 1 && normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private record ByteRange(long start, long endInclusive) {
        private static final ByteRange UNSATISFIABLE = new ByteRange(-1L, -1L);

        private static ByteRange of(long start, long endInclusive) {
            if (start < 0 || endInclusive < start) {
                throw new IllegalArgumentException("Invalid byte range");
            }
            return new ByteRange(start, endInclusive);
        }

        private long length() {
            return endInclusive - start + 1;
        }
    }

    private record RangeSelection(List<ByteRange> ranges, boolean unsatisfiable) {
        private static RangeSelection none() {
            return new RangeSelection(List.of(), false);
        }

        private static RangeSelection unsatisfiableSelection() {
            return new RangeSelection(List.of(), true);
        }

        private static RangeSelection of(List<ByteRange> ranges) {
            return new RangeSelection(List.copyOf(ranges), false);
        }

        private boolean hasRanges() {
            return !ranges.isEmpty();
        }

        private boolean isSingleRange() {
            return ranges.size() == 1;
        }

        private ByteRange singleRange() {
            return ranges.getFirst();
        }
    }
}
