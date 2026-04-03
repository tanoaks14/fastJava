#!/usr/bin/env pwsh
<#
.SYNOPSIS
    One-click, repeatable server-only JFR profiling for FastJava.

.DESCRIPTION
    This script starts FastJavaIsolatedProfileServer in a dedicated JVM,
    generates HTTP load, captures JFR + heap histogram, and prints a quick
    hotspot summary using existing parsing scripts.

.PARAMETER DurationSeconds
    JFR recording duration in seconds.

.PARAMETER LoadSeconds
    Duration of synthetic HTTP load in seconds. Defaults to DurationSeconds + 2.

.PARAMETER Concurrency
    Number of parallel load jobs.

.PARAMETER Port
    Server port for FastJavaIsolatedProfileServer.

.PARAMETER OutputDir
    Directory where JFR/heap/log files are written.

.PARAMETER Name
    Base name for output files. Default: isolated-<timestamp>.

.PARAMETER EventProfile
    JFR settings profile (default/profile).

.PARAMETER TopN
    Top N rows for read-profile summary.

.PARAMETER SkipBuild
    Skip Maven test-compile and classpath rebuild.

.EXAMPLE
    .\scripts\oneclick-jfr-isolated.ps1

.EXAMPLE
    .\scripts\oneclick-jfr-isolated.ps1 -DurationSeconds 45 -Concurrency 48 -Name s1-45s
#>
Param(
    [int]$DurationSeconds = 30,
    [int]$LoadSeconds = 0,
    [int]$Concurrency = 32,
    [int]$Port = 8080,
    [string]$OutputDir = "target/profiles/manual",
    [string]$Name = "",
    [ValidateSet("default", "profile")]
    [string]$EventProfile = "profile",
    [string]$ExecutionSamplePeriod = "",
    [string]$NativeSamplePeriod = "",
    [int]$TopN = 20,
    [switch]$SkipBuild,
    [switch]$StaticRoute
)

$ErrorActionPreference = "Stop"

function Wait-ForTcpPort {
    param(
        [string]$TargetHost,
        [int]$Port,
        [int]$TimeoutSeconds
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        $client = $null
        try {
            $client = New-Object System.Net.Sockets.TcpClient
            $iar = $client.BeginConnect($TargetHost, $Port, $null, $null)
            if ($iar.AsyncWaitHandle.WaitOne(300)) {
                $client.EndConnect($iar)
                return $true
            }
        }
        catch {
            Start-Sleep -Milliseconds 200
        }
        finally {
            if ($client) { $client.Close() }
        }
    }
    return $false
}

function Start-OptimizedLoad {
    param(
        [int]$Concurrency,
        [int]$DurationSeconds,
        [string]$Url,
        [string]$Classpath,
        [int]$WarmupSeconds = 5
    )
    
    Write-Host "  Running $DurationSeconds second load test with $Concurrency concurrent threads"
    Write-Host "  Warmup: $WarmupSeconds seconds"
    
    $previousErrorActionPreference = $ErrorActionPreference
    try {
        # The load generator may write progress lines to stderr; do not fail the
        # profiling pipeline when that happens.
        $ErrorActionPreference = "Continue"
        $output = & java -cp $Classpath `
            com.fastjava.bench.profile.DurationBasedHttpLoadGenerator `
            "$Url" `
            $DurationSeconds `
            $Concurrency `
            $WarmupSeconds 2>&1
    }
    finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    
    return $output
}

if ($LoadSeconds -le 0) {
    $LoadSeconds = $DurationSeconds + 2
}

if ([string]::IsNullOrWhiteSpace($Name)) {
    $Name = "isolated-" + (Get-Date -Format "yyyyMMdd-HHmmss")
}

$workspace = Split-Path -Parent $PSScriptRoot
Set-Location $workspace

if ([string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
    throw "JAVA_HOME is not set. Set JAVA_HOME to your JDK (e.g. d:\\tools\\jdk\\jdk-25)."
}

$javaExe = Join-Path $env:JAVA_HOME "bin\java.exe"
if (!(Test-Path $javaExe)) {
    throw "java.exe not found under JAVA_HOME: $javaExe"
}

if (!(Get-Command jcmd -ErrorAction SilentlyContinue)) {
    throw "jcmd not found on PATH. Ensure %JAVA_HOME%\\bin is in PATH."
}
if (!(Get-Command jfr -ErrorAction SilentlyContinue)) {
    throw "jfr not found on PATH. Ensure %JAVA_HOME%\\bin is in PATH."
}

if (!$SkipBuild) {
    Write-Host "Preparing test classes and benchmark classpath..."
    mvn -q -DskipTests test-compile
    mvn -q "-Dmdep.includeScope=test" "-Dmdep.outputFile=target/webserver-bench-classpath.txt" dependency:build-classpath
}

$cpFile = "target/webserver-bench-classpath.txt"
if (!(Test-Path $cpFile)) {
    throw "Classpath file not found: $cpFile"
}

$classpath = "target/test-classes;target/classes;" + (Get-Content $cpFile -Raw).Trim()

if (!(Test-Path $OutputDir)) {
    New-Item -Path $OutputDir -ItemType Directory -Force | Out-Null
}

$existingIsolated = (& jcmd -l 2>$null) | Where-Object { $_ -match "FastJavaIsolatedProfileServer" }
if ($existingIsolated) {
    throw "Existing FastJavaIsolatedProfileServer JVM detected. Stop it first to avoid mixed captures.`n$($existingIsolated -join "`n")"
}

$serverLog = Join-Path $OutputDir ("{0}-server.log" -f $Name)
$serverErrLog = Join-Path $OutputDir ("{0}-server.err.log" -f $Name)
$loadUrl = "http://127.0.0.1:{0}/hello" -f $Port

$serverArgs = @(
    "--add-modules", "jdk.incubator.vector",
    "-cp", $classpath,
    "com.fastjava.bench.profile.FastJavaIsolatedProfileServer",
    "$Port"
)
if ($StaticRoute) {
    $serverArgs += "static"
    Write-Host "Static route mode: bypassing servlet pipeline for /hello"
}

Write-Host "Starting isolated profile server on port $Port..."
$server = Start-Process -FilePath $javaExe -ArgumentList $serverArgs -PassThru -RedirectStandardOutput $serverLog -RedirectStandardError $serverErrLog

if (!(Wait-ForTcpPort -TargetHost "127.0.0.1" -Port $Port -TimeoutSeconds 20)) {
    if (!$server.HasExited) { Stop-Process -Id $server.Id -Force -ErrorAction SilentlyContinue }
    throw "Server did not open port $Port in time. Check log: $serverLog"
}

$jobs = @()
try {
    Write-Host "Starting optimized load: concurrency=$Concurrency, duration=$LoadSeconds seconds"
    $loadOutput = Start-OptimizedLoad -Concurrency $Concurrency -DurationSeconds $LoadSeconds -Url $loadUrl -Classpath $classpath

    $captureScript = Join-Path $PSScriptRoot "jfr-capture.ps1"

    Write-Host "Capturing JFR: duration=$DurationSeconds, profile=$EventProfile"
    & $captureScript `
        -ProcessId $server.Id `
        -DurationSeconds $DurationSeconds `
        -OutputDir $OutputDir `
        -Name $Name `
        -EventProfile $EventProfile `
        -ExecutionSamplePeriod $ExecutionSamplePeriod `
        -NativeSamplePeriod $NativeSamplePeriod

    # Parse load results
    $totalOk = 0
    $throughput = 0
    foreach ($line in $loadOutput) {
        if ($line -match "throughput=([\d.]+)") {
            $throughput = [double]$Matches[1]
        }
        if ($line -match "success=(\d+)") {
            $totalOk = [int]$Matches[1]
        }
    }

    $jfrFile = Join-Path $OutputDir ("{0}.jfr" -f $Name)
    $heapFile = Join-Path $OutputDir ("{0}-heap.txt" -f $Name)

    Write-Host ""
    Write-Host "Load summary"
    Write-Host "  URL           : $loadUrl"
    Write-Host "  Total 2xx req : $totalOk"
    if ($throughput -gt 0) {
        Write-Host ("  Actual TPS    : {0:N0}" -f $throughput)
    }
    Write-Host ("  Expected TPS  : {0:N0} req/s (if achieved)" -f ($totalOk / [Math]::Max(1, $LoadSeconds)))

    $summaryScript = Join-Path $PSScriptRoot "jfr-summary.ps1"
    if ((Test-Path $summaryScript) -and (Test-Path $jfrFile)) {
        Write-Host ""
        Write-Host "JFR summary"
        & $summaryScript -JfrFile $jfrFile -TopN $TopN
    }

    $readScript = Join-Path $PSScriptRoot "read-profile.ps1"
    if ((Test-Path $readScript) -and (Test-Path $jfrFile)) {
        Write-Host ""
        Write-Host "Hotspot summary"
        & $readScript -JfrFile $jfrFile -HeapFile $heapFile -TopN $TopN -Mode both
    }

    Write-Host ""
    Write-Host "Artifacts"
    Write-Host "  JFR       : $jfrFile"
    Write-Host "  Heap      : $heapFile"
    Write-Host "  Server log: $serverLog"
    Write-Host "  Server err: $serverErrLog"
}
finally {
    if ($server -and !$server.HasExited) {
        Stop-Process -Id $server.Id -Force -ErrorAction SilentlyContinue
    }
}
