package com.fastjava.websocket;

public record WebSocketFrame(int opcode, boolean fin, byte[] payload) {
}
