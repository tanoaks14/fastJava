package com.fastjava.http.filter;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Random;

/**
 * Developer-focused microbenchmark for gzip decision paths.
 * Usage: java ... com.fastjava.http.filter.GzipFilterMicroBench [iterations]
 */
public class GzipFilterMicroBench {

    private static final int DEFAULT_ITERATIONS = 400_000;
    private static final int WARMUP_ROUNDS = 2;

    private final String[] mimeCases;
    private final byte[][] bodyCases;

    public GzipFilterMicroBench() {
        mimeCases = new String[] {
                "text/plain",
                "application/json; charset=UTF-8",
                "IMAGE/PNG",
                "application/octet-stream",
                "video/mp4",
                "application/x-custom"
        };

        bodyCases = new byte[8][];
        Random random = new Random(42L);
        for (int i = 0; i < bodyCases.length; i++) {
            byte[] body = new byte[512];
            random.nextBytes(body);
            bodyCases[i] = body;
        }

        byte[] mostlyAscii = "hello".repeat(120).getBytes(StandardCharsets.US_ASCII);
        byte[] mostlyBinary = new byte[512];
        for (int i = 0; i < mostlyBinary.length; i++) {
            mostlyBinary[i] = (byte) (i % 3 == 0 ? 0xFF : i);
        }
        bodyCases[0] = mostlyAscii;
        bodyCases[1] = mostlyBinary;
    }

    public static void main(String[] args) {
        int iterations = DEFAULT_ITERATIONS;
        if (args.length > 0) {
            try {
                iterations = Math.max(1, Integer.parseInt(args[0]));
            } catch (NumberFormatException ignored) {
                iterations = DEFAULT_ITERATIONS;
            }
        }

        new GzipFilterMicroBench().run(iterations);
    }

    private void run(int iterations) {
        runBenchmark("mimeScalar", iterations, this::runMimeScalar);
        runBenchmark("mimeSimd", iterations, this::runMimeSimd);
        runBenchmark("binaryScalar", iterations, this::runBinaryScalar);
        runBenchmark("binarySimd", iterations, this::runBinarySimd);
    }

    private Object runMimeScalar() {
        boolean result = false;
        for (String mime : mimeCases) {
            result ^= GzipFilter.isExcludedMimeTypeScalar(mime);
        }
        return result;
    }

    private Object runMimeSimd() {
        boolean result = false;
        for (String mime : mimeCases) {
            result ^= GzipFilter.isExcludedMimeTypeSimd(mime);
        }
        return result;
    }

    private Object runBinaryScalar() {
        boolean result = false;
        for (byte[] body : bodyCases) {
            result ^= GzipFilter.isBinaryContentScalar(body);
        }
        return result;
    }

    private Object runBinarySimd() {
        boolean result = false;
        for (byte[] body : bodyCases) {
            result ^= GzipFilter.isBinaryContentSimd(body);
        }
        return result;
    }

    private static void runBenchmark(String name, int iterations, ThrowingSupplier<Object> action) {
        for (int warmup = 0; warmup < WARMUP_ROUNDS; warmup++) {
            for (int i = 0; i < iterations; i++) {
                consume(action.get());
            }
        }

        long startNanos = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            consume(action.get());
        }
        long elapsedNanos = System.nanoTime() - startNanos;

        double avgMicros = (elapsedNanos / 1000.0) / iterations;
        double opsPerSecond = iterations * (1_000_000_000.0 / elapsedNanos);

        System.out.println(String.format(
                Locale.ROOT,
                "com.fastjava.http.filter.GzipFilterMicroBench.%s avg_us=%.3f ops_s=%.0f iterations=%d",
                name,
                avgMicros,
                opsPerSecond,
                iterations));
    }

    private static void consume(Object value) {
        if (value instanceof Boolean bool && bool) {
            // no-op branch to keep the result observable
        }
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get();
    }
}
