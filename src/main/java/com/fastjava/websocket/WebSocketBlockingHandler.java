package com.fastjava.websocket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Handles WebSocket frame I/O for blocking sockets.
 * Adapts the RFC 6455 frame protocol to blocking socket semantics.
 */
public final class WebSocketBlockingHandler {

    private static final Logger logger = LoggerFactory.getLogger(WebSocketBlockingHandler.class);
    private static final int MAX_FRAME_HEADER_SIZE = 10;
    private static final int MAX_PAYLOAD_BYTES = 64 * 1024;
    private static final int MASK_SIZE = 4;

    private final Socket socket;
    private final InputStream input;
    private final OutputStream output;
    private final byte[] headerBuffer;
    private final byte[] framePayloadBuffer;
    private final boolean perMessageDeflateEnabled;
    private int frameTimeoutMillis;

    public WebSocketBlockingHandler(Socket socket, InputStream input, OutputStream output) {
        this(socket, input, output, false);
    }

    public WebSocketBlockingHandler(Socket socket, InputStream input, OutputStream output,
            boolean perMessageDeflateEnabled) {
        this.socket = socket;
        this.input = input;
        this.output = output;
        this.headerBuffer = new byte[MAX_FRAME_HEADER_SIZE];
        this.framePayloadBuffer = new byte[MAX_PAYLOAD_BYTES + MASK_SIZE];
        this.perMessageDeflateEnabled = perMessageDeflateEnabled;
        this.frameTimeoutMillis = 30_000; // 30s default
    }

    /**
     * Set WebSocket frame operation timeout in milliseconds.
     */
    public void setFrameTimeoutMillis(int timeoutMillis) {
        this.frameTimeoutMillis = timeoutMillis;
    }

    /**
     * Read next WebSocket frame from the input stream.
     * 
     * @return Parsed WebSocket frame, or null if connection closed
     * @throws IOException if frame parsing fails or socket error
     */
    public WebSocketFrame readClientFrame() throws IOException {
        int originalTimeout = socket.getSoTimeout();
        try {
            socket.setSoTimeout(frameTimeoutMillis);

            // Read first 2 bytes (opcode, length indicator, mask bit)
            int bytesRead = readExactly(input, headerBuffer, 0, 2);
            if (bytesRead == 0) {
                return null; // Connection closed
            }

            byte b0 = headerBuffer[0];
            byte b1 = headerBuffer[1];

            boolean fin = (b0 & 0x80) != 0;
            boolean rsv1 = (b0 & 0x40) != 0;
            boolean rsv2 = (b0 & 0x20) != 0;
            boolean rsv3 = (b0 & 0x10) != 0;
            int opcode = b0 & 0x0F;
            boolean masked = (b1 & 0x80) != 0;
            int payloadLengthIndicator = b1 & 0x7F;
            boolean controlFrame = (opcode & 0x08) != 0;

            if (perMessageDeflateEnabled) {
                if (rsv2 || rsv3) {
                    throw new IOException("Unsupported RSV bits");
                }
                if (controlFrame && rsv1) {
                    throw new IOException("Control frames must not use RSV1");
                }
                if (opcode == WebSocketFrameCodec.OPCODE_CONTINUATION && rsv1) {
                    throw new IOException("Continuation frame must not use RSV1");
                }
            } else if (rsv1 || rsv2 || rsv3) {
                throw new IOException("RSV bits are not supported");
            }

            // Validate basics
            if (!masked) {
                throw new IOException("Server received unmasked frame (protocol violation)");
            }

            // Read extended payload length if needed
            int headerLength = 2;
            long payloadLength;

            if (payloadLengthIndicator <= 125) {
                payloadLength = payloadLengthIndicator;
            } else if (payloadLengthIndicator == 126) {
                bytesRead = readExactly(input, headerBuffer, 2, 2);
                if (bytesRead < 2) {
                    throw new IOException("Incomplete 16-bit payload length");
                }
                payloadLength = ((headerBuffer[2] & 0xFFL) << 8) | (headerBuffer[3] & 0xFFL);
                headerLength = 4;
            } else { // 127
                bytesRead = readExactly(input, headerBuffer, 2, 8);
                if (bytesRead < 8) {
                    throw new IOException("Incomplete 64-bit payload length");
                }
                payloadLength = readUnsignedLong(headerBuffer, 2);
                headerLength = 10;
            }

            // Validate payload size
            if (payloadLength > MAX_PAYLOAD_BYTES) {
                throw new IOException(
                        "Payload too large: " + payloadLength + " > " + MAX_PAYLOAD_BYTES);
            }

            // Read mask key (4 bytes)
            int maskOffset = headerLength;
            bytesRead = readExactly(input, headerBuffer, maskOffset, MASK_SIZE);
            if (bytesRead < MASK_SIZE) {
                throw new IOException("Incomplete mask key");
            }

            // Read masked payload
            int payloadLengthInt = (int) payloadLength;
            if (payloadLengthInt > 0) {
                bytesRead = readExactly(input, framePayloadBuffer, 0, payloadLengthInt);
                if (bytesRead < payloadLengthInt) {
                    throw new IOException("Incomplete payload: expected " + payloadLengthInt + ", got " + bytesRead);
                }
            }

            // Unmask payload
            byte[] unmaskedPayload = Arrays.copyOfRange(framePayloadBuffer, 0, payloadLengthInt);
            applyMask(unmaskedPayload, headerBuffer, maskOffset);

            if (perMessageDeflateEnabled && rsv1) {
                if (opcode != WebSocketFrameCodec.OPCODE_TEXT && opcode != WebSocketFrameCodec.OPCODE_BINARY) {
                    throw new IOException("RSV1 only valid for data frames");
                }
                if (!fin) {
                    throw new IOException("Compressed fragmented messages are not supported");
                }
                unmaskedPayload = WebSocketPerMessageDeflate.inflateMessage(unmaskedPayload, MAX_PAYLOAD_BYTES);
            }

            return new WebSocketFrame(opcode, fin, unmaskedPayload);

        } catch (SocketTimeoutException timeoutException) {
            throw new IOException("WebSocket frame read timeout", timeoutException);
        } finally {
            // Restore original timeout
            try {
                socket.setSoTimeout(originalTimeout);
            } catch (IOException ignored) {
                // Already erroring out
            }
        }
    }

    /**
     * Send a WebSocket frame to the client (unmasked server frame).
     */
    public void sendServerFrame(int opcode, boolean fin, byte[] payload) throws IOException {
        byte[] frame = WebSocketFrameCodec.encodeServerFrame(opcode, fin, payload, perMessageDeflateEnabled);
        output.write(frame);
        output.flush();
    }

    /**
     * Send a close frame with optional status code.
     */
    public void sendCloseFrame(int closeCode) throws IOException {
        byte[] closePayload = WebSocketFrameCodec.closePayload(closeCode);
        sendServerFrame(WebSocketFrameCodec.OPCODE_CLOSE, true, closePayload);
    }

    /**
     * Send a ping frame (server-initiated keep-alive).
     */
    public void sendPingFrame(byte[] payload) throws IOException {
        sendServerFrame(WebSocketFrameCodec.OPCODE_PING, true, payload);
    }

    /**
     * Send a pong frame (response to client ping).
     */
    public void sendPongFrame(byte[] payload) throws IOException {
        sendServerFrame(WebSocketFrameCodec.OPCODE_PONG, true, payload);
    }

    /**
     * Read exactly n bytes from the input stream, handling partial reads.
     * Returns actual bytes read (0 if EOF on first attempt, < n if EOF mid-read, n
     * if successful).
     */
    private int readExactly(InputStream in, byte[] buffer, int offset, int length) throws IOException {
        int totalRead = 0;
        while (totalRead < length) {
            int read = in.read(buffer, offset + totalRead, length - totalRead);
            if (read == -1) {
                if (totalRead == 0) {
                    return 0; // EOF on first read
                }
                throw new IOException("Unexpected EOF: needed " + (length - totalRead) + " more bytes");
            }
            totalRead += read;
        }
        return totalRead;
    }

    /**
     * Read unsigned 64-bit big-endian long from buffer at offset.
     */
    private long readUnsignedLong(byte[] buffer, int offset) {
        long value = 0;
        for (int i = 0; i < 8; i++) {
            value = (value << 8) | (buffer[offset + i] & 0xFFL);
        }
        return value;
    }

    /**
     * Apply XOR mask to payload bytes (in-place unmasking).
     */
    private void applyMask(byte[] payload, byte[] buffer, int maskOffset) {
        for (int index = 0; index < payload.length; index++) {
            payload[index] = (byte) (payload[index] ^ buffer[maskOffset + (index & 3)]);
        }
    }
}
