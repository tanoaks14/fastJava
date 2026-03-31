package com.fastjava.websocket;

import com.fastjava.websocket.annotation.OnMessage;
import com.fastjava.websocket.annotation.WebSocketEndpoint;
import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class WebSocketSubprotocolTest {

    @Test
    public void extractsSubprotocolsFromEndpointMetadataInDeclaredOrder() {
        WebSocketEndpointMetadata metadata = WebSocketEndpointMetadata.fromClass(SubprotocolEndpoint.class);

        assertArrayEquals(new String[] { "chat.v1", "chat.v2" }, metadata.subprotocols());
    }

    @Test
    public void sessionExposesNegotiatedSubprotocol() {
        WebSocketSession session = new WebSocketSession("session-1", "chat.v1", message -> {
            // no-op for test
        });

        assertEquals("chat.v1", session.getNegotiatedSubprotocol());
    }

    @WebSocketEndpoint(path = "/ws/subprotocol", subprotocols = { "chat.v1", "chat.v2" })
    public static final class SubprotocolEndpoint {

        @OnMessage
        public void onMessage(WebSocketSession session, String message) throws IOException {
            session.sendText(message);
        }
    }
}
