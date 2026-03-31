package com.fastjava.http.parser;

import com.fastjava.http.simd.SIMDByteScanner;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardCopyOption;

/**
 * Buffered multipart/form-data parser with SIMD-assisted boundary detection.
 */
public final class MultipartFormDataParser {

    private static final int MAX_PARTS = 512;
    private static final int MAX_HEADER_LINE_BYTES = 8_192;

    private MultipartFormDataParser() {
    }

    public static boolean parseTextParameters(
            byte[] body,
            String contentType,
            Map<String, List<String>> valuesByKey,
            boolean skipExistingKeys) {
        ParsedMultipart parsed = parse(body, contentType);
        if (!parsed.valid()) {
            return false;
        }

        for (ParsedPart part : parsed.parts()) {
            if (part.isFilePart()) {
                continue;
            }
            if (part.name() == null || part.name().isBlank()) {
                continue;
            }
            if (skipExistingKeys && valuesByKey.containsKey(part.name())) {
                continue;
            }
            String value = new String(part.valueBytes(), StandardCharsets.UTF_8);
            valuesByKey.computeIfAbsent(part.name(), key -> new ArrayList<>()).add(value);
        }

        return true;
    }

    public static ParsedMultipart parse(byte[] body, String contentType) {
        return parse(body, 0, body == null ? 0 : body.length, contentType, MultipartLimits.defaults());
    }

    public static ParsedMultipart parse(InputStream bodyStream, String contentType, MultipartLimits limits) {
        if (bodyStream == null || limits == null) {
            return ParsedMultipart.invalid();
        }
        try {
            byte[] body = bodyStream.readNBytes(limits.maxMultipartBytes() + 1);
            if (body.length > limits.maxMultipartBytes()) {
                return ParsedMultipart.invalid();
            }
            return parse(body, 0, body.length, contentType, limits);
        } catch (IOException exception) {
            return ParsedMultipart.invalid();
        }
    }

    public static ParsedMultipart parse(byte[] buffer, int bodyOffset, int bodyLength, String contentType,
            MultipartLimits limits) {
        if (buffer == null || bodyLength <= 0 || contentType == null || limits == null) {
            return ParsedMultipart.invalid();
        }
        if (bodyOffset < 0 || bodyOffset + bodyLength > buffer.length || bodyLength > limits.maxMultipartBytes()) {
            return ParsedMultipart.invalid();
        }

        String boundary = extractBoundary(contentType);
        if (boundary == null || boundary.isBlank()) {
            return ParsedMultipart.invalid();
        }

        byte[] delimiter = ("--" + boundary).getBytes(StandardCharsets.US_ASCII);
        int scanEnd = bodyOffset + bodyLength;
        int boundaryPos = SIMDByteScanner.findBoundaryLine(buffer, bodyOffset, scanEnd, delimiter);
        if (boundaryPos != bodyOffset) {
            return ParsedMultipart.invalid();
        }

        int partCount = 0;
        List<ParsedPart> parts = new ArrayList<>();
        while (boundaryPos >= 0) {
            int cursor = boundaryPos + delimiter.length;
            if (cursor + 1 < scanEnd && buffer[cursor] == '-' && buffer[cursor + 1] == '-') {
                return partCount > 0 ? ParsedMultipart.valid(parts) : ParsedMultipart.invalid();
            }
            if (cursor + 1 >= scanEnd || buffer[cursor] != '\r' || buffer[cursor + 1] != '\n') {
                return ParsedMultipart.invalid();
            }
            cursor += 2;

            String partName = null;
            String submittedFileName = null;
            String partContentType = null;

            while (true) {
                int lineEnd = SIMDByteScanner.findCRLF(buffer, cursor, scanEnd);
                if (lineEnd == -1) {
                    return ParsedMultipart.invalid();
                }
                if (lineEnd == cursor) {
                    cursor += 2;
                    break;
                }
                if (lineEnd - cursor > MAX_HEADER_LINE_BYTES) {
                    return ParsedMultipart.invalid();
                }

                int colon = SIMDByteScanner.indexOfByte(buffer, cursor, lineEnd, (byte) ':');
                if (colon > cursor) {
                    String headerName = new String(buffer, cursor, colon - cursor, StandardCharsets.US_ASCII)
                            .trim()
                            .toLowerCase(Locale.ROOT);
                    String headerValue = new String(buffer, colon + 1, lineEnd - colon - 1, StandardCharsets.US_ASCII)
                            .trim();
                    if ("content-disposition".equals(headerName)) {
                        Disposition disposition = parseDisposition(headerValue);
                        partName = disposition.name;
                        submittedFileName = disposition.filename;
                    } else if ("content-type".equals(headerName)) {
                        partContentType = headerValue;
                    }
                }
                cursor = lineEnd + 2;
            }

            int nextBoundary = SIMDByteScanner.findBoundaryLine(buffer, cursor, scanEnd, delimiter);
            if (nextBoundary == -1 || nextBoundary < cursor) {
                return ParsedMultipart.invalid();
            }

            int valueEnd = nextBoundary;
            if (valueEnd >= bodyOffset + 2 && buffer[valueEnd - 2] == '\r' && buffer[valueEnd - 1] == '\n') {
                valueEnd -= 2;
            }

            if (partName != null && !partName.isBlank()) {
                int partLength = Math.max(0, valueEnd - cursor);
                if (partLength > limits.maxMultipartPartBytes()) {
                    return ParsedMultipart.invalid();
                }

                Path spilledPath = null;
                boolean spillToDisk = submittedFileName != null && !submittedFileName.isBlank()
                        && partLength > limits.multipartMemoryThresholdBytes();
                if (spillToDisk) {
                    try {
                        spilledPath = Files.createTempFile("fastjava-upload-", ".part");
                        try (java.io.OutputStream output = Files.newOutputStream(spilledPath,
                                StandardOpenOption.TRUNCATE_EXISTING)) {
                            output.write(buffer, cursor, partLength);
                        }
                    } catch (IOException exception) {
                        return ParsedMultipart.invalid();
                    }
                }
                parts.add(new ParsedPart(
                        partName,
                        submittedFileName,
                        partContentType,
                        spillToDisk ? null : buffer,
                        cursor,
                        partLength,
                        spilledPath));
            }

            partCount++;
            if (partCount > MAX_PARTS) {
                return ParsedMultipart.invalid();
            }

            boundaryPos = nextBoundary;
        }

        return ParsedMultipart.invalid();
    }

    private static String extractBoundary(String contentType) {
        int cursor = contentType.indexOf(';');
        if (cursor < 0) {
            return null;
        }
        cursor += 1;
        while (cursor < contentType.length()) {
            int next = contentType.indexOf(';', cursor);
            if (next < 0) {
                next = contentType.length();
            }
            String token = contentType.substring(cursor, next).trim();
            if (token.regionMatches(true, 0, "boundary=", 0, "boundary=".length())) {
                String boundary = token.substring("boundary=".length()).trim();
                if (boundary.length() >= 2 && boundary.charAt(0) == '"'
                        && boundary.charAt(boundary.length() - 1) == '"') {
                    boundary = boundary.substring(1, boundary.length() - 1);
                }
                return boundary;
            }
            cursor = next + 1;
        }
        return null;
    }

    private static Disposition parseDisposition(String value) {
        String name = null;
        String filename = null;

        int cursor = value.indexOf(';');
        if (cursor < 0) {
            return new Disposition(name, filename);
        }
        cursor += 1;
        while (cursor < value.length()) {
            int next = value.indexOf(';', cursor);
            if (next < 0) {
                next = value.length();
            }
            String section = value.substring(cursor, next).trim();
            int eq = section.indexOf('=');
            if (eq <= 0) {
                cursor = next + 1;
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
            cursor = next + 1;
        }

        return new Disposition(name, filename);
    }

    private static String stripQuotes(String value) {
        if (value.length() >= 2 && value.charAt(0) == '"' && value.charAt(value.length() - 1) == '"') {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private record Disposition(String name, String filename) {
    }

    public record ParsedMultipart(List<ParsedPart> parts, boolean valid) {
        public static ParsedMultipart invalid() {
            return new ParsedMultipart(List.of(), false);
        }

        public static ParsedMultipart valid(List<ParsedPart> parts) {
            return new ParsedMultipart(List.copyOf(parts), true);
        }
    }

    public static final class ParsedPart implements com.fastjava.servlet.Part {
        private final String name;
        private final String submittedFileName;
        private final String contentType;
        private final byte[] sourceBuffer;
        private final int valueOffset;
        private final int size;
        private final Path spilledPath;

        private ParsedPart(
                String name,
                String submittedFileName,
                String contentType,
                byte[] sourceBuffer,
                int valueOffset,
                int size,
                Path spilledPath) {
            this.name = name;
            this.submittedFileName = submittedFileName;
            this.contentType = contentType;
            this.sourceBuffer = sourceBuffer;
            this.valueOffset = valueOffset;
            this.size = size;
            this.spilledPath = spilledPath;
        }

        public String name() {
            return name;
        }

        @Override
        public String getName() {
            return name;
        }

        public String submittedFileName() {
            return submittedFileName;
        }

        @Override
        public String getSubmittedFileName() {
            return submittedFileName;
        }

        public String contentType() {
            return contentType;
        }

        @Override
        public String getContentType() {
            return contentType;
        }

        public int size() {
            return size;
        }

        public Path spilledPath() {
            return spilledPath;
        }

        public byte[] valueBytes() {
            return getBytes();
        }

        public boolean isFilePart() {
            return submittedFileName != null && !submittedFileName.isBlank();
        }

        public boolean isSpilledToDisk() {
            return spilledPath != null;
        }

        public byte[] getBytes() {
            if (!isSpilledToDisk()) {
                if (sourceBuffer == null || size <= 0) {
                    return new byte[0];
                }
                return Arrays.copyOfRange(sourceBuffer, valueOffset, valueOffset + size);
            }
            try {
                return Files.readAllBytes(spilledPath);
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }

        public InputStream openStream() {
            try {
                if (isSpilledToDisk()) {
                    return Files.newInputStream(spilledPath);
                }
                if (sourceBuffer == null || size <= 0) {
                    return InputStream.nullInputStream();
                }
                return new java.io.ByteArrayInputStream(sourceBuffer, valueOffset, size);
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }

        public long transferTo(java.io.OutputStream output) throws IOException {
            try (InputStream input = openStream()) {
                byte[] copyBuffer = new byte[8192];
                int read;
                long total = 0;
                while ((read = input.read(copyBuffer)) != -1) {
                    output.write(copyBuffer, 0, read);
                    total += read;
                }
                return total;
            }
        }

        public void writeTo(Path target) throws IOException {
            if (isSpilledToDisk()) {
                Files.copy(spilledPath, target, StandardCopyOption.REPLACE_EXISTING);
                return;
            }
            try (java.io.OutputStream output = Files.newOutputStream(target,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                transferTo(output);
            }
        }

        @Override
        public void write(String fileName) throws IOException {
            writeTo(Path.of(fileName));
        }

        @Override
        public void delete() throws IOException {
            if (isSpilledToDisk() && spilledPath != null && Files.exists(spilledPath)) {
                Files.delete(spilledPath);
            }
        }

        @Override
        public InputStream getInputStream() {
            return openStream();
        }

        @Override
        public long getSize() {
            return size;
        }
    }

    public record MultipartLimits(int maxMultipartBytes, int maxMultipartPartBytes, int multipartMemoryThresholdBytes) {
        public static MultipartLimits defaults() {
            return new MultipartLimits(16 * 1024 * 1024, 8 * 1024 * 1024, 64 * 1024);
        }
    }
}
