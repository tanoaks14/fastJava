package com.fastjava.bench.comparison.jfr;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.LongAdder;

/**
 * Standalone HTTP load generator for JFR profiling.
 * Sends concurrent HTTP requests to a specified endpoint.
 * 
 * Usage: java JfrLoadGenerator --url=<url> --requests=<n> --concurrency=<n>
 */
public class JfrLoadGenerator {

    public static void main(String[] args) throws Exception {
        String url = null;
        int requests = 100000;
        int concurrency = 32;

        // Parse arguments
        for (String arg : args) {
            if (arg.startsWith("--url=")) {
                url = arg.substring("--url=".length());
            } else if (arg.startsWith("--requests=")) {
                requests = Integer.parseInt(arg.substring("--requests=".length()));
            } else if (arg.startsWith("--concurrency=")) {
                concurrency = Integer.parseInt(arg.substring("--concurrency=".length()));
            }
        }

        if (url == null) {
            System.err.println("Usage: java JfrLoadGenerator --url=<url> --requests=<n> --concurrency=<n>");
            System.exit(1);
        }

        System.out.println("[JfrLoadGenerator] URL: " + url);
        System.out.println("[JfrLoadGenerator] Requests: " + requests);
        System.out.println("[JfrLoadGenerator] Concurrency: " + concurrency);
        System.out.println();

        runLoad(url, requests, concurrency);
    }

    private static void runLoad(String url, int totalRequests, int concurrency) throws Exception {
        var client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .executor(java.util.concurrent.Executors.newFixedThreadPool(concurrency))
                .build();

        var request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();

        var remaining = new java.util.concurrent.atomic.AtomicInteger(totalRequests);
        var success = new LongAdder();
        var errors = new LongAdder();
        var latencies = Collections.synchronizedList(new ArrayList<Long>());

        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        CountDownLatch latch = new CountDownLatch(concurrency);

        Instant startTime = Instant.now();

        // Start load threads
        for (int i = 0; i < concurrency; i++) {
            executor.submit(() -> {
                try {
                    while (true) {
                        int req = remaining.decrementAndGet();
                        if (req < 0)
                            break;

                        long startNanos = System.nanoTime();
                        try {
                            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
                            if (response.statusCode() == 200) {
                                success.increment();
                            } else {
                                errors.increment();
                            }
                        } catch (Exception e) {
                            errors.increment();
                        }
                        long latencyNanos = System.nanoTime() - startNanos;
                        latencies.add(latencyNanos);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        // Wait for completion or timeout
        boolean completed = latch.await(300, java.util.concurrent.TimeUnit.SECONDS);
        long elapsedNanos = java.time.Duration.between(startTime, Instant.now()).toNanos();

        executor.shutdown();
        executor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS);

        // Report results
        long successCount = success.sum();
        long errorCount = errors.sum();
        long totalCompleted = successCount + errorCount;

        double throughput = totalCompleted > 0 ? (totalCompleted * 1_000_000_000.0 / elapsedNanos) : 0;
        double avgLatency = totalCompleted > 0 ? (latencies.stream().mapToLong(Long::longValue).sum() /
                (totalCompleted * 1_000_000.0)) : 0;

        // Calculate percentiles
        Collections.sort(latencies);
        long p95Nanos = getPercentile(latencies, 0.95);
        long p99Nanos = getPercentile(latencies, 0.99);
        double p95Millis = p95Nanos / 1_000_000.0;
        double p99Millis = p99Nanos / 1_000_000.0;

        System.out.println("[JfrLoadGenerator] ═══════════════════════════════════════");
        System.out.println("[JfrLoadGenerator] Results:");
        System.out.println(String.format("[JfrLoadGenerator]   Success      : %,d", successCount));
        System.out.println(String.format("[JfrLoadGenerator]   Errors       : %,d", errorCount));
        System.out.println(String.format("[JfrLoadGenerator]   Throughput   : %.2f req/s", throughput));
        System.out.println(String.format("[JfrLoadGenerator]   Avg Latency  : %.3f ms", avgLatency));
        System.out.println(String.format("[JfrLoadGenerator]   p95 Latency  : %.3f ms", p95Millis));
        System.out.println(String.format("[JfrLoadGenerator]   p99 Latency  : %.3f ms", p99Millis));
        System.out.println("[JfrLoadGenerator] ═══════════════════════════════════════");

        if (!completed) {
            System.err.println("[JfrLoadGenerator] WARNING: Load generation timeout!");
        }
    }

    private static long getPercentile(List<Long> sortedValues, double percentile) {
        if (sortedValues.isEmpty())
            return 0;
        int index = (int) (sortedValues.size() * percentile);
        return sortedValues.get(Math.min(index, sortedValues.size() - 1));
    }
}
