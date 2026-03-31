package com.fastjava.server;

import com.fastjava.sse.SseEmitter;

import java.util.function.Supplier;

/**
 * Holds the SSE emitter for the current request scope.
 * Uses ScopedValue (Java 25, JEP 506) for safe, inherited, zero-leak
 * propagation
 * across virtual threads — more efficient than ThreadLocal for this pattern.
 */
final class SseRuntime {

    static final ScopedValue<SseEmitter> CURRENT = ScopedValue.newInstance();

    private SseRuntime() {
    }

    /**
     * Runs {@code action} within the emitter's binding scope and returns its
     * result.
     * The ScopedValue binding is automatically released when the call returns.
     */
    static <T> T callWithEmitter(SseEmitter emitter, Supplier<T> action) {
        try {
            return ScopedValue.where(CURRENT, emitter).call(action::get);
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Exception e) {
            // Supplier.get() cannot throw checked exceptions — unreachable
            throw new AssertionError("Unreachable", e);
        }
    }

    static SseEmitter currentEmitter() {
        return CURRENT.isBound() ? CURRENT.get() : null;
    }
}
