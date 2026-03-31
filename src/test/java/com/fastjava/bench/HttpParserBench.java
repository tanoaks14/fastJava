package com.fastjava.bench;

import com.fastjava.http.simd.SIMDByteScanner;
import com.fastjava.http.parser.HttpRequestParser;
import org.openjdk.jmh.annotations.*;
import java.util.concurrent.TimeUnit;

/**
 * JMH benchmarks for HTTP parsing performance.
 * 
 * Run with:
 * mvn clean package
 * java -jar target/benchmarks.jar com.fastjava.bench.HttpParserBench -f 3
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Thread)
@Fork(1)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 2, timeUnit = TimeUnit.SECONDS)
public class HttpParserBench {

    private byte[] simpleGetRequest;
    private byte[] complexRequest;
    private byte[] chunkedRequest;
    private byte[] getPattern;
    private byte[] multipartBoundaryPayload;
    private byte[] multipartBoundaryNoisyPayload;
    private byte[] multipartBoundaryDelimiter;
    private byte[] pathBytes;
    private String rangeExpression;
    private byte[] rangeExpressionBytes;

    @Setup
    public void setup() {
        simpleGetRequest = ("GET / HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "User-Agent: test\r\n" +
                "Accept: */*\r\n" +
                "\r\n").getBytes();

        complexRequest = ("POST /api/users/123 HTTP/1.1\r\n" +
                "Host: localhost:8080\r\n" +
                "Content-Type: application/json\r\n" +
                "User-Agent: Mozilla/5.0\r\n" +
                "Accept: application/json\r\n" +
                "Accept-Language: en-US,en;q=0.9\r\n" +
                "Accept-Encoding: gzip, deflate\r\n" +
                "Connection: keep-alive\r\n" +
                "Content-Length: 50\r\n" +
                "\r\n" +
                "{\"name\":\"test\",\"email\":\"test@example.com\"}").getBytes();

        chunkedRequest = ("POST /upload HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Transfer-Encoding: chunked\r\n" +
                "\r\n" +
                "5\r\n" +
                "hello\r\n" +
                "6\r\n" +
                " world\r\n" +
                "0\r\n" +
                "\r\n").getBytes();

        getPattern = "GET".getBytes();

        multipartBoundaryPayload = ("--bench-boundary\r\n" +
                "Content-Disposition: form-data; name=\"field\"\r\n" +
                "\r\n" +
                "value\r\n" +
                "--bench-boundary--\r\n").getBytes();

        String noisy = "-a--b---c----d-----e------f".repeat(100);
        multipartBoundaryNoisyPayload = ("--bench-boundary\r\n" +
                "Content-Disposition: form-data; name=\"field\"\r\n" +
                "\r\n" +
                noisy + "\r\n" +
                "--bench-boundary--\r\n").getBytes();

        multipartBoundaryDelimiter = "--bench-boundary".getBytes();

        pathBytes = ("assets\\images\\2026\\03\\banner\\hero\\main\\content\\index.html"
                + "?v=42&lang=en").getBytes();
        rangeExpression = "0-99,120-220,300-410,450-510,700-850,900-1024,1200-1300";
        rangeExpressionBytes = rangeExpression.getBytes();
    }

    @Benchmark
    public int benchmarkSimpleGetParse() {
        var parsed = HttpRequestParser.parse(simpleGetRequest, simpleGetRequest.length);
        return parsed != null ? 1 : 0;
    }

    @Benchmark
    public int benchmarkComplexPostParse() {
        var parsed = HttpRequestParser.parse(complexRequest, complexRequest.length);
        return parsed != null ? 1 : 0;
    }

    @Benchmark
    public int benchmarkFindCRLF() {
        return SIMDByteScanner.findCRLF(complexRequest, 0, complexRequest.length);
    }

    @Benchmark
    public int benchmarkFindDoubleCRLF() {
        return SIMDByteScanner.findDoubleCRLF(complexRequest, 0, complexRequest.length);
    }

    @Benchmark
    public int benchmarkBytesEqual() {
        return SIMDByteScanner.bytesEqual(simpleGetRequest, 0, getPattern) ? 1 : 0;
    }

    @Benchmark
    public int benchmarkChunkedParse() {
        var parsed = HttpRequestParser.parse(chunkedRequest, chunkedRequest.length);
        return parsed != null ? parsed.bodyLength : -1;
    }

    @Benchmark
    public int benchmarkFindBoundaryLine() {
        return SIMDByteScanner.findBoundaryLine(
                multipartBoundaryPayload,
                0,
                multipartBoundaryPayload.length,
                multipartBoundaryDelimiter);
    }

    @Benchmark
    public int benchmarkFindBoundaryLineNoisyPayload() {
        return SIMDByteScanner.findBoundaryLine(
                multipartBoundaryNoisyPayload,
                0,
                multipartBoundaryNoisyPayload.length,
                multipartBoundaryDelimiter);
    }

    @Benchmark
    public int benchmarkPathSeparatorNormalizeScalar() {
        byte[] copy = pathBytes.clone();
        for (int i = 0; i < copy.length; i++) {
            if (copy[i] == '\\') {
                copy[i] = '/';
            }
        }
        return copy[0] + copy[copy.length - 1];
    }

    @Benchmark
    public int benchmarkPathSeparatorNormalizeSimd() {
        byte[] copy = SIMDByteScanner.replaceByte(pathBytes, (byte) '\\', (byte) '/');
        return copy[0] + copy[copy.length - 1];
    }

    @Benchmark
    public int benchmarkRangeCommaSplitScalar() {
        int count = 1;
        int start = 0;
        while (start < rangeExpression.length()) {
            int comma = rangeExpression.indexOf(',', start);
            if (comma < 0) {
                break;
            }
            count++;
            start = comma + 1;
        }
        return count;
    }

    @Benchmark
    public int benchmarkRangeCommaSplitSimd() {
        int[] commas = SIMDByteScanner.findAllOccurrences(
                rangeExpressionBytes,
                (byte) ',',
                0,
                rangeExpressionBytes.length);
        return commas.length + 1;
    }

    @Benchmark
    public int benchmarkRangeDashIndexScalar() {
        int sum = 0;
        int tokenStart = 0;
        while (tokenStart < rangeExpression.length()) {
            int tokenEnd = rangeExpression.indexOf(',', tokenStart);
            if (tokenEnd < 0) {
                tokenEnd = rangeExpression.length();
            }
            String token = rangeExpression.substring(tokenStart, tokenEnd);
            sum += token.indexOf('-');
            tokenStart = tokenEnd + 1;
        }
        return sum;
    }

    @Benchmark
    public int benchmarkRangeDashIndexSimd() {
        int sum = 0;
        int tokenStart = 0;
        while (tokenStart < rangeExpressionBytes.length) {
            int comma = SIMDByteScanner.indexOfByte(rangeExpressionBytes, tokenStart, rangeExpressionBytes.length,
                    (byte) ',');
            int tokenEnd = comma < 0 ? rangeExpressionBytes.length : comma;
            int dash = SIMDByteScanner.indexOfByte(rangeExpressionBytes, tokenStart, tokenEnd, (byte) '-');
            sum += dash - tokenStart;
            tokenStart = tokenEnd + 1;
        }
        return sum;
    }

    public static void main(String[] args) throws Exception {
        org.openjdk.jmh.Main.main(args);
    }
}
