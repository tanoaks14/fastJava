# Web Server Comparison Framework

This folder contains a reproducible benchmark harness to compare FastJava against:
- Undertow
- Tomcat (embedded)
- Netty
- Helidon Nima
- Avaje Nima

## Scenario

- Endpoint: `GET /hello`
- Payload: small plain text (`ok`)
- Client: Java `HttpClient` (HTTP/1.1)
- Metrics:
- throughput (requests/sec)
- average latency (ms)
- p95 latency (ms)
- p99 latency (ms)
- error count

## Run

```powershell
./scripts/run-webserver-comparison.ps1
```

Optional tuning:

```powershell
./scripts/run-webserver-comparison.ps1 -WarmupRequests 5000 -BenchmarkRequests 50000 -Concurrency 64
```

## Output

- Markdown: `benchmarks/webserver-comparison/results/latest-results.md`
- JSON: `benchmarks/webserver-comparison/results/latest-results.json`

## Notes

- This is an in-process comparative microbenchmark, useful for directional comparison.
- For publication-grade numbers, run on a dedicated machine, pin CPU governor, and repeat multiple times.
