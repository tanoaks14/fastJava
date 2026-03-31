package com.fastjava.sse;

import java.nio.charset.StandardCharsets;

final class SseChunkEncoder {

    static final byte[] END_CHUNK = "0\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] CRLF = "\r\n".getBytes(StandardCharsets.US_ASCII);

    private SseChunkEncoder() {
    }

    static byte[] chunk(byte[] payload) {
        byte[] safePayload = payload == null ? new byte[0] : payload;
        byte[] size = Integer.toHexString(safePayload.length).getBytes(StandardCharsets.US_ASCII);
        byte[] out = new byte[size.length + CRLF.length + safePayload.length + CRLF.length];
        System.arraycopy(size, 0, out, 0, size.length);
        System.arraycopy(CRLF, 0, out, size.length, CRLF.length);
        System.arraycopy(safePayload, 0, out, size.length + CRLF.length, safePayload.length);
        System.arraycopy(CRLF, 0, out, size.length + CRLF.length + safePayload.length, CRLF.length);
        return out;
    }
}
