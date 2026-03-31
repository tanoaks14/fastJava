package com.fastjava.websocket;

import java.util.Map;

public record WebSocketEndpointMatch(WebSocketEndpointMetadata metadata, Map<String, String> pathParams) {
}
