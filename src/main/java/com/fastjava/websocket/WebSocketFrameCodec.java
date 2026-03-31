package com.fastjava.websocket;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.VectorSpecies;

import java.util.Arrays;
import java.io.IOException;

public final class WebSocketFrameCodec {

    public static final int OPCODE_CONTINUATION = 0x0;
    public static final int OPCODE_TEXT = 0x1;
    public static final int OPCODE_BINARY = 0x2;
    public static final int OPCODE_CLOSE = 0x8;
    public static final int OPCODE_PING = 0x9;
    public static final int OPCODE_PONG = 0xA;

    public static final int CLOSE_NORMAL = 1000;
    public static final int CLOSE_PROTOCOL_ERROR = 1002;
    public static final int CLOSE_UNSUPPORTED_DATA = 1003;
    public static final int CLOSE_TOO_LARGE = 1009;

    private static final int MASK_BIT = 0x80;
    private static final VectorSpecies<Byte> BYTE_SPECIES = ByteVector.SPECIES_PREFERRED;

    private WebSocketFrameCodec() {
    }

    public static ParseResult parseClientFrame(byte[] buffer, int offset, int availableBytes, int maxPayloadBytes) {
        return parseClientFrame(buffer, offset, availableBytes, maxPayloadBytes, false);
    }

    public static ParseResult parseClientFrame(
            byte[] buffer,
            int offset,
            int availableBytes,
            int maxPayloadBytes,
            boolean perMessageDeflateEnabled) {
        if (availableBytes < 2) {
            return ParseResult.incomplete();
        }

        int b0 = buffer[offset] & 0xFF;
        int b1 = buffer[offset + 1] & 0xFF;

        boolean fin = (b0 & 0x80) != 0;
        boolean rsv1 = (b0 & 0x40) != 0;
        boolean rsv2 = (b0 & 0x20) != 0;
        boolean rsv3 = (b0 & 0x10) != 0;
        int opcode = b0 & 0x0F;
        boolean masked = (b1 & MASK_BIT) != 0;
        int payloadIndicator = b1 & 0x7F;

        if (!isSupportedOpcode(opcode)) {
            return ParseResult.error(CLOSE_PROTOCOL_ERROR, "Unsupported opcode");
        }
        if (!masked) {
            return ParseResult.error(CLOSE_PROTOCOL_ERROR, "Client frame must be masked");
        }

        boolean controlFrame = (opcode & 0x08) != 0;
        if (perMessageDeflateEnabled) {
            if (rsv2 || rsv3) {
                return ParseResult.error(CLOSE_PROTOCOL_ERROR, "Unsupported RSV bits");
            }
            if (controlFrame && rsv1) {
                return ParseResult.error(CLOSE_PROTOCOL_ERROR, "Control frames must not use RSV1");
            }
            if (opcode == OPCODE_CONTINUATION && rsv1) {
                return ParseResult.error(CLOSE_PROTOCOL_ERROR, "Continuation frame must not use RSV1");
            }
        } else if (rsv1 || rsv2 || rsv3) {
            return ParseResult.error(CLOSE_PROTOCOL_ERROR, "RSV bits are not supported");
        }

        int cursor = offset + 2;
        long payloadLength;
        if (payloadIndicator <= 125) {
            payloadLength = payloadIndicator;
        } else if (payloadIndicator == 126) {
            if (availableBytes < 4) {
                return ParseResult.incomplete();
            }
            payloadLength = ((buffer[offset + 2] & 0xFFL) << 8) | (buffer[offset + 3] & 0xFFL);
            cursor += 2;
        } else {
            if (availableBytes < 10) {
                return ParseResult.incomplete();
            }
            payloadLength = readUnsignedLong(buffer, offset + 2);
            cursor += 8;
        }

        if (payloadLength > Integer.MAX_VALUE) {
            return ParseResult.error(CLOSE_TOO_LARGE, "Payload exceeds supported limits");
        }
        if (payloadLength > maxPayloadBytes) {
            return ParseResult.error(CLOSE_TOO_LARGE, "Payload too large");
        }

        if (controlFrame && (!fin || payloadLength > 125)) {
            return ParseResult.error(CLOSE_PROTOCOL_ERROR, "Invalid control frame");
        }

        int payloadLengthInt = (int) payloadLength;
        int requiredBytes = cursor - offset + 4 + payloadLengthInt;
        if (availableBytes < requiredBytes) {
            return ParseResult.incomplete();
        }

        int maskOffset = cursor;
        int payloadOffset = cursor + 4;
        byte[] payload = Arrays.copyOfRange(buffer, payloadOffset, payloadOffset + payloadLengthInt);
        applyMask(payload, buffer, maskOffset);

        if (perMessageDeflateEnabled && rsv1) {
            if (opcode != OPCODE_TEXT && opcode != OPCODE_BINARY) {
                return ParseResult.error(CLOSE_PROTOCOL_ERROR, "RSV1 only valid for data frames");
            }
            if (!fin) {
                return ParseResult.error(CLOSE_PROTOCOL_ERROR, "Compressed fragmented messages are not supported");
            }
            try {
                payload = WebSocketPerMessageDeflate.inflateMessage(payload, maxPayloadBytes);
            } catch (IOException inflationException) {
                return ParseResult.error(CLOSE_PROTOCOL_ERROR, inflationException.getMessage());
            }
        }

        return ParseResult.complete(
                new WebSocketFrame(opcode, fin, payload),
                requiredBytes);
    }

    public static byte[] encodeServerFrame(int opcode, boolean fin, byte[] payload) {
        return encodeServerFrame(opcode, fin, payload, false);
    }

    public static byte[] encodeServerFrame(int opcode, boolean fin, byte[] payload, boolean perMessageDeflateEnabled) {
        byte[] normalizedPayload = payload == null ? new byte[0] : payload;
        boolean compressPayload = perMessageDeflateEnabled
                && fin
                && (opcode == OPCODE_TEXT || opcode == OPCODE_BINARY)
                && normalizedPayload.length > 0;
        if (compressPayload) {
            normalizedPayload = WebSocketPerMessageDeflate.deflateMessage(normalizedPayload);
        }
        int payloadLength = normalizedPayload.length;

        int headerLength = payloadLength <= 125 ? 2 : (payloadLength <= 0xFFFF ? 4 : 10);
        byte[] frame = new byte[headerLength + payloadLength];
        int firstByte = (fin ? 0x80 : 0) | (opcode & 0x0F);
        if (compressPayload) {
            firstByte |= 0x40;
        }
        frame[0] = (byte) firstByte;

        int cursor = 2;
        if (payloadLength <= 125) {
            frame[1] = (byte) payloadLength;
        } else if (payloadLength <= 0xFFFF) {
            frame[1] = 126;
            frame[2] = (byte) ((payloadLength >>> 8) & 0xFF);
            frame[3] = (byte) (payloadLength & 0xFF);
            cursor = 4;
        } else {
            frame[1] = 127;
            long length = payloadLength;
            for (int i = 9; i >= 2; i--) {
                frame[i] = (byte) (length & 0xFF);
                length >>>= 8;
            }
            cursor = 10;
        }

        System.arraycopy(normalizedPayload, 0, frame, cursor, payloadLength);
        return frame;
    }

    public static byte[] closePayload(int closeCode) {
        return new byte[] {
                (byte) ((closeCode >>> 8) & 0xFF),
                (byte) (closeCode & 0xFF)
        };
    }

    private static boolean isSupportedOpcode(int opcode) {
        return opcode == OPCODE_CONTINUATION
                || opcode == OPCODE_TEXT
                || opcode == OPCODE_BINARY
                || opcode == OPCODE_CLOSE
                || opcode == OPCODE_PING
                || opcode == OPCODE_PONG;
    }

    private static long readUnsignedLong(byte[] buffer, int offset) {
        long value = 0;
        for (int index = 0; index < 8; index++) {
            value = (value << 8) | (buffer[offset + index] & 0xFFL);
        }
        return value;
    }

    private static void applyMask(byte[] payload, byte[] buffer, int maskOffset) {
        byte[] maskPattern = new byte[BYTE_SPECIES.length()];
        for (int lane = 0; lane < maskPattern.length; lane++) {
            maskPattern[lane] = buffer[maskOffset + (lane & 3)];
        }
        ByteVector maskVector = ByteVector.fromArray(BYTE_SPECIES, maskPattern, 0);

        int index = 0;
        int vectorLength = BYTE_SPECIES.length();
        for (; index <= payload.length - vectorLength; index += vectorLength) {
            ByteVector payloadVector = ByteVector.fromArray(BYTE_SPECIES, payload, index);
            payloadVector.lanewise(jdk.incubator.vector.VectorOperators.XOR, maskVector).intoArray(payload, index);
        }
        for (; index < payload.length; index++) {
            payload[index] = (byte) (payload[index] ^ buffer[maskOffset + (index & 3)]);
        }
    }

    public enum ParseStatus {
        COMPLETE,
        INCOMPLETE,
        ERROR
    }

    public record ParseResult(
            ParseStatus status,
            WebSocketFrame frame,
            int bytesConsumed,
            int closeCode,
            String errorMessage) {

        public static ParseResult complete(WebSocketFrame frame, int bytesConsumed) {
            return new ParseResult(ParseStatus.COMPLETE, frame, bytesConsumed, 0, null);
        }

        public static ParseResult incomplete() {
            return new ParseResult(ParseStatus.INCOMPLETE, null, 0, 0, null);
        }

        public static ParseResult error(int closeCode, String errorMessage) {
            return new ParseResult(ParseStatus.ERROR, null, 0, closeCode, errorMessage);
        }
    }
}
