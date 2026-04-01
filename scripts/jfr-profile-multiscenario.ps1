<#
.SYNOPSIS
    Multi-scenario JFR profiling for FastJava, Undertow, and Netty.
    Captures CPU, allocations, and lock contention across 3 test scenarios.

.PARAMETER ServerName
    Server to profile: "FastJava", "Undertow", "Tomcat", "Netty". Default: "FastJava"

.PARAMETER Scenario
    Test scenario: "simple" (GET /hello), "dynamic" (GET /api/hello), "static" (static files).
    Default: "simple"

.PARAMETER WarmupRequests
    Number of requests for warm-up (before JFR capture). Default: 10000

.PARAMETER BenchmarkRequests
    Number of requests during JFR capture. Default: 50000

.PARAMETER Concurrency
    Thread count. Default: 32

.PARAMETER JfrDuration
    JFR capture duration in seconds. Default: 40

.PARAMETER OutputDir
    Directory to save JFR and heap files. Default: "target/profiles/multi-scenario"

.EXAMPLE
    # Profile FastJava simple GET scenario
    .\scripts\jfr-profile-multiscenario.ps1 -ServerName FastJava -Scenario simple

.EXAMPLE
    # Profile Netty on static file serving
    .\scripts\jfr-profile-multiscenario.ps1 -ServerName Netty -Scenario static -Concurrency 64
#>

Param(
    [ValidateSet("FastJava", "Undertow", "Tomcat", "Netty")]
    [string]$ServerName = "FastJava",
    
    [ValidateSet("simple", "dynamic", "static")]
    [string]$Scenario = "simple",
    
    [int]$WarmupRequests = 10000,
    [int]$BenchmarkRequests = 50000,
    [int]$Concurrency = 32,
    [int]$JfrDuration = 40,
    [string]$OutputDir = "target/profiles/multi-scenario"
)

$ErrorActionPreference = "Stop"

# ─────────────────────────────────────────────────────────────────────────────
# Setup
# ─────────────────────────────────────────────────────────────────────────────

$timestamp = (Get-Date -Format "yyyyMMdd-HHmmss")
$scenarioOutputDir = Join-Path $OutputDir "$ServerName/$Scenario/$timestamp"
New-Item -ItemType Directory -Path $scenarioOutputDir -Force | Out-Null

Write-Host "═══════════════════════════════════════════════════════════════════"
Write-Host "JFR Multi-Scenario Profile: $ServerName / $Scenario"
Write-Host "═══════════════════════════════════════════════════════════════════"
Write-Host "Warmup: $WarmupRequests, Benchmark: $BenchmarkRequests, Concurrency: $Concurrency"
Write-Host "JFR Duration: $JfrDuration seconds"
Write-Host "Output Dir: $scenarioOutputDir"
Write-Host ""

# ─────────────────────────────────────────────────────────────────────────────
# Build classpath
# ─────────────────────────────────────────────────────────────────────────────

Write-Host "Building test runtime classpath..."
mvn -q -DskipTests test-compile 2>&1 | Out-Null
mvn -q "-Dmdep.includeScope=test" "-Dmdep.outputFile=target/webserver-bench-classpath.txt" dependency:build-classpath 2>&1 | Out-Null

$cpFile = "target/webserver-bench-classpath.txt"
if (!(Test-Path $cpFile)) {
    throw "Missing classpath file: $cpFile"
}

$runtimeClasspath = "target/test-classes;target/classes;" + (Get-Content $cpFile -Raw)

# ─────────────────────────────────────────────────────────────────────────────
# Start server in isolated JVM with JFR enabled
# ─────────────────────────────────────────────────────────────────────────────

Write-Host "Starting $ServerName in isolated JVM with JFR enabled..."

$javaExe = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME "bin\java.exe" } else { "" }
if ([string]::IsNullOrWhiteSpace($javaExe) -or !(Test-Path $javaExe)) {
    $javaCommand = Get-Command java -ErrorAction SilentlyContinue
    if ($null -eq $javaCommand) {
        throw "Java executable not found. Set JAVA_HOME or add java to PATH."
    }
    $javaExe = $javaCommand.Source
    Write-Host "JAVA_HOME not usable; falling back to PATH java: $javaExe"
}

$jfrStart = Join-Path $scenarioOutputDir "jfr-start-recording.jfr"
$jfrFile = Join-Path $scenarioOutputDir "profile-$Scenario.jfr"
$heapFile = Join-Path $scenarioOutputDir "heap-histogram-$Scenario.txt"

# Start server process with JFR, capturing to file after initial recording starts
$serverProcArgs = @(
    "--add-modules", "jdk.incubator.vector",
    "-Dcom.sun.management.jmxremote",
    "-Dcom.sun.management.jmxremote.port=9010",
    "-Dcom.sun.management.jmxremote.authenticate=false",
    "-Dcom.sun.management.jmxremote.ssl=false",
    "-Dcom.sun.management.jmxremote.rmi.port=9010",
    "-cp",
    $runtimeClasspath,
    "com.fastjava.bench.comparison.WebServerComparisonBenchmark",
    "--single-server",
    $ServerName
)

$urls = @{
    simple = "http://127.0.0.1:9876/hello"
    dynamic = "http://127.0.0.1:9876/api/hello"
    static = "http://127.0.0.1:9876/static/asset.txt"
}
$url = $urls[$Scenario]

try {
    # Start in background (redirects stdout/stderr to null to avoid blocking)
    $serverProcess = Start-Process -FilePath $javaExe -ArgumentList $serverProcArgs -PassThru -NoNewWindow
    $serverPid = $serverProcess.Id
    Write-Host "Server PID: $serverPid (waiting 5s for startup...)"
    
    Start-Sleep -Seconds 5
    
    # Verify server is running
    $proc = Get-Process -Id $serverPid -ErrorAction SilentlyContinue
    if (!$proc) {
        Write-Error "Server process failed to start or exited prematurely."
        exit 1
    }
    
    # ─────────────────────────────────────────────────────────────────────────────
    # Send warm-up requests in parallel
    # ─────────────────────────────────────────────────────────────────────────────
    
    Write-Host ""
    Write-Host "Sending warm-up requests ($WarmupRequests, $Concurrency threads)..."
    & $javaExe -cp $runtimeClasspath `
        com.fastjava.bench.comparison.jfr.JfrLoadGenerator `
        "--url=$url" `
        "--requests=$WarmupRequests" `
        "--concurrency=$Concurrency" | Out-Host
    
    Write-Host "Warm-up sent. Waiting 3s..."
    Start-Sleep -Seconds 3
    
    # ─────────────────────────────────────────────────────────────────────────────
    # Start JFR recording
    # ─────────────────────────────────────────────────────────────────────────────
    
    Write-Host "Starting JFR recording for $JfrDuration seconds..."
    
    $jcmdRecArgs = @(
        $serverPid,
        "JFR.start",
        "name=profile-$Scenario",
        "settings=profile",
        "duration=${JfrDuration}s",
        "filename=$jfrFile"
    )
    
    & jcmd @jcmdRecArgs
    
    if ($LASTEXITCODE -ne 0) {
        Write-Error "JFR.start failed (exit $LASTEXITCODE)"
        exit 1
    }
    
    # ─────────────────────────────────────────────────────────────────────────────
    # Send benchmark requests during JFR capture
    # ─────────────────────────────────────────────────────────────────────────────
    
    Write-Host "Sending benchmark requests while JFR records ($BenchmarkRequests, $Concurrency threads)..."
    & $javaExe -cp $runtimeClasspath `
        com.fastjava.bench.comparison.jfr.JfrLoadGenerator `
        "--url=$url" `
        "--requests=$BenchmarkRequests" `
        "--concurrency=$Concurrency" | Out-Host

    # Give JFR a brief buffer to flush recording output.
    Start-Sleep -Seconds 2
    
    # ─────────────────────────────────────────────────────────────────────────────
    # Capture heap histogram
    # ─────────────────────────────────────────────────────────────────────────────
    
    Write-Host "Capturing heap histogram..."
    & jcmd $serverPid GC.class_histogram | Out-File -FilePath $heapFile -Encoding utf8
    
    Write-Host ""
    Write-Host "═══════════════════════════════════════════════════════════════════"
    Write-Host "Profile Complete"
    Write-Host "═══════════════════════════════════════════════════════════════════"
    Write-Host "JFR File  : $jfrFile"
    Write-Host "Heap File : $heapFile"
    Write-Host ""
    Write-Host "Next: Open $jfrFile in JDK Mission Control (jmc)"
    Write-Host ""
    
} finally {
    # Clean up: kill server if still running
    if ($serverProcess -and !$serverProcess.HasExited) {
        Write-Host "Stopping server process..."
        Stop-Process -Id $serverPid -Force -ErrorAction SilentlyContinue
        Wait-Process -Id $serverPid -ErrorAction SilentlyContinue
    }
}
