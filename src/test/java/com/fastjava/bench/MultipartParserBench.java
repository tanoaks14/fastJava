package com.fastjava.bench;

import com.fastjava.http.parser.MultipartFormDataParser;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * JMH benchmarks for end-to-end multipart parser throughput.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
@Fork(1)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 2, timeUnit = TimeUnit.SECONDS)
public class MultipartParserBench {

    @Param({ "1", "8", "32" })
    public int partCount;

    @Param({ "64", "512" })
    public int payloadBytes;

    @Param({ "false", "true" })
    public boolean noisyHyphens;

    private static final String BOUNDARY = "bench-multipart-boundary";
    private static final String CONTENT_TYPE = "multipart/form-data; boundary=" + BOUNDARY;

    private byte[] multipartBody;
    private MultipartFormDataParser.MultipartLimits limits;

    @Setup(Level.Trial)
    public void setup() {
        String payloadSeed = noisyHyphens
                ? "-a--b---c----d-----e------f"
                : "abcdefghijklmnopqrstuvwxyz012345";
        String payload = expandPayload(payloadSeed, payloadBytes);

        StringBuilder body = new StringBuilder(partCount * (payloadBytes + 128));
        for (int index = 0; index < partCount; index++) {
            body.append("--").append(BOUNDARY).append("\r\n")
                    .append("Content-Disposition: form-data; name=\"field").append(index).append("\"\r\n")
                    .append("\r\n")
                    .append(payload)
                    .append("\r\n");
        }
        body.append("--").append(BOUNDARY).append("--\r\n");

        multipartBody = body.toString().getBytes(StandardCharsets.UTF_8);
        limits = new MultipartFormDataParser.MultipartLimits(
                Math.max(1024, multipartBody.length + 1024),
                Math.max(1024, payloadBytes + 256),
                1024 * 1024);
    }

    @Benchmark
    public int benchmarkParseMultipart() {
        MultipartFormDataParser.ParsedMultipart parsed = MultipartFormDataParser.parse(
                multipartBody,
                0,
                multipartBody.length,
                CONTENT_TYPE,
                limits);
        return parsed.valid() ? parsed.parts().size() : -1;
    }

    @Benchmark
    public int benchmarkParseMultipartTextParameters() {
        Map<String, List<String>> values = new LinkedHashMap<>();
        boolean ok = MultipartFormDataParser.parseTextParameters(multipartBody, CONTENT_TYPE, values, false);
        return ok ? values.size() : -1;
    }

    private static String expandPayload(String seed, int targetLength) {
        if (targetLength <= 0) {
            return "";
        }
        StringBuilder builder = new StringBuilder(targetLength);
        while (builder.length() < targetLength) {
            builder.append(seed);
        }
        return builder.substring(0, targetLength);
    }

    public static void main(String[] args) throws Exception {
        org.openjdk.jmh.Main.main(args);
    }
}
