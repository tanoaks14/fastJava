<#
.SYNOPSIS
    A/B throughput benchmark for FastJava using the isolated profile server.
    Runs both sides in the same session so machine state is consistent.

.PARAMETER BaselineFlags
    Additional JVM system properties for the baseline run.
    Example: "-Dfastjava.tracing.enabled=false"

.PARAMETER CandidateFlags
    Additional JVM system properties for the candidate run.
    Example: "-Dfastjava.tracing.enabled=false -Dfastjava.access.log.disabled=true"

.PARAMETER WarmupRequests
    Requests to discard before measuring. Default 10000.

.PARAMETER BenchmarkRequests
    Requests to measure per round. Default 100000.

.PARAMETER Concurrency
    Parallel HTTP connections. Default 32.

.PARAMETER Rounds
    How many rounds to run for each side. Median is used. Default 5.

.PARAMETER Port
    Port for the isolated server. Default 19080.

.PARAMETER RoutePath
    Route to benchmark (for dynamic servlet focus use /api/hello).
    Default /api/hello.

.EXAMPLE
    # Compare tracing off vs tracing off + access log off
    .\scripts\ab-benchmark.ps1 `
        -BaselineFlags "" `
        -CandidateFlags "-Dfastjava.access.log.disabled=true"

.EXAMPLE
    # Compare SSE eager (default) vs SSE skipped
    .\scripts\ab-benchmark.ps1 `
        -BaselineFlags "" `
        -CandidateFlags "-Dfastjava.sse.skip=true"
#>
Param(
    [string]$BaselineFlags   = "",
    [string]$CandidateFlags  = "",
    [int]$WarmupRequests     = 10000,
    [int]$BenchmarkRequests  = 100000,
    [int]$Concurrency        = 32,
    [int]$Rounds             = 5,
    [int]$Port               = 19080,
    [string]$RoutePath       = "/api/hello"
)

$ErrorActionPreference = "Stop"

# ── Build ──────────────────────────────────────────────────────────────────────
Write-Host "Compiling test classes..."
mvn -q -DskipTests test-compile
mvn -q "-Dmdep.includeScope=test" "-Dmdep.outputFile=target/webserver-bench-classpath.txt" dependency:build-classpath

$cpFile = "target/webserver-bench-classpath.txt"
if (!(Test-Path $cpFile)) { throw "Missing classpath file: $cpFile" }
$runtimeClasspath = "target/test-classes;target/classes;" + (Get-Content $cpFile -Raw).Trim()

# ── Helpers ───────────────────────────────────────────────────────────────────
function Start-FjServer {
    param([string]$ExtraFlags, [int]$ListenPort)
    $flagArray = @("--add-modules", "jdk.incubator.vector",
                   "-Dfastjava.tracing.enabled=false")
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
    # wait until port is open (max 10 s)
    $deadline = (Get-Date).AddSeconds(10)
    while ((Get-Date) -lt $deadline) {
        try {
            $tcp = New-Object System.Net.Sockets.TcpClient
            $tcp.Connect("127.0.0.1", $ListenPort)
            $tcp.Close()
            break
        } catch { Start-Sleep -Milliseconds 200 }
    }
    return $proc
}

function Stop-FjServer {
    param($Proc)
    if ($Proc -and !$Proc.HasExited) { Stop-Process -Id $Proc.Id -Force -ErrorAction SilentlyContinue }
}

function Measure-Throughput {
    param([int]$ListenPort)
    $output = & java -cp $runtimeClasspath `
        com.fastjava.bench.profile.SimpleHttpLoadGenerator `
        "http://127.0.0.1:$ListenPort$RoutePath" `
        $BenchmarkRequests $Concurrency $WarmupRequests 2>&1
    $throughput = $null
    $success = $null
    $errors = $null
    foreach ($line in $output) {
        if ($line -match "LOAD_RESULT\s+throughput=([\d.]+)\s+req/s\s+success=(\d+)\s+errors=(\d+)") {
            $throughput = [double]$Matches[1]
            $success = [long]$Matches[2]
            $errors = [long]$Matches[3]
            break
        }
    }
    if ($null -ne $throughput) {
        return [PSCustomObject]@{
            Throughput = $throughput
            Success = $success
            Errors = $errors
        }
    }
    throw "Load generator produced no throughput line.`n$($output -join "`n")"
}

function Get-Median {
    param([double[]]$Values)
    $sorted = $Values | Sort-Object
    $mid = [int]($sorted.Count / 2)
    if ($sorted.Count % 2 -eq 0) { return ($sorted[$mid - 1] + $sorted[$mid]) / 2.0 }
    return $sorted[$mid]
}

function Get-MedianLong {
    param([long[]]$Values)
    $sorted = $Values | Sort-Object
    $mid = [int]($sorted.Count / 2)
    if ($sorted.Count % 2 -eq 0) {
        return [long](($sorted[$mid - 1] + $sorted[$mid]) / 2)
    }
    return [long]$sorted[$mid]
}

# ── Run one side ───────────────────────────────────────────────────────────────
function Invoke-Side {
    param([string]$Label, [string]$Flags, [int]$ListenPort)
    Write-Host ""
    Write-Host "── $Label ──"
    if ($Flags -ne "") { Write-Host "   JVM flags: $Flags" }
    $throughputs = @()
    $successes = @()
    $errors = @()
    for ($r = 1; $r -le $Rounds; $r++) {
        $srv = Start-FjServer -ExtraFlags $Flags -ListenPort $ListenPort
        try {
            $sample = Measure-Throughput -ListenPort $ListenPort
            $throughputs += [double]$sample.Throughput
            $successes += [long]$sample.Success
            $errors += [long]$sample.Errors
            Write-Host ("  round {0}: {1:N2} req/s, success={2}, errors={3}" -f $r, $sample.Throughput, $sample.Success, $sample.Errors)
        } finally {
            Stop-FjServer -Proc $srv
            Start-Sleep -Milliseconds 300
        }
    }
    return [PSCustomObject]@{
        Throughputs = $throughputs
        Successes = $successes
        Errors = $errors
    }
}

# ── Main ───────────────────────────────────────────────────────────────────────
Write-Host ""
Write-Host "=== FastJava A/B Benchmark ==="
Write-Host ("Route={0}" -f $RoutePath)
Write-Host ("Warmup={0}  Benchmark={1}  Concurrency={2}  Rounds={3}" -f $WarmupRequests, $BenchmarkRequests, $Concurrency, $Rounds)

$baseline  = Invoke-Side -Label "Baseline"  -Flags $BaselineFlags  -ListenPort $Port
$candidate = Invoke-Side -Label "Candidate" -Flags $CandidateFlags -ListenPort $Port

$baselineMedian  = Get-Median -Values $baseline.Throughputs
$candidateMedian = Get-Median -Values $candidate.Throughputs
$delta           = $candidateMedian - $baselineMedian
$pct             = if ($baselineMedian -gt 0) { ($delta / $baselineMedian) * 100 } else { 0 }
$sign            = if ($delta -ge 0) { "+" } else { "" }
$baselineErrorsMedian = Get-MedianLong -Values $baseline.Errors
$candidateErrorsMedian = Get-MedianLong -Values $candidate.Errors
$baselineSuccessMedian = Get-MedianLong -Values $baseline.Successes
$candidateSuccessMedian = Get-MedianLong -Values $candidate.Successes

Write-Host ""
Write-Host "=== Results ==="
Write-Host ("Baseline  median : {0,10:N2} req/s   (rounds: {1})" -f $baselineMedian,  ($baseline.Throughputs  | ForEach-Object { "{0:N0}" -f $_ }) -join ", ")
Write-Host ("Candidate median : {0,10:N2} req/s   (rounds: {1})" -f $candidateMedian, ($candidate.Throughputs | ForEach-Object { "{0:N0}" -f $_ }) -join ", ")
Write-Host ("Baseline  medians: success={0} errors={1}" -f $baselineSuccessMedian, $baselineErrorsMedian)
Write-Host ("Candidate medians: success={0} errors={1}" -f $candidateSuccessMedian, $candidateErrorsMedian)
Write-Host ("Delta            : {0}{1:N2} req/s  ({0}{2:N2}%)" -f $sign, $delta, $pct)
Write-Host ""
if ($delta -gt 0) {
    Write-Host "VERDICT: Candidate is FASTER"
} elseif ($delta -lt 0) {
    Write-Host "VERDICT: Candidate is SLOWER (regression)"
} else {
    Write-Host "VERDICT: No measurable difference"
}
