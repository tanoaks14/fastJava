package com.fastjava.websocket;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class WebSocketHandshakeTest {

    @Test
    public void createsExpectedAcceptKey() {
        String accept = WebSocketHandshake.createAcceptKey("dGhlIHNhbXBsZSBub25jZQ==");
        assertEquals("s3pPLMBiTxaQ9kYGzzhZRbK+xOo=", accept);
    }

    @Test
    public void buildsSwitchingProtocolsResponse() {
        byte[] response = WebSocketHandshake.buildSwitchingProtocolsResponse("dGhlIHNhbXBsZSBub25jZQ==");
        String asText = new String(response, StandardCharsets.US_ASCII);

        assertTrue(asText.startsWith("HTTP/1.1 101 Switching Protocols\r\n"));
        assertTrue(asText.contains("Upgrade: websocket\r\n"));
        assertTrue(asText.contains("Connection: Upgrade\r\n"));
        assertTrue(asText.contains("Sec-WebSocket-Accept: s3pPLMBiTxaQ9kYGzzhZRbK+xOo=\r\n"));
        assertTrue(asText.endsWith("\r\n\r\n"));
    }

    @Test
    public void buildsSwitchingProtocolsResponseWithNegotiatedSubprotocol() {
        byte[] response = WebSocketHandshake.buildSwitchingProtocolsResponse(
                "dGhlIHNhbXBsZSBub25jZQ==",
                "chat.v1");
        String asText = new String(response, StandardCharsets.US_ASCII);

        assertTrue(asText.contains("Sec-WebSocket-Protocol: chat.v1\r\n"));
    }

    @Test
    public void omitsSubprotocolHeaderWhenSubprotocolIsBlank() {
        byte[] response = WebSocketHandshake.buildSwitchingProtocolsResponse(
                "dGhlIHNhbXBsZSBub25jZQ==",
                "   ");
        String asText = new String(response, StandardCharsets.US_ASCII);

        assertFalse(asText.contains("Sec-WebSocket-Protocol:"));
    }

    @Test
    public void buildsSwitchingProtocolsResponseWithNegotiatedExtension() {
        byte[] response = WebSocketHandshake.buildSwitchingProtocolsResponse(
                "dGhlIHNhbXBsZSBub25jZQ==",
                "chat.v1",
                "permessage-deflate; server_no_context_takeover; client_no_context_takeover");
        String asText = new String(response, StandardCharsets.US_ASCII);

        assertTrue(asText.contains("Sec-WebSocket-Protocol: chat.v1\r\n"));
        assertTrue(asText.contains(
                "Sec-WebSocket-Extensions: permessage-deflate; server_no_context_takeover; client_no_context_takeover\r\n"));
    }
}
