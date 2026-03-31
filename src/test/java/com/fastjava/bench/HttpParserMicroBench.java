package com.fastjava.bench;

import com.fastjava.http.parser.HttpRequestParser;
import com.fastjava.http.simd.SIMDByteScanner;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Lightweight benchmark runner intended for developer-fast feedback.
 * Usage: java ... HttpParserMicroBench [iterations]
 */
public class HttpParserMicroBench {

    private static final int DEFAULT_ITERATIONS = 10_000;
    private static final int WARMUP_ROUNDS = 3;

    private final byte[] simpleGetRequest;
    private final byte[] complexRequest;
    private final byte[] mixedCaseHeaderRequest;
    private final byte[] chunkedRequest;
    private final byte[] getPattern;
    private final byte[] multipartBoundaryPayload;
    private final byte[] multipartBoundaryDelimiter;
    private final byte[] multipartBoundaryNoisyPayload;

    public HttpParserMicroBench() {
        simpleGetRequest = ("GET / HTTP/1.1\r\n"
                + "Host: localhost\r\n"
                + "User-Agent: test\r\n"
                + "Accept: */*\r\n"
                + "\r\n").getBytes(StandardCharsets.US_ASCII);

        complexRequest = ("POST /api/users/123 HTTP/1.1\r\n"
                + "Host: localhost:8080\r\n"
                + "Content-Type: application/json\r\n"
                + "User-Agent: Mozilla/5.0\r\n"
                + "Accept: application/json\r\n"
                + "Accept-Language: en-US,en;q=0.9\r\n"
                + "Accept-Encoding: gzip, deflate\r\n"
                + "Connection: keep-alive\r\n"
                + "Content-Length: 50\r\n"
                + "\r\n"
                + "{\"name\":\"test\",\"email\":\"test@example.com\"}").getBytes(StandardCharsets.US_ASCII);

        mixedCaseHeaderRequest = ("GET /assets/app.js HTTP/1.1\r\n"
                + "hOsT: localhost\r\n"
                + "uSeR-aGeNt: perf-client/1.0\r\n"
                + "aCcEpT: text/html,application/xhtml+xml\r\n"
                + "aCcEpT-eNcOdInG: gzip, deflate, br\r\n"
                + "x-FoRwArDeD-fOr: 10.0.0.20\r\n"
                + "x-ReQuEsT-iD: 7f5b6a82-42f3-4b0b-831f-1dc6ca55feab\r\n"
                + "iF-nOnE-mAtCh: \"17a3b2c4-2800\"\r\n"
                + "\r\n").getBytes(StandardCharsets.US_ASCII);

        chunkedRequest = ("POST /upload HTTP/1.1\r\n"
                + "Host: localhost\r\n"
                + "Transfer-Encoding: chunked\r\n"
                + "\r\n"
                + "5\r\n"
                + "hello\r\n"
                + "6\r\n"
                + " world\r\n"
                + "0\r\n"
                + "\r\n").getBytes(StandardCharsets.US_ASCII);

        getPattern = "GET".getBytes(StandardCharsets.US_ASCII);

        multipartBoundaryPayload = ("--bench-boundary\r\n"
                + "Content-Disposition: form-data; name=\"field\"\r\n"
                + "\r\n"
                + "value\r\n"
                + "--bench-boundary--\r\n").getBytes(StandardCharsets.US_ASCII);
        multipartBoundaryDelimiter = "--bench-boundary".getBytes(StandardCharsets.US_ASCII);

        String noisy = "-a--b---c----d-----e------f".repeat(80);
        multipartBoundaryNoisyPayload = ("--bench-boundary\r\n"
                + "Content-Disposition: form-data; name=\"field\"\r\n"
                + "\r\n"
                + noisy + "\r\n"
                + "--bench-boundary--\r\n").getBytes(StandardCharsets.US_ASCII);
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

        HttpParserMicroBench bench = new HttpParserMicroBench();
        bench.run(iterations);
    }

    @Test
    public void benchmarkDefaultProfile() {
        run(DEFAULT_ITERATIONS);
    }

    private void run(int iterations) {
        runBenchmark("parseSimpleGet", iterations,
                () -> HttpRequestParser.parse(simpleGetRequest, simpleGetRequest.length));
        runBenchmark("parseComplexPost", iterations,
                () -> HttpRequestParser.parse(complexRequest, complexRequest.length));
        runBenchmark("parseMixedCaseHeaders", iterations,
                () -> HttpRequestParser.parse(mixedCaseHeaderRequest, mixedCaseHeaderRequest.length));
        runBenchmark("parseChunked", iterations, () -> HttpRequestParser.parse(chunkedRequest, chunkedRequest.length));
        runBenchmark("simdFindCRLF", iterations,
                () -> SIMDByteScanner.findCRLF(complexRequest, 0, complexRequest.length));
        runBenchmark("simdFindDoubleCRLF", iterations,
                () -> SIMDByteScanner.findDoubleCRLF(complexRequest, 0, complexRequest.length));
        runBenchmark("simdBytesEqual", iterations, () -> SIMDByteScanner.bytesEqual(simpleGetRequest, 0, getPattern));
        runBenchmark("simdFindBoundaryLine", iterations,
                () -> SIMDByteScanner.findBoundaryLine(
                        multipartBoundaryPayload,
                        0,
                        multipartBoundaryPayload.length,
                        multipartBoundaryDelimiter));
        runBenchmark("simdFindBoundaryLineNoisy", iterations,
                () -> SIMDByteScanner.findBoundaryLine(
                        multipartBoundaryNoisyPayload,
                        0,
                        multipartBoundaryNoisyPayload.length,
                        multipartBoundaryDelimiter));
    }

    private static void runBenchmark(String name, int iterations, ThrowingSupplier<Object> action) {
        for (int warmup = 0; warmup < WARMUP_ROUNDS; warmup++) {
            for (int index = 0; index < iterations; index++) {
                consume(action.get());
            }
        }

        long startNanos = System.nanoTime();
        for (int index = 0; index < iterations; index++) {
            consume(action.get());
        }
        long elapsedNanos = System.nanoTime() - startNanos;

        double avgMicros = (elapsedNanos / 1000.0) / iterations;
        double opsPerSecond = iterations * (1_000_000_000.0 / elapsedNanos);

        System.out.println(String.format(
                Locale.ROOT,
                "com.fastjava.bench.HttpParserMicroBench.%s avg_us=%.3f ops_s=%.0f iterations=%d",
                name,
                avgMicros,
                opsPerSecond,
                iterations));
    }

    private static void consume(Object value) {
        if (value == null) {
            return;
        }
        if (value instanceof Boolean boolValue && boolValue) {
            // no-op branch to keep the boolean result observable
        }
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get();
    }
}
