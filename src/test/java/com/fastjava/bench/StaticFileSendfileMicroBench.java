package com.fastjava.bench;

import com.fastjava.examples.StaticFileServlet;
import com.fastjava.server.FastJavaNioServer;
import com.fastjava.server.RequestLimits;
import com.fastjava.servlet.HttpServlet;
import com.fastjava.servlet.HttpServletRequest;
import com.fastjava.servlet.HttpServletResponse;
import com.fastjava.servlet.ServletException;
import com.sun.management.OperatingSystemMXBean;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Focused benchmark for large static-file delivery.
 * Compares sendfile-backed path vs buffered servlet path under keep-alive
 * traffic.
 */
public class StaticFileSendfileMicroBench {

    private static final int QUICK_FILE_SIZE_BYTES = 256 * 1024;
    private static final int QUICK_WARMUP_ITERATIONS = 10;
    private static final int QUICK_MEASURED_ITERATIONS = 30;

    private static final int STRESS_FILE_SIZE_BYTES = 2 * 1024 * 1024;
    private static final int STRESS_WARMUP_ITERATIONS = 40;
    private static final int STRESS_MEASURED_ITERATIONS = 120;

    private static final BenchProfile DEFAULT_PROFILE = BenchProfile.QUICK;

    private FastJavaNioServer server;
    private int port;
    private Path tempDir;
    private String payload;
    private int fileSizeBytes;
    private int warmupIterations;
    private OperatingSystemMXBean osBean;

    public StaticFileSendfileMicroBench() {
        if (ManagementFactory.getOperatingSystemMXBean() instanceof OperatingSystemMXBean bean) {
            this.osBean = bean;
        }
    }

    public static void main(String[] args) throws Exception {
        BenchProfile profile = args.length > 0
                ? BenchProfile.fromArg(args[0])
                : DEFAULT_PROFILE;
        int iterations = args.length > 1
                ? parseIterationsOrDefault(args[1], profile.measuredIterations)
                : profile.measuredIterations;

        StaticFileSendfileMicroBench bench = new StaticFileSendfileMicroBench();
        bench.execute(profile, iterations);
    }

    @Test
    public void benchmarkDefaultProfile() throws Exception {
        execute(DEFAULT_PROFILE, DEFAULT_PROFILE.measuredIterations);
    }

    private void execute(BenchProfile profile, int iterations) throws Exception {
        setUp(profile);
        try {
            runScenario("/sendfile/asset.txt", warmupIterations);
            runScenario("/buffered/asset.txt", warmupIterations);

            BenchmarkResult sendfile = runScenario("/sendfile/asset.txt", iterations);
            BenchmarkResult buffered = runScenario("/buffered/asset.txt", iterations);

            printMetrics(profile.profileName, "sendfile", sendfile, iterations);
            printMetrics(profile.profileName, "buffered", buffered, iterations);

            double throughputPct = percentDelta(sendfile.requestsPerSecond(), buffered.requestsPerSecond());
            double cpuReductionPct = cpuReduction(sendfile.cpuMillis(), buffered.cpuMillis());

            System.out.println(String.format(
                    Locale.ROOT,
                    "com.fastjava.bench.StaticFileSendfileMicroBench.delta profile=%s throughput_pct=%.2f cpu_reduction_pct=%.2f file_bytes=%d iterations=%d",
                    profile.profileName,
                    throughputPct,
                    cpuReductionPct,
                    fileSizeBytes,
                    iterations));
        } finally {
            tearDown();
        }
    }

    private void setUp(BenchProfile profile) throws Exception {
        this.fileSizeBytes = profile.fileSizeBytes;
        this.warmupIterations = profile.warmupIterations;
        tempDir = Files.createTempDirectory("fastjava-sendfile-bench");
        payload = "0123456789abcdef".repeat(fileSizeBytes / 16);
        Files.writeString(tempDir.resolve("asset.txt"), payload, StandardCharsets.US_ASCII);

        server = new FastJavaNioServer(0, new RequestLimits(16_384, 3_000, 4_096, 8_192, 8_192), 4);
        server.addServletPattern("/sendfile/*", new StaticFileServlet(tempDir, "/sendfile"));
        server.addServletPattern("/buffered/*", new BufferedStaticFileServlet(tempDir, "/buffered"));
        server.start();
        port = server.getBoundPort();
        waitForServerReady();
    }

    private void tearDown() {
        if (server != null) {
            server.stop();
        }

        if (tempDir != null) {
            try {
                Files.deleteIfExists(tempDir.resolve("asset.txt"));
                Files.deleteIfExists(tempDir);
            } catch (IOException ignored) {
                // Best-effort cleanup for benchmark temp files.
            }
        }
    }

    private BenchmarkResult runScenario(String path, int iterations) throws Exception {
        long cpuStart = readCpuNanos();
        long start = System.nanoTime();
        long bytes = 0;

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 1_000);
            socket.setSoTimeout(15_000);

            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            for (int i = 0; i < iterations; i++) {
                boolean close = i == iterations - 1;
                String request = "GET " + path + " HTTP/1.1\r\n"
                        + "Host: localhost\r\n"
                        + "Connection: " + (close ? "close" : "keep-alive") + "\r\n"
                        + "\r\n";
                out.write(request.getBytes(StandardCharsets.US_ASCII));
                out.flush();

                RawHttpResponse response = RawHttpResponse.readFrom(in);
                if (response.statusCode() != 200) {
                    throw new IllegalStateException("Unexpected status " + response.statusCode() + " for " + path);
                }
                bytes += response.body().length;
            }
        }

        long elapsed = System.nanoTime() - start;
        long cpuEnd = readCpuNanos();

        double seconds = elapsed / 1_000_000_000.0;
        double reqPerSec = iterations / seconds;
        double mibPerSec = (bytes / (1024.0 * 1024.0)) / seconds;
        double avgMs = (elapsed / 1_000_000.0) / iterations;
        double cpuMs = cpuStart >= 0 && cpuEnd >= 0 ? (cpuEnd - cpuStart) / 1_000_000.0 : -1.0;

        return new BenchmarkResult(reqPerSec, mibPerSec, avgMs, cpuMs);
    }

    private void printMetrics(String profileName, String name, BenchmarkResult result, int iterations) {
        System.out.println(String.format(
                Locale.ROOT,
                "com.fastjava.bench.StaticFileSendfileMicroBench.%s profile=%s req_s=%.2f mib_s=%.2f avg_ms=%.3f cpu_ms=%.2f file_bytes=%d iterations=%d",
                name,
                profileName,
                result.requestsPerSecond(),
                result.mibPerSecond(),
                result.avgMillis(),
                result.cpuMillis(),
                fileSizeBytes,
                iterations));
    }

    private long readCpuNanos() {
        if (osBean == null) {
            return -1;
        }
        return osBean.getProcessCpuTime();
    }

    private double percentDelta(double current, double baseline) {
        if (baseline == 0.0) {
            return 0.0;
        }
        return ((current - baseline) / baseline) * 100.0;
    }

    private double cpuReduction(double sendfileCpuMs, double bufferedCpuMs) {
        if (sendfileCpuMs < 0 || bufferedCpuMs <= 0) {
            return -1.0;
        }
        return ((bufferedCpuMs - sendfileCpuMs) / bufferedCpuMs) * 100.0;
    }

    private void waitForServerReady() throws Exception {
        long deadline = System.currentTimeMillis() + 2_000;
        while (System.currentTimeMillis() < deadline) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", port), 100);
                return;
            } catch (IOException ignored) {
                Thread.sleep(25);
            }
        }
        throw new IllegalStateException("Server not ready");
    }

    private static final class BufferedStaticFileServlet extends HttpServlet {
        private final Path root;
        private final String mountPath;

        private BufferedStaticFileServlet(Path root, String mountPath) {
            this.root = root.toAbsolutePath().normalize();
            this.mountPath = mountPath;
        }

        @Override
        protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException {
            try {
                String requestUri = request.getRequestURI();
                if (requestUri == null || !requestUri.startsWith(mountPath)) {
                    response.setStatus(404);
                    response.getWriter().print("Not Found");
                    return;
                }

                String relative = requestUri.substring(mountPath.length());
                if (relative.startsWith("/")) {
                    relative = relative.substring(1);
                }

                Path target = root.resolve(relative).normalize();
                if (!target.startsWith(root) || !Files.isRegularFile(target)) {
                    response.setStatus(404);
                    response.getWriter().print("Not Found");
                    return;
                }

                String body = Files.readString(target, StandardCharsets.US_ASCII);
                response.setStatus(200);
                response.setContentType("text/plain");
                response.setHeader("Content-Length", String.valueOf(body.length()));
                response.getWriter().print(body);
                response.getWriter().flush();
            } catch (IOException exception) {
                throw new ServletException("Buffered benchmark servlet failed", exception);
            }
        }
    }

    private enum BenchProfile {
        QUICK("quick", QUICK_FILE_SIZE_BYTES, QUICK_WARMUP_ITERATIONS, QUICK_MEASURED_ITERATIONS),
        STRESS("stress", STRESS_FILE_SIZE_BYTES, STRESS_WARMUP_ITERATIONS, STRESS_MEASURED_ITERATIONS);

        private final String profileName;
        private final int fileSizeBytes;
        private final int warmupIterations;
        private final int measuredIterations;

        BenchProfile(String profileName, int fileSizeBytes, int warmupIterations, int measuredIterations) {
            this.profileName = profileName;
            this.fileSizeBytes = fileSizeBytes;
            this.warmupIterations = warmupIterations;
            this.measuredIterations = measuredIterations;
        }

        private static BenchProfile fromArg(String value) {
            if (value == null) {
                return DEFAULT_PROFILE;
            }
            String normalized = value.trim().toLowerCase(Locale.ROOT);
            return switch (normalized) {
                case "quick", "ci" -> QUICK;
                case "stress", "full" -> STRESS;
                default -> DEFAULT_PROFILE;
            };
        }
    }

    private static int parseIterationsOrDefault(String candidate, int defaultValue) {
        if (candidate == null || candidate.isBlank()) {
            return defaultValue;
        }
        try {
            return Math.max(1, Integer.parseInt(candidate.trim()));
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private record BenchmarkResult(double requestsPerSecond, double mibPerSecond, double avgMillis, double cpuMillis) {
    }

    private record RawHttpResponse(int statusCode, byte[] body) {

        private static RawHttpResponse readFrom(InputStream input) throws IOException {
            String headers = readHeaders(input);
            int statusCode = Integer.parseInt(headers.split(" ")[1]);
            int contentLength = contentLength(headers);
            byte[] body = input.readNBytes(contentLength);
            if (body.length != contentLength) {
                throw new IOException("Incomplete body");
            }
            return new RawHttpResponse(statusCode, body);
        }

        private static String readHeaders(InputStream input) throws IOException {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            int current;
            int matched = 0;
            byte[] terminator = new byte[] { '\r', '\n', '\r', '\n' };

            while ((current = input.read()) != -1) {
                output.write(current);
                if (current == terminator[matched]) {
                    matched++;
                    if (matched == terminator.length) {
                        byte[] raw = output.toByteArray();
                        return new String(raw, 0, raw.length - terminator.length, StandardCharsets.UTF_8);
                    }
                } else {
                    matched = current == terminator[0] ? 1 : 0;
                }
            }

            throw new IOException("Incomplete headers");
        }

        private static int contentLength(String headers) {
            String[] lines = headers.split("\\r\\n");
            for (int i = 1; i < lines.length; i++) {
                String line = lines[i];
                int colon = line.indexOf(':');
                if (colon > 0 && line.substring(0, colon).equalsIgnoreCase("Content-Length")) {
                    return Integer.parseInt(line.substring(colon + 1).trim());
                }
            }
            return 0;
        }
    }
}
