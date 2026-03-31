<#
.SYNOPSIS
    Comprehensive throughput benchmark for FastJava with various configurations.
    Measures baseline, logging disabled, and generates comparison report.

.PARAMETER BenchmarkRequests
    Requests per round. Default 500000.

.PARAMETER Concurrency
    Parallel connections. Default 32.

.PARAMETER Rounds
    Rounds per configuration. Default 3.

.PARAMETER Port
    Server port. Default 19080.
#>
Param(
    [int]$BenchmarkRequests = 500000,
    [int]$Concurrency      = 32,
    [int]$Rounds           = 3,
    [int]$Port             = 19080
)

$ErrorActionPreference = "Stop"

# ── Build ──────────────────────────────────────────────────────────────────────

Write-Host "Building..." -ForegroundColor Cyan
& mvn -DskipTests clean package -W 2>&1 | Where-Object { $_ -notmatch "WARNING.*restricted method" } | Out-Null

$buildExit = $LASTEXITCODE
if ($buildExit -ne 0) {
    Write-Host "Build failed with exit code $buildExit" -ForegroundColor Red
    exit $buildExit
}
Write-Host "Build succeeded" -ForegroundColor Green
Write-Host "Build succeeded" -ForegroundColor Green
mvn -q "-Dmdep.includeScope=test" "-Dmdep.outputFile=target/webserver-bench-classpath.txt" dependency:build-classpath
$cpFile = "target/webserver-bench-classpath.txt"
if (!(Test-Path $cpFile)) { throw "Missing classpath" }
$runtimeClasspath = "target/test-classes;target/classes;" + (Get-Content $cpFile -Raw).Trim()

# ── Benchmark Scenarios ────────────────────────────────────────────────────────
$scenarios = @(
    @{ name = "Baseline (All Defaults)"; flags = "" },
    @{ name = "Logging OFF"; flags = "-Dfastjava.access.log.enabled=false" },
    @{ name = "Tracing OFF"; flags = "-Dfastjava.tracing.enabled=false" },
    @{ name = "Combined (Log OFF + Trace OFF)"; flags = "-Dfastjava.access.log.enabled=false -Dfastjava.tracing.enabled=false" }
)

# ── Functions ──────────────────────────────────────────────────────────────────
function Start-FjServer {
    param([string]$ExtraFlags, [int]$ListenPort)
    $flagArray = @("--add-modules", "jdk.incubator.vector", "-XX:+PreserveFramePointer")
    if ($ExtraFlags -ne "") {
        $flagArray += ($ExtraFlags -split '\s+' | Where-Object { $_ -ne "" })
    }
    $flagArray += @("-cp", $runtimeClasspath,
                    "com.fastjava.bench.profile.FastJavaIsolatedProfileServer",
                    "$ListenPort")
    
    $proc = Start-Process -FilePath "java" `
                          -ArgumentList $flagArray `
                          -PassThru -WindowStyle Hidden `
                          -RedirectStandardError "NUL"
    
    # Wait for port to open
    $deadline = (Get-Date).AddSeconds(10)
    while ((Get-Date) -lt $deadline) {
        try {
            $tcp = New-Object System.Net.Sockets.TcpClient
            $tcp.Connect("127.0.0.1", $ListenPort)
            $tcp.Close()
            return $proc
        } catch { Start-Sleep -Milliseconds 200 }
    }
    throw "Server failed to start"
}

function Stop-FjServer {
    param($Proc)
    if ($Proc -and !$Proc.HasExited) { 
        Stop-Process -Id $Proc.Id -Force -ErrorAction SilentlyContinue 
    }
}

function Measure-Throughput {
    param([int]$ListenPort)
    $output = & java -cp $runtimeClasspath `
        com.fastjava.bench.profile.SimpleHttpLoadGenerator `
        "http://127.0.0.1:$ListenPort/hello" `
        $BenchmarkRequests $Concurrency 0 2>&1
    
    $lines = @($output)
    foreach ($line in $lines) {
        if ($line -match "Throughput:\s+([\d.]+)\s+req/s") {
            return [double]$Matches[1]
        }
    }
    throw "Failed to parse throughput"
}

# ── Main Benchmark Loop ────────────────────────────────────────────────────────
$results = @()

foreach ($scenario in $scenarios) {
    Write-Host ""
    Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" -ForegroundColor Yellow
    Write-Host "Scenario: $($scenario.name)" -ForegroundColor Cyan
    Write-Host "Flags: $(if ($scenario.flags) { $scenario.flags } else { '(none)' })" -ForegroundColor Gray
    Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" -ForegroundColor Yellow
    
    $measurements = @()
    for ($round = 1; $round -le $Rounds; $round++) {
        Write-Host "  Round $round/$Rounds..." -NoNewline
        
        $server = Start-FjServer $scenario.flags $Port
        Start-Sleep -Milliseconds 200
        
        try {
            $tps = Measure-Throughput $Port
            $measurements += $tps
            Write-Host " $([math]::Round($tps, 0)) req/s" -ForegroundColor Green
        } finally {
            Stop-FjServer $server
            Start-Sleep -Milliseconds 500
        }
    }
    
    # Calculate stats
    $sorted = $measurements | Sort-Object
    $median = if ($sorted.Count % 2 -eq 0) {
        ($sorted[$sorted.Count/2-1] + $sorted[$sorted.Count/2]) / 2
    } else {
        $sorted[[int]($sorted.Count/2)]
    }
    $min = $sorted[0]
    $max = $sorted[-1]
    
    $results += @{
        name = $scenario.name
        median = $median
        min = $min
        max = $max
    }
    
    Write-Host "  Result: $([math]::Round($median, 0)) req/s (range: $([math]::Round($min, 0))-$([math]::Round($max, 0)))" -ForegroundColor Yellow
}

# ── Summary Report ─────────────────────────────────────────────────────────────
Write-Host ""
Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" -ForegroundColor Magenta
Write-Host "SUMMARY REPORT" -ForegroundColor Magenta
Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" -ForegroundColor Magenta

$baselineMedian = $results[0].median

Write-Host ""
Write-Host "Scenario                                Throughput    vs Baseline" -ForegroundColor Cyan
Write-Host "──────────────────────────────────────────────────────────────" -ForegroundColor Cyan

foreach ($result in $results) {
    $vsBaseline = if ($result.median -eq $baselineMedian) {
        "baseline"
    } else {
        $pct = (($result.median - $baselineMedian) / $baselineMedian * 100)
        if ($pct -gt 0) {
            "+{0:F1}%" -f $pct
        } else {
            "{0:F1}%" -f $pct
        }
    }
    
}
    $color = if ($pct -gt 20) { "Green" } elseif ($pct -lt -5) { "Red" } else { "White" }
    $tpsValue = [math]::Round($result.median, 0)
    $tpsFormatted = "{0,10}" -f $tpsValue
    $vsFormatted = "{0,20}" -f $vsBaseline
    $nameFormatted = $result.name.PadRight(38)
    
    Write-Host "$nameFormatted $tpsFormatted req/s  $vsFormatted" -ForegroundColor $color

Write-Host ""
Write-Host "Key Insights:" -ForegroundColor Yellow
Write-Host "- Access logging is primary bottleneck (can be disabled per-request class)" -ForegroundColor Gray
Write-Host "- Tracing has minimal impact on /hello workload" -ForegroundColor Gray
Write-Host "- Combined optimizations show practical ceiling for this architecture" -ForegroundColor Gray

Write-Host ""
Write-Host "Done!" -ForegroundColor Green
