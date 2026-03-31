package com.fastjava.server;

import com.fastjava.examples.HelloWorldServlet;
import com.fastjava.http.h2.HpackCodec;
import com.fastjava.http.h2.Http2FrameCodec;
import com.fastjava.server.config.Http2Config;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class FastJavaHttp2IntegrationTest {

    private static final byte[] H2C_PREFACE = new byte[] {
            'P', 'R', 'I', ' ', '*', ' ', 'H', 'T', 'T', 'P', '/', '2', '.', '0', '\r', '\n',
            '\r', '\n', 'S', 'M', '\r', '\n', '\r', '\n'
    };

    private FastJavaNioServer server;
    private int port;

    @Before
    public void setUp() throws Exception {
        Http2Config http2Config = new Http2Config(true, true, 100, 65_535, 16_384, 4096, 32_768, false);
        server = new FastJavaNioServer(0, new RequestLimits(16_384, 2000, 2000, 4096, 8192, 65_536), 4, 4096,
                null, http2Config);
        server.addServlet("/", new HelloWorldServlet());
        server.start();
        port = server.getBoundPort();
    }

    @After
    public void tearDown() {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    public void servesRequestOverH2cPriorKnowledge() throws Exception {
        try (Socket socket = connect()) {
            OutputStream output = socket.getOutputStream();
            output.write(H2C_PREFACE);
            output.write(Http2FrameCodec.encodeFrame(Http2FrameCodec.TYPE_SETTINGS, 0, 0, new byte[0]));
            output.write(buildHeadersFrame(1, true));
            output.flush();

            String body = readResponseBody(socket.getInputStream(), 1);
            assertTrue(body.contains("Hello from FastJava SIMD Server!"));
        }
    }

    @Test
    public void supportsHeaderContinuationFrames() throws Exception {
        try (Socket socket = connect()) {
            OutputStream output = socket.getOutputStream();
            output.write(H2C_PREFACE);
            output.write(Http2FrameCodec.encodeFrame(Http2FrameCodec.TYPE_SETTINGS, 0, 0, new byte[0]));

            byte[] headerBlock = buildRequestHeaderBlock("/");
            int split = Math.max(1, headerBlock.length / 2);
            byte[] first = Arrays.copyOfRange(headerBlock, 0, split);
            byte[] second = Arrays.copyOfRange(headerBlock, split, headerBlock.length);

            output.write(Http2FrameCodec.encodeFrame(
                    Http2FrameCodec.TYPE_HEADERS,
                    Http2FrameCodec.FLAG_END_STREAM,
                    1,
                    first));
            output.write(Http2FrameCodec.encodeFrame(
                    Http2FrameCodec.TYPE_CONTINUATION,
                    Http2FrameCodec.FLAG_END_HEADERS,
                    1,
                    second));
            output.flush();

            String body = readResponseBody(socket.getInputStream(), 1);
            assertTrue(body.contains("Hello from FastJava SIMD Server!"));
        }
    }

    @Test
    public void upgradesHttp11ConnectionToH2c() throws Exception {
        try (Socket socket = connect()) {
            OutputStream output = socket.getOutputStream();
            InputStream input = socket.getInputStream();

            byte[] settingsPayload = new byte[] { 0x00, 0x03, 0x00, 0x00, 0x00, 0x64 };
            String http2Settings = Base64.getUrlEncoder().withoutPadding().encodeToString(settingsPayload);

            String upgradeRequest = "GET / HTTP/1.1\r\n"
                    + "Host: localhost\r\n"
                    + "Connection: Upgrade, HTTP2-Settings\r\n"
                    + "Upgrade: h2c\r\n"
                    + "HTTP2-Settings: " + http2Settings + "\r\n"
                    + "\r\n";
            output.write(upgradeRequest.getBytes(StandardCharsets.US_ASCII));
            output.flush();

            String statusLine = readHttp1StatusLine(input);
            assertEquals("HTTP/1.1 101 Switching Protocols", statusLine);
            consumeHttp1Headers(input);

            output.write(Http2FrameCodec.encodeFrame(Http2FrameCodec.TYPE_SETTINGS, 0, 0, new byte[0]));
            output.write(buildHeadersFrame(3, true));
            output.flush();

            String body = readResponseBody(input, 3);
            assertTrue(body.contains("Hello from FastJava SIMD Server!"));
        }
    }

    private Socket connect() throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress("127.0.0.1", port), 2000);
        socket.setSoTimeout(4000);
        return socket;
    }

    private byte[] buildHeadersFrame(int streamId, boolean endStream) throws IOException {
        byte[] headerBlock = buildRequestHeaderBlock("/");
        int flags = Http2FrameCodec.FLAG_END_HEADERS | (endStream ? Http2FrameCodec.FLAG_END_STREAM : 0);
        return Http2FrameCodec.encodeFrame(Http2FrameCodec.TYPE_HEADERS, flags, streamId, headerBlock);
    }

    private byte[] buildRequestHeaderBlock(String path) throws IOException {
        HpackCodec codec = new HpackCodec(32_768, 4096);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(":method", "GET");
        headers.put(":scheme", "http");
        headers.put(":path", path);
        headers.put(":authority", "localhost");
        return codec.encode(headers);
    }

    private String readResponseBody(InputStream input, int streamId) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        long deadline = System.currentTimeMillis() + 4000;
        while (System.currentTimeMillis() < deadline) {
            Frame frame = readFrame(input);
            if (frame.type == Http2FrameCodec.TYPE_DATA && frame.streamId == streamId) {
                body.write(frame.payload);
                if ((frame.flags & Http2FrameCodec.FLAG_END_STREAM) != 0) {
                    break;
                }
            }
        }
        return body.toString(StandardCharsets.UTF_8);
    }

    private Frame readFrame(InputStream input) throws IOException {
        byte[] header = readExact(input, Http2FrameCodec.FRAME_HEADER_LENGTH);
        int length = ((header[0] & 0xFF) << 16)
                | ((header[1] & 0xFF) << 8)
                | (header[2] & 0xFF);
        int type = header[3] & 0xFF;
        int flags = header[4] & 0xFF;
        int streamId = ((header[5] & 0x7F) << 24)
                | ((header[6] & 0xFF) << 16)
                | ((header[7] & 0xFF) << 8)
                | (header[8] & 0xFF);
        byte[] payload = readExact(input, length);
        return new Frame(type, flags, streamId, payload);
    }

    private String readHttp1StatusLine(InputStream input) throws IOException {
        return readAsciiLine(input);
    }

    private void consumeHttp1Headers(InputStream input) throws IOException {
        while (true) {
            String line = readAsciiLine(input);
            if (line.isEmpty()) {
                return;
            }
        }
    }

    private String readAsciiLine(InputStream input) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int previous = -1;
        while (true) {
            int current = input.read();
            if (current == -1) {
                throw new IOException("Unexpected EOF while reading line");
            }
            if (previous == '\r' && current == '\n') {
                byte[] bytes = line.toByteArray();
                return new String(bytes, 0, Math.max(0, bytes.length - 1), StandardCharsets.US_ASCII);
            }
            line.write(current);
            previous = current;
        }
    }

    private byte[] readExact(InputStream input, int length) throws IOException {
        byte[] bytes = new byte[length];
        int offset = 0;
        while (offset < length) {
            int read = input.read(bytes, offset, length - offset);
            if (read == -1) {
                throw new IOException("Unexpected EOF while reading " + length + " bytes");
            }
            offset += read;
        }
        return bytes;
    }

    private record Frame(int type, int flags, int streamId, byte[] payload) {
    }
}
