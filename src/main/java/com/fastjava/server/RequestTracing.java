package com.fastjava.server;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;

final class RequestTracing {

    private static final boolean ENABLED = Boolean.getBoolean("fastjava.tracing.enabled");
    private static final Tracer tracer = GlobalOpenTelemetry.getTracer("com.fastjava.server", "0.1.0");
    private static final SpanScope NOOP_SCOPE = new SpanScope(Span.getInvalid(), () -> {
    });

    private RequestTracing() {
    }

    static boolean isEnabled() {
        return ENABLED;
    }

    static SpanScope startServerSpan(String spanName) {
        if (!ENABLED) {
            return NOOP_SCOPE;
        }
        Span span = tracer.spanBuilder(spanName).setSpanKind(SpanKind.SERVER).startSpan();
        return new SpanScope(span, span.makeCurrent());
    }

    static SpanScope startChildSpan(String spanName) {
        if (!ENABLED) {
            return NOOP_SCOPE;
        }
        Span span = tracer.spanBuilder(spanName).startSpan();
        return new SpanScope(span, span.makeCurrent());
    }

    static void recordError(Span span, Throwable throwable) {
        if (!ENABLED || span == null || throwable == null) {
            return;
        }
        span.recordException(throwable);
        span.setStatus(StatusCode.ERROR, throwable.getMessage() == null ? "error" : throwable.getMessage());
    }

    static final class SpanScope implements AutoCloseable {

        private final Span span;
        private final Scope scope;

        private SpanScope(Span span, Scope scope) {
            this.span = span;
            this.scope = scope;
        }

        Span span() {
            return span;
        }

        void setAttribute(String key, String value) {
            if (ENABLED && value != null) {
                span.setAttribute(key, value);
            }
        }

        void setAttribute(String key, long value) {
            if (ENABLED) {
                span.setAttribute(key, value);
            }
        }

        @Override
        public void close() {
            if (!ENABLED) {
                return;
            }
            try {
                scope.close();
            } finally {
                span.end();
            }
        }
    }
}
