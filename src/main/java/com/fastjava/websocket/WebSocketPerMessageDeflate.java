package com.fastjava.websocket;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

public final class WebSocketPerMessageDeflate {

    private static final byte[] DEFLATE_TAIL = new byte[] { 0x00, 0x00, (byte) 0xFF, (byte) 0xFF };

    private WebSocketPerMessageDeflate() {
    }

    public static byte[] deflateMessage(byte[] payload) {
        byte[] input = payload == null ? new byte[0] : payload;
        if (input.length == 0) {
            return input;
        }

        Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
        deflater.setInput(input);

        ByteArrayOutputStream compressed = new ByteArrayOutputStream(input.length);
        byte[] buffer = new byte[256];
        while (true) {
            int written = deflater.deflate(buffer, 0, buffer.length, Deflater.SYNC_FLUSH);
            if (written > 0) {
                compressed.write(buffer, 0, written);
            }
            if (deflater.needsInput() && written == 0) {
                break;
            }
        }
        deflater.end();

        byte[] result = compressed.toByteArray();
        if (result.length >= DEFLATE_TAIL.length
                && result[result.length - 4] == DEFLATE_TAIL[0]
                && result[result.length - 3] == DEFLATE_TAIL[1]
                && result[result.length - 2] == DEFLATE_TAIL[2]
                && result[result.length - 1] == DEFLATE_TAIL[3]) {
            return Arrays.copyOf(result, result.length - DEFLATE_TAIL.length);
        }
        return result;
    }

    public static byte[] inflateMessage(byte[] compressedPayload, int maxPayloadBytes) throws IOException {
        byte[] compressed = compressedPayload == null ? new byte[0] : compressedPayload;
        if (compressed.length == 0) {
            return compressed;
        }

        byte[] framed = new byte[compressed.length + DEFLATE_TAIL.length];
        System.arraycopy(compressed, 0, framed, 0, compressed.length);
        System.arraycopy(DEFLATE_TAIL, 0, framed, compressed.length, DEFLATE_TAIL.length);

        Inflater inflater = new Inflater(true);
        inflater.setInput(framed);
        ByteArrayOutputStream payload = new ByteArrayOutputStream(Math.min(256, maxPayloadBytes));
        byte[] buffer = new byte[256];
        try {
            while (true) {
                int read = inflater.inflate(buffer);
                if (read > 0) {
                    payload.write(buffer, 0, read);
                    if (payload.size() > maxPayloadBytes) {
                        throw new IOException("Inflated payload too large");
                    }
                    continue;
                }
                if (inflater.finished() || inflater.needsInput()) {
                    break;
                }
                if (inflater.needsDictionary()) {
                    throw new IOException("Invalid permessage-deflate payload");
                }
            }
            return payload.toByteArray();
        } catch (DataFormatException formatException) {
            throw new IOException("Invalid permessage-deflate payload", formatException);
        } finally {
            inflater.end();
        }
    }
}