package com.fastjava.sse;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;

public final class BlockingSseEmitter implements SseEmitter {

    private final OutputStream output;
    private final Object writeLock;
    private final Object closeLock;
    private final Deque<byte[]> pendingChunks;
    private volatile boolean ready;
    private volatile boolean open;

    public BlockingSseEmitter(OutputStream output) {
        this.output = Objects.requireNonNull(output, "SSE output stream cannot be null");
        this.writeLock = new Object();
        this.closeLock = new Object();
        this.pendingChunks = new ArrayDeque<>();
        this.ready = false;
        this.open = true;
    }

    @Override
    public void send(SseEvent event) throws IOException {
        Objects.requireNonNull(event, "SSE event cannot be null");
        sendRaw(event.serialize());
    }

    @Override
    public void sendRaw(byte[] payload) throws IOException {
        if (!open) {
            throw new IOException("SSE stream already closed");
        }
        byte[] chunk = SseChunkEncoder.chunk(payload);
        synchronized (writeLock) {
            if (!ready) {
                pendingChunks.addLast(chunk);
                return;
            }
            output.write(chunk);
            output.flush();
        }
    }

    @Override
    public void close() throws IOException {
        if (!open) {
            return;
        }
        synchronized (closeLock) {
            if (!open) {
                return;
            }
            synchronized (writeLock) {
                if (!ready) {
                    pendingChunks.addLast(SseChunkEncoder.END_CHUNK);
                } else {
                    output.write(SseChunkEncoder.END_CHUNK);
                    output.flush();
                }
            }
            open = false;
            closeLock.notifyAll();
        }
    }

    public void markReady() throws IOException {
        synchronized (writeLock) {
            if (ready) {
                return;
            }
            ready = true;
            while (!pendingChunks.isEmpty()) {
                output.write(pendingChunks.removeFirst());
            }
            output.flush();
        }
    }

    public void awaitClosed() throws InterruptedException {
        if (!open) {
            return;
        }
        synchronized (closeLock) {
            while (open) {
                closeLock.wait(1000L);
            }
        }
    }

    @Override
    public boolean isOpen() {
        return open;
    }
}
