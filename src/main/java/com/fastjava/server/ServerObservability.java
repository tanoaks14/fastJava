package com.fastjava.server;

import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAdder;

final class ServerObservability {

    private static final double[] LATENCY_BUCKETS_MS = {
            1, 5, 10, 25, 50, 100, 250, 500, 1_000, 2_500, 5_000, 10_000
    };

    private static final AtomicLong activeConnections = new AtomicLong();
    // LongAdder has less contention than AtomicLong under multi-threaded high-frequency updates.
    private static final LongAdder requestsTotal = new LongAdder();
    private static final LongAdder responsesTotal = new LongAdder();
    private static final LongAdder bytesReceivedTotal = new LongAdder();
    private static final LongAdder bytesSentTotal = new LongAdder();
    private static final LongAdder requestDurationNanosSum = new LongAdder();
    private static final AtomicLong writeTimeoutsTotal = new AtomicLong();
    private static final AtomicLong rejectedConnectionsTotal = new AtomicLong();
    private static final AtomicLong keepAlivePreemptionsTotal = new AtomicLong();
    private static final AtomicLong handlerQueueDepth = new AtomicLong();
    private static final AtomicLongArray requestDurationBuckets = new AtomicLongArray(LATENCY_BUCKETS_MS.length + 1);
    private static final ConcurrentHashMap<Integer, AtomicLong> responsesByStatus = new ConcurrentHashMap<>();
    private static final long PROMETHEUS_CACHE_TTL_MILLIS = 1_000L;
    private static volatile String cachedPrometheusSnapshot = "";
    private static volatile long cachedPrometheusSnapshotAtMillis = 0L;

    private ServerObservability() {
    }

    static {
        int[] commonStatusCodes = { 200, 204, 301, 302, 304, 400, 404, 500 };
        for (int statusCode : commonStatusCodes) {
            responsesByStatus.put(statusCode, new AtomicLong());
        }
    }

    static void connectionOpened() {
        activeConnections.incrementAndGet();
    }

    static void connectionClosed() {
        activeConnections.updateAndGet(current -> Math.max(0, current - 1));
    }

    static void recordRequestBytesReceived(long bytes) {
        if (bytes > 0) {
            bytesReceivedTotal.add(bytes);
        }
    }

    static void recordWriteTimeout() {
        writeTimeoutsTotal.incrementAndGet();
    }

    static void recordRejectedConnection() {
        rejectedConnectionsTotal.incrementAndGet();
    }

    static void recordKeepAlivePreemption() {
        keepAlivePreemptionsTotal.incrementAndGet();
    }

    static void recordHandlerQueueDepth(long depth) {
        handlerQueueDepth.set(Math.max(0, depth));
    }

    static void recordCompletedRequest(int statusCode, long durationNanos, long bytesSent) {
        requestsTotal.increment();
        responsesTotal.increment();
        AtomicLong statusCounter = responsesByStatus.get(statusCode);
        if (statusCounter == null) {
            AtomicLong created = new AtomicLong();
            AtomicLong existing = responsesByStatus.putIfAbsent(statusCode, created);
            statusCounter = existing == null ? created : existing;
        }
        statusCounter.incrementAndGet();
        if (durationNanos > 0) {
            requestDurationNanosSum.add(durationNanos);
            // Integer nanos→ms without floating-point: nanos / 1_000_000
            long durationMs = durationNanos / 1_000_000L;
            requestDurationBuckets.incrementAndGet(findLatencyBucket(durationMs));
        }
        if (bytesSent > 0) {
            bytesSentTotal.add(bytesSent);
        }
    }

    static String renderPrometheus() {
        long now = System.currentTimeMillis();
        if (now - cachedPrometheusSnapshotAtMillis < PROMETHEUS_CACHE_TTL_MILLIS) {
            return cachedPrometheusSnapshot;
        }
        synchronized (ServerObservability.class) {
            now = System.currentTimeMillis();
            if (now - cachedPrometheusSnapshotAtMillis < PROMETHEUS_CACHE_TTL_MILLIS) {
                return cachedPrometheusSnapshot;
            }

            StringBuilder output = new StringBuilder(2_048);
            appendGauge(output, "fastjava_active_connections", activeConnections.get());
            appendCounter(output, "fastjava_requests_total", requestsTotal.sum());
            appendCounter(output, "fastjava_responses_total", responsesTotal.sum());
            appendCounter(output, "fastjava_bytes_received_total", bytesReceivedTotal.sum());
            appendCounter(output, "fastjava_bytes_sent_total", bytesSentTotal.sum());
            appendCounter(output, "fastjava_write_timeouts_total", writeTimeoutsTotal.get());
            appendCounter(output, "fastjava_rejected_connections_total", rejectedConnectionsTotal.get());
            appendCounter(output, "fastjava_keepalive_preemptions_total", keepAlivePreemptionsTotal.get());
            appendGauge(output, "fastjava_handler_queue_depth", handlerQueueDepth.get());

            output.append("# HELP fastjava_request_duration_ms HTTP request duration in milliseconds\n");
            output.append("# TYPE fastjava_request_duration_ms histogram\n");
            long cumulative = 0;
            for (int index = 0; index < LATENCY_BUCKETS_MS.length; index++) {
                cumulative += requestDurationBuckets.get(index);
                output.append("fastjava_request_duration_ms_bucket{le=\"")
                        .append(stripTrailingZero(LATENCY_BUCKETS_MS[index]))
                        .append("\"} ")
                        .append(cumulative)
                        .append('\n');
            }
            cumulative += requestDurationBuckets.get(LATENCY_BUCKETS_MS.length);
            output.append("fastjava_request_duration_ms_bucket{le=\"+Inf\"} ").append(cumulative).append('\n');
            output.append("fastjava_request_duration_ms_sum ")
                    .append(requestDurationNanosSum.sum() / 1_000_000.0)
                    .append('\n');
            output.append("fastjava_request_duration_ms_count ").append(requestsTotal.sum()).append('\n');

            output.append("# HELP fastjava_responses_by_status_total Responses grouped by HTTP status code\n");
            output.append("# TYPE fastjava_responses_by_status_total counter\n");
            for (Map.Entry<Integer, AtomicLong> entry : new TreeMap<>(responsesByStatus).entrySet()) {
                output.append("fastjava_responses_by_status_total{status=\"")
                        .append(entry.getKey())
                        .append("\"} ")
                        .append(entry.getValue().get())
                        .append('\n');
            }
            cachedPrometheusSnapshot = output.toString();
            cachedPrometheusSnapshotAtMillis = now;
            return cachedPrometheusSnapshot;
        }
    }

    private static int findLatencyBucket(long durationMs) {
        int index = Arrays.binarySearch(LATENCY_BUCKETS_MS, durationMs);
        if (index >= 0) {
            return index;
        }
        int insertionPoint = -index - 1;
        if (insertionPoint >= LATENCY_BUCKETS_MS.length) {
            return LATENCY_BUCKETS_MS.length;
        }
        return insertionPoint;
    }

    private static String stripTrailingZero(double value) {
        if (value == Math.rint(value)) {
            return String.valueOf((long) value);
        }
        return String.valueOf(value);
    }

    private static void appendGauge(StringBuilder output, String metricName, long value) {
        output.append("# TYPE ").append(metricName).append(" gauge\n");
        output.append(metricName).append(' ').append(value).append('\n');
    }

    private static void appendCounter(StringBuilder output, String metricName, long value) {
        output.append("# TYPE ").append(metricName).append(" counter\n");
        output.append(metricName).append(' ').append(value).append('\n');
    }
}
