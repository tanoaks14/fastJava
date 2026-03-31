package com.fastjava.http.h2;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class Http2FrameCodecTest {

    @Test
    public void parsesCompleteFrame() {
        byte[] frameBytes = new byte[] {
                0x00, 0x00, 0x05, // length=5
                0x01, // type=HEADERS
                0x04, // flags=END_HEADERS
                0x00, 0x00, 0x00, 0x03, // stream id=3
                'h', 'e', 'l', 'l', 'o'
        };

        Http2FrameCodec.ParseResult result = Http2FrameCodec.parseFrame(frameBytes, 0, frameBytes.length, 16_384);

        assertFalse(result.isIncomplete());
        assertFalse(result.isError());
        assertEquals(frameBytes.length, result.bytesConsumed());
        assertEquals(5, result.frame().length());
        assertEquals(1, result.frame().type());
        assertEquals(4, result.frame().flags());
        assertEquals(3, result.frame().streamId());
        assertArrayEquals(new byte[] { 'h', 'e', 'l', 'l', 'o' }, result.frame().payload());
    }

    @Test
    public void returnsIncompleteForPartialHeader() {
        byte[] partial = new byte[] { 0x00, 0x00, 0x01, 0x00, 0x00 };

        Http2FrameCodec.ParseResult result = Http2FrameCodec.parseFrame(partial, 0, partial.length, 16_384);

        assertTrue(result.isIncomplete());
        assertFalse(result.isError());
    }

    @Test
    public void returnsIncompleteForPartialPayload() {
        byte[] partialPayload = new byte[] {
                0x00, 0x00, 0x03,
                0x00,
                0x00,
                0x00, 0x00, 0x00, 0x01,
                0x11
        };

        Http2FrameCodec.ParseResult result = Http2FrameCodec.parseFrame(
                partialPayload,
                0,
                partialPayload.length,
                16_384);

        assertTrue(result.isIncomplete());
        assertFalse(result.isError());
    }

    @Test
    public void rejectsFrameLargerThanConfiguredMax() {
        byte[] frame = new byte[] {
                0x00, 0x40, 0x00, // length=16384
                0x00,
                0x00,
                0x00, 0x00, 0x00, 0x00
        };

        Http2FrameCodec.ParseResult result = Http2FrameCodec.parseFrame(frame, 0, frame.length, 1024);

        assertFalse(result.isIncomplete());
        assertTrue(result.isError());
        assertTrue(result.error().contains("maxFrameSize"));
    }
}