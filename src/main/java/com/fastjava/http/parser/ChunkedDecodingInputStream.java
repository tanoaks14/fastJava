package com.fastjava.http.parser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

final class ChunkedDecodingInputStream extends InputStream {

    private final byte[] buffer;
    private final int end;
    private int cursor;
    private int chunkRemaining;
    private boolean finished;

    ChunkedDecodingInputStream(byte[] buffer, int start, int end) {
        this.buffer = buffer;
        this.cursor = start;
        this.end = Math.min(end, buffer.length);
        this.chunkRemaining = 0;
        this.finished = false;
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

        if (chunkRemaining == 0) {
            if (!readNextChunkHeader()) {
                finished = true;
                return -1;
            }
        }

        int readable = Math.min(length, chunkRemaining);
        if (cursor + readable > end) {
            throw new IOException("Invalid chunked payload bounds");
        }
        System.arraycopy(buffer, cursor, target, offset, readable);
        cursor += readable;
        chunkRemaining -= readable;

        if (chunkRemaining == 0) {
            consumeCRLF();
        }
        return readable;
    }

    private boolean readNextChunkHeader() throws IOException {
        int lineEnd = findCRLF(cursor);
        if (lineEnd < 0) {
            throw new IOException("Invalid chunked stream: missing chunk size terminator");
        }
        String rawSize = new String(buffer, cursor, lineEnd - cursor, StandardCharsets.US_ASCII);
        int extensionIndex = rawSize.indexOf(';');
        String sizeToken = (extensionIndex >= 0 ? rawSize.substring(0, extensionIndex) : rawSize).trim();
        if (sizeToken.isEmpty()) {
            throw new IOException("Invalid chunk size");
        }

        int size;
        try {
            size = Integer.parseInt(sizeToken, 16);
        } catch (NumberFormatException exception) {
            throw new IOException("Invalid chunk size", exception);
        }
        if (size < 0) {
            throw new IOException("Negative chunk size");
        }

        cursor = lineEnd + 2;
        if (size == 0) {
            consumeTrailers();
            return false;
        }

        chunkRemaining = size;
        return true;
    }

    private void consumeCRLF() throws IOException {
        if (cursor + 1 >= end || buffer[cursor] != '\r' || buffer[cursor + 1] != '\n') {
            throw new IOException("Invalid chunk terminator");
        }
        cursor += 2;
    }

    private void consumeTrailers() throws IOException {
        while (true) {
            int lineEnd = findCRLF(cursor);
            if (lineEnd < 0) {
                throw new IOException("Invalid chunk trailer termination");
            }
            if (lineEnd == cursor) {
                cursor += 2;
                return;
            }
            cursor = lineEnd + 2;
        }
    }

    private int findCRLF(int from) {
        for (int index = from; index + 1 < end; index++) {
            if (buffer[index] == '\r' && buffer[index + 1] == '\n') {
                return index;
            }
        }
        return -1;
    }
}
