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
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages parallel selector threads. Selector 0 owns OP_ACCEPT and accepts new
 * channels.
 */
final class SelectorGroup {

    private static final Logger logger = LoggerFactory.getLogger(SelectorGroup.class);
    private static final boolean DISTRIBUTE_CONNECTIONS = Boolean.parseBoolean(
            System.getProperty("fastjava.selectors.distribute", "true"));
    private static final int COMPLETION_RING_CAPACITY = Integer.getInteger("fastjava.selectors.completion.ring", 8192);
    private static final int IDLE_SCAN_TICK_INTERVAL = Integer.getInteger("fastjava.selectors.idle.tick", 16);
    private static final int WRITE_TIMEOUT_SCAN_TICK_INTERVAL = Integer.getInteger("fastjava.selectors.write.tick", 4);
    private static final int SELECT_TIMEOUT_MILLIS = Integer.getInteger("fastjava.selectors.select.timeout.ms", 100);
    private static final int ACCEPTOR_PENDING_CHECK_INTERVAL
            = Integer.getInteger("fastjava.selectors.acceptor.pending.check.interval", 8);
    private static final int LOCAL_DRAIN_BATCH = Integer.getInteger("fastjava.selectors.local.drain.batch", 256);
    private static final int ACCEPTOR_GLOBAL_DRAIN_BATCH
            = Integer.getInteger("fastjava.selectors.acceptor.global.drain.batch", 512);

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

    private int drainLocalTasks(int selectorIndex, int maxTasks) {
        int processed = 0;
        Runnable task;
        while ((maxTasks <= 0 || processed < maxTasks) && (task = selectorLocalTasks[selectorIndex].poll()) != null) {
            try {
                task.run();
            } catch (RuntimeException taskError) {
                logger.debug("Selector-local task failed: {}", taskError.getMessage());
            }
            processed++;
        }
        return processed;
    }

    private int drainLocalCompletions(int selectorIndex, int maxCompletions) {
        int processed = 0;
        FastJavaNioServer.NioCompletion completion;
        while ((maxCompletions <= 0 || processed < maxCompletions)
                && (completion = selectorLocalCompletionRings[selectorIndex].poll()) != null) {
            try {
                server.applyCompletion(completion);
            } catch (RuntimeException completionError) {
                logger.debug("Selector-local completion failed: {}", completionError.getMessage());
            }
            processed++;
        }

        while ((maxCompletions <= 0 || processed < maxCompletions)
                && (completion = selectorLocalCompletionOverflow[selectorIndex].poll()) != null) {
            try {
                server.applyCompletion(completion);
            } catch (RuntimeException completionError) {
                logger.debug("Selector-local completion failed: {}", completionError.getMessage());
            }
            processed++;
        }
        return processed;
    }

    private int adaptiveTickInterval(int baseInterval, int selectedKeyCount, boolean hadLocalWork) {
        if (baseInterval <= 0) {
            return 0;
        }
        if (selectedKeyCount >= 256) {
            return baseInterval * 4;
        }
        if (selectedKeyCount >= 128 || (hadLocalWork && selectedKeyCount >= 64)) {
            return baseInterval * 2;
        }
        return baseInterval;
    }

    private void runSelectorLoop(int selectorIndex) {
        Selector currentSelector = selectors[selectorIndex];
        boolean isAcceptor = selectorIndex == 0;
        Thread currentThread = Thread.currentThread();
        server.onSelectorThreadStart(currentThread);

        try {
            while (running.get()) {
                long tickBefore = selectorLoopTicks[selectorIndex];
                boolean selectNow = selectorWakeupPending[selectorIndex].get();
                if (!selectNow && isAcceptor && ACCEPTOR_PENDING_CHECK_INTERVAL > 0
                        && (tickBefore % ACCEPTOR_PENDING_CHECK_INTERVAL) == 0
                        && server.hasPendingWork()) {
                    selectNow = true;
                }
                if (selectNow) {
                    currentSelector.selectNow();
                } else {
                    currentSelector.select(SELECT_TIMEOUT_MILLIS);
                }

                selectorWakeupPending[selectorIndex].set(false);

                int drainedLocalCompletions = drainLocalCompletions(selectorIndex, LOCAL_DRAIN_BATCH);
                int localTaskBudget = LOCAL_DRAIN_BATCH <= 0
                        ? 0
                        : Math.max(1, LOCAL_DRAIN_BATCH - drainedLocalCompletions);
                int drainedLocalTasks = drainLocalTasks(selectorIndex, localTaskBudget);
                boolean hadLocalWork = drainedLocalCompletions > 0 || drainedLocalTasks > 0;

                if (isAcceptor) {
                    server.maybeHotReloadTlsCertificates();
                    server.drainPendingTlsRegistrations();
                    int globalTaskBudget = ACCEPTOR_GLOBAL_DRAIN_BATCH <= 0
                            ? 0
                            : Math.max(1, ACCEPTOR_GLOBAL_DRAIN_BATCH / 2);
                    int drainedGlobalTasks = server.drainSelectorTasks(globalTaskBudget);
                    int completionBudget = ACCEPTOR_GLOBAL_DRAIN_BATCH <= 0
                            ? 0
                            : Math.max(1, ACCEPTOR_GLOBAL_DRAIN_BATCH - drainedGlobalTasks);
                    server.applyCompletedExecutions(completionBudget);
                }

                Set<SelectionKey> selectedKeys = currentSelector.selectedKeys();
                int selectedKeyCount = selectedKeys.size();
                long tick = ++selectorLoopTicks[selectorIndex];
                int idleTickInterval = adaptiveTickInterval(IDLE_SCAN_TICK_INTERVAL, selectedKeyCount, hadLocalWork);
                int writeTickInterval = adaptiveTickInterval(
                        WRITE_TIMEOUT_SCAN_TICK_INTERVAL,
                        selectedKeyCount,
                        hadLocalWork);
                boolean doIdleCheck = idleTickInterval > 0 && (tick % idleTickInterval) == 0;
                boolean doWriteTimeoutCheck = writeTickInterval > 0 && (tick % writeTickInterval) == 0;
                if (doIdleCheck || doWriteTimeoutCheck) {
                    long nowMillis = System.currentTimeMillis();
                    server.expireIdleConnectionsOnSelector(currentSelector, nowMillis, doIdleCheck, doWriteTimeoutCheck);
                }

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
