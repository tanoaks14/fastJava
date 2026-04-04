# Web Server Comparison Results

- Scenario: `GET /hello`
- Warmup requests: `30000`
- Benchmark requests: `1500000`
- Concurrency: `64`
- Rounds: `5`
- Peak tune mode: `enabled`
- Execution: `isolated JVM per server`
- Timestamp: `2026-04-04T20:28:02.935455400Z`

## Aggregate (median across rounds)

| Server | Throughput (req/s) | Avg Latency (ms) | p95 (ms) | p99 (ms) | Errors |
|---|---:|---:|---:|---:|---:|
| FastJava | 117611.31 | 0.544 | 0.933 | 1.257 | 0 |
| Undertow | 110741.20 | 0.577 | 0.998 | 1.363 | 0 |
| Netty | 108746.21 | 0.588 | 1.017 | 1.425 | 0 |
| Tomcat | 74400.48 | 0.860 | 1.770 | 2.450 | 0 |
| AvajeNima | 65847.66 | 0.971 | 1.799 | 2.403 | 0 |
| HelidonNima | 55416.20 | 1.154 | 2.069 | 2.835 | 0 |

Winner by throughput median: **FastJava**

## Per-run samples

| Round | Server | Throughput (req/s) | Avg Latency (ms) | p95 (ms) | p99 (ms) | Errors |
|---:|---|---:|---:|---:|---:|---:|
| 1 | FastJava | 110836.69 | 0.577 | 0.949 | 1.257 | 0 |
| 1 | Undertow | 109105.15 | 0.586 | 0.998 | 1.310 | 0 |
| 1 | Tomcat | 74400.48 | 0.860 | 1.770 | 2.450 | 0 |
| 1 | Netty | 108746.21 | 0.588 | 1.017 | 1.353 | 0 |
| 1 | HelidonNima | 49364.65 | 1.296 | 2.069 | 2.632 | 0 |
| 1 | AvajeNima | 65291.19 | 0.980 | 1.773 | 2.323 | 0 |
| 2 | Undertow | 112348.58 | 0.569 | 0.972 | 1.298 | 0 |
| 2 | Tomcat | 61074.44 | 1.047 | 2.304 | 4.293 | 0 |
| 2 | Netty | 99537.97 | 0.642 | 1.091 | 1.428 | 0 |
| 2 | HelidonNima | 65886.95 | 0.971 | 1.849 | 2.466 | 0 |
| 2 | AvajeNima | 69933.60 | 0.915 | 2.096 | 2.994 | 0 |
| 2 | FastJava | 122269.40 | 0.523 | 0.884 | 1.162 | 0 |
| 3 | Tomcat | 77243.11 | 0.828 | 1.658 | 2.293 | 0 |
| 3 | Netty | 111355.98 | 0.574 | 0.979 | 1.300 | 0 |
| 3 | HelidonNima | 74059.26 | 0.863 | 1.923 | 2.835 | 0 |
| 3 | AvajeNima | 112562.53 | 0.568 | 0.978 | 1.370 | 0 |
| 3 | FastJava | 120306.98 | 0.531 | 0.903 | 1.214 | 0 |
| 3 | Undertow | 110741.20 | 0.577 | 1.000 | 1.429 | 0 |
| 4 | Netty | 109592.83 | 0.583 | 0.993 | 1.499 | 0 |
| 4 | HelidonNima | 48152.79 | 1.328 | 2.245 | 2.940 | 0 |
| 4 | AvajeNima | 65847.66 | 0.971 | 1.799 | 2.403 | 0 |
| 4 | FastJava | 116586.24 | 0.548 | 0.946 | 1.354 | 0 |
| 4 | Undertow | 111712.27 | 0.572 | 0.997 | 1.363 | 0 |
| 4 | Tomcat | 73097.54 | 0.875 | 1.810 | 2.531 | 0 |
| 5 | HelidonNima | 55416.20 | 1.154 | 2.546 | 4.123 | 0 |
| 5 | AvajeNima | 63890.84 | 1.001 | 1.830 | 2.433 | 0 |
| 5 | FastJava | 117611.31 | 0.544 | 0.933 | 1.312 | 0 |
| 5 | Undertow | 101506.80 | 0.630 | 1.102 | 1.464 | 0 |
| 5 | Tomcat | 77319.28 | 0.827 | 1.630 | 2.257 | 0 |
| 5 | Netty | 103743.40 | 0.617 | 1.052 | 1.425 | 0 |
