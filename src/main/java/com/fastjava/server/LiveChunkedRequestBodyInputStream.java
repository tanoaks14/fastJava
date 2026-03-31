package com.fastjava.server;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Incrementally decodes a chunked request body by consuming any prefetched
 * bytes first, then continuing from the underlying socket input stream.
 */
final class LiveChunkedRequestBodyInputStream extends InputStream {

    private static final int MAX_LINE_BYTES = 8_192;

    private final PrefixedInputStream source;
    private final RequestLimits limits;
    private int chunkRemaining;
    private int chunkCount;
    private int decodedBytes;
    private boolean finished;

    LiveChunkedRequestBodyInputStream(
            byte[] buffered,
            int offset,
            int length,
            InputStream socketInput,
            RequestLimits limits) {
        this.source = new PrefixedInputStream(buffered, offset, length, socketInput);
        this.limits = limits;
    }

    @Override
    public int read() throws IOException {
        byte[] one = new byte[1];
        int read = read(one, 0, 1);
        return read == -1 ? -1 : one[0] & 0xFF;
    }

    @Override
    public int read(byte[] target, int offset, int length) throws IOException {
        if (target == null) {
            throw new NullPointerException("target");
        }
        if (offset < 0 || length < 0 || offset + length > target.length) {
            throw new IndexOutOfBoundsException();
        }
        if (length == 0) {
            return 0;
        }
        if (finished) {
            return -1;
        }

        while (chunkRemaining == 0) {
            if (!readNextChunkHeader()) {
                finished = true;
                return -1;
            }
        }

        int toRead = Math.min(length, chunkRemaining);
        int copied = source.read(target, offset, toRead);
        if (copied <= 0) {
            throw new EOFException("Unexpected EOF while reading chunk data");
        }

        chunkRemaining -= copied;
        if (chunkRemaining == 0) {
            expectCRLF();
        }
        return copied;
    }

    private boolean readNextChunkHeader() throws IOException {
        String rawSize = readAsciiLine();
        int extensionIndex = rawSize.indexOf(';');
        String sizeToken = (extensionIndex >= 0 ? rawSize.substring(0, extensionIndex) : rawSize).trim();
        if (sizeToken.isEmpty()) {
            throw new IOException("Invalid chunk size");
        }

        final int chunkSize;
        try {
            chunkSize = Integer.parseInt(sizeToken, 16);
        } catch (NumberFormatException exception) {
            throw new IOException("Invalid chunk size", exception);
        }

        if (chunkSize < 0 || chunkSize > limits.maxChunkSizeBytes()) {
            throw new IOException("Chunk size exceeds limits");
        }

        if (chunkSize == 0) {
            readTrailers();
            return false;
        }

        chunkCount++;
        if (chunkCount > limits.maxChunkCount()) {
            throw new IOException("Chunk count exceeds limits");
        }

        decodedBytes += chunkSize;
        if (decodedBytes > limits.maxBodyBytes()) {
            throw new IOException("Chunked body exceeds limits");
        }

        chunkRemaining = chunkSize;
        return true;
    }

    private void readTrailers() throws IOException {
        while (true) {
            String trailer = readAsciiLine();
            if (trailer.isEmpty()) {
                return;
            }
            int colon = trailer.indexOf(':');
            if (colon <= 0 || colon >= trailer.length() - 1) {
                throw new IOException("Invalid trailer header");
            }
        }
    }

    private void expectCRLF() throws IOException {
        int cr = source.read();
        int lf = source.read();
        if (cr != '\r' || lf != '\n') {
            throw new IOException("Invalid chunk data terminator");
        }
    }

    private String readAsciiLine() throws IOException {
        byte[] lineBuffer = new byte[128];
        int length = 0;

        while (true) {
            int current = source.read();
            if (current == -1) {
                throw new EOFException("Unexpected EOF while reading chunk header");
            }
            if (current == '\r') {
                int next = source.read();
                if (next != '\n') {
                    throw new IOException("Invalid line terminator");
                }
                return new String(lineBuffer, 0, length, StandardCharsets.US_ASCII);
            }
            if (length == MAX_LINE_BYTES) {
                throw new IOException("Chunk header line too long");
            }
            if (length == lineBuffer.length) {
                byte[] grown = new byte[Math.min(MAX_LINE_BYTES, lineBuffer.length * 2)];
                System.arraycopy(lineBuffer, 0, grown, 0, lineBuffer.length);
                lineBuffer = grown;
            }
            lineBuffer[length++] = (byte) current;
        }
    }

    private static final class PrefixedInputStream extends InputStream {
        private final byte[] prefix;
        private final int prefixEnd;
        private final InputStream delegate;
        private int cursor;

        private PrefixedInputStream(byte[] prefix, int offset, int length, InputStream delegate) {
            this.prefix = prefix;
            this.cursor = Math.max(0, offset);
            this.prefixEnd = Math.min(prefix.length, this.cursor + Math.max(0, length));
            this.delegate = delegate;
        }

        @Override
        public int read() throws IOException {
            if (cursor < prefixEnd) {
                return prefix[cursor++] & 0xFF;
            }
            return delegate.read();
        }

        @Override
        public int read(byte[] target, int offset, int length) throws IOException {
            if (cursor < prefixEnd) {
                int available = prefixEnd - cursor;
                int toCopy = Math.min(length, available);
                System.arraycopy(prefix, cursor, target, offset, toCopy);
                cursor += toCopy;
                return toCopy;
            }
            return delegate.read(target, offset, length);
        }
    }
}
