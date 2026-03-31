package com.fastjava.http.response;

/**
 * Thread-local pool for response buffers to reduce allocation overhead without
 * cross-thread synchronization.
 */
public final class ResponseBufferPool {

    private static final int DEFAULT_BUFFER_SIZE = 8_192;
    // Enabled by default; disable with -Dfastjava.buffer.pool.enabled=false
    private static final boolean POOL_ENABLED = Boolean.parseBoolean(
            System.getProperty("fastjava.buffer.pool.enabled", "true"));

    private static final class PooledBuffer {
        final byte[] data;
        boolean inUse;

        PooledBuffer(int size) {
            this.data = new byte[size];
            this.inUse = false;
        }
    }

    private static final ThreadLocal<PooledBuffer> THREAD_LOCAL_POOL = POOL_ENABLED
            ? ThreadLocal.withInitial(() -> new PooledBuffer(DEFAULT_BUFFER_SIZE))
            : null;

    private ResponseBufferPool() {
    }

    /**
     * Acquire a reusable thread-local buffer. If this thread already has one in
     * use (re-entrant path), allocate a fresh buffer to avoid accidental sharing.
     */
    public static byte[] acquire() {
        if (!POOL_ENABLED || THREAD_LOCAL_POOL == null) {
            return new byte[DEFAULT_BUFFER_SIZE];
        }
        PooledBuffer pooled = THREAD_LOCAL_POOL.get();
        if (pooled.inUse) {
            return new byte[DEFAULT_BUFFER_SIZE];
        }
        pooled.inUse = true;
        return pooled.data;
    }

    /**
     * Return a buffer to this thread's pool slot. Only the canonical 8K
     * thread-local
     * buffer is retained.
     */
    public static void release(byte[] buffer) {
        if (!POOL_ENABLED || THREAD_LOCAL_POOL == null || buffer == null || buffer.length != DEFAULT_BUFFER_SIZE) {
            return;
        }
        PooledBuffer pooled = THREAD_LOCAL_POOL.get();
        if (pooled.data == buffer) {
            pooled.inUse = false;
        }
    }

    /**
     * Get thread-local slot utilization: 1.0 if the current thread has its pooled
     * buffer checked out, else 0.0. Returns 0.0 when pooling is disabled.
     */
    public static double getPoolUtilization() {
        if (!POOL_ENABLED || THREAD_LOCAL_POOL == null) {
            return 0.0;
        }
        return THREAD_LOCAL_POOL.get().inUse ? 1.0 : 0.0;
    }
}
