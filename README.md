# FastJava SIMD Web Server

Lightweight HTTP server prototype focused on low-indirection internals and SIMD-assisted parsing with Java's Vector API. The current codebase exposes a servlet-style API inspired by Tomcat and Jakarta Servlet, but it is not yet binary-compatible with Tomcat or the full servlet specification.

## Project Status (2026-03-30)

| Area | Status | Notes |
|---|---|---|
| HTTP/1.1 request/response pipeline | Implemented | Blocking and NIO paths, keep-alive, NIO ordered pipelined completion |
| TLS + ALPN (NIO) | Implemented | ALPN advertises `http/1.1` by default, optional mutual TLS, certificate hot-reload |
| WebSocket (RFC 6455) | Implemented (NIO + blocking v1) | Handshake validation, masked frame parsing, text/binary dispatch, ping/pong, close, continuation, annotation endpoint API, subprotocol negotiation, URI-template path params, permessage-deflate |
| Multipart form-data | Partial | Buffered + stream-first APIs implemented; full backpressure-aware incremental path still in progress |
| Async servlet lifecycle | Implemented (NIO + blocking) | `startAsync`, `dispatch`, listeners, timeout callbacks |
| Server-Sent Events (SSE) | Implemented (NIO + blocking v1) | Request-scoped `SseEmitter`, chunked `text/event-stream`, lifecycle wiring |
| Blocking path scalability parity | Partial | Functional, but less scalable than NIO path |
| HTTP/2 (`h2` / `h2c`) | Partial (hardened runtime slice) | Config + ALPN/h2c mode selection, preface/SETTINGS/PING handling, HEADERS+CONTINUATION assembly, strict flow-control window accounting, fair queued DATA scheduling, and servlet-backed HEADERS/DATA responses |
| WebSocket on blocking path | Implemented | Blocking server now supports upgrade + frame loop parity with NIO v1 |
| Hot deploy / classloader isolation | Implemented (v1) | `deployWebApp` / `undeployWebApp` with context-path mounting and per-app TCCL isolation |

## Latest Cross-Framework Benchmark (2026-04-03)

Scenario:
- Endpoint: `GET /hello`
- Warmup: `30000` requests
- Benchmark: `150000` requests
- Concurrency: `64`
- Rounds: `5`
- Execution model: isolated JVM per server

Aggregate median results:

| Server | Throughput (req/s) | Avg Latency (ms) | p95 (ms) | p99 (ms) | Errors |
|---|---:|---:|---:|---:|---:|
| FastJava | 106993.25 | 0.593 | 1.086 | 2.046 | 0 |
| Undertow | 93112.02 | 0.680 | 1.294 | 2.364 | 0 |
| Netty | 82846.07 | 0.766 | 1.573 | 2.676 | 0 |
| Tomcat | 74225.89 | 0.859 | 1.793 | 2.655 | 0 |

Winner by throughput median: **FastJava**

Reproduce on Windows PowerShell:

```powershell
$env:JAVA_HOME = "d:\tools\jdk\jdk-25"
$env:Path = "d:\tools\jdk\jdk-25\bin;" + $env:Path
Remove-Item Env:JAVA_TOOL_OPTIONS -ErrorAction SilentlyContinue
.\scripts\run-webserver-comparison.ps1
```

Latest raw output files:
- `benchmarks/webserver-comparison/results/latest-results.md`
- `benchmarks/webserver-comparison/results/latest-results.json`

## Quick Start

### Prerequisites
- Java 25+
- Maven 3.9+

### Compile
```bash
mvn clean compile
```

### Build JAR
```bash
mvn package
```

Creates `target/simd-webserver-0.1.0.jar` with all dependencies.

### Run HTTP
```bash
java --add-modules jdk.incubator.vector -jar target/simd-webserver-0.1.0.jar 8080 16
```

Arguments: port, worker threads.

### Run HTTP With External Config

Config precedence:
1. CLI positional args (`port`, `threads`)
2. Config file values
3. Built-in defaults

```bash
java --add-modules jdk.incubator.vector -jar target/simd-webserver-0.1.0.jar 8080 16 ./server.properties
```

```bash
java --add-modules jdk.incubator.vector -Dfastjava.config=./server.properties -jar target/simd-webserver-0.1.0.jar
```

```bash
java --add-modules jdk.incubator.vector -Dfastjava.config=./server.yaml -jar target/simd-webserver-0.1.0.jar
```

### Run HTTPS (NIO)
```java
TlsConfig tls = TlsConfig.defaults(Path.of("keystore.p12"), "password".toCharArray());
FastJavaNioServer server = new FastJavaNioServer(8443, RequestLimits.defaults(16_384), tls);
server.addServlet("/", new HelloWorldServlet());
server.start();
```

Generate a self-signed keystore for development:
```bash
keytool -genkeypair -keyalg RSA -keysize 2048 -validity 365 \
  -alias server -storetype PKCS12 -keystore keystore.p12 \
  -storepass changeit -dname "CN=localhost"
```

### Quick Test
```bash
curl http://localhost:8080/
curl https://localhost:8443/ --insecure
```

## Demo Backend Example

Compile the project and generate the runtime classpath.

Windows PowerShell:

```powershell
mvn --% -q -DskipTests compile dependency:build-classpath -Dmdep.outputFile=target/demo.classpath
```

Then run the example application.

Windows PowerShell:

```powershell
$cp = "target/classes;$(Get-Content target/demo.classpath -Raw)"
java --add-modules jdk.incubator.vector -cp $cp com.fastjava.examples.DemoBackendApplication 9090 ./static
```

Core demo endpoints:

- `GET /healthz` for the static fast route
- `GET /api/info` for JSON metadata and request details
- `GET|POST|PUT|DELETE /api/todos` for in-memory REST CRUD
- `GET /api/session` for session-backed counters
- `GET /api/async/time` for async servlet processing
- `GET /api/sse/ticks` for server-sent events
- `GET|POST /api/upload` for multipart upload inspection
- `GET /assets/*` for static files
- `WS /ws/demo/{room}` for annotated WebSocket chat rooms

## Configuration Reference

Example `server.properties`:

```properties
server.port=8080
server.threads=16
request.maxRequestSize=16384
request.keepAliveTimeoutMillis=5000
request.readTimeoutMillis=2000
request.maxRequestLineBytes=4096
request.maxHeaderBytes=8192
request.maxBodyBytes=16384
request.maxChunkSizeBytes=16384
request.maxChunkCount=16384
request.writeTimeoutMillis=30000
request.maxMultipartBytes=16384
request.maxMultipartPartBytes=16384
request.multipartMemoryThresholdBytes=65536
request.maxConcurrentConnections=4096
request.keepAlivePressureQueueThreshold=800

# HTTP/2 (disabled by default)
http2.enabled=false
http2.h2cEnabled=false
http2.maxConcurrentStreams=100
http2.initialWindowSize=65535
http2.maxFrameSize=16384
http2.headerTableSize=4096
http2.maxHeaderListSize=32768
http2.strictAlpn=false
```

Equivalent `server.yaml`:

```yaml
server:
    port: 8080
    threads: 16
request:
    maxRequestSize: 16384
    keepAliveTimeoutMillis: 5000
    readTimeoutMillis: 2000
    maxRequestLineBytes: 4096
    maxHeaderBytes: 8192
    maxBodyBytes: 16384
    maxChunkSizeBytes: 16384
    maxChunkCount: 16384
    writeTimeoutMillis: 30000
    maxMultipartBytes: 16384
    maxMultipartPartBytes: 16384
    multipartMemoryThresholdBytes: 65536
    maxConcurrentConnections: 4096
    keepAlivePressureQueueThreshold: 800
http2:
    enabled: false
    h2cEnabled: false
    maxConcurrentStreams: 100
    initialWindowSize: 65535
    maxFrameSize: 16384
    headerTableSize: 4096
    maxHeaderListSize: 32768
    strictAlpn: false
```

## Project Structure

```text
fastJava/
├── pom.xml
├── WEBSERVER_ARCHITECTURE.md
├── src/main/java/com/fastjava/
│   ├── servlet/
│   │   ├── HttpServlet.java
│   │   ├── HttpServletRequest.java
│   │   ├── HttpServletResponse.java
│   │   ├── AsyncContext.java / AsyncListener.java / AsyncEvent.java
│   │   ├── Filter.java / FilterChain.java / FilterConfig.java
│   │   └── ServletException.java
│   ├── http/
│   │   ├── simd/SIMDByteScanner.java
│   │   ├── parser/
│   │   │   ├── HttpRequestParser.java
│   │   │   ├── MultipartFormDataParser.java
│   │   │   ├── MultipartStreamingParser.java
│   │   │   └── ParsedHttpRequest.java
│   │   ├── response/HttpResponseBuilder.java
│   │   ├── impl/DefaultHttpServletRequest.java
│   │   ├── impl/DefaultHttpServletResponse.java
│   │   └── filter/
│   │       ├── CorsFilter.java
│   │       ├── GzipFilter.java
│   │       └── GzipResponseWrapper.java
│   ├── server/
│   │   ├── FastJavaServer.java
│   │   ├── FastJavaNioServer.java
│   │   ├── ServletRouter.java
│   │   ├── ClientRequestHandler.java
│   │   ├── HttpRequestExecutor.java
│   │   ├── HttpRequestInspector.java
│   │   ├── HttpErrorDispatcher.java
│   │   ├── RequestLimits.java
│   │   ├── RequestTracing.java
│   │   ├── RequestValidationResult.java
│   │   ├── ServerObservability.java
│   │   ├── ErrorPage.java
│   │   ├── FileResponseBody.java
│   │   ├── HttpExecutionResult.java
│   │   ├── LiveChunkedRequestBodyInputStream.java
│   │   ├── SimpleHttpResponses.java
│   │   ├── config/
│   │   │   ├── ServerConfig.java
│   │   │   └── ServerConfigLoader.java
│   │   ├── session/
│   │   │   ├── DefaultHttpSession.java
│   │   │   ├── InMemorySessionManager.java
│   │   │   ├── SessionConfig.java
│   │   │   └── SessionManager.java
│   │   └── tls/
│   │       ├── TlsConfig.java
│   │       ├── SslContextFactory.java
│   │       └── TlsChannelHandler.java
│   ├── websocket/
│   │   ├── annotation/
│   │   │   ├── WebSocketEndpoint.java
│   │   │   ├── OnOpen.java
│   │   │   ├── OnMessage.java
│   │   │   ├── OnClose.java
│   │   │   └── OnError.java
│   │   ├── WebSocketBlockingHandler.java
│   │   ├── WebSocketEndpointBinding.java
│   │   ├── WebSocketEndpointMetadata.java
│   │   ├── WebSocketSession.java
│   │   ├── WebSocketHandshake.java
│   │   ├── WebSocketFrame.java
│   │   └── WebSocketFrameCodec.java
│   └── examples/
│       ├── HelloWorldServlet.java
│       ├── ApiServlet.java
│       └── StaticFileServlet.java
```

## Feature Overview

### Performance Optimizations
- SIMD vectorized parsing (Java Vector API)
- Batch operations for delimiters and pattern scans
- Zero-copy static file path where applicable
- Allocation-aware, inline-friendly internals
- Multi-threaded execution model

### Server and Protocol Features
- Servlet-style API with request/response wrappers
- Filter pipeline lifecycle (`init` / `doFilter` / `destroy`)
- Dual server paths: blocking and NIO selector
- HTTP/1.1 keep-alive and NIO pipelined response ordering
- Chunked request decoding and chunked response encoding
- TLS on NIO path with ALPN `http/1.1`
- Server-Sent Events (SSE) streaming via `text/event-stream`
- WebSocket upgrade on NIO + blocking paths (RFC 6455 v1)
- Annotation-based WebSocket endpoint API (`@WebSocketEndpoint`, `@OnOpen`, `@OnMessage`, `@OnClose`, `@OnError`)
- Conditional caching + byte ranges for static files
- gzip and CORS filters
- Error page dispatch and `RequestDispatcher`
- Async servlet lifecycle support

## Usage Examples

### Custom Servlet
```java
public class MyServlet extends HttpServlet {
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException {
        response.setContentType("text/html");
        response.getWriter().println("<h1>Hello World</h1>");
    }
}
```

### Register Servlet and Filters
```java
FastJavaNioServer server = new FastJavaNioServer(8080, RequestLimits.defaults(16_384));
server.addFilter(new GzipFilter());
server.addServlet("/", new MyServlet());
server.start();
```

### Async Servlet Example
```java
public final class AsyncHelloServlet extends HttpServlet {
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException {
        AsyncContext async = request.startAsync();
        async.setTimeout(500);
        async.addListener(new AsyncListener() {
            @Override
            public void onTimeout(AsyncEvent event) {
                event.getSuppliedResponse().setStatus(503);
            }
        });

        new Thread(() -> {
            response.setContentType("text/plain");
            response.getWriter().print("async-ok");
            async.complete();
        }, "Example-Async").start();
    }
}
```

### WebSocket Annotation Endpoint
```java
FastJavaNioServer server = new FastJavaNioServer(8080, RequestLimits.defaults(16_384));
server.addWebSocketEndpoint(ChatEndpoint.class);
server.start();
```

### Server-Sent Events (SSE)
```java
public final class ClockSseServlet extends HttpServlet {
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException {
        SseSupport.prepareResponse(response);
        SseEmitter emitter = SseSupport.from(request);
        if (emitter == null) {
            throw new ServletException("SSE emitter unavailable");
        }

        try {
            emitter.send(SseEvent.builder().event("tick").data("ready").build());
            emitter.close();
        } catch (IOException ioException) {
            throw new ServletException(ioException);
        }
    }
}
```

### Multipart (Buffered + Streaming)
```java
if (request instanceof DefaultHttpServletRequest fastRequest) {
    fastRequest.enableStreamingMultipart();
}

for (Part part : request.getParts()) {
    if (part.getSubmittedFileName() != null) {
        part.write("uploads/" + part.getSubmittedFileName());
    }
}
```

## HTTP Feature Details

### Conditional Caching (ETag / 304)
`StaticFileServlet` enforces RFC 7232 precedence:
1. `If-None-Match` present: compare ETag
2. Else `If-Modified-Since` present: compare timestamp

### Byte Ranges (206 / 416)
`StaticFileServlet` supports RFC 7233 single ranges:
- `Range: bytes=start-end`
- `Range: bytes=start-`
- `Range: bytes=-suffix`

### gzip Compression
Register `GzipFilter`:
```java
server.addFilter(new GzipFilter());
```

Behavior:
- Negotiates `Accept-Encoding: gzip`
- Skips small payloads (< 256 bytes by default)
- Skips already-compressed MIME types and binary-heavy payloads
- Sets `Content-Encoding: gzip`, `Vary: Accept-Encoding`, and corrected `Content-Length`

### CORS Filter
Register `CorsFilter` with allowed origins:
```java
server.addFilter(new CorsFilter(Set.of("https://app.example")));
```

Behavior:
- Handles simple and preflight requests
- Preserves safe cache behavior via `Vary`
- Enforces `allowCredentials=true` cannot be combined with `*`

### WebSocket Upgrade (NIO + Blocking v1)
Handshake requirements:
- `GET` + `HTTP/1.1`
- `Upgrade: websocket`
- `Connection` token containing `upgrade`
- `Sec-WebSocket-Version: 13`
- valid base64 `Sec-WebSocket-Key` decoding to 16 bytes

Current frame-loop scope:
- Masked client frame enforcement
- Text and binary frame dispatch
- Ping/pong
- Continuation handling
- Close handshake

#### Annotation-Based Endpoint API
Register annotated POJO endpoints via `addWebSocketEndpoint(Class<?>)`. Lifecycle is managed per connection with a `WebSocketSession` handle. Endpoint paths support exact match and URI templates (for example, `/ws/room/{roomId}`) with `@PathParam` binding to method parameters:

```java
@WebSocketEndpoint(path = "/ws/chat")
public class ChatEndpoint {
    @OnOpen
    public void onOpen(WebSocketSession session) {
        session.setAttribute("joined", System.currentTimeMillis());
    }

    @OnMessage
    public void onMessage(WebSocketSession session, String text) throws IOException {
        session.sendText("echo: " + text);
    }

    @OnClose
    public void onClose(WebSocketSession session) {
        session.removeAttribute("joined");
    }

    @OnError
    public void onError(WebSocketSession session, Throwable cause) {
        cause.printStackTrace();
    }
}
```

Template example:

```java
@WebSocketEndpoint(path = "/ws/room/{roomId}/{userId}")
public class RoomEndpoint {
    @OnMessage
    public void onMessage(WebSocketSession session, String text,
                          @PathParam("roomId") String roomId,
                          @PathParam("userId") String userId) throws IOException {
        session.sendText(roomId + ":" + userId + ":" + text);
    }
}
```

Registration:
```java
FastJavaNioServer server = new FastJavaNioServer(8080, RequestLimits.defaults(16_384));
server.addWebSocketEndpoint(ChatEndpoint.class);
server.addServlet("/", new MyServlet());
server.start();
```

`WebSocketSession` members: `sendText(String)`, `sendBinary(byte[])`, `close()`, `close(int code)`, `getSubprotocol()`, `getAttribute/setAttribute/removeAttribute`.

## Detailed Capability Matrix (2026-03-30)

### Done

| Feature | Notes |
|---|---|
| HTTP/1.1 keep-alive | Blocking + NIO paths |
| HTTP/1.1 pipelining ordering (NIO) | Responses flush in request order |
| Chunked request decoding | Complete when body is fully buffered |
| Chunked response encoding | Buffered, automatic |
| 100-continue handshake | RFC 7231 compliant |
| RFC request validation | 400/414/431/501 as appropriate |
| RFC edge-case hardening | Rejects obs-fold, validates chunked trailers, rejects unsupported transfer-encoding chains |
| Write-timeout / slow-client protection | Blocking + NIO with telemetry counters |
| Blocking path pooling hardening | Connection cap, pressure preemption, split read timeout, rejection/preemption metrics |
| Observability foundation | Prometheus `/metrics`, structured JSON access log, OpenTelemetry lifecycle spans |
| Static file serving + sendfile | Zero-copy `FileChannel.transferTo` |
| Path traversal protection | Normalize + prefix check |
| Filter pipeline | Full lifecycle |
| Conditional caching + ranges | RFC 7232 precedence + RFC 7233 range support |
| Request parameter parsing | Query, form-urlencoded, multipart; query precedence on key conflicts |
| gzip filter | MIME exclusion, binary detection, tunable `minSize` |
| CORS filter | Allow-list, preflight, safe `Vary` |
| Cookies | Request parsing and repeated `Set-Cookie` response support |
| Sessions | In-memory lifecycle with `JSESSIONID` continuity |
| TLS / ALPN (NIO) | JDK `SSLEngine`, off-selector handshake, ALPN, optional mTLS, cert hot-reload |
| WebSocket (NIO + blocking) | RFC 6455 v1 with strict handshake validation, subprotocol negotiation, permessage-deflate, and frame loop |
| WebSocket endpoint annotation API | `@WebSocketEndpoint`, `@OnOpen`, `@OnMessage`, `@OnClose`, `@OnError`, `@PathParam` with per-connection `WebSocketSession` |
| Hot deploy / classloader isolation | Per-app context-path deployment with dynamic redeploy/undeploy and request-time TCCL switching |
| Error page dispatch | Status + exception mapping, `javax.servlet.error.*` attrs, XSS-safe URI |
| Request dispatcher | `forward` / `include` with servlet attribute population |
| SIMD parser foundations | High-throughput CRLF scan |
| SIMD header canonicalization | Parse-time lowercase normalization |
| SIMD multipart boundary scanning | Vector-assisted boundary detection |
| Streaming multipart boundary parsing | Incremental parsing before full buffering |
| SIMD gzip decision fast paths | Vectorized MIME checks and binary detection |
| SIMD router path matching | Adaptive ASCII SIMD prefix/suffix checks with fallback |
| SIMD path/range parser fast paths | SIMD separator normalization and token scanning |
| External config support | Typed key validation and startup precedence rules |
| JMH benchmarks | Parser + sendfile hot paths |
| Dependency vulnerability scan | `dependency-check` artifact in `target/` |

### Partial (In Progress)

| Feature | What Works | What Is Missing |
|---|---|---|
| HTTP/1.1 compliance edge matrix | Core methods, keep-alive, pipelining, `100-continue`, transfer-encoding hardening, ALPN fallback | Absolute-URI request target (proxy mode), `CONNECT` tunneling, mandatory `Host` header validation (RFC 7230 §5.4), `OPTIONS *` server-wide target, `TE` request header negotiation |
| HTTP request canonical header conflict handling | Duplicate `Content-Length`/`Transfer-Encoding` rejection in parser + inspector | `Host` header deduplication, header field name token-character validation, `CL`+`TE` conflict detection for request-smuggling mitigation (RFC 7230 §3.3.3) |
| Multipart form-data | Buffered + stream-first APIs, SIMD boundary scanning | Full socket-level incremental path (backpressure-aware chunk-by-chunk delivery without full buffering) |
| HTTP/2 (`h2` / `h2c`) hardened runtime slice | Feature-flagged config, ALPN/h2c prior-knowledge mode selection, preface + SETTINGS/PING control flow, HPACK decode/encode path, HEADERS+CONTINUATION assembly, strict connection/stream flow-control accounting with WINDOW_UPDATE validation, fair queued DATA scheduling, and HTTP/2 HEADERS/DATA response emission | Full RFC 7540 completion: broader stream-state/priority coverage and fully hardened HTTP/1.1 Upgrade-to-h2c transition |

### Not Yet Implemented

| Feature | Impact |
|---|---|
| HTTP/2 RFC-completeness hardening | Remaining RFC 7540 closure work focused on deeper edge-case/state-matrix coverage and final hardening of the HTTP/1.1 Upgrade-to-h2c transition |

### HTTP/2 RFC-Completeness Hardening Scope

- Protocol edge-case handling: expand stream-state transition matrix and invalid sequencing coverage across more frame combinations.
- h2c upgrade-path correctness: complete and harden HTTP/1.1 `Upgrade: h2c` transition handling and `HTTP2-Settings` validation under all parser/selector timing scenarios.
- Stream scheduling evolution: extend current fair queued scheduling with richer prioritization behavior while preserving backpressure and write-timeout safety.

## Quality, Coverage, and Benchmarks

## Web Server Comparison Framework

This repository now includes a dedicated comparison harness for basic HTTP operations against:
- FastJava
- Undertow
- Tomcat
- Netty

Framework location:
- `benchmarks/webserver-comparison/README.md`
- Runner script: `scripts/run-webserver-comparison.ps1`
- Latest markdown output: `benchmarks/webserver-comparison/results/latest-results.md`
- Latest JSON output: `benchmarks/webserver-comparison/results/latest-results.json`

Run it:

```powershell
./scripts/run-webserver-comparison.ps1
```

Latest recorded run (GET `/hello`, warmup=1000, requests=10000, concurrency=32):

## Aggregate (median across rounds)

| Server | Throughput (req/s) | Avg Latency (ms) | p95 (ms) | p99 (ms) | Errors |
|---|---:|---:|---:|---:|---:|
| Tomcat | 41574.96 | 0.384 | 0.750 | 1.356 | 0 |
| Undertow | 40151.99 | 0.397 | 0.844 | 1.432 | 0 |
| FastJava | 35716.29 | 0.447 | 0.781 | 1.202 | 0 |
| Netty | 35682.48 | 0.447 | 1.084 | 1.711 | 0 |

Notes:
- This is an in-process directional benchmark for rapid iteration.
- For publication-grade benchmarking, pin CPU frequency/governor, isolate cores, and run repeated trials.

Run quality pipeline:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/run-quality-and-bench.ps1
```

Outputs:
- Markdown report: `target/reports/quality-report.md`
- JaCoCo HTML report: `target/site/jacoco/index.html`
- Microbenchmark results: `target/benchmarks/microbench-results.txt`

Skip benchmarks:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/run-quality-and-bench.ps1 -SkipBench
```

## JMH Benchmarking Guide

Prepare:
```bash
mvn clean test-compile
mvn -DskipTests -DincludeScope=test "-Dmdep.outputFile=target/jmh-test-classpath.txt" dependency:build-classpath
```

Run parser/sendfile hot-path benchmark:
```bash
java --add-modules jdk.incubator.vector -cp "target/test-classes;target/classes;$(cat target/jmh-test-classpath.txt)" \
    org.openjdk.jmh.Main com.fastjava.bench.HttpParserBench -f 3 -wi 3 -i 5 -t 4
```

Run SIMD before/after comparisons:
```bash
java --add-modules jdk.incubator.vector -cp "target/test-classes;target/classes;$(cat target/jmh-test-classpath.txt)" \
    org.openjdk.jmh.Main "com.fastjava.bench.HttpParserBench.benchmark(PathSeparatorNormalize|RangeCommaSplit|RangeDashIndex).*(Scalar|Simd)" \
    -f 1 -wi 2 -i 3 -t 1 -rf json -rff target/benchmarks/jmh-simd-before-after.json
```

Run gzip decision-path microbench:
```bash
java --add-modules jdk.incubator.vector -cp "target/test-classes;target/classes;$(cat target/jmh-test-classpath.txt)" \
    com.fastjava.http.filter.GzipFilterMicroBench 400000
```

Run router matching microbench:
```bash
java --add-modules jdk.incubator.vector -cp "target/test-classes;target/classes;$(cat target/jmh-test-classpath.txt)" \
    com.fastjava.bench.ServletRouterMicroBench 500000
```

Run multipart parser throughput benchmark:
```bash
java --add-modules jdk.incubator.vector -cp "target/test-classes;target/classes;$(cat target/jmh-test-classpath.txt)" \
    org.openjdk.jmh.Main com.fastjava.bench.MultipartParserBench -f 1 -wi 3 -i 5
```

PowerShell equivalent:
```powershell
$cp = "target/test-classes;target/classes;" + ((Get-Content target/jmh-test-classpath.txt -Raw).Trim())
java --add-modules jdk.incubator.vector -cp $cp org.openjdk.jmh.Main com.fastjava.bench.MultipartParserBench -f 1 -wi 3 -i 5
```

Run focused large-file sendfile benchmark:
```bash
mvn "-Dtest=StaticFileSendfileMicroBench" test
```

Profiles:
- `quick`: CI-friendly, smaller file/lower iterations
- `stress`: larger file/more iterations

## Latest Benchmark Snapshot

| Benchmark | Throughput |
|---|---|
| HTTP request parse (simple GET) | 2.4 M ops/s |
| SIMD CRLF scan | 20.6 M ops/s |
| Static file sendfile (NIO) | 294 req/s / 588 MiB/s |
| gzip MIME exclusion (scalar) | 3.26 M ops/s (0.307 us avg) |
| gzip MIME exclusion (SIMD) | 3.77 M ops/s (0.265 us avg) |
| gzip binary detection (scalar) | 1.11 M ops/s (0.897 us avg) |
| gzip binary detection (SIMD) | 24.54 M ops/s (0.041 us avg) |
| router prefix match (cached ASCII scalar) | 11.46 M ops/s (0.087 us avg) |
| router prefix match (cached ASCII SIMD) | 10.00 M ops/s (0.100 us avg) |
| router suffix match (cached ASCII scalar) | 11.41 M ops/s (0.088 us avg) |
| router suffix match (cached ASCII SIMD) | 11.68 M ops/s (0.086 us avg) |

### SIMD Before/After Snapshot (JDK 25.0.2, Windows, 2026-03-30)

| Benchmark Pair | Scalar (us/op) | SIMD (us/op) | Delta |
|---|---:|---:|---:|
| Path separator normalization | 0.0227 | 0.0126 | 1.79x faster |
| Range comma split | 0.0274 | 0.0346 | 0.79x (SIMD slower) |
| Range dash index scan | 0.0769 | 0.0310 | 2.48x faster |

### Multipart Parser Bench Record Template

| partCount | payloadBytes | noisyHyphens | parse avg (us) | parseTextParameters avg (us) |
|---|---|---|---|---|
| 1 | 64 | false | TBD | TBD |
| 8 | 64 | false | TBD | TBD |
| 32 | 64 | false | TBD | TBD |
| 1 | 512 | true | TBD | TBD |
| 8 | 512 | true | TBD | TBD |
| 32 | 512 | true | TBD | TBD |

## Roadmap

- Harden and complete HTTP/2 (`h2` / `h2c`) to full RFC-level behavior (expanded state-matrix coverage, richer stream scheduling, and fully hardened upgrade-path coverage)
- Hot deploy and classloader isolation for multi-application deployment in one JVM

