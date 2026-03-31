package com.fastjava.http.filter;

import com.fastjava.http.impl.DefaultHttpServletResponse;
import com.fastjava.http.simd.SIMDByteScanner;
import com.fastjava.servlet.*;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.zip.GZIPOutputStream;

/**
 * Servlet {@link Filter} that compresses response bodies with gzip when the
 * client
 * advertises {@code Accept-Encoding: gzip}.
 *
 * <p>
 * Compression is skipped for:
 * <ul>
 * <li>Responses below {@value #DEFAULT_MIN_SIZE} bytes (default, override with
 * init-param
 * {@code minSize}).</li>
 * <li>MIME types that are already compressed (images, video, audio, archive
 * formats).</li>
 * <li>Error/redirect responses (sendError / sendRedirect paths).</li>
 * <li>Responses that appear to be binary content (> 30 % high bytes in the
 * first 256 bytes).
 * </ul>
 *
 * <p>
 * When compression is applied the filter:
 * <ol>
 * <li>Sets {@code Content-Encoding: gzip}.</li>
 * <li>Sets {@code Vary: Accept-Encoding}.</li>
 * <li>Replaces the {@code Content-Length} header with the compressed size.</li>
 * </ol>
 */
public class GzipFilter implements Filter {

    private static final int DEFAULT_MIN_SIZE = 256;

    /**
     * MIME type prefixes/exact values that should never be compressed because they
     * are
     * already in a compressed or binary format where gzip adds overhead.
     */
    private static final Set<String> EXCLUDED_MIME_PREFIXES = Set.of(
            "image/", "video/", "audio/",
            "application/zip", "application/gzip", "application/x-gzip",
            "application/zstd", "application/brotli",
            "application/x-bzip2", "application/x-7z-compressed",
            "application/x-rar-compressed", "application/pdf",
            "application/octet-stream");

    private static final byte[][] EXCLUDED_MIME_PREFIXES_ASCII = EXCLUDED_MIME_PREFIXES.stream()
            .map(value -> value.getBytes(StandardCharsets.US_ASCII))
            .toArray(byte[][]::new);

    private static final int BINARY_SAMPLE_LIMIT = 256;
    private static final int BINARY_PERCENT_THRESHOLD = 30;

    private int minSize = DEFAULT_MIN_SIZE;

    @Override
    public void init(FilterConfig config) throws ServletException {
        String minSizeParam = config.getInitParameter("minSize");
        if (minSizeParam != null) {
            try {
                minSize = Math.max(0, Integer.parseInt(minSizeParam.trim()));
            } catch (NumberFormatException ignored) {
                // keep default
            }
        }
    }

    @Override
    public void doFilter(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException {
        // Only wrap DefaultHttpServletResponse — that is what HttpRequestExecutor
        // provides.
        if (!(response instanceof DefaultHttpServletResponse dsr) || !acceptsGzip(request)) {
            chain.doFilter(request, response);
            return;
        }

        GzipResponseWrapper wrapper = new GzipResponseWrapper(dsr);
        chain.doFilter(request, wrapper);

        if (wrapper.isErrored()) {
            // Body already written to delegate via sendError/sendRedirect — skip
            // compression.
            return;
        }

        byte[] body = wrapper.getCapturedBytes();

        if (body.length < minSize
                || isExcludedMimeType(dsr.getContentType())
                || isBinaryContent(body)) {
            // Push uncompressed body to the real response.
            dsr.setRawBody(body);
            dsr.setHeader("Content-Length", String.valueOf(body.length));
            return;
        }

        byte[] compressed = gzip(body);
        dsr.setRawBody(compressed);
        dsr.setHeader("Content-Encoding", "gzip");
        mergeVaryHeader(dsr, "Accept-Encoding");
        dsr.setHeader("Content-Length", String.valueOf(compressed.length));
    }

    // ---- helpers ----

    private static boolean acceptsGzip(HttpServletRequest request) {
        String ae = request.getHeader("Accept-Encoding");
        return ae != null && ae.contains("gzip");
    }

    private static boolean isExcludedMimeType(String contentType) {
        if (contentType == null) {
            return false;
        }
        if (contentType.isEmpty()) {
            return false;
        }

        int start = 0;
        int end = contentType.length();

        while (start < end && Character.isWhitespace(contentType.charAt(start))) {
            start++;
        }

        int semicolon = contentType.indexOf(';', start);
        if (semicolon >= 0) {
            end = semicolon;
        }

        while (end > start && Character.isWhitespace(contentType.charAt(end - 1))) {
            end--;
        }

        int length = end - start;
        if (length <= 0) {
            return false;
        }

        byte[] loweredMime = new byte[length];
        for (int index = 0; index < length; index++) {
            char c = contentType.charAt(start + index);
            if (c > 0x7F) {
                return isExcludedMimeTypeScalar(contentType);
            }
            loweredMime[index] = (byte) c;
        }

        SIMDByteScanner.lowercaseAscii(loweredMime, 0, loweredMime.length, loweredMime, 0);
        for (byte[] prefix : EXCLUDED_MIME_PREFIXES_ASCII) {
            if (SIMDByteScanner.startsWith(loweredMime, 0, loweredMime.length, prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Quick binary-content heuristic: if more than 30 % of the first 256 bytes have
     * the
     * high bit set the content is likely already encoded and compression is
     * skipped.
     */
    private static boolean isBinaryContent(byte[] body) {
        int sampleLen = Math.min(body.length, BINARY_SAMPLE_LIMIT);
        if (sampleLen <= 0) {
            return false;
        }

        int highByteCount = SIMDByteScanner.countHighBitSetBytes(body, 0, sampleLen);
        return (highByteCount * 100 / sampleLen) > BINARY_PERCENT_THRESHOLD;
    }

    static boolean isExcludedMimeTypeScalar(String contentType) {
        if (contentType == null) {
            return false;
        }
        int semicolon = contentType.indexOf(';');
        String bare = (semicolon < 0 ? contentType : contentType.substring(0, semicolon)).trim()
                .toLowerCase(Locale.ROOT);
        for (String prefix : EXCLUDED_MIME_PREFIXES) {
            if (bare.startsWith(prefix) || bare.equals(prefix)) {
                return true;
            }
        }
        return false;
    }

    static boolean isBinaryContentScalar(byte[] body) {
        int sampleLen = Math.min(body.length, BINARY_SAMPLE_LIMIT);
        if (sampleLen <= 0) {
            return false;
        }

        int highByteCount = 0;
        for (int i = 0; i < sampleLen; i++) {
            if ((body[i] & 0xFF) >= 128) {
                highByteCount++;
            }
        }
        return (highByteCount * 100 / sampleLen) > BINARY_PERCENT_THRESHOLD;
    }

    static boolean isExcludedMimeTypeSimd(String contentType) {
        return isExcludedMimeType(contentType);
    }

    static boolean isBinaryContentSimd(byte[] body) {
        return isBinaryContent(body);
    }

    private static byte[] gzip(byte[] input) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(input.length / 2);
        try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
            gz.write(input);
        } catch (IOException e) {
            throw new UncheckedIOException("gzip compression failed", e);
        }
        return bos.toByteArray();
    }

    private static void mergeVaryHeader(DefaultHttpServletResponse response, String token) {
        String merged = mergeCommaTokens(response.getHeader("Vary"), token);
        response.setHeader("Vary", merged);
    }

    private static String mergeCommaTokens(String existing, String add) {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        if (existing != null && !existing.isBlank()) {
            addCommaSeparatedTokens(existing, merged);
        }
        if (add != null && !add.isBlank()) {
            String trimmed = add.trim();
            boolean present = false;
            for (String token : merged) {
                if (token.equalsIgnoreCase(trimmed)) {
                    present = true;
                    break;
                }
            }
            if (!present) {
                merged.add(trimmed);
            }
        }
        return String.join(", ", merged);
    }

    private static void addCommaSeparatedTokens(String source, Set<String> target) {
        int length = source.length();
        int tokenStart = 0;
        while (tokenStart < length) {
            int tokenEnd = source.indexOf(',', tokenStart);
            if (tokenEnd < 0) {
                tokenEnd = length;
            }
            int start = tokenStart;
            while (start < tokenEnd && Character.isWhitespace(source.charAt(start))) {
                start++;
            }
            int end = tokenEnd;
            while (end > start && Character.isWhitespace(source.charAt(end - 1))) {
                end--;
            }
            if (start < end) {
                target.add(source.substring(start, end));
            }
            tokenStart = tokenEnd + 1;
        }
    }
}
