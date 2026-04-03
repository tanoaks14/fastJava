package com.fastjava.bench.profile;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

/**
 * Enhanced HTTP load generator that runs for a specified duration instead of a
 * fixed request count. This is useful for profiling scenarios where you want to
 * measure throughput over time.
 *
 * Usage: java DurationBasedHttpLoadGenerator <url> <durationSeconds>
 * <concurrency> [warmupSeconds] Output: LOAD_RESULT throughput=123.45 req/s
 * success=1234 errors=5 elapsedMs=10000
 */
public final class DurationBasedHttpLoadGenerator {

    private DurationBasedHttpLoadGenerator() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            throw new IllegalArgumentException("Usage: <url> <durationSeconds> <concurrency> [warmupSeconds]");
        }

        String url = args[0];
        int durationSeconds = Integer.parseInt(args[1]);
        int concurrency = Integer.parseInt(args[2]);
        int warmupSeconds = args.length > 3 ? Integer.parseInt(args[3]) : 0;

        if (warmupSeconds > 0) {
            System.err.println("Warming up for " + warmupSeconds + " seconds...");
            runLoad(url, warmupSeconds, concurrency);
            System.err.println("Warmup complete.");
        }

        LoadResult result = runLoad(url, durationSeconds, concurrency);
        System.out.printf(Locale.ROOT,
                "LOAD_RESULT throughput=%.2f req/s success=%d errors=%d elapsedMs=%d%n",
                result.throughputRequestsPerSecond,
                result.success,
                result.errors,
                result.elapsedMillis);
    }

    private static LoadResult runLoad(String url, int durationSeconds, int concurrency) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .version(HttpClient.Version.HTTP_1_1)
                .build();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .timeout(Duration.ofSeconds(5))
                .build();

        AtomicBoolean running = new AtomicBoolean(true);
        LongAdder success = new LongAdder();
        LongAdder errors = new LongAdder();
        CountDownLatch latch = new CountDownLatch(concurrency);
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);

        Instant started = Instant.now();
        Instant deadline = started.plusSeconds(durationSeconds);

        // Start worker threads
        for (int threadIndex = 0; threadIndex < concurrency; threadIndex++) {
            executor.submit(() -> {
                try {
                    while (running.get() && Instant.now().isBefore(deadline)) {
                        try {
                            HttpResponse<byte[]> response = client.send(request,
                                    HttpResponse.BodyHandlers.ofByteArray());
                            if (response.statusCode() == 200) {
                                success.increment();
                            } else {
                                errors.increment();
                            }
                        } catch (Exception requestError) {
                            errors.increment();
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        // Wait for test duration or completion
        boolean finished = latch.await(durationSeconds + 5, TimeUnit.SECONDS);
        running.set(false);

        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        long elapsedMillis = Duration.between(started, Instant.now()).toMillis();
        long completed = success.sum() + errors.sum();
        double throughput = elapsedMillis == 0 ? 0.0 : (completed * 1000.0 / elapsedMillis);

        return new LoadResult(success.sum(), errors.sum(), elapsedMillis, throughput);
    }

    private record LoadResult(long success, long errors, long elapsedMillis, double throughputRequestsPerSecond) {

    }
}
