<#
.SYNOPSIS
    Fast throughput benchmark - reuses compiled artifacts.
    Measures baseline, logging disabled, tracing disabled configs.
#>
Param(
    [int]$BenchmarkRequests = 300000,
    [int]$Concurrency      = 32,
    [int]$Rounds           = 2,
    [int]$Port             = 19080
)

$ErrorActionPreference = "Stop"

# Check artifacts exist
$cpFile = "target/webserver-bench-classpath.txt"
if (!(Test-Path $cpFile)) { 
    Write-Host "Building..." -ForegroundColor Yellow
    mvn -q -DskipTests clean package
    mvn -q "-Dmdep.includeScope=test" "-Dmdep.outputFile=target/webserver-bench-classpath.txt" dependency:build-classpath
}

$runtimeClasspath = "target/test-classes;target/classes;" + (Get-Content $cpFile -Raw).Trim()

$scenarios = @(
    @{ name = "Baseline"; flags = "" },
    @{ name = "Logging OFF"; flags = "-Dfastjava.access.log.enabled=false" }
)

function Start-FjServer {
    param([string]$ExtraFlags, [int]$ListenPort)
    $flagArray = @("--add-modules", "jdk.incubator.vector")
    if ($ExtraFlags -ne "") {
        $flagArray += ($ExtraFlags -split '\s+' | Where-Object { $_ -ne "" })
    }
    $flagArray += @("-cp", $runtimeClasspath,
                    "com.fastjava.bench.profile.FastJavaIsolatedProfileServer",
                    "$ListenPort")
    
    
    $proc = Start-Process -FilePath "java" `
                          -ArgumentList $flagArray `
                          -PassThru -WindowStyle Hidden -NoNewWindow
    # Wait for port
    $deadline = (Get-Date).AddSeconds(10)
    while ((Get-Date) -lt $deadline) {
        try {
            $tcp = New-Object System.Net.Sockets.TcpClient
            $tcp.Connect("127.0.0.1", $ListenPort)
            $tcp.Close()
            return $proc
        } catch { Start-Sleep -Milliseconds 200 }
    }
    throw "Server failed to start within 10s"
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
    
    foreach ($line in $output) {
        if ($line -match "Throughput:\s+([\d.]+)\s+req/s") {
            return [double]$Matches[1]
        }
    }
    throw "Failed to parse throughput from: $output"
}

# ── Main ────────────────────────────────────────────────────────────────────
$results = @()

foreach ($scenario in $scenarios) {
    Write-Host ""
    Write-Host "Testing: $($scenario.name)" -ForegroundColor Cyan
    
    $measurements = @()
        
    for ($round = 1; $round -le $Rounds; $round++) {
        Write-Host "  Round $round/$($Rounds): " -NoNewline
        $server = Start-FjServer $scenario.flags $Port
        Start-Sleep -Milliseconds 300
        
        try {
            $tps = Measure-Throughput $Port
            $measurements += $tps
            Write-Host "$([math]::Round($tps, 0)) req/s" -ForegroundColor Green
        } catch {
            Write-Host "ERROR: $_" -ForegroundColor Red
            throw
        } finally {
            Stop-FjServer $server
            Start-Sleep -Milliseconds 500
        }
    }
    
    $sorted = $measurements | Sort-Object
    $median = if ($sorted.Count % 2 -eq 0) {
        ($sorted[$sorted.Count/2-1] + $sorted[$sorted.Count/2]) / 2
    } else {
        $sorted[[int]($sorted.Count/2)]
    }
    
    $results += @{
        name = $scenario.name
        median = $median
        min = $sorted[0]
        max = $sorted[-1]
    }
}

# ── Report ──────────────────────────────────────────────────────────────────
Write-Host ""
Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" -ForegroundColor Magenta
Write-Host "RESULTS" -ForegroundColor Magenta
Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" -ForegroundColor Magenta

$baselineMedian = $results[0].median

foreach ($result in $results) {
    $tpsValue = [math]::Round($result.median, 0)
    if ($result.median -eq $baselineMedian) {
        Write-Host "$($result.name): $tpsValue req/s (baseline)" -ForegroundColor Green
    } else {
        $pct = (($result.median - $baselineMedian) / $baselineMedian * 100)
        $sign = if ($pct -gt 0) { "+" } else { "" }
        $color = if ($pct -gt 10) { "Green" } elseif ($pct -lt -5) { "Red" } else { "Yellow" }
        Write-Host "$($result.name): $tpsValue req/s ($sign$([math]::Round($pct, 1))%)" -ForegroundColor $color
    }
}

Write-Host ""

    $proc = Start-Process -FilePath "java" `
                          -ArgumentList $flagArray `
                          -PassThru -WindowStyle Hidden

    $proc = Start-Process -FilePath "java" `
                          -ArgumentList $flagArray `
                          -PassThru -WindowStyle Hidden
