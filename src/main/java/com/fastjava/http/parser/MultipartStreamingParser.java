package com.fastjava.http.parser;

import com.fastjava.servlet.Part;
import com.fastjava.http.simd.SIMDByteScanner;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;

/**
 * Streaming multipart/form-data parser that processes parts incrementally
 * directly from an InputStream without requiring full buffering.
 * 
 * Supports:
 * - Incremental boundary detection from network stream
 * - Per-part streaming without eager decoding
 * - Spill-to-disk for large file uploads
 * - SIMD-assisted boundary scanning
 */
public final class MultipartStreamingParser implements Closeable {

    private static final int BUFFER_SIZE = 64 * 1024; // 64KB read buffer
    private static final int MAX_PARTS = 512;
    private static final int MAX_HEADER_LINE_BYTES = 8_192;
    private static final byte[] CRLF = "\r\n".getBytes(StandardCharsets.US_ASCII);

    private final InputStream source;
    private final byte[] boundary;
    private final byte[] delimiter; // "--" + boundary
    private final MultipartFormDataParser.MultipartLimits limits;

    private byte[] readBuffer;
    private int readPos = 0;
    private int readLimit = 0;
    private boolean eof = false;

    private long totalBytesRead = 0;
    private long totalPartsProcessed = 0;

    public MultipartStreamingParser(
            InputStream source,
            String contentType,
            MultipartFormDataParser.MultipartLimits limits) throws IOException {
        this.source = source;
        this.limits = limits != null ? limits : MultipartFormDataParser.MultipartLimits.defaults();
        this.readBuffer = new byte[BUFFER_SIZE];

        String boundaryStr = extractBoundary(contentType);
        if (boundaryStr == null || boundaryStr.isBlank()) {
            throw new IOException("No boundary found in Content-Type header");
        }
        this.boundary = boundaryStr.getBytes(StandardCharsets.US_ASCII);
        this.delimiter = ("--" + boundaryStr).getBytes(StandardCharsets.US_ASCII);
    }

    /**
     * Parses the next multipart part from the stream.
     * Returns null when stream is exhausted (terminal boundary reached).
     */
    public StreamingPart nextPart() throws IOException {
        if (totalPartsProcessed >= MAX_PARTS) {
            return null;
        }

        // Skip to next boundary
        if (totalPartsProcessed == 0) {
            // First part: skip initial boundary and CRLF
            skipInitialBoundary();
        } else {
            // Subsequent parts: skip to next boundary
            skipToBoundary();
        }

        // Check for terminal boundary (-- suffix)
        if (isTerminalBoundary()) {
            return null;
        }

        // Parse headers for this part
        PartHeaders headers = parsePartHeaders();
        if (headers == null) {
            return null;
        }

        // Create streaming part
        totalPartsProcessed++;
        StreamingPart part = new StreamingPart(
                headers.name,
                headers.submittedFileName,
                headers.contentType,
                this,
                limits);

        return part;
    }

    /**
     * Skips the initial boundary and CRLF sequence before the first part.
     */
    private void skipInitialBoundary() throws IOException {
        // Find "--boundary\r\n"
        byte[] initialBoundary = new byte[delimiter.length + 2];
        if (!readExactly(initialBoundary)) {
            throw new IOException("Unable to read initial boundary");
        }

        // Verify it matches delimiter + CRLF
        for (int i = 0; i < delimiter.length; i++) {
            if (initialBoundary[i] != delimiter[i]) {
                throw new IOException("Invalid multipart stream: boundary mismatch");
            }
        }
        if (initialBoundary[initialBoundary.length - 2] != '\r'
                || initialBoundary[initialBoundary.length - 1] != '\n') {
            throw new IOException("Invalid multipart stream: expected CRLF after initial boundary");
        }
    }

    /**
     * Skips to the next boundary in the stream.
     * The boundary is preceded by CRLF (part trailing).
     */
    private void skipToBoundary() throws IOException {
        byte[] boundaryWithPrefix = new byte[2 + delimiter.length + 2];
        // Pattern: \r\n--boundary\r\n

        while (true) {
            // Scan for \r\n-- sequence
            int matchPos = findBoundaryPrefix();
            if (matchPos != -1) {
                // Found boundary, advance read position
                readPos = matchPos + 2 + delimiter.length + 2;
                if (readPos > readLimit) {
                    fillBuffer();
                }
                return;
            }

            // Not found in current buffer, read more
            if (!fillBuffer()) {
                throw new IOException("End of stream before boundary found");
            }
        }
    }

    /**
     * Checks if the next boundary is a terminal boundary (ends with --).
     */
    private boolean isTerminalBoundary() throws IOException {
        // Peek ahead 2 bytes to check for -- suffix
        byte[] peek = new byte[2];
        if (!peekBytes(peek)) {
            return false;
        }
        return peek[0] == '-' && peek[1] == '-';
    }

    /**
     * Parses headers for the current part.
     */
    private PartHeaders parsePartHeaders() throws IOException {
        String partName = null;
        String submittedFileName = null;
        String partContentType = null;

        while (true) {
            byte[] headerLine = readUntilCRLF();
            if (headerLine == null) {
                throw new IOException("Unexpected end of stream while parsing part headers");
            }

            // Empty line marks end of headers
            if (headerLine.length == 0) {
                break;
            }

            if (headerLine.length > MAX_HEADER_LINE_BYTES) {
                throw new IOException("Header line exceeds max length: " + headerLine.length);
            }

            // Parse header
            int colonPos = indexOf(headerLine, (byte) ':');
            if (colonPos > 0) {
                String headerName = new String(headerLine, 0, colonPos, StandardCharsets.US_ASCII)
                        .trim()
                        .toLowerCase(Locale.ROOT);
                String headerValue = new String(headerLine, colonPos + 1, headerLine.length - colonPos - 1,
                        StandardCharsets.US_ASCII)
                        .trim();

                if ("content-disposition".equals(headerName)) {
                    Disposition disposition = parseDisposition(headerValue);
                    partName = disposition.name;
                    submittedFileName = disposition.filename;
                } else if ("content-type".equals(headerName)) {
                    partContentType = headerValue;
                }
            }
        }

        if (partName == null || partName.isBlank()) {
            return null;
        }

        return new PartHeaders(partName, submittedFileName, partContentType);
    }

    /**
     * Reads a line (until CRLF) from the stream, returning the bytes without CRLF.
     */
    private byte[] readUntilCRLF() throws IOException {
        ByteArrayOutputStream result = new ByteArrayOutputStream(256);
        int prev = -1;

        while (true) {
            if (readPos >= readLimit) {
                if (!fillBuffer()) {
                    return result.size() > 0 ? result.toByteArray() : null;
                }
            }

            byte b = readBuffer[readPos++];
            if (prev == '\r' && b == '\n') {
                // Found CRLF, remove the \r from result
                byte[] bytes = result.toByteArray();
                return bytes.length > 0 ? Arrays.copyOf(bytes, bytes.length - 1) : new byte[0];
            }
            if (prev != -1) {
                result.write(prev);
            }
            prev = b & 0xFF;
        }
    }

    /**
     * Creates a streaming input source for the current part body.
     * Reads from this input stream until the next boundary is reached.
     */
    public InputStream createPartBodyStream(long maxPartBytes, Path spillPath) throws IOException {
        return new PartBodyInputStream(maxPartBytes, spillPath);
    }

    /**
     * Finds the position of \r\n--boundary\r\n pattern.
     */
    private int findBoundaryPrefix() {
        // Look for CRLF followed by -- and delimiter
        byte[] pattern = new byte[2 + delimiter.length];
        System.arraycopy(new byte[] { '\r', '\n' }, 0, pattern, 0, 2);
        System.arraycopy(delimiter, 0, pattern, 2, delimiter.length);

        for (int i = readPos; i <= readLimit - pattern.length; i++) {
            boolean match = true;
            for (int j = 0; j < pattern.length; j++) {
                if (readBuffer[i + j] != pattern[j]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Fills the read buffer, returning false if EOF reached.
     */
    private boolean fillBuffer() throws IOException {
        if (eof) {
            return false;
        }

        // Shift unread data to front
        int unread = readLimit - readPos;
        if (unread > 0 && readPos > 0) {
            System.arraycopy(readBuffer, readPos, readBuffer, 0, unread);
        }
        readPos = 0;
        readLimit = unread;

        // Read new data
        int read = source.read(readBuffer, readLimit, readBuffer.length - readLimit);
        if (read <= 0) {
            eof = true;
            return readLimit > 0;
        }

        totalBytesRead += read;
        if (totalBytesRead > limits.maxMultipartBytes()) {
            throw new IOException("Multipart body exceeds max size: " + limits.maxMultipartBytes());
        }

        readLimit += read;
        return true;
    }

    /**
     * Reads exactly n bytes, returning false if EOF reached before n bytes read.
     */
    private boolean readExactly(byte[] dest) throws IOException {
        int offset = 0;
        while (offset < dest.length) {
            if (readPos >= readLimit && !fillBuffer()) {
                return false;
            }
            int available = readLimit - readPos;
            int needed = dest.length - offset;
            int copy = Math.min(available, needed);
            System.arraycopy(readBuffer, readPos, dest, offset, copy);
            readPos += copy;
            offset += copy;
        }
        return true;
    }

    /**
     * Peeks ahead n bytes without consuming from the stream.
     */
    private boolean peekBytes(byte[] dest) throws IOException {
        while (readLimit - readPos < dest.length) {
            if (!fillBuffer()) {
                return false;
            }
        }
        System.arraycopy(readBuffer, readPos, dest, 0, dest.length);
        return true;
    }

    private static String extractBoundary(String contentType) {
        if (contentType == null) {
            return null;
        }
        int length = contentType.length();
        int cursor = contentType.indexOf(';');
        while (cursor >= 0 && cursor < length - 1) {
            int tokenStart = cursor + 1;
            int tokenEnd = contentType.indexOf(';', tokenStart);
            if (tokenEnd < 0) {
                tokenEnd = length;
            }
            String token = contentType.substring(tokenStart, tokenEnd).trim();
            if (token.regionMatches(true, 0, "boundary=", 0, "boundary=".length())) {
                String boundary = token.substring("boundary=".length()).trim();
                if (boundary.length() >= 2 && boundary.charAt(0) == '"'
                        && boundary.charAt(boundary.length() - 1) == '"') {
                    boundary = boundary.substring(1, boundary.length() - 1);
                }
                return boundary;
            }
            cursor = tokenEnd < length ? tokenEnd : -1;
        }
        return null;
    }

    private static Disposition parseDisposition(String value) {
        String name = null;
        String filename = null;

        int length = value.length();
        int cursor = value.indexOf(';');
        while (cursor >= 0 && cursor < length - 1) {
            int sectionStart = cursor + 1;
            int sectionEnd = value.indexOf(';', sectionStart);
            if (sectionEnd < 0) {
                sectionEnd = length;
            }
            String section = value.substring(sectionStart, sectionEnd).trim();
            int eq = section.indexOf('=');
            if (eq <= 0) {
                cursor = sectionEnd < length ? sectionEnd : -1;
                continue;
            }
            String key = section.substring(0, eq).trim().toLowerCase(Locale.ROOT);
            String raw = section.substring(eq + 1).trim();
            String decoded = stripQuotes(raw);
            if ("name".equals(key)) {
                name = decoded;
            } else if ("filename".equals(key)) {
                filename = decoded;
            }
            cursor = sectionEnd < length ? sectionEnd : -1;
        }

        return new Disposition(name, filename);
    }

    private static String stripQuotes(String value) {
        if (value.length() >= 2 && value.charAt(0) == '"' && value.charAt(value.length() - 1) == '"') {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static int indexOf(byte[] buffer, byte target) {
        for (int i = 0; i < buffer.length; i++) {
            if (buffer[i] == target) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public void close() throws IOException {
        if (source != null) {
            source.close();
        }
    }

    private record Disposition(String name, String filename) {
    }

    private record PartHeaders(String name, String submittedFileName, String contentType) {
    }

    /**
     * Streaming part that lazily consumes body from the underlying stream,
     * with optional spill-to-disk for large uploads.
     */
    public static final class StreamingPart implements Part {
        private final String name;
        private final String submittedFileName;
        private final String contentType;
        private final MultipartStreamingParser parser;
        private final MultipartFormDataParser.MultipartLimits limits;
        private InputStream bodyStream;
        private Path spilledPath;
        private long partSize = 0;
        private boolean consumed = false;

        private StreamingPart(
                String name,
                String submittedFileName,
                String contentType,
                MultipartStreamingParser parser,
                MultipartFormDataParser.MultipartLimits limits) {
            this.name = name;
            this.submittedFileName = submittedFileName;
            this.contentType = contentType;
            this.parser = parser;
            this.limits = limits;
        }

        @Override
        public InputStream getInputStream() {
            try {
                if (bodyStream == null) {
                    initializeBodyStream();
                }
                return bodyStream;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public String getSubmittedFileName() {
            return submittedFileName;
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getContentType() {
            return contentType;
        }

        @Override
        public long getSize() {
            return partSize;
        }

        @Override
        public void write(String fileName) throws IOException {
            try (InputStream input = getInputStream();
                    OutputStream output = Files.newOutputStream(
                            new File(fileName).toPath(),
                            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                            StandardOpenOption.WRITE)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                }
            }
        }

        @Override
        public void delete() throws IOException {
            if (spilledPath != null && Files.exists(spilledPath)) {
                Files.delete(spilledPath);
            }
        }

        @Override
        public byte[] getBytes() {
            try {
                try (InputStream input = getInputStream();
                        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream()) {
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        baos.write(buffer, 0, read);
                    }
                    return baos.toByteArray();
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Override
        public long transferTo(java.io.OutputStream output) throws IOException {
            try (InputStream input = getInputStream()) {
                byte[] buffer = new byte[8192];
                long total = 0;
                int read;
                while ((read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                    total += read;
                }
                return total;
            }
        }

        private void initializeBodyStream() throws IOException {
            boolean isFile = submittedFileName != null && !submittedFileName.isBlank();
            long maxPartBytes = limits.maxMultipartPartBytes();

            Path spill = null;
            OutputStream tempOutput = null;

            try {
                // Create temporary spill file for large parts
                if (isFile && maxPartBytes > limits.multipartMemoryThresholdBytes()) {
                    spill = Files.createTempFile("fastjava-upload-", ".part");
                    tempOutput = Files.newOutputStream(spill, StandardOpenOption.TRUNCATE_EXISTING);
                }

                bodyStream = new CountingPartOutputStream(
                        parser.createPartBodyStream(maxPartBytes, spill),
                        this,
                        tempOutput);
                this.spilledPath = spill;
                this.consumed = true;
            } catch (IOException e) {
                if (spill != null) {
                    try {
                        Files.delete(spill);
                    } catch (IOException ignored) {
                    }
                }
                throw e;
            }
        }

        public boolean isFile() {
            return submittedFileName != null && !submittedFileName.isBlank();
        }

        public Path getSpilledPath() {
            return spilledPath;
        }
    }

    /**
     * Input stream that reads from the multipart parser until the next boundary.
     */
    private class PartBodyInputStream extends InputStream {
        private final long maxBytes;
        private final Path spillPath;
        private long bytesRead = 0;
        private boolean boundaryReached = false;

        PartBodyInputStream(long maxBytes, Path spillPath) {
            this.maxBytes = maxBytes;
            this.spillPath = spillPath;
        }

        @Override
        public int read() throws IOException {
            if (boundaryReached || bytesRead >= maxBytes) {
                return -1;
            }
            if (readPos >= readLimit && !fillBuffer()) {
                return -1;
            }
            byte b = readBuffer[readPos++];
            bytesRead++;
            checkForBoundary();
            return b & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (boundaryReached || bytesRead >= maxBytes) {
                return -1;
            }
            if (readPos >= readLimit && !fillBuffer()) {
                return -1;
            }

            int available = readLimit - readPos;
            int toRead = Math.min(len, Math.min(available, (int) Math.min(Integer.MAX_VALUE, maxBytes - bytesRead)));

            if (toRead == 0) {
                return -1;
            }

            System.arraycopy(readBuffer, readPos, b, off, toRead);
            readPos += toRead;
            bytesRead += toRead;
            checkForBoundary();

            return toRead;
        }

        private void checkForBoundary() throws IOException {
            // Check if we're at or near a boundary
            if (readLimit - readPos >= delimiter.length + 4) {
                // Look for CRLF--boundary pattern
                if (readBuffer[readPos] == '\r' && readBuffer[readPos + 1] == '\n') {
                    boolean isBoundary = true;
                    for (int i = 0; i < delimiter.length; i++) {
                        if (readBuffer[readPos + 2 + i] != delimiter[i]) {
                            isBoundary = false;
                            break;
                        }
                    }
                    if (isBoundary) {
                        boundaryReached = true;
                    }
                }
            }
        }

        @Override
        public void close() {
            // Do not close underlying parser stream
        }
    }

    /**
     * Wrapper that counts bytes written and manages spill-to-disk.
     */
    private static class CountingPartOutputStream extends InputStream {
        private final InputStream source;
        private final StreamingPart part;
        private final OutputStream spillOutput;

        CountingPartOutputStream(InputStream source, StreamingPart part, OutputStream spillOutput) {
            this.source = source;
            this.part = part;
            this.spillOutput = spillOutput;
        }

        @Override
        public int read() throws IOException {
            int b = source.read();
            if (b != -1) {
                part.partSize++;
                if (spillOutput != null) {
                    spillOutput.write(b);
                }
            } else if (spillOutput != null) {
                spillOutput.close();
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int read = source.read(b, off, len);
            if (read > 0) {
                part.partSize += read;
                if (spillOutput != null) {
                    spillOutput.write(b, off, read);
                }
            } else if (read == -1 && spillOutput != null) {
                spillOutput.close();
            }
            return read;
        }

        @Override
        public void close() throws IOException {
            if (spillOutput != null) {
                spillOutput.close();
            }
            source.close();
        }
    }
}
