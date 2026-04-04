package com.fastjava.bench.comparison.jfr;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * JFR-aware multi-scenario profiler. Orchestrates JFR recording synchronized
 * with load generation.
 */
public final class JfrMultiScenarioProfiler {

    private static final DateTimeFormatter TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final String DEFAULT_OUTPUT_DIR = "target/profiles/multi-scenario";

    public enum Scenario {
        SIMPLE("simple", "http://127.0.0.1:9876/hello", "Simple GET (1KB response)"),
        DYNAMIC("dynamic", "http://127.0.0.1:9876/api/hello", "Dynamic Servlet (JSON)"),
        STATIC("static", "http://127.0.0.1:9876/static/asset.txt", "Static File (5KB-100KB)");

        final String name;
        final String url;
        final String description;

        Scenario(String name, String url, String description) {
            this.name = name;
            this.url = url;
            this.description = description;
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: java JfrMultiScenarioProfiler <server> <scenario> [options]");
            System.err.println("  server: FastJava, Undertow, Tomcat, Netty, HelidonNima, AvajeNima");
            System.err.println("  scenario: simple, dynamic, static");
            System.err.println("  options:");
            System.err.println("    --warmup-requests=N (default: 10000)");
            System.err.println("    --benchmark-requests=N (default: 100000)");
            System.err.println("    --concurrency=N (default: 32)");
            System.err.println("    --jfr-duration=N (default: 40 seconds)");
            System.err.println("    --output-dir=PATH (default: " + DEFAULT_OUTPUT_DIR + ")");
            System.exit(1);
        }

        String serverName = args[0];
        String scenarioName = args[1];

        // Parse options
        int warmupRequests = 10000;
        int benchmarkRequests = 100000;
        int concurrency = 32;
        int jfrDuration = 40;
        String outputDir = DEFAULT_OUTPUT_DIR;

        for (int i = 2; i < args.length; i++) {
            String arg = args[i];
            if (arg.startsWith("--warmup-requests=")) {
                warmupRequests = Integer.parseInt(arg.substring("--warmup-requests=".length()));
            } else if (arg.startsWith("--benchmark-requests=")) {
                benchmarkRequests = Integer.parseInt(arg.substring("--benchmark-requests=".length()));
            } else if (arg.startsWith("--concurrency=")) {
                concurrency = Integer.parseInt(arg.substring("--concurrency=".length()));
            } else if (arg.startsWith("--jfr-duration=")) {
                jfrDuration = Integer.parseInt(arg.substring("--jfr-duration=".length()));
            } else if (arg.startsWith("--output-dir=")) {
                outputDir = arg.substring("--output-dir=".length());
            }
        }

        Scenario scenario = findScenario(scenarioName);
        if (scenario == null) {
            System.err.println("Unknown scenario: " + scenarioName);
            System.exit(1);
        }

        // Create output directory
        String timestamp = LocalDateTime.now().format(TIMESTAMP);
        Path outputPath = Path.of(outputDir, serverName, scenario.name, timestamp);
        Files.createDirectories(outputPath);

        System.out.println("═══════════════════════════════════════════════════════════════════");
        System.out.println("JFR Multi-Scenario Profile: " + serverName + " / " + scenario.description);
        System.out.println("═══════════════════════════════════════════════════════════════════");
        System.out.println("Warmup requests: " + warmupRequests);
        System.out.println("Benchmark requests: " + benchmarkRequests);
        System.out.println("Concurrency: " + concurrency);
        System.out.println("JFR Duration: " + jfrDuration + "s");
        System.out.println("Output directory: " + outputPath.toAbsolutePath());
        System.out.println();

        try {
            // Start server in subprocess
            System.out.println("Starting " + serverName + " server...");
            ProcessBuilder pb = new ProcessBuilder(
                    getJavaExe(),
                    "--add-modules", "jdk.incubator.vector",
                    "-Dcom.sun.management.jmxremote",
                    "-Dcom.sun.management.jmxremote.port=9010",
                    "-Dcom.sun.management.jmxremote.authenticate=false",
                    "-Dcom.sun.management.jmxremote.ssl=false",
                    "-cp",
                    System.getProperty("java.class.path"),
                    "com.fastjava.bench.comparison.WebServerComparisonBenchmark",
                    "--single-server",
                    serverName);
            pb.redirectErrorStream(true);
            Process serverProc = pb.start();

            // Give server time to start
            Thread.sleep(3000);

            if (!serverProc.isAlive()) {
                System.err.println("Server failed to start");
                System.exit(1);
            }

            long serverPid = getProcessId(serverProc);
            System.out.println("Server PID: " + serverPid);

            try {
                profileServerWithScenario(serverPid, scenario.url, warmupRequests, benchmarkRequests,
                        concurrency, jfrDuration, outputPath);
            } finally {
                if (serverProc.isAlive()) {
                    serverProc.destroyForcibly();
                    serverProc.waitFor(5, TimeUnit.SECONDS);
                }
            }

            System.out.println();
            System.out.println("═══════════════════════════════════════════════════════════════════");
            System.out.println("Profile Complete");
            System.out.println("═══════════════════════════════════════════════════════════════════");
            System.out.println("Output directory: " + outputPath.toAbsolutePath());
            System.out.println();
            System.out.println("Files created:");
            System.out.println("  - profile-" + scenario.name + ".jfr");
            System.out.println("  - heap-histogram-" + scenario.name + ".txt");
            System.out.println("  - benchmark-results-" + scenario.name + ".txt");
            System.out.println();

        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void profileServerWithScenario(long pid, String url, int warmupRequests,
            int benchmarkRequests, int concurrency,
            int jfrDuration, Path outputDir) throws Exception {
        // Send warmup requests
        System.out.println();
        System.out.println("Sending warmup requests (" + warmupRequests + " requests, " + concurrency + " threads)...");
        loadTestUrl(url, warmupRequests, concurrency, 30);

        Thread.sleep(2000);

        // Start JFR recording
        String jfrFileName = outputDir.resolve("profile-" + getScenarioFromUrl(url) + ".jfr").toAbsolutePath()
                .toString();
        System.out.println();
        System.out.println("Starting JFR recording for " + jfrDuration + "s → " + jfrFileName);

        ProcessBuilder jcmdStart = new ProcessBuilder(
                "jcmd", String.valueOf(pid), "JFR.start",
                "name=profile",
                "settings=profile",
                "duration=" + jfrDuration + "s",
                "filename=" + jfrFileName);
        Process p = jcmdStart.start();
        p.waitFor();

        if (p.exitValue() != 0) {
            System.err.println("Warning: JFR.start returned non-zero exit code");
        }

        // Send benchmark requests DURING JFR recording
        System.out.println("Sending benchmark requests while JFR records (" + benchmarkRequests
                + " requests, " + concurrency + " threads)...");
        long startBench = System.currentTimeMillis();
        loadTestUrl(url, benchmarkRequests, concurrency, 30);
        long benchDuration = System.currentTimeMillis() - startBench;
        System.out.println("  Benchmark completed in " + (benchDuration / 1000.0) + "s");

        // Wait a bit for JFR to finish
        Thread.sleep(2000);

        // Dump heap histogram
        String heapFileName = outputDir.resolve("heap-histogram-" + getScenarioFromUrl(url) + ".txt").toAbsolutePath()
                .toString();
        System.out.println("Capturing heap histogram → " + heapFileName);
        ProcessBuilder jcmdHeap = new ProcessBuilder("jcmd", String.valueOf(pid), "GC.class_histogram");
        Process heapProc = jcmdHeap.start();
        try (var out = Files.newOutputStream(Path.of(heapFileName))) {
            heapProc.getInputStream().transferTo(out);
        }
        heapProc.waitFor();
    }

    private static void loadTestUrl(String url, int requests, int threadCount, int timeoutSec) throws Exception {
        // Simple HTTP load generation using threads
        var executor = Executors.newFixedThreadPool(threadCount);
        var latch = new java.util.concurrent.CountDownLatch(threadCount);
        var remaining = new java.util.concurrent.atomic.AtomicInteger(requests);

        var client = java.net.http.HttpClient.newBuilder()
                .version(java.net.http.HttpClient.Version.HTTP_1_1)
                .connectTimeout(java.time.Duration.ofSeconds(5))
                .build();

        var httpRequest = java.net.http.HttpRequest.newBuilder()
                .uri(new java.net.URI(url))
                .timeout(java.time.Duration.ofSeconds(5))
                .GET()
                .build();

        long start = System.currentTimeMillis();
        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    while (remaining.decrementAndGet() >= 0) {
                        try {
                            client.send(httpRequest, java.net.http.HttpResponse.BodyHandlers.discarding());
                        } catch (Exception e) {
                            // ignore request errors
                        }
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        boolean done = latch.await(timeoutSec, TimeUnit.SECONDS);
        long elapsed = System.currentTimeMillis() - start;
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        if (!done) {
            System.err.println("  ⚠ Load generation timeout after " + timeoutSec + "s");
        }

        int completed = Math.max(0, requests - remaining.get());
        double throughput = completed * 1000.0 / elapsed;
        System.out.println("  Completed: " + completed + " requests in " + (elapsed / 1000.0) + "s ("
                + String.format("%.0f", throughput) + " req/s)");
    }

    private static Scenario findScenario(String name) {
        for (Scenario s : Scenario.values()) {
            if (s.name.equalsIgnoreCase(name)) {
                return s;
            }
        }
        return null;
    }

    private static String getScenarioFromUrl(String url) {
        if (url.contains("/hello")) {
            return "simple";
        }
        if (url.contains("/api/")) {
            return "dynamic";
        }
        return "static";
    }

    private static String getJavaExe() {
        String javaHome = System.getProperty("java.home");
        return Path.of(javaHome, "bin", "java.exe").toString();
    }

    private static long getProcessId(Process process) {
        return process.pid();
    }
}
