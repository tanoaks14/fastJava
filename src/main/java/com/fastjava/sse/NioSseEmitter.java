package com.fastjava.sse;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

public final class NioSseEmitter implements SseEmitter {

    @FunctionalInterface
    public interface ChunkWriter {
        void writeChunk(byte[] payload, boolean closeAfterWrite) throws IOException;
    }

    private final ChunkWriter chunkWriter;
    private final Object lock;
    private final Deque<byte[]> pendingChunks;
    private volatile boolean ready;
    private final AtomicBoolean open;

    public NioSseEmitter(ChunkWriter chunkWriter) {
        this.chunkWriter = Objects.requireNonNull(chunkWriter, "SSE chunk writer cannot be null");
        this.lock = new Object();
        this.pendingChunks = new ArrayDeque<>();
        this.ready = false;
        this.open = new AtomicBoolean(true);
    }

    @Override
    public void send(SseEvent event) throws IOException {
        Objects.requireNonNull(event, "SSE event cannot be null");
        sendRaw(event.serialize());
    }

    @Override
    public void sendRaw(byte[] payload) throws IOException {
        if (!open.get()) {
            throw new IOException("SSE stream already closed");
        }
        byte[] chunk = SseChunkEncoder.chunk(payload);
        synchronized (lock) {
            if (!ready) {
                pendingChunks.addLast(chunk);
                return;
            }
        }
        chunkWriter.writeChunk(chunk, false);
    }

    @Override
    public void close() throws IOException {
        if (!open.compareAndSet(true, false)) {
            return;
        }
        synchronized (lock) {
            if (!ready) {
                pendingChunks.addLast(SseChunkEncoder.END_CHUNK);
                return;
            }
        }
        chunkWriter.writeChunk(SseChunkEncoder.END_CHUNK, true);
    }

    public void markReady() throws IOException {
        Deque<byte[]> toFlush = new ArrayDeque<>();
        synchronized (lock) {
            if (ready) {
                return;
            }
            ready = true;
            while (!pendingChunks.isEmpty()) {
                toFlush.addLast(pendingChunks.removeFirst());
            }
        }

        while (!toFlush.isEmpty()) {
            byte[] chunk = toFlush.removeFirst();
            boolean close = chunk == SseChunkEncoder.END_CHUNK;
            chunkWriter.writeChunk(chunk, close);
        }
    }

    @Override
    public boolean isOpen() {
        return open.get();
    }
}
