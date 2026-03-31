<#
.SYNOPSIS
    Run all flag-permutation combinations for FastJava hot-path flags and
    print a ranked summary table.

    Flags tested (all combinations = 2^N):
      fastjava.access.log.disabled  (access logging)
      fastjava.sse.skip             (SSE emitter eager creation)
      fastjava.tracing.enabled      (OpenTelemetry tracing — default OFF,
                                     so "on" here means explicitly enabling it)

.PARAMETER WarmupRequests   Default 10000
.PARAMETER BenchmarkRequests Default 100000
.PARAMETER Concurrency       Default 32
.PARAMETER Rounds            Default 3  (kept lower for speed; median used)
.PARAMETER Port              Default 19080
#>
Param(
    [int]$WarmupRequests    = 10000,
    [int]$BenchmarkRequests = 100000,
    [int]$Concurrency       = 32,
    [int]$Rounds            = 3,
    [int]$Port              = 19080
)

$ErrorActionPreference = "Stop"

# ── Build ──────────────────────────────────────────────────────────────────────
Write-Host "Compiling test classes..."
mvn -q -DskipTests test-compile
mvn -q "-Dmdep.includeScope=test" "-Dmdep.outputFile=target/webserver-bench-classpath.txt" dependency:build-classpath
$cpFile = "target/webserver-bench-classpath.txt"
if (!(Test-Path $cpFile)) { throw "Missing classpath file: $cpFile" }
$runtimeClasspath = "target/test-classes;target/classes;" + (Get-Content $cpFile -Raw).Trim()

# ── Define all permutations ────────────────────────────────────────────────────
# Each entry: Label, Flags array
# tracing defaults to OFF; we include a "tracing ON" variant explicitly
$permutations = @(
    [PSCustomObject]@{
        Label = "baseline (log=ON  sse=EAGER  trace=OFF)"
        Flags = @()
    },
    [PSCustomObject]@{
        Label = "log=OFF  sse=EAGER  trace=OFF"
        Flags = @("-Dfastjava.access.log.disabled=true")
    },
    [PSCustomObject]@{
        Label = "log=ON   sse=SKIP   trace=OFF"
        Flags = @("-Dfastjava.sse.skip=true")
    },
    [PSCustomObject]@{
        Label = "log=OFF  sse=SKIP   trace=OFF"
        Flags = @("-Dfastjava.access.log.disabled=true", "-Dfastjava.sse.skip=true")
    },
    [PSCustomObject]@{
        Label = "log=ON   sse=EAGER  trace=ON "
        Flags = @("-Dfastjava.tracing.enabled=true")
    },
    [PSCustomObject]@{
        Label = "log=OFF  sse=EAGER  trace=ON "
        Flags = @("-Dfastjava.tracing.enabled=true", "-Dfastjava.access.log.disabled=true")
    },
    [PSCustomObject]@{
        Label = "log=ON   sse=SKIP   trace=ON "
        Flags = @("-Dfastjava.tracing.enabled=true", "-Dfastjava.sse.skip=true")
    },
    [PSCustomObject]@{
        Label = "log=OFF  sse=SKIP   trace=ON "
        Flags = @("-Dfastjava.tracing.enabled=true", "-Dfastjava.access.log.disabled=true", "-Dfastjava.sse.skip=true")
    }
)

# ── Helpers ───────────────────────────────────────────────────────────────────
function Start-FjServer {
    param([string[]]$ExtraFlags, [int]$ListenPort)
    $flagArray = @("--add-modules", "jdk.incubator.vector") + $ExtraFlags +
                 @("-cp", $runtimeClasspath,
                   "com.fastjava.bench.profile.FastJavaIsolatedProfileServer",
                   "$ListenPort")
    $proc = Start-Process -FilePath "java" -ArgumentList $flagArray `
                          -PassThru -WindowStyle Hidden `
                          -RedirectStandardError "NUL"
    $deadline = (Get-Date).AddSeconds(10)
    while ((Get-Date) -lt $deadline) {
        try {
            $tcp = New-Object System.Net.Sockets.TcpClient
            $tcp.Connect("127.0.0.1", $ListenPort); $tcp.Close(); break
        } catch { Start-Sleep -Milliseconds 200 }
    }
    return $proc
}

function Stop-FjServer { param($Proc)
    if ($Proc -and !$Proc.HasExited) { Stop-Process -Id $Proc.Id -Force -ErrorAction SilentlyContinue }
}

function Measure-Throughput { param([int]$ListenPort)
    $output = & java -cp $runtimeClasspath `
        com.fastjava.bench.profile.SimpleHttpLoadGenerator `
        "http://127.0.0.1:$ListenPort/hello" `
        $BenchmarkRequests $Concurrency $WarmupRequests 2>&1
    foreach ($line in $output) {
        if ($line -match "throughput=([\d.]+)") { return [double]$Matches[1] }
    }
    throw "Load generator produced no throughput line.`n$($output -join "`n")"
}

function Get-Median { param([double[]]$Values)
    $sorted = $Values | Sort-Object
    $mid = [int]($sorted.Count / 2)
    if ($sorted.Count % 2 -eq 0) { return ($sorted[$mid-1] + $sorted[$mid]) / 2.0 }
    return $sorted[$mid]
}

# ── Main loop ──────────────────────────────────────────────────────────────────
Write-Host ""
Write-Host "=== FastJava Permutation Benchmark ==="
Write-Host ("Warmup={0}  Benchmark={1}  Concurrency={2}  Rounds={3}" -f $WarmupRequests, $BenchmarkRequests, $Concurrency, $Rounds)
Write-Host ("Running {0} permutations × {1} rounds each..." -f $permutations.Count, $Rounds)

$results = @()

foreach ($perm in $permutations) {
    Write-Host ""
    Write-Host ("-- {0}" -f $perm.Label)
    $roundResults = @()
    for ($r = 1; $r -le $Rounds; $r++) {
        $srv = Start-FjServer -ExtraFlags $perm.Flags -ListenPort $Port
        try {
            $tps = Measure-Throughput -ListenPort $Port
            $roundResults += $tps
            Write-Host ("   round {0}: {1:N0} req/s" -f $r, $tps)
        } finally {
            Stop-FjServer -Proc $srv
            Start-Sleep -Milliseconds 400
        }
    }
    $median = Get-Median -Values $roundResults
    $results += [PSCustomObject]@{
        Label  = $perm.Label
        Median = $median
        Rounds = $roundResults
    }
}

# ── Ranked table ───────────────────────────────────────────────────────────────
$sorted   = $results | Sort-Object Median -Descending
$best     = $sorted[0].Median
$baseline = ($results | Where-Object { $_.Label -like "baseline*" } | Select-Object -First 1).Median

Write-Host ""
Write-Host "=== Results (ranked by median throughput) ==="
Write-Host ("{0,-42} {1,12}  {2,8}  {3}" -f "Configuration", "Median req/s", "vs best", "vs baseline")
Write-Host ("-" * 85)
foreach ($r in $sorted) {
    $vsBest     = if ($best -gt 0)     { "{0:+0.0}%" -f (($r.Median - $best)     / $best     * 100) } else { "n/a" }
    $vsBaseline = if ($baseline -gt 0) { "{0:+0.0}%" -f (($r.Median - $baseline) / $baseline * 100) } else { "n/a" }
    Write-Host ("{0,-42} {1,12:N0}  {2,8}  {3}" -f $r.Label.PadRight(42), $r.Median, $vsBest, $vsBaseline)
}
Write-Host ("-" * 85)
Write-Host ""
Write-Host ("Best:     {0}  →  {1:N0} req/s" -f $sorted[0].Label.Trim(), $sorted[0].Median)
Write-Host ("Baseline: {0}  →  {1:N0} req/s" -f "baseline", $baseline)
$gain = $sorted[0].Median - $baseline
Write-Host ("Max gain: {0:+N0} req/s  ({1:+0.1}%)" -f $gain, ($gain / $baseline * 100))
