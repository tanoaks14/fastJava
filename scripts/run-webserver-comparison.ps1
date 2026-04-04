Param(
    [int]$WarmupRequests = 30000,
    [int]$BenchmarkRequests = 1500000,
    [int]$Concurrency = 64,
    [int]$Rounds = 5,
    [ValidateSet("FastJava", "Undertow", "Tomcat", "Netty", "HelidonNima", "AvajeNima")]
    [string]$ServerName,
    [switch]$PeakTune
)

$ErrorActionPreference = "Stop"

Write-Host "Compiling test classes..."
mvn -q -DskipTests test-compile

Write-Host "Building test runtime classpath..."
mvn -q "-Dmdep.includeScope=test" "-Dmdep.outputFile=target/webserver-bench-classpath.txt" dependency:build-classpath

$cpFile = "target/webserver-bench-classpath.txt"
if (!(Test-Path $cpFile)) {
    throw "Missing classpath file: $cpFile"
}

$runtimeClasspath = "target/test-classes;target/classes;" + (Get-Content $cpFile -Raw)

Write-Host "Running comparison benchmark..."
$env:FASTJAVA_BENCH_WARMUP_REQUESTS = "$WarmupRequests"
$env:FASTJAVA_BENCH_BENCHMARK_REQUESTS = "$BenchmarkRequests"
$env:FASTJAVA_BENCH_CONCURRENCY = "$Concurrency"
$env:FASTJAVA_BENCH_ROUNDS = "$Rounds"
$env:FASTJAVA_BENCH_PEAK_TUNE = if ($PeakTune) { "true" } else { "false" }

if ([string]::IsNullOrWhiteSpace($ServerName)) {
    java --add-modules jdk.incubator.vector -cp $runtimeClasspath com.fastjava.bench.comparison.WebServerComparisonBenchmark
}
else {
    java --add-modules jdk.incubator.vector -cp $runtimeClasspath com.fastjava.bench.comparison.WebServerComparisonBenchmark --single-server $ServerName
}

Write-Host "Done. See benchmarks/webserver-comparison/results/latest-results.md"
