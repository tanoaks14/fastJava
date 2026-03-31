package com.fastjava.sse;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertTrue;

public class SseEventTest {

    @Test
    public void serializesMultilineEventPayload() {
        SseEvent event = SseEvent.builder()
                .id("42")
                .event("update")
                .retryMillis(1500)
                .data("line-1\nline-2")
                .build();

        String payload = new String(event.serialize(), StandardCharsets.UTF_8);
        assertTrue(payload.contains("id: 42\n"));
        assertTrue(payload.contains("event: update\n"));
        assertTrue(payload.contains("retry: 1500\n"));
        assertTrue(payload.contains("data: line-1\n"));
        assertTrue(payload.contains("data: line-2\n"));
        assertTrue(payload.endsWith("\n\n"));
    }
}
