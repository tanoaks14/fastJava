package com.fastjava.http.h2;

/**
 * Minimal HTTP/2 frame header codec.
 */
public final class Http2FrameCodec {

    public static final int FRAME_HEADER_LENGTH = 9;
    public static final int TYPE_DATA = 0x0;
    public static final int TYPE_HEADERS = 0x1;
    public static final int TYPE_PRIORITY = 0x2;
    public static final int TYPE_RST_STREAM = 0x3;
    public static final int TYPE_SETTINGS = 0x4;
    public static final int TYPE_PUSH_PROMISE = 0x5;
    public static final int TYPE_PING = 0x6;
    public static final int TYPE_GOAWAY = 0x7;
    public static final int TYPE_WINDOW_UPDATE = 0x8;
    public static final int TYPE_CONTINUATION = 0x9;

    public static final int FLAG_END_STREAM = 0x1;
    public static final int FLAG_END_HEADERS = 0x4;
    public static final int FLAG_ACK = 0x1;

    private Http2FrameCodec() {
    }

    public static ParseResult parseFrame(byte[] buffer, int offset, int availableBytes, int maxFrameSize) {
        if (availableBytes < FRAME_HEADER_LENGTH) {
            return ParseResult.incomplete();
        }

        int length = ((buffer[offset] & 0xFF) << 16)
                | ((buffer[offset + 1] & 0xFF) << 8)
                | (buffer[offset + 2] & 0xFF);
        if (length > maxFrameSize) {
            return ParseResult.error("Frame length exceeds configured maxFrameSize");
        }

        int totalFrameLength = FRAME_HEADER_LENGTH + length;
        if (availableBytes < totalFrameLength) {
            return ParseResult.incomplete();
        }

        int type = buffer[offset + 3] & 0xFF;
        int flags = buffer[offset + 4] & 0xFF;
        int streamId = ((buffer[offset + 5] & 0x7F) << 24)
                | ((buffer[offset + 6] & 0xFF) << 16)
                | ((buffer[offset + 7] & 0xFF) << 8)
                | (buffer[offset + 8] & 0xFF);

        byte[] payload = new byte[length];
        if (length > 0) {
            System.arraycopy(buffer, offset + FRAME_HEADER_LENGTH, payload, 0, length);
        }

        return ParseResult.complete(new Http2Frame(length, type, flags, streamId, payload), totalFrameLength);
    }

    public static byte[] encodeFrame(int type, int flags, int streamId, byte[] payload) {
        int payloadLength = payload == null ? 0 : payload.length;
        byte[] encoded = new byte[FRAME_HEADER_LENGTH + payloadLength];
        encoded[0] = (byte) ((payloadLength >>> 16) & 0xFF);
        encoded[1] = (byte) ((payloadLength >>> 8) & 0xFF);
        encoded[2] = (byte) (payloadLength & 0xFF);
        encoded[3] = (byte) (type & 0xFF);
        encoded[4] = (byte) (flags & 0xFF);
        encoded[5] = (byte) ((streamId >>> 24) & 0x7F);
        encoded[6] = (byte) ((streamId >>> 16) & 0xFF);
        encoded[7] = (byte) ((streamId >>> 8) & 0xFF);
        encoded[8] = (byte) (streamId & 0xFF);
        if (payloadLength > 0) {
            System.arraycopy(payload, 0, encoded, FRAME_HEADER_LENGTH, payloadLength);
        }
        return encoded;
    }

    public record Http2Frame(int length, int type, int flags, int streamId, byte[] payload) {
    }

    public static final class ParseResult {
        private final Http2Frame frame;
        private final int bytesConsumed;
        private final String error;
        private final boolean incomplete;

        private ParseResult(Http2Frame frame, int bytesConsumed, String error, boolean incomplete) {
            this.frame = frame;
            this.bytesConsumed = bytesConsumed;
            this.error = error;
            this.incomplete = incomplete;
        }

        public static ParseResult complete(Http2Frame frame, int bytesConsumed) {
            return new ParseResult(frame, bytesConsumed, null, false);
        }

        public static ParseResult error(String error) {
            return new ParseResult(null, 0, error, false);
        }

        public static ParseResult incomplete() {
            return new ParseResult(null, 0, null, true);
        }

        public Http2Frame frame() {
            return frame;
        }

        public int bytesConsumed() {
            return bytesConsumed;
        }

        public String error() {
            return error;
        }

        public boolean isError() {
            return error != null;
        }

        public boolean isIncomplete() {
            return incomplete;
        }
    }
}