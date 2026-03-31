package com.fastjava.websocket;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class WebSocketSession {

    private final String id;
    private final String negotiatedSubprotocol;
    private final Transport transport;
    private final Map<String, Object> attributes;

    public WebSocketSession(String id, Transport transport) {
        this(id, null, transport);
    }

    public WebSocketSession(String id, String negotiatedSubprotocol, Transport transport) {
        this.id = id;
        this.negotiatedSubprotocol = negotiatedSubprotocol;
        this.transport = transport;
        this.attributes = new ConcurrentHashMap<>();
    }

    public String getId() {
        return id;
    }

    public String getNegotiatedSubprotocol() {
        return negotiatedSubprotocol;
    }

    public void sendText(String message) throws IOException {
        transport.sendText(message);
    }

    public void sendBinary(byte[] payload) throws IOException {
        transport.sendBinary(payload);
    }

    public void close() throws IOException {
        close(WebSocketFrameCodec.CLOSE_NORMAL);
    }

    public void close(int code) throws IOException {
        transport.close(code);
    }

    public Object getAttribute(String key) {
        return attributes.get(key);
    }

    public void setAttribute(String key, Object value) {
        attributes.put(key, value);
    }

    public Object removeAttribute(String key) {
        return attributes.remove(key);
    }

    @FunctionalInterface
    public interface Transport {
        void sendText(String message) throws IOException;

        default void sendBinary(byte[] payload) throws IOException {
            throw new IOException("Binary frames are not supported by this transport");
        }

        default void close(int code) throws IOException {
            // Default no-op close for transports that do not explicitly handle it.
        }
    }
}
