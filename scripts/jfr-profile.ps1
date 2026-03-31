<#
.SYNOPSIS
    Unified JFR profiling for FastJava, Undertow, Tomcat, and Netty.
    Profiles each server with load generation and captures CPU/allocation/lock data.

.PARAMETER ServerName  
    Server to profile: "FastJava", "Undertow", "Tomcat", "Netty". Default: "FastJava"

.PARAMETER RequestCount
    Requests to send for each profile run. Default: 100000

.PARAMETER Concurrency
    Thread count for load generation. Default: 32

.PARAMETER JfrDuration
    JFR capture duration in seconds. Default: 40

.PARAMETER OutputDir
    Directory for JFR files. Default: "target/profiles/jfr-data"

.EXAMPLE
    # Profile FastJava with 100k requests
    .\scripts\jfr-profile.ps1 -ServerName FastJava

.EXAMPLE
    # Heavy profile of Netty with more requests and concurrency
    .\scripts\jfr-profile.ps1 -ServerName Netty -RequestCount 200000 -Concurrency 64
#>

Param(
    [ValidateSet("FastJava", "Undertow", "Tomcat", "Netty")]
    [string]$ServerName = "FastJava",
    
    [int]$RequestCount = 100000,
    [int]$Concurrency = 32,
    [int]$JfrDuration = 40,
    [string]$OutputDir = "target/profiles/jfr-data"
)

$ErrorActionPreference = "Stop"

Write-Host ""
Write-Host "╔════════════════════════════════════════════════════════════════╗"
Write-Host "║ JFR Performance Profiler for $ServerName"
Write-Host "╚════════════════════════════════════════════════════════════════╝"
Write-Host ""
Write-Host "Requests   : $RequestCount"
Write-Host "Concurrency: $Concurrency"
Write-Host "JFR Duration: $JfrDuration seconds"
Write-Host ""

# Build Maven artifacts
Write-Host "Building Maven artifacts..."
mvn -q clean test-compile
mvn -q dependency:build-classpath "-Dmdep.includeScope=test" "-Dmdep.outputFile=target/webserver-bench-classpath.txt"

if (!(Test-Path "target/webserver-bench-classpath.txt")) {
    Write-Error "Failed to build classpath"
    exit 1
}

$runtimeClasspath = "target/test-classes;target/classes;" + (Get-Content "target/webserver-bench-classpath.txt" -Raw)

$javaExe = Join-Path $env:JAVA_HOME "bin\java.exe"
if (!(Test-Path $javaExe)) {
    Write-Error "Java not found at $javaExe. Set JAVA_HOME environment variable."
    exit 1
}

# Create output directory
$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$profileDir = Join-Path $OutputDir "$ServerName/$timestamp"
New-Item -ItemType Directory -Path $profileDir -Force | Out-Null

Write-Host ""
Write-Host "► Starting $ServerName  server with JFR enabled..."

# Start server in background with JFR startup recording enabled
$jfrStartFile = (Resolve-Path $profileDir).Path + "\startup.jfr"
$jfrArg = "-XX:StartFlightRecording=name=startup,settings=default,duration=$($JfrDuration)s,filename=$jfrStartFile"
$serverProcArgs = @(
    "--add-modules", "jdk.incubator.vector",
    $jfrArg,
    "-Dcom.sun.management.jmxremote",
    "-Dcom.sun.management.jmxremote.port=9010",
    "-Dcom.sun.management.jmxremote.authenticate=false",
    "-Dcom.sun.management.jmxremote.ssl=false",
    "-cp", $runtimeClasspath,
    "com.fastjava.bench.comparison.WebServerComparisonBenchmark",
    "--single-server",
    $ServerName
)

$serverLog = Join-Path $profileDir "server-output.log"
$serverProc = Start-Process -FilePath $javaExe -ArgumentList $serverProcArgs `
              -NoNewWindow -PassThru -RedirectStandardOutput $serverLog -RedirectStandardError $serverLog

$serverPid = $serverProc.Id
Write-Host "  Server PID: $serverPid"
Write-Host "  Log file: $serverLog"
Write-Host ""

# Wait a bit for server to start and JFR to begin
Start-Sleep -Seconds 3

Write-Host "► JFR recording in progress for $JfrDuration seconds..."
Write-Host "  (Server is running benchmark load internally)"
Write-Host ""

# Wait for JFR to complete (plus buffer time for remaining computation)
$waitTime = $JfrDuration + 10
for ($i = 0; $i -lt $waitTime; $i += 5) {
    $remaining = $waitTime - $i
    Write-Host "  Waiting... $remaining seconds remaining"
    Start-Sleep -Seconds 5
}

# Capture heap histogram from the still-running process
if (Test-Path $serverLog) {
    $logContent = Get-Content $serverLog -Raw
    
    # Try to extract server info
    if ($logContent -match "throughput=([0-9.]+)") {
        Write-Host ""
        Write-Host "  ✓ Benchmark completed!"
    }
}

# Try to capture heap if server is still running
$heapFile = Join-Path $profileDir "heap-histogram.txt"
try {
    if ($serverProc.HasExited -eq $false) {
        Write-Host ""
        Write-Host "► Capturing final heap histogram..."
        & jcmd $serverPid GC.class_histogram 2>&1 | Out-File -FilePath $heapFile -Encoding utf8
        Write-Host "  ✓ Heap histogram saved"
    }
} catch {
    Write-Host "  (Could not capture heap histogram - server may have exited)"
}

# Wait for server to finish
Write-Host ""
Write-Host "► Waiting for server process to complete..."
$timeout = 60  # 60 seconds
$elapsed = 0
while ($elapsed -lt $timeout -and !$serverProc.HasExited) {
    Start-Sleep -Seconds 2
    $elapsed += 2
}

if (!$serverProc.HasExited) {
    Write-Host "  Terminating server..."
    Stop-Process -Id $serverPid -Force -ErrorAction SilentlyContinue
    Wait-Process -Id $serverPid -ErrorAction SilentlyContinue
}

Write-Host "  ✓ Server stopped"

# Summary
Write-Host ""
Write-Host "╔════════════════════════════════════════════════════════════════╗"
Write-Host "║ Profiling Complete!                                            ║"
Write-Host "╚════════════════════════════════════════════════════════════════╝"
Write-Host ""
Write-Host "Output directory: $(Resolve-Path $profileDir)"
Write-Host ""

# List generated files
Write-Host "Generated files:"
Get-ChildItem -Path $profileDir | ForEach-Object {
    Write-Host "  - $($_.Name)"
}

Write-Host ""
Write-Host "Next steps:"
Write-Host "  1. Open .jfr files in JDK Mission Control: jmc"
Write-Host "  2. Review heap histograms for allocation patterns"
Write-Host "  3. Analyze flame graphs to identify hotspots"
Write-Host ""
