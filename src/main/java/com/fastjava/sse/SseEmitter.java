package com.fastjava.sse;

import java.io.IOException;

/**
 * Streaming emitter for Server-Sent Events.
 */
public interface SseEmitter {

    void send(SseEvent event) throws IOException;

    default void sendData(String data) throws IOException {
        send(SseEvent.data(data));
    }

    default void sendComment(String comment) throws IOException {
        sendRaw((":" + (comment == null ? "" : comment) + "\n\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    void sendRaw(byte[] payload) throws IOException;

    void close() throws IOException;

    boolean isOpen();
}
