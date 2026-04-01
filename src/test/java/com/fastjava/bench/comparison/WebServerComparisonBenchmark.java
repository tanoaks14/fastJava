package com.fastjava.bench.comparison;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

import org.apache.catalina.Context;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.startup.Tomcat;

import com.fastjava.server.FastJavaNioServer;
import com.fastjava.server.RequestLimits;
import com.fastjava.servlet.HttpServlet;
import com.fastjava.servlet.HttpServletRequest;
import com.fastjava.servlet.HttpServletResponse;
import com.fastjava.servlet.ServletException;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpVersion;
import io.undertow.Undertow;
import io.undertow.util.Headers;

public final class WebServerComparisonBenchmark {

    private static final String SINGLE_SERVER_RESULT_PREFIX = "SINGLE_RESULT_JSON=";
    private static final byte[] RESPONSE_BYTES = "ok".getBytes(StandardCharsets.UTF_8);
    private static final int WARMUP_REQUESTS = envInt("FASTJAVA_BENCH_WARMUP_REQUESTS", 10_000);
    private static final int BENCHMARK_REQUESTS = envInt("FASTJAVA_BENCH_BENCHMARK_REQUESTS", 50_000);
    private static final int CONCURRENCY = envInt("FASTJAVA_BENCH_CONCURRENCY", 32);
    private static final int ROUNDS = envInt("FASTJAVA_BENCH_ROUNDS", 5);
    private static final List<String> SERVER_NAMES = List.of("FastJava", "Undertow", "Tomcat", "Netty");

    private WebServerComparisonBenchmark() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length >= 2 && "--single-server".equals(args[0])) {
            BenchmarkResult result = runSingleServer(args[1]);
            System.out.println(SINGLE_SERVER_RESULT_PREFIX + singleResultJson(result));
            return;
        }

        System.out.println("=== Basic Web Server Comparison (GET /hello) ===");
        System.out.printf(Locale.ROOT,
                "Warmup requests=%d, benchmark requests=%d, concurrency=%d, rounds=%d%n",
                WARMUP_REQUESTS,
                BENCHMARK_REQUESTS,
                CONCURRENCY,
                ROUNDS);
        System.out.println("Execution model: isolated JVM per server, rotated order per round.");

        List<RunSample> rawSamples = new ArrayList<>();
        java.util.Map<String, List<BenchmarkResult>> byServer = new java.util.LinkedHashMap<>();
        for (String serverName : SERVER_NAMES) {
            byServer.put(serverName, new ArrayList<>());
        }

        for (int round = 1; round <= ROUNDS; round++) {
            List<String> order = new ArrayList<>(SERVER_NAMES);
            // Rotate deterministic order so each server appears in each slot over
            // consecutive rounds. This reduces thermal/CPU-state bias from always
            // running one server last.
            int offset = (round - 1) % SERVER_NAMES.size();
            java.util.Collections.rotate(order, -offset);
            System.out.printf(Locale.ROOT, "%nRound %d order: %s%n", round, String.join(", ", order));

            for (String serverName : order) {
                BenchmarkResult result = runServerInIsolatedProcess(serverName);
                byServer.get(serverName).add(result);
                rawSamples.add(new RunSample(round, serverName, result));
                System.out.printf(Locale.ROOT,
                        "  %s: throughput=%.2f req/s, avg=%.3f ms, p95=%.3f ms, p99=%.3f ms, errors=%d%n",
                        serverName,
                        result.throughputRequestsPerSecond,
                        result.avgLatencyMillis,
                        result.p95LatencyMillis,
                        result.p99LatencyMillis,
                        result.errors);
            }
        }

        List<BenchmarkResult> aggregateResults = new ArrayList<>();
        for (String serverName : SERVER_NAMES) {
            aggregateResults.add(BenchmarkResult.aggregate(serverName, byServer.get(serverName)));
        }

        aggregateResults
                .sort(Comparator.comparingDouble((BenchmarkResult r) -> r.throughputRequestsPerSecond).reversed());
        BenchmarkResult winner = aggregateResults.get(0);
        writeResults(aggregateResults, rawSamples, winner);

        System.out.println("\nResults written to benchmarks/webserver-comparison/results/latest-results.md");
        System.out.println("Results written to benchmarks/webserver-comparison/results/latest-results.json");
    }

    private static BenchmarkResult runSingleServer(String serverName) throws Exception {
        BenchServer server = createServer(serverName);
        System.out.printf(Locale.ROOT, "Starting %s in isolated JVM...%n", server.name());
        server.start();
        String url = "http://127.0.0.1:" + server.port() + "/hello";
        try {
            runLoad(url, WARMUP_REQUESTS, CONCURRENCY);
            return runLoad(url, BENCHMARK_REQUESTS, CONCURRENCY).withServer(server.name());
        } finally {
            server.close();
        }
    }

    private static BenchServer createServer(String serverName) {
        return switch (serverName) {
            case "FastJava" ->
                new FastJavaAdapter();
            case "Undertow" ->
                new UndertowAdapter();
            case "Tomcat" ->
                new TomcatAdapter();
            case "Netty" ->
                new NettyAdapter();
            default ->
                throw new IllegalArgumentException("Unknown server: " + serverName);
        };
    }

    private static BenchmarkResult runServerInIsolatedProcess(String serverName) throws Exception {
        String javaBin = Path.of(System.getProperty("java.home"), "bin", "java.exe").toString();
        String classpath = System.getProperty("java.class.path");
        ProcessBuilder processBuilder = new ProcessBuilder(
                javaBin,
                "--add-modules",
                "jdk.incubator.vector",
                "-cp",
                classpath,
                WebServerComparisonBenchmark.class.getName(),
                "--single-server",
                serverName);
        processBuilder.redirectErrorStream(true);

        Process process = processBuilder.start();
        List<String> lines = new ArrayList<>();
        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IllegalStateException("Benchmark subprocess failed for " + serverName + " with exit code "
                    + exitCode + "\n" + String.join("\n", lines));
        }

        for (String line : lines) {
            if (line.startsWith(SINGLE_SERVER_RESULT_PREFIX)) {
                return parseSingleResultJson(line.substring(SINGLE_SERVER_RESULT_PREFIX.length()));
            }
        }
        throw new IllegalStateException("Missing single-server result output for " + serverName + "\n"
                + String.join("\n", lines));
    }

    private static BenchmarkResult runLoad(String url, int totalRequests, int concurrency) throws Exception {
        ExecutorService httpClientExecutor = Executors.newFixedThreadPool(Math.max(4, concurrency));
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .version(HttpClient.Version.HTTP_1_1)
                .executor(httpClientExecutor)
                .build();

        AtomicInteger remaining = new AtomicInteger(totalRequests);
        LongAdder success = new LongAdder();
        LongAdder errors = new LongAdder();
        LongAdder latencyNanosTotal = new LongAdder();
        AtomicInteger latencyIndex = new AtomicInteger();
        long[] latencies = new long[totalRequests];

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .timeout(Duration.ofSeconds(5))
                .build();

        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        CountDownLatch latch = new CountDownLatch(concurrency);
        Instant started = Instant.now();

        for (int t = 0; t < concurrency; t++) {
            executor.submit(() -> {
                try {
                    while (remaining.getAndDecrement() > 0) {
                        long startNanos = System.nanoTime();
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
                        } finally {
                            long latency = System.nanoTime() - startNanos;
                            latencyNanosTotal.add(latency);
                            int index = latencyIndex.getAndIncrement();
                            if (index < latencies.length) {
                                latencies[index] = latency;
                            }
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
        httpClientExecutor.shutdown();
        httpClientExecutor.awaitTermination(30, TimeUnit.SECONDS);

        long elapsedNanos = Duration.between(started, Instant.now()).toNanos();
        long completed = success.sum() + errors.sum();
        int latencyCount = (int) Math.min(completed, latencies.length);
        Arrays.sort(latencies, 0, latencyCount);

        return new BenchmarkResult(
                "",
                totalRequests,
                success.sum(),
                errors.sum(),
                completed == 0 ? 0.0 : (completed * 1_000_000_000.0 / elapsedNanos),
                completed == 0 ? 0.0 : (latencyNanosTotal.sum() / 1_000_000.0 / completed),
                percentileMillis(latencies, latencyCount, 0.95),
                percentileMillis(latencies, latencyCount, 0.99));
    }

    private static double percentileMillis(long[] latencies, int size, double percentile) {
        if (size <= 0) {
            return 0.0;
        }
        int index = Math.max(0, (int) Math.ceil(percentile * size) - 1);
        if (index >= size) {
            index = size - 1;
        }
        return latencies[index] / 1_000_000.0;
    }

    private static void writeResults(List<BenchmarkResult> results, List<RunSample> rawSamples, BenchmarkResult winner)
            throws IOException {
        Path resultsDir = Path.of("benchmarks", "webserver-comparison", "results");
        Files.createDirectories(resultsDir);

        StringBuilder markdown = new StringBuilder();
        markdown.append("# Web Server Comparison Results\n\n");
        markdown.append("- Scenario: `GET /hello`\n");
        markdown.append("- Warmup requests: `").append(WARMUP_REQUESTS).append("`\n");
        markdown.append("- Benchmark requests: `").append(BENCHMARK_REQUESTS).append("`\n");
        markdown.append("- Concurrency: `").append(CONCURRENCY).append("`\n");
        markdown.append("- Rounds: `").append(ROUNDS).append("`\n");
        markdown.append("- Execution: `isolated JVM per server`\n");
        markdown.append("- Timestamp: `").append(Instant.now()).append("`\n\n");

        markdown.append("## Aggregate (median across rounds)\n\n");
        markdown.append("| Server | Throughput (req/s) | Avg Latency (ms) | p95 (ms) | p99 (ms) | Errors |\n");
        markdown.append("|---|---:|---:|---:|---:|---:|\n");
        for (BenchmarkResult result : results) {
            markdown.append("| ").append(result.server)
                    .append(" | ").append(String.format(Locale.ROOT, "%.2f", result.throughputRequestsPerSecond))
                    .append(" | ").append(String.format(Locale.ROOT, "%.3f", result.avgLatencyMillis))
                    .append(" | ").append(String.format(Locale.ROOT, "%.3f", result.p95LatencyMillis))
                    .append(" | ").append(String.format(Locale.ROOT, "%.3f", result.p99LatencyMillis))
                    .append(" | ").append(result.errors)
                    .append(" |\n");
        }

        markdown.append("\nWinner by throughput median: **").append(winner.server).append("**\n\n");

        markdown.append("## Per-run samples\n\n");
        markdown.append("| Round | Server | Throughput (req/s) | Avg Latency (ms) | p95 (ms) | p99 (ms) | Errors |\n");
        markdown.append("|---:|---|---:|---:|---:|---:|---:|\n");
        for (RunSample sample : rawSamples) {
            BenchmarkResult result = sample.result;
            markdown.append("| ").append(sample.round)
                    .append(" | ").append(sample.server)
                    .append(" | ").append(String.format(Locale.ROOT, "%.2f", result.throughputRequestsPerSecond))
                    .append(" | ").append(String.format(Locale.ROOT, "%.3f", result.avgLatencyMillis))
                    .append(" | ").append(String.format(Locale.ROOT, "%.3f", result.p95LatencyMillis))
                    .append(" | ").append(String.format(Locale.ROOT, "%.3f", result.p99LatencyMillis))
                    .append(" | ").append(result.errors)
                    .append(" |\n");
        }

        Files.writeString(resultsDir.resolve("latest-results.md"), markdown.toString(), StandardCharsets.UTF_8);
        Files.writeString(resultsDir.resolve("latest-results.json"), toJson(results, rawSamples),
                StandardCharsets.UTF_8);
    }

    private static String toJson(List<BenchmarkResult> results, List<RunSample> rawSamples) {
        StringBuilder json = new StringBuilder();
        json.append("{\n")
                .append("  \"warmupRequests\": ").append(WARMUP_REQUESTS).append(",\n")
                .append("  \"benchmarkRequests\": ").append(BENCHMARK_REQUESTS).append(",\n")
                .append("  \"concurrency\": ").append(CONCURRENCY).append(",\n")
                .append("  \"rounds\": ").append(ROUNDS).append(",\n")
                .append("  \"executionModel\": \"isolated-jvm\",\n")
                .append("  \"aggregate\": [\n");
        for (int i = 0; i < results.size(); i++) {
            BenchmarkResult result = results.get(i);
            json.append("    {\n")
                    .append("      \"server\": \"").append(result.server).append("\",\n")
                    .append("      \"requests\": ").append(result.requests).append(",\n")
                    .append("      \"success\": ").append(result.success).append(",\n")
                    .append("      \"errors\": ").append(result.errors).append(",\n")
                    .append("      \"throughputReqPerSec\": ")
                    .append(String.format(Locale.ROOT, "%.6f", result.throughputRequestsPerSecond)).append(",\n")
                    .append("      \"avgLatencyMs\": ")
                    .append(String.format(Locale.ROOT, "%.6f", result.avgLatencyMillis)).append(",\n")
                    .append("      \"p95LatencyMs\": ")
                    .append(String.format(Locale.ROOT, "%.6f", result.p95LatencyMillis)).append(",\n")
                    .append("      \"p99LatencyMs\": ")
                    .append(String.format(Locale.ROOT, "%.6f", result.p99LatencyMillis)).append("\n")
                    .append("    }");
            if (i < results.size() - 1) {
                json.append(',');
            }
            json.append("\n");
        }
        json.append("  ],\n")
                .append("  \"samples\": [\n");
        for (int i = 0; i < rawSamples.size(); i++) {
            RunSample sample = rawSamples.get(i);
            BenchmarkResult result = sample.result;
            json.append("    {\n")
                    .append("      \"round\": ").append(sample.round).append(",\n")
                    .append("      \"server\": \"").append(sample.server).append("\",\n")
                    .append("      \"throughputReqPerSec\": ")
                    .append(String.format(Locale.ROOT, "%.6f", result.throughputRequestsPerSecond)).append(",\n")
                    .append("      \"avgLatencyMs\": ")
                    .append(String.format(Locale.ROOT, "%.6f", result.avgLatencyMillis)).append(",\n")
                    .append("      \"p95LatencyMs\": ")
                    .append(String.format(Locale.ROOT, "%.6f", result.p95LatencyMillis)).append(",\n")
                    .append("      \"p99LatencyMs\": ")
                    .append(String.format(Locale.ROOT, "%.6f", result.p99LatencyMillis)).append(",\n")
                    .append("      \"errors\": ").append(result.errors).append("\n")
                    .append("    }");
            if (i < rawSamples.size() - 1) {
                json.append(',');
            }
            json.append("\n");
        }
        json.append("  ]\n}\n");
        return json.toString();
    }

    private static String singleResultJson(BenchmarkResult result) {
        return "{"
                + "\"server\":\"" + result.server + "\","
                + "\"requests\":" + result.requests + ","
                + "\"success\":" + result.success + ","
                + "\"errors\":" + result.errors + ","
                + "\"throughputReqPerSec\":" + String.format(Locale.ROOT, "%.9f", result.throughputRequestsPerSecond)
                + ","
                + "\"avgLatencyMs\":" + String.format(Locale.ROOT, "%.9f", result.avgLatencyMillis) + ","
                + "\"p95LatencyMs\":" + String.format(Locale.ROOT, "%.9f", result.p95LatencyMillis) + ","
                + "\"p99LatencyMs\":" + String.format(Locale.ROOT, "%.9f", result.p99LatencyMillis)
                + "}";
    }

    private static BenchmarkResult parseSingleResultJson(String json) {
        String server = stringField(json, "server");
        int requests = intField(json, "requests");
        long success = longField(json, "success");
        long errors = longField(json, "errors");
        double throughput = doubleField(json, "throughputReqPerSec");
        double avgLatency = doubleField(json, "avgLatencyMs");
        double p95 = doubleField(json, "p95LatencyMs");
        double p99 = doubleField(json, "p99LatencyMs");
        return new BenchmarkResult(server, requests, success, errors, throughput, avgLatency, p95, p99);
    }

    private static String stringField(String json, String name) {
        String marker = "\"" + name + "\":\"";
        int start = json.indexOf(marker);
        if (start < 0) {
            throw new IllegalArgumentException("Missing field: " + name);
        }
        start += marker.length();
        int end = json.indexOf('"', start);
        if (end < 0) {
            throw new IllegalArgumentException("Malformed field: " + name);
        }
        return json.substring(start, end);
    }

    private static int intField(String json, String name) {
        return (int) longField(json, name);
    }

    private static long longField(String json, String name) {
        String marker = "\"" + name + "\":";
        int start = json.indexOf(marker);
        if (start < 0) {
            throw new IllegalArgumentException("Missing field: " + name);
        }
        start += marker.length();
        int end = valueEnd(json, start);
        return Long.parseLong(json.substring(start, end));
    }

    private static double doubleField(String json, String name) {
        String marker = "\"" + name + "\":";
        int start = json.indexOf(marker);
        if (start < 0) {
            throw new IllegalArgumentException("Missing field: " + name);
        }
        start += marker.length();
        int end = valueEnd(json, start);
        return Double.parseDouble(json.substring(start, end));
    }

    private static int valueEnd(String json, int start) {
        int end = start;
        while (end < json.length()) {
            char ch = json.charAt(end);
            if (ch == ',' || ch == '}') {
                break;
            }
            end++;
        }
        return end;
    }

    private static int envInt(String key, int defaultValue) {
        String raw = System.getenv(key);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private interface BenchServer extends AutoCloseable {

        String name();

        void start() throws Exception;

        int port();

        @Override
        void close() throws Exception;
    }

    private static final class FastJavaAdapter implements BenchServer {

        private FastJavaNioServer server;

        @Override
        public String name() {
            return "FastJava";
        }

        @Override
        public void start() throws Exception {
            int workerThreads = Math.max(8, CONCURRENCY);
            server = new FastJavaNioServer(0, RequestLimits.defaults(64 * 1024), workerThreads);
            server.addStaticPlainTextRoute("/hello", "ok");
            server.addServlet("/hello", new HttpServlet() {
                @Override
                protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException {
                    response.setStatus(200);
                    response.setContentType("text/plain");
                    response.getWriter().write("ok");
                }
            });
            server.start();
        }

        @Override
        public int port() {
            return server.getBoundPort();
        }

        @Override
        public void close() {
            if (server != null) {
                server.stop();
            }
        }
    }

    private static final class UndertowAdapter implements BenchServer {

        private Undertow server;
        private int port;

        @Override
        public String name() {
            return "Undertow";
        }

        @Override
        public void start() {
            server = Undertow.builder()
                    .addHttpListener(0, "127.0.0.1")
                    .setHandler(exchange -> {
                        if ("/hello".equals(exchange.getRequestPath())) {
                            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "text/plain");
                            exchange.getResponseSender().send("ok");
                        } else {
                            exchange.setStatusCode(404);
                            exchange.endExchange();
                        }
                    })
                    .build();
            server.start();
            port = ((InetSocketAddress) server.getListenerInfo().get(0).getAddress()).getPort();
        }

        @Override
        public int port() {
            return port;
        }

        @Override
        public void close() {
            if (server != null) {
                server.stop();
            }
        }
    }

    private static final class TomcatAdapter implements BenchServer {

        private Tomcat tomcat;
        private Path baseDir;
        private int port;

        @Override
        public String name() {
            return "Tomcat";
        }

        @Override
        public void start() throws Exception {
            baseDir = Files.createTempDirectory("fastjava-tomcat-bench-");
            tomcat = new Tomcat();
            tomcat.setBaseDir(baseDir.toString());
            Connector connector = new Connector();
            connector.setPort(0);
            tomcat.setConnector(connector);

            Context context = tomcat.addContext("", baseDir.toString());
            Tomcat.addServlet(context, "hello", new javax.servlet.http.HttpServlet() {
                @Override
                protected void doGet(javax.servlet.http.HttpServletRequest req,
                        javax.servlet.http.HttpServletResponse resp) throws IOException {
                    resp.setStatus(200);
                    resp.setContentType("text/plain");
                    resp.getWriter().write("ok");
                }
            });
            context.addServletMappingDecoded("/hello", "hello");
            tomcat.start();
            port = tomcat.getConnector().getLocalPort();
        }

        @Override
        public int port() {
            return port;
        }

        @Override
        public void close() throws Exception {
            if (tomcat != null) {
                tomcat.stop();
                tomcat.destroy();
            }
            if (baseDir != null) {
                try {
                    Files.walk(baseDir)
                            .sorted(Comparator.reverseOrder())
                            .forEach(path -> {
                                try {
                                    Files.deleteIfExists(path);
                                } catch (IOException ignored) {
                                }
                            });
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static final class NettyAdapter implements BenchServer {

        private EventLoopGroup bossGroup;
        private EventLoopGroup workerGroup;
        private Channel channel;
        private int port;

        @Override
        public String name() {
            return "Netty";
        }

        @Override
        public void start() throws Exception {
            bossGroup = new NioEventLoopGroup(1);
            workerGroup = new NioEventLoopGroup();

            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ch.pipeline().addLast(new HttpServerCodec());
                            ch.pipeline().addLast(new HttpObjectAggregator(16 * 1024));
                            ch.pipeline().addLast(new SimpleChannelInboundHandler<FullHttpRequest>() {
                                @Override
                                protected void channelRead0(io.netty.channel.ChannelHandlerContext ctx,
                                        FullHttpRequest request) {
                                    if ("/hello".equals(request.uri())) {
                                        FullHttpResponse response = new DefaultFullHttpResponse(
                                                HttpVersion.HTTP_1_1,
                                                HttpResponseStatus.OK,
                                                Unpooled.wrappedBuffer(RESPONSE_BYTES));
                                        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain");
                                        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH,
                                                RESPONSE_BYTES.length);
                                        ctx.writeAndFlush(response);
                                    } else {
                                        FullHttpResponse notFound = new DefaultFullHttpResponse(
                                                HttpVersion.HTTP_1_1,
                                                HttpResponseStatus.NOT_FOUND,
                                                Unpooled.EMPTY_BUFFER);
                                        notFound.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, 0);
                                        ctx.writeAndFlush(notFound);
                                    }
                                }
                            });
                        }
                    });

            ChannelFuture bindFuture = bootstrap.bind(0).sync();
            channel = bindFuture.channel();
            port = ((InetSocketAddress) channel.localAddress()).getPort();
        }

        @Override
        public int port() {
            return port;
        }

        @Override
        public void close() throws Exception {
            if (channel != null) {
                channel.close().sync();
            }
            if (workerGroup != null) {
                workerGroup.shutdownGracefully().sync();
            }
            if (bossGroup != null) {
                bossGroup.shutdownGracefully().sync();
            }
        }
    }

    private static final class BenchmarkResult {

        private final String server;
        private final int requests;
        private final long success;
        private final long errors;
        private final double throughputRequestsPerSecond;
        private final double avgLatencyMillis;
        private final double p95LatencyMillis;
        private final double p99LatencyMillis;

        private BenchmarkResult(
                String server,
                int requests,
                long success,
                long errors,
                double throughputRequestsPerSecond,
                double avgLatencyMillis,
                double p95LatencyMillis,
                double p99LatencyMillis) {
            this.server = server;
            this.requests = requests;
            this.success = success;
            this.errors = errors;
            this.throughputRequestsPerSecond = throughputRequestsPerSecond;
            this.avgLatencyMillis = avgLatencyMillis;
            this.p95LatencyMillis = p95LatencyMillis;
            this.p99LatencyMillis = p99LatencyMillis;
        }

        private BenchmarkResult withServer(String serverName) {
            return new BenchmarkResult(
                    serverName,
                    requests,
                    success,
                    errors,
                    throughputRequestsPerSecond,
                    avgLatencyMillis,
                    p95LatencyMillis,
                    p99LatencyMillis);
        }

        private static BenchmarkResult aggregate(String serverName, List<BenchmarkResult> samples) {
            if (samples == null || samples.isEmpty()) {
                return new BenchmarkResult(serverName, 0, 0, 0, 0, 0, 0, 0);
            }
            List<Double> throughput = new ArrayList<>(samples.size());
            List<Double> avg = new ArrayList<>(samples.size());
            List<Double> p95 = new ArrayList<>(samples.size());
            List<Double> p99 = new ArrayList<>(samples.size());
            long totalErrors = 0;
            long totalSuccess = 0;
            int requests = samples.get(0).requests;
            for (BenchmarkResult sample : samples) {
                throughput.add(sample.throughputRequestsPerSecond);
                avg.add(sample.avgLatencyMillis);
                p95.add(sample.p95LatencyMillis);
                p99.add(sample.p99LatencyMillis);
                totalErrors += sample.errors;
                totalSuccess += sample.success;
            }
            return new BenchmarkResult(
                    serverName,
                    requests,
                    totalSuccess / samples.size(),
                    totalErrors,
                    median(throughput),
                    median(avg),
                    median(p95),
                    median(p99));
        }

        private static double median(List<Double> values) {
            if (values.isEmpty()) {
                return 0.0;
            }
            values.sort(Double::compareTo);
            int middle = values.size() / 2;
            if ((values.size() & 1) == 1) {
                return values.get(middle);
            }
            return (values.get(middle - 1) + values.get(middle)) / 2.0;
        }
    }

    private static final class RunSample {

        private final int round;
        private final String server;
        private final BenchmarkResult result;

        private RunSample(int round, String server, BenchmarkResult result) {
            this.round = round;
            this.server = server;
            this.result = result;
        }
    }
}
