package com.fastjava.sse;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Immutable Server-Sent Event payload model.
 */
public final class SseEvent {

    private final String id;
    private final String event;
    private final String data;
    private final Long retryMillis;

    private SseEvent(String id, String event, String data, Long retryMillis) {
        this.id = id;
        this.event = event;
        this.data = data;
        this.retryMillis = retryMillis;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static SseEvent data(String data) {
        return builder().data(data).build();
    }

    public byte[] serialize() {
        StringBuilder out = new StringBuilder(128);
        if (id != null && !id.isBlank()) {
            out.append("id: ").append(id).append("\n");
        }
        if (event != null && !event.isBlank()) {
            out.append("event: ").append(event).append("\n");
        }
        if (retryMillis != null && retryMillis >= 0) {
            out.append("retry: ").append(retryMillis).append("\n");
        }
        if (data != null) {
            String normalized = data.replace("\r\n", "\n").replace('\r', '\n');
            int length = normalized.length();
            int lineStart = 0;
            while (lineStart <= length) {
                int lineEnd = normalized.indexOf('\n', lineStart);
                if (lineEnd < 0) {
                    lineEnd = length;
                }
                String line = normalized.substring(lineStart, lineEnd);
                out.append("data: ").append(line).append("\n");
                if (lineEnd == length) {
                    break;
                }
                lineStart = lineEnd + 1;
            }
        }
        out.append("\n");
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    public static final class Builder {
        private String id;
        private String event;
        private String data;
        private Long retryMillis;

        private Builder() {
        }

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder event(String event) {
            this.event = event;
            return this;
        }

        public Builder data(String data) {
            this.data = Objects.requireNonNull(data, "SSE event data cannot be null");
            return this;
        }

        public Builder retryMillis(long retryMillis) {
            this.retryMillis = retryMillis;
            return this;
        }

        public SseEvent build() {
            return new SseEvent(id, event, data, retryMillis);
        }
    }
}
