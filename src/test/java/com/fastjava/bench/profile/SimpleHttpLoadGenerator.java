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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

public final class SimpleHttpLoadGenerator {

    private SimpleHttpLoadGenerator() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            throw new IllegalArgumentException("Usage: <url> <requests> <concurrency> [warmupRequests]");
        }

        String url = args[0];
        int requests = Integer.parseInt(args[1]);
        int concurrency = Integer.parseInt(args[2]);
        int warmupRequests = args.length > 3 ? Integer.parseInt(args[3]) : 0;

        if (warmupRequests > 0) {
            runLoad(url, warmupRequests, concurrency);
        }
        LoadResult result = runLoad(url, requests, concurrency);
        System.out.printf(Locale.ROOT,
                "LOAD_RESULT throughput=%.2f req/s success=%d errors=%d elapsedMs=%d%n",
                result.throughputRequestsPerSecond,
                result.success,
                result.errors,
                result.elapsedMillis);
    }

    private static LoadResult runLoad(String url, int totalRequests, int concurrency) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .timeout(Duration.ofSeconds(5))
                .build();

        AtomicInteger remaining = new AtomicInteger(totalRequests);
        LongAdder success = new LongAdder();
        LongAdder errors = new LongAdder();
        CountDownLatch latch = new CountDownLatch(concurrency);
        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        Instant started = Instant.now();

        for (int threadIndex = 0; threadIndex < concurrency; threadIndex++) {
            executor.submit(() -> {
                try {
                    while (remaining.getAndDecrement() > 0) {
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

        latch.await();
        executor.shutdown();
        executor.awaitTermination(30, TimeUnit.SECONDS);
        long elapsedMillis = Duration.between(started, Instant.now()).toMillis();
        long completed = success.sum() + errors.sum();
        double throughput = elapsedMillis == 0 ? 0.0 : (completed * 1000.0 / elapsedMillis);
        return new LoadResult(success.sum(), errors.sum(), elapsedMillis, throughput);
    }

    private record LoadResult(long success, long errors, long elapsedMillis, double throughputRequestsPerSecond) {
    }
}
