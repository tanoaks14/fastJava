package com.fastjava.bench;

import com.fastjava.server.ServletRouter;
import java.nio.charset.StandardCharsets;

import java.util.Locale;

/**
 * Developer-focused microbenchmark for router pattern checks on cached ASCII
 * bytes.
 * Usage: java ... com.fastjava.bench.ServletRouterMicroBench [iterations]
 */
public class ServletRouterMicroBench {

    private static final int DEFAULT_ITERATIONS = 500_000;
    private static final int WARMUP_ROUNDS = 2;

    private final String[] paths = {
            "/api/users/42",
            "/api/orders/2026/03/30",
            "/assets/main.bundle.js",
            "/download/releases/app-v1.2.3.tar.gz",
            "/reports/daily.json",
            "/health",
            "/api/v1/tenant/region/service/cluster/node/endpoint/metrics/2026/03/30/summary/report/detail/segment-a",
            "/download/releases/channel/stable/linux/x64/builds/2026/03/30/app-release-1.2.3-hotfix-4.tar.gz"
    };

    private final String[] prefixes = { "/api", "/assets", "/download", "/admin", "/reports" };
    private final String[] suffixes = { ".json", ".js", ".gz", ".csv", ".txt", ".xml" };

    private final byte[][] pathAscii;
    private final byte[][] prefixAscii;
    private final byte[][] suffixAscii;

    public ServletRouterMicroBench() {
        pathAscii = new byte[paths.length][];
        for (int i = 0; i < paths.length; i++) {
            pathAscii[i] = paths[i].getBytes(StandardCharsets.US_ASCII);
        }

        prefixAscii = new byte[prefixes.length][];
        for (int i = 0; i < prefixes.length; i++) {
            prefixAscii[i] = prefixes[i].getBytes(StandardCharsets.US_ASCII);
        }

        suffixAscii = new byte[suffixes.length][];
        for (int i = 0; i < suffixes.length; i++) {
            suffixAscii[i] = suffixes[i].getBytes(StandardCharsets.US_ASCII);
        }
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

        new ServletRouterMicroBench().run(iterations);
    }

    private void run(int iterations) {
        runBenchmark("prefixScalarCachedAscii", iterations, this::runPrefixScalarCachedAscii);
        runBenchmark("prefixSimdCachedAscii", iterations, this::runPrefixSimdCachedAscii);
        runBenchmark("suffixScalarCachedAscii", iterations, this::runSuffixScalarCachedAscii);
        runBenchmark("suffixSimdCachedAscii", iterations, this::runSuffixSimdCachedAscii);
    }

    private Object runPrefixScalarCachedAscii() {
        boolean result = false;
        for (byte[] path : pathAscii) {
            for (byte[] prefix : prefixAscii) {
                result ^= ServletRouter.prefixMatchScalarAscii(path, prefix);
            }
        }
        return result;
    }

    private Object runPrefixSimdCachedAscii() {
        boolean result = false;
        for (byte[] path : pathAscii) {
            for (byte[] prefix : prefixAscii) {
                result ^= ServletRouter.prefixMatchSimdAscii(path, prefix);
            }
        }
        return result;
    }

    private Object runSuffixScalarCachedAscii() {
        boolean result = false;
        for (byte[] path : pathAscii) {
            for (byte[] suffix : suffixAscii) {
                result ^= ServletRouter.suffixMatchScalarAscii(path, suffix);
            }
        }
        return result;
    }

    private Object runSuffixSimdCachedAscii() {
        boolean result = false;
        for (byte[] path : pathAscii) {
            for (byte[] suffix : suffixAscii) {
                result ^= ServletRouter.suffixMatchSimdAscii(path, suffix);
            }
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
                "com.fastjava.bench.ServletRouterMicroBench.%s avg_us=%.3f ops_s=%.0f iterations=%d",
                name,
                avgMicros,
                opsPerSecond,
                iterations));
    }

    private static void consume(Object value) {
        if (value instanceof Boolean bool && bool) {
            // no-op branch to keep output observable
        }
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get();
    }
}
