package com.fastjava.websocket;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class WebSocketFrameCodecTest {

    @Test
    public void parsesMaskedClientTextFrame() {
        byte[] payload = "hello".getBytes(StandardCharsets.UTF_8);
        byte[] frame = maskedFrame(WebSocketFrameCodec.OPCODE_TEXT, true, payload, new byte[] { 1, 2, 3, 4 });

        WebSocketFrameCodec.ParseResult result = WebSocketFrameCodec.parseClientFrame(frame, 0, frame.length,
                64 * 1024);

        assertEquals(WebSocketFrameCodec.ParseStatus.COMPLETE, result.status());
        assertEquals(WebSocketFrameCodec.OPCODE_TEXT, result.frame().opcode());
        assertEquals(true, result.frame().fin());
        assertArrayEquals(payload, result.frame().payload());
    }

    @Test
    public void rejectsUnmaskedClientFrame() {
        byte[] frame = new byte[] {
                (byte) 0x81,
                (byte) 0x05,
                'h', 'e', 'l', 'l', 'o'
        };

        WebSocketFrameCodec.ParseResult result = WebSocketFrameCodec.parseClientFrame(frame, 0, frame.length,
                64 * 1024);

        assertEquals(WebSocketFrameCodec.ParseStatus.ERROR, result.status());
        assertEquals(WebSocketFrameCodec.CLOSE_PROTOCOL_ERROR, result.closeCode());
    }

    @Test
    public void rejectsOversizedPayload() {
        byte[] payload = new byte[70];
        Arrays.fill(payload, (byte) 'a');
        byte[] frame = maskedFrame(WebSocketFrameCodec.OPCODE_TEXT, true, payload, new byte[] { 9, 8, 7, 6 });

        WebSocketFrameCodec.ParseResult result = WebSocketFrameCodec.parseClientFrame(frame, 0, frame.length, 64);

        assertEquals(WebSocketFrameCodec.ParseStatus.ERROR, result.status());
        assertEquals(WebSocketFrameCodec.CLOSE_TOO_LARGE, result.closeCode());
    }

    @Test
    public void encodesServerTextFrame() {
        byte[] payload = "ok".getBytes(StandardCharsets.UTF_8);

        byte[] encoded = WebSocketFrameCodec.encodeServerFrame(WebSocketFrameCodec.OPCODE_TEXT, true, payload);

        assertEquals((byte) 0x81, encoded[0]);
        assertEquals((byte) 0x02, encoded[1]);
        assertArrayEquals(payload, Arrays.copyOfRange(encoded, 2, encoded.length));
    }

    @Test
    public void parsesCompressedClientTextFrameWhenPerMessageDeflateEnabled() throws Exception {
        byte[] payload = "compressed-message".getBytes(StandardCharsets.UTF_8);
        byte[] compressed = WebSocketPerMessageDeflate.deflateMessage(payload);
        byte[] frame = maskedFrame(
                WebSocketFrameCodec.OPCODE_TEXT,
                true,
                true,
                compressed,
                new byte[] { 1, 4, 2, 8 });

        WebSocketFrameCodec.ParseResult result = WebSocketFrameCodec.parseClientFrame(
                frame,
                0,
                frame.length,
                64 * 1024,
                true);

        assertEquals(WebSocketFrameCodec.ParseStatus.COMPLETE, result.status());
        assertEquals(WebSocketFrameCodec.OPCODE_TEXT, result.frame().opcode());
        assertArrayEquals(payload, result.frame().payload());
    }

    @Test
    public void rejectsCompressedFrameWhenPerMessageDeflateIsDisabled() {
        byte[] payload = "compressed-message".getBytes(StandardCharsets.UTF_8);
        byte[] frame = maskedFrame(
                WebSocketFrameCodec.OPCODE_TEXT,
                true,
                true,
                payload,
                new byte[] { 5, 6, 7, 8 });

        WebSocketFrameCodec.ParseResult result = WebSocketFrameCodec.parseClientFrame(frame, 0, frame.length,
                64 * 1024);

        assertEquals(WebSocketFrameCodec.ParseStatus.ERROR, result.status());
        assertEquals(WebSocketFrameCodec.CLOSE_PROTOCOL_ERROR, result.closeCode());
    }

    @Test
    public void encodesCompressedServerTextFrameWhenPerMessageDeflateEnabled() throws Exception {
        byte[] payload = "server-compressed".getBytes(StandardCharsets.UTF_8);

        byte[] encoded = WebSocketFrameCodec.encodeServerFrame(
                WebSocketFrameCodec.OPCODE_TEXT,
                true,
                payload,
                true);

        assertEquals((byte) 0xC1, encoded[0]);
        byte[] compressedPayload = Arrays.copyOfRange(encoded, 2, encoded.length);
        byte[] inflated = WebSocketPerMessageDeflate.inflateMessage(compressedPayload, 64 * 1024);
        assertArrayEquals(payload, inflated);
    }

    private static byte[] maskedFrame(int opcode, boolean fin, byte[] payload, byte[] mask) {
        return maskedFrame(opcode, fin, false, payload, mask);
    }

    private static byte[] maskedFrame(int opcode, boolean fin, boolean compressed, byte[] payload, byte[] mask) {
        byte[] frame = new byte[2 + 4 + payload.length];
        int firstByte = (fin ? 0x80 : 0) | (opcode & 0x0F);
        if (compressed) {
            firstByte |= 0x40;
        }
        frame[0] = (byte) firstByte;
        frame[1] = (byte) (0x80 | payload.length);
        System.arraycopy(mask, 0, frame, 2, 4);
        for (int index = 0; index < payload.length; index++) {
            frame[6 + index] = (byte) (payload[index] ^ mask[index & 3]);
        }
        return frame;
    }
}
