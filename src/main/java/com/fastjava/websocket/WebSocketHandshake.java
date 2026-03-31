package com.fastjava.websocket;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

public final class WebSocketHandshake {

    private static final String MAGIC_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private WebSocketHandshake() {
    }

    public static byte[] buildSwitchingProtocolsResponse(String secWebSocketKey) {
        return buildSwitchingProtocolsResponse(secWebSocketKey, null, null);
    }

    public static byte[] buildSwitchingProtocolsResponse(String secWebSocketKey, String negotiatedSubprotocol) {
        return buildSwitchingProtocolsResponse(secWebSocketKey, negotiatedSubprotocol, null);
    }

    public static byte[] buildSwitchingProtocolsResponse(
            String secWebSocketKey,
            String negotiatedSubprotocol,
            String negotiatedExtensions) {
        String accept = createAcceptKey(secWebSocketKey);
        StringBuilder responseBuilder = new StringBuilder("HTTP/1.1 101 Switching Protocols\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + accept + "\r\n");
        if (negotiatedSubprotocol != null && !negotiatedSubprotocol.isBlank()) {
            responseBuilder.append("Sec-WebSocket-Protocol: ")
                    .append(negotiatedSubprotocol)
                    .append("\r\n");
        }
        if (negotiatedExtensions != null && !negotiatedExtensions.isBlank()) {
            responseBuilder.append("Sec-WebSocket-Extensions: ")
                    .append(negotiatedExtensions)
                    .append("\r\n");
        }
        responseBuilder.append("\r\n");
        return responseBuilder.toString().getBytes(StandardCharsets.US_ASCII);
    }

    public static String createAcceptKey(String secWebSocketKey) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] digest = sha1.digest((secWebSocketKey + MAGIC_GUID).getBytes(StandardCharsets.US_ASCII));
            return Base64.getEncoder().encodeToString(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-1 digest unavailable", exception);
        }
    }
}
