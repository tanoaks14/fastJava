package com.fastjava.http.h2;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * HTTP/2 Performance Benchmarks - baseline for SIMD optimization analysis.
 * Run with: mvn test -Dtest=Http2PerformanceBenchmark
 */
public class Http2PerformanceBenchmark {

    public static void main(String[] args) throws IOException {
        new Http2PerformanceBenchmark().runAllBenchmarks();
    }

    private void runAllBenchmarks() throws IOException {
        System.out.println("=== HTTP/2 Performance Baseline Benchmarks ===\n");

        benchmarkHpackEncoding();
        benchmarkHpackDecoding();
        benchmarkFrameEncoding();
        benchmarkFrameDecoding();
        benchmarkHeaderValidation();
    }

    private void benchmarkHpackEncoding() throws IOException {
        System.out.println("1. HPACK Header Encoding Benchmark");
        System.out.println("-----------------------------------");

        HpackCodec codec = new HpackCodec(32_768, 4096);
        Map<String, String> headers = createTypicalHeaders();

        long warmupStart = System.nanoTime();
        for (int i = 0; i < 1000; i++) {
            codec.encode(headers);
        }
        long warmup = (System.nanoTime() - warmupStart) / 1_000_000;
        System.out.println("Warmup (1000 iterations): " + warmup + "ms");

        int iterations = 10_000;
        long benchmarkStart = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            codec.encode(headers);
        }
        long benchmarkTime = System.nanoTime() - benchmarkStart;
        long throughput = (iterations * 1_000_000_000L) / benchmarkTime;
        double avgTime = (double) benchmarkTime / iterations / 1000;

        System.out.printf("Benchmark (%d iterations):%n", iterations);
        System.out.printf("  Total time: %.2f ms%n", benchmarkTime / 1_000_000.0);
        System.out.printf("  Avg per encoding: %.2f µs%n", avgTime);
        System.out.printf("  Throughput: %d encodings/sec%n", throughput);
        System.out.println();
    }

    private void benchmarkHpackDecoding() throws IOException {
        System.out.println("2. HPACK Header Decoding Benchmark");
        System.out.println("-----------------------------------");

        HpackCodec codec = new HpackCodec(32_768, 4096);
        Map<String, String> headers = createTypicalHeaders();
        byte[] encoded = codec.encode(headers);

        long warmupStart = System.nanoTime();
        for (int i = 0; i < 1000; i++) {
            codec.decode(encoded);
        }
        long warmup = (System.nanoTime() - warmupStart) / 1_000_000;
        System.out.println("Warmup (1000 iterations): " + warmup + "ms");

        int iterations = 10_000;
        long benchmarkStart = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            codec.decode(encoded);
        }
        long benchmarkTime = System.nanoTime() - benchmarkStart;
        long throughput = (iterations * 1_000_000_000L) / benchmarkTime;
        double avgTime = (double) benchmarkTime / iterations / 1000;

        System.out.printf("Benchmark (%d iterations):%n", iterations);
        System.out.printf("  Total time: %.2f ms%n", benchmarkTime / 1_000_000.0);
        System.out.printf("  Avg per decoding: %.2f µs%n", avgTime);
        System.out.printf("  Throughput: %d decodings/sec%n", throughput);
        System.out.println();
    }

    private void benchmarkFrameEncoding() {
        System.out.println("3. HTTP/2 Frame Encoding Benchmark");
        System.out.println("-----------------------------------");

        byte[] payload = new byte[16_384];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (i & 0xFF);
        }

        long warmupStart = System.nanoTime();
        for (int i = 0; i < 1000; i++) {
            Http2FrameCodec.encodeFrame(Http2FrameCodec.TYPE_DATA, Http2FrameCodec.FLAG_END_STREAM, 1, payload);
        }
        long warmup = (System.nanoTime() - warmupStart) / 1_000_000;
        System.out.println("Warmup (1000 iterations): " + warmup + "ms");

        int iterations = 10_000;
        long benchmarkStart = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            Http2FrameCodec.encodeFrame(Http2FrameCodec.TYPE_DATA, Http2FrameCodec.FLAG_END_STREAM, 1, payload);
        }
        long benchmarkTime = System.nanoTime() - benchmarkStart;
        double avgTime = (double) benchmarkTime / iterations / 1000;
        long throughput = (iterations * 1_000_000_000L) / benchmarkTime;

        System.out.printf("Benchmark (%d iterations):%n", iterations);
        System.out.printf("  Total time: %.2f ms%n", benchmarkTime / 1_000_000.0);
        System.out.printf("  Avg per frame: %.2f µs%n", avgTime);
        System.out.printf("  Throughput: %d frames/sec%n", throughput);
        System.out.println();
    }

    private void benchmarkFrameDecoding() {
        System.out.println("4. HTTP/2 Frame Decoding Benchmark");
        System.out.println("-----------------------------------");

        byte[] payload = new byte[16_384];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (i & 0xFF);
        }
        byte[] frame = Http2FrameCodec.encodeFrame(Http2FrameCodec.TYPE_DATA, Http2FrameCodec.FLAG_END_STREAM, 1,
                payload);
        byte[] buffer = new byte[frame.length * 2];
        System.arraycopy(frame, 0, buffer, 0, frame.length);

        long warmupStart = System.nanoTime();
        for (int i = 0; i < 1000; i++) {
            Http2FrameCodec.parseFrame(buffer, 0, frame.length, 16_384);
        }
        long warmup = (System.nanoTime() - warmupStart) / 1_000_000;
        System.out.println("Warmup (1000 iterations): " + warmup + "ms");

        int iterations = 10_000;
        long benchmarkStart = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            Http2FrameCodec.parseFrame(buffer, 0, frame.length, 16_384);
        }
        long benchmarkTime = System.nanoTime() - benchmarkStart;
        double avgTime = (double) benchmarkTime / iterations / 1000;
        long throughput = (iterations * 1_000_000_000L) / benchmarkTime;

        System.out.printf("Benchmark (%d iterations):%n", iterations);
        System.out.printf("  Total time: %.2f ms%n", benchmarkTime / 1_000_000.0);
        System.out.printf("  Avg per frame: %.2f µs%n", avgTime);
        System.out.printf("  Throughput: %d frames/sec%n", throughput);
        System.out.println();
    }

    private void benchmarkHeaderValidation() throws IOException {
        System.out.println("5. Header Validation & Normalization Benchmark");
        System.out.println("----------------------------------------------");

        HpackCodec codec = new HpackCodec(32_768, 4096);
        Map<String, String> headers = createTypicalHeaders();
        byte[] encoded = codec.encode(headers);

        long warmupStart = System.nanoTime();
        for (int i = 0; i < 1000; i++) {
            Map<String, String> decoded = codec.decode(encoded);
            // Simulate header validation
            for (String value : decoded.values()) {
                value.toLowerCase();
            }
        }
        long warmup = (System.nanoTime() - warmupStart) / 1_000_000;
        System.out.println("Warmup (1000 iterations): " + warmup + "ms");

        int iterations = 5_000;
        long benchmarkStart = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            Map<String, String> decoded = codec.decode(encoded);
            for (String value : decoded.values()) {
                value.toLowerCase();
            }
        }
        long benchmarkTime = System.nanoTime() - benchmarkStart;
        double avgTime = (double) benchmarkTime / iterations / 1000;
        long throughput = (iterations * 1_000_000_000L) / benchmarkTime;

        System.out.printf("Benchmark (%d iterations):%n", iterations);
        System.out.printf("  Total time: %.2f ms%n", benchmarkTime / 1_000_000.0);
        System.out.printf("  Avg per validation: %.2f µs%n", avgTime);
        System.out.printf("  Throughput: %d validations/sec%n", throughput);
        System.out.println();
    }

    private Map<String, String> createTypicalHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(":method", "GET");
        headers.put(":scheme", "https");
        headers.put(":path", "/api/v1/users/12345/profile?format=json&include=metadata");
        headers.put(":authority", "api.example.com:443");
        headers.put("user-agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
        headers.put("accept", "application/json, text/html, application/xhtml+xml, application/xml;q=0.9");
        headers.put("accept-language", "en-US,en;q=0.9");
        headers.put("accept-encoding", "gzip, deflate, br");
        headers.put("connection", "keep-alive");
        headers.put("cache-control", "no-cache, no-store, must-revalidate");
        headers.put("x-request-id", "12345-67890-abcdef-12345");
        headers.put("x-correlation-id", "request-correlation-12345");
        headers.put("authorization", "Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.");
        headers.put("custom-header-1", "value1");
        headers.put("custom-header-2", "value2");
        return headers;
    }
}
