package com.fastjava.server;

import java.io.IOException;
import java.nio.channels.ClosedSelectorException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages parallel selector threads.
 * Selector 0 owns OP_ACCEPT and accepts new channels.
 */
final class SelectorGroup {
    private static final Logger logger = LoggerFactory.getLogger(SelectorGroup.class);
    private static final boolean DISTRIBUTE_CONNECTIONS = Boolean.getBoolean("fastjava.selectors.distribute");
    private static final int COMPLETION_RING_CAPACITY = Integer.getInteger("fastjava.selectors.completion.ring", 8192);
    private static final int IDLE_SCAN_TICK_INTERVAL = Integer.getInteger("fastjava.selectors.idle.tick", 16);
    private static final int WRITE_TIMEOUT_SCAN_TICK_INTERVAL = Integer.getInteger("fastjava.selectors.write.tick", 4);

    private final int numSelectors;
    private final Selector[] selectors;
    private final Thread[] threads;
    private final ConcurrentLinkedQueue<Runnable>[] selectorLocalTasks;
    private final MpscCompletionRing[] selectorLocalCompletionRings;
    private final ConcurrentLinkedQueue<FastJavaNioServer.NioCompletion>[] selectorLocalCompletionOverflow;
    private final AtomicBoolean[] selectorWakeupPending;
    private final Map<Selector, Integer> selectorIndexByIdentity;
    private final long[] selectorLoopTicks;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicInteger nextSelectorIndex = new AtomicInteger(0);
    private final FastJavaNioServer server;

    SelectorGroup(int numSelectors, FastJavaNioServer server, ServerSocketChannel serverSocketChannel) throws IOException {
        this.numSelectors = Math.max(1, numSelectors);
        this.server = server;
        this.selectors = new Selector[this.numSelectors];
        this.threads = new Thread[this.numSelectors];
        this.selectorLocalTasks = new ConcurrentLinkedQueue[this.numSelectors];
        this.selectorLocalCompletionRings = new MpscCompletionRing[this.numSelectors];
        this.selectorLocalCompletionOverflow = new ConcurrentLinkedQueue[this.numSelectors];
        this.selectorWakeupPending = new AtomicBoolean[this.numSelectors];
        this.selectorIndexByIdentity = new IdentityHashMap<>(this.numSelectors * 2);
        this.selectorLoopTicks = new long[this.numSelectors];

        for (int i = 0; i < this.numSelectors; i++) {
            selectors[i] = Selector.open();
            selectorIndexByIdentity.put(selectors[i], i);
            selectorLocalTasks[i] = new ConcurrentLinkedQueue<>();
            selectorLocalCompletionRings[i] = new MpscCompletionRing(COMPLETION_RING_CAPACITY);
            selectorLocalCompletionOverflow[i] = new ConcurrentLinkedQueue<>();
            selectorWakeupPending[i] = new AtomicBoolean(false);
            selectorLoopTicks[i] = 0L;
        }

        serverSocketChannel.register(selectors[0], SelectionKey.OP_ACCEPT);

        if (this.numSelectors > 1) {
            logger.info("Multi-selector enabled with {} selector threads", this.numSelectors);
        }
    }

    void start() {
        running.set(true);
        for (int i = 0; i < numSelectors; i++) {
            final int selectorIndex = i;
            Thread thread = new Thread(() -> runSelectorLoop(selectorIndex), "FastJava-Nio-Selector-" + selectorIndex);
            thread.setDaemon(false);
            threads[i] = thread;
            thread.start();
        }
    }

    void stop() {
        running.set(false);
        wakeupAll();
    }

    void waitForStop() throws InterruptedException {
        for (Thread thread : threads) {
            if (thread != null) {
                thread.join();
            }
        }
    }

    void wakeupAll() {
        for (Selector selector : selectors) {
            try {
                selector.wakeup();
            } catch (RuntimeException ignored) {
                // no-op
            }
        }
    }

    void wakeupAcceptor() {
        try {
            selectors[0].wakeup();
        } catch (RuntimeException ignored) {
            // no-op
        }
    }

    Selector getAcceptorSelector() {
        return selectors[0];
    }

    Selector getNextSelectorForConnection() {
        if (numSelectors <= 1 || !DISTRIBUTE_CONNECTIONS) {
            return selectors[0];
        }
        int idx = 1 + Math.floorMod(nextSelectorIndex.getAndIncrement(), numSelectors - 1);
        return selectors[idx];
    }

    void enqueueSelectorTask(Selector selector, Runnable task) {
        int idx = indexOfSelector(selector);
        if (idx < 0) {
            task.run();
            return;
        }
        selectorLocalTasks[idx].offer(task);
        if (selectorWakeupPending[idx].compareAndSet(false, true)) {
            selectors[idx].wakeup();
        }
    }

    void enqueueSelectorCompletion(Selector selector, FastJavaNioServer.NioCompletion completion) {
        int idx = indexOfSelector(selector);
        if (idx < 0) {
            server.applyCompletion(completion);
            return;
        }
        if (!selectorLocalCompletionRings[idx].offer(completion)) {
            selectorLocalCompletionOverflow[idx].offer(completion);
        }
        if (selectorWakeupPending[idx].compareAndSet(false, true)) {
            selectors[idx].wakeup();
        }
    }

    void requestSelectorWakeup(Selector selector) {
        int idx = indexOfSelector(selector);
        if (idx < 0) {
            if (selector != null) {
                selector.wakeup();
            }
            return;
        }
        if (selectorWakeupPending[idx].compareAndSet(false, true)) {
            selectors[idx].wakeup();
        }
    }

    private int indexOfSelector(Selector selector) {
        if (selector == null) {
            return -1;
        }
        Integer idx = selectorIndexByIdentity.get(selector);
        return idx == null ? -1 : idx;
    }

    private void drainLocalTasks(int selectorIndex) {
        Runnable task;
        while ((task = selectorLocalTasks[selectorIndex].poll()) != null) {
            try {
                task.run();
            } catch (RuntimeException taskError) {
                logger.debug("Selector-local task failed: {}", taskError.getMessage());
            }
        }
    }

    private void drainLocalCompletions(int selectorIndex) {
        FastJavaNioServer.NioCompletion completion;
        while ((completion = selectorLocalCompletionRings[selectorIndex].poll()) != null) {
            try {
                server.applyCompletion(completion);
            } catch (RuntimeException completionError) {
                logger.debug("Selector-local completion failed: {}", completionError.getMessage());
            }
        }

        while ((completion = selectorLocalCompletionOverflow[selectorIndex].poll()) != null) {
            try {
                server.applyCompletion(completion);
            } catch (RuntimeException completionError) {
                logger.debug("Selector-local completion failed: {}", completionError.getMessage());
            }
        }
    }

    private void runSelectorLoop(int selectorIndex) {
        Selector currentSelector = selectors[selectorIndex];
        boolean isAcceptor = selectorIndex == 0;
        Thread currentThread = Thread.currentThread();
        server.onSelectorThreadStart(currentThread);

        try {
            while (running.get()) {
                if (isAcceptor) {
                    if (server.hasPendingWork()) {
                        currentSelector.selectNow();
                    } else {
                        currentSelector.select(100);
                    }
                } else {
                    if (!selectorLocalTasks[selectorIndex].isEmpty()) {
                        currentSelector.selectNow();
                    } else {
                        currentSelector.select(100);
                    }
                }

                selectorWakeupPending[selectorIndex].set(false);

                drainLocalCompletions(selectorIndex);
                drainLocalTasks(selectorIndex);

                if (isAcceptor) {
                    server.drainPendingTlsRegistrations();
                    server.drainSelectorTasks();
                    server.applyCompletedExecutions(0);
                }

                long tick = ++selectorLoopTicks[selectorIndex];
                boolean doIdleCheck = IDLE_SCAN_TICK_INTERVAL > 0
                        && (tick % IDLE_SCAN_TICK_INTERVAL) == 0;
                boolean doWriteTimeoutCheck = WRITE_TIMEOUT_SCAN_TICK_INTERVAL > 0
                        && (tick % WRITE_TIMEOUT_SCAN_TICK_INTERVAL) == 0;
                if (doIdleCheck || doWriteTimeoutCheck) {
                    long nowMillis = System.currentTimeMillis();
                    server.expireIdleConnectionsOnSelector(currentSelector, nowMillis, doIdleCheck, doWriteTimeoutCheck);
                }

                Set<SelectionKey> selectedKeys = currentSelector.selectedKeys();
                Iterator<SelectionKey> iterator = selectedKeys.iterator();
                while (iterator.hasNext()) {
                    SelectionKey key = iterator.next();
                    iterator.remove();
                    if (!key.isValid()) {
                        continue;
                    }

                    try {
                        if (isAcceptor && key.isAcceptable()) {
                            server.acceptConnection();
                        }
                        if (!key.isValid()) {
                            continue;
                        }
                        if (key.isReadable()) {
                            server.handleRead(key);
                        }
                        if (!key.isValid()) {
                            continue;
                        }
                        if (key.isWritable()) {
                            server.handleWrite(key);
                        }
                    } catch (IOException ioException) {
                        logger.debug("Closing connection after I/O failure: {}", ioException.getMessage());
                        server.closeKey(key);
                    }
                }
            }
        } catch (ClosedSelectorException ignored) {
            if (running.get()) {
                logger.debug("Selector {} closed unexpectedly", selectorIndex);
            }
        } catch (IOException exception) {
            if (running.get()) {
                logger.error("Selector {} loop error", selectorIndex, exception);
            }
        } finally {
            server.onSelectorThreadStop(currentThread);
            try {
                currentSelector.close();
            } catch (IOException ignored) {
                // no-op
            }
        }
    }

    private static final class MpscCompletionRing {
        private final int capacity;
        private final int mask;
        private final AtomicReferenceArray<FastJavaNioServer.NioCompletion> entries;
        private final AtomicLong head;
        private final AtomicLong tail;

        MpscCompletionRing(int requestedCapacity) {
            int cap = 1;
            int normalized = Math.max(256, requestedCapacity);
            while (cap < normalized) {
                cap <<= 1;
            }
            this.capacity = cap;
            this.mask = cap - 1;
            this.entries = new AtomicReferenceArray<>(cap);
            this.head = new AtomicLong(0L);
            this.tail = new AtomicLong(0L);
        }

        boolean offer(FastJavaNioServer.NioCompletion completion) {
            if (completion == null) {
                return true;
            }
            while (true) {
                long t = tail.get();
                long h = head.get();
                if (t - h >= capacity) {
                    return false;
                }
                if (tail.compareAndSet(t, t + 1)) {
                    int slot = (int) (t & mask);
                    entries.lazySet(slot, completion);
                    return true;
                }
            }
        }

        FastJavaNioServer.NioCompletion poll() {
            long h = head.get();
            int slot = (int) (h & mask);
            FastJavaNioServer.NioCompletion completion = entries.get(slot);
            if (completion == null) {
                return null;
            }
            entries.set(slot, null);
            head.lazySet(h + 1);
            return completion;
        }
    }
}
