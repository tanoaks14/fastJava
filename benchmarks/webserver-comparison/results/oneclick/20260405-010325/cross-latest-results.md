# Web Server Comparison Results

- Scenario: `GET /hello`
- Warmup requests: `20000`
- Benchmark requests: `300000`
- Concurrency: `32`
- Rounds: `5`
- Peak tune mode: `disabled`
- Execution: `isolated JVM per server`
- Timestamp: `2026-04-04T19:36:28.065502400Z`

## Aggregate (median across rounds)

| Server | Throughput (req/s) | Avg Latency (ms) | p95 (ms) | p99 (ms) | Errors |
|---|---:|---:|---:|---:|---:|
| Undertow | 106885.44 | 0.299 | 0.520 | 1.053 | 0 |
| FastJava | 100179.95 | 0.319 | 0.523 | 0.878 | 0 |
| Netty | 94703.86 | 0.337 | 0.634 | 1.130 | 0 |
| HelidonNima | 92674.31 | 0.344 | 0.823 | 1.264 | 0 |
| Tomcat | 75606.87 | 0.423 | 0.891 | 1.330 | 0 |
| AvajeNima | 42499.70 | 0.752 | 1.216 | 1.665 | 0 |

Winner by throughput median: **Undertow**

## Per-run samples

| Round | Server | Throughput (req/s) | Avg Latency (ms) | p95 (ms) | p99 (ms) | Errors |
|---:|---|---:|---:|---:|---:|---:|
| 1 | FastJava | 100179.95 | 0.319 | 0.534 | 0.893 | 0 |
| 1 | Undertow | 113388.64 | 0.282 | 0.482 | 0.887 | 0 |
| 1 | Tomcat | 77555.30 | 0.412 | 0.876 | 1.333 | 0 |
| 1 | Netty | 98258.97 | 0.325 | 0.597 | 1.193 | 0 |
| 1 | HelidonNima | 98530.62 | 0.324 | 0.683 | 1.264 | 0 |
| 1 | AvajeNima | 82947.29 | 0.385 | 1.069 | 1.537 | 0 |
| 2 | Undertow | 107194.21 | 0.298 | 0.520 | 1.059 | 0 |
| 2 | Tomcat | 81230.25 | 0.393 | 0.814 | 1.221 | 0 |
| 2 | Netty | 94703.86 | 0.337 | 0.640 | 1.130 | 0 |
| 2 | HelidonNima | 40369.37 | 0.792 | 1.304 | 1.855 | 0 |
| 2 | AvajeNima | 42201.95 | 0.757 | 1.215 | 1.641 | 0 |
| 2 | FastJava | 93976.64 | 0.340 | 0.545 | 0.922 | 0 |
| 3 | Tomcat | 75606.87 | 0.423 | 0.898 | 1.330 | 0 |
| 3 | Netty | 89935.57 | 0.355 | 0.708 | 1.184 | 0 |
| 3 | HelidonNima | 92674.31 | 0.344 | 0.823 | 1.189 | 0 |
| 3 | AvajeNima | 42499.70 | 0.752 | 1.263 | 1.665 | 0 |
| 3 | FastJava | 102477.49 | 0.311 | 0.495 | 0.878 | 0 |
| 3 | Undertow | 106885.44 | 0.299 | 0.536 | 0.958 | 0 |
| 4 | Netty | 92768.92 | 0.344 | 0.634 | 1.098 | 0 |
| 4 | HelidonNima | 89034.82 | 0.358 | 0.958 | 1.501 | 0 |
| 4 | AvajeNima | 42585.12 | 0.751 | 1.216 | 1.689 | 0 |
| 4 | FastJava | 101971.53 | 0.313 | 0.505 | 0.857 | 0 |
| 4 | Undertow | 104089.36 | 0.307 | 0.512 | 1.141 | 0 |
| 4 | Tomcat | 75465.00 | 0.423 | 0.891 | 1.294 | 0 |
| 5 | HelidonNima | 98643.80 | 0.323 | 0.603 | 1.161 | 0 |
| 5 | AvajeNima | 41340.00 | 0.773 | 1.255 | 1.683 | 0 |
| 5 | FastJava | 99022.13 | 0.322 | 0.523 | 0.823 | 0 |
| 5 | Undertow | 104026.51 | 0.307 | 0.547 | 1.053 | 0 |
| 5 | Tomcat | 74872.51 | 0.427 | 0.900 | 1.332 | 0 |
| 5 | Netty | 103676.94 | 0.308 | 0.574 | 0.959 | 0 |
