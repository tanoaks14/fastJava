package com.fastjava.sse;

import com.fastjava.servlet.HttpServletRequest;
import com.fastjava.servlet.HttpServletResponse;

/**
 * Helper utilities for SSE request handlers.
 */
public final class SseSupport {

    public static final String REQUEST_ATTRIBUTE = "fastjava.sse.emitter";

    private SseSupport() {
    }

    public static SseEmitter from(HttpServletRequest request) {
        Object candidate = request.getAttribute(REQUEST_ATTRIBUTE);
        return candidate instanceof SseEmitter emitter ? emitter : null;
    }

    public static void prepareResponse(HttpServletResponse response) {
        response.setContentType("text/event-stream; charset=utf-8");
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("Connection", "keep-alive");
        response.setHeader("X-Accel-Buffering", "no");
        response.setChunkedResponseEnabled(true);
    }
}
