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
    [int]$TopN = 20,
    [switch]$SkipBuild
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
        } catch {
            Start-Sleep -Milliseconds 200
        } finally {
            if ($client) { $client.Close() }
        }
    }
    return $false
}

function Start-LoadJobs {
    param(
        [int]$JobCount,
        [int]$Seconds,
        [string]$Url
    )

    $jobs = @()
    1..$JobCount | ForEach-Object {
        $jobs += Start-Job -ScriptBlock {
            param($secs, $targetUrl)
            $sw = [Diagnostics.Stopwatch]::StartNew()
            $ok = 0
            while ($sw.Elapsed.TotalSeconds -lt $secs) {
                & curl.exe -s $targetUrl > $null
                if ($LASTEXITCODE -eq 0) { $ok++ }
            }
            $ok
        } -ArgumentList $Seconds, $Url
    }
    return $jobs
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

Write-Host "Starting isolated profile server on port $Port..."
$server = Start-Process -FilePath $javaExe -ArgumentList $serverArgs -PassThru -RedirectStandardOutput $serverLog -RedirectStandardError $serverErrLog

if (!(Wait-ForTcpPort -TargetHost "127.0.0.1" -Port $Port -TimeoutSeconds 20)) {
    if (!$server.HasExited) { Stop-Process -Id $server.Id -Force -ErrorAction SilentlyContinue }
    throw "Server did not open port $Port in time. Check log: $serverLog"
}

$jobs = @()
try {
    Write-Host "Starting load: concurrency=$Concurrency, seconds=$LoadSeconds"
    $jobs = Start-LoadJobs -JobCount $Concurrency -Seconds $LoadSeconds -Url $loadUrl

    $captureScript = Join-Path $PSScriptRoot "jfr-capture.ps1"

    Write-Host "Capturing JFR: duration=$DurationSeconds, profile=$EventProfile"
    & $captureScript `
        -ProcessId $server.Id `
        -DurationSeconds $DurationSeconds `
        -OutputDir $OutputDir `
        -Name $Name `
        -EventProfile $EventProfile

    $counts = Receive-Job -Job $jobs -Wait -AutoRemoveJob
    $totalOk = ($counts | Measure-Object -Sum).Sum
    if ($null -eq $totalOk) { $totalOk = 0 }

    $jfrFile = Join-Path $OutputDir ("{0}.jfr" -f $Name)
    $heapFile = Join-Path $OutputDir ("{0}-heap.txt" -f $Name)

    Write-Host ""
    Write-Host "Load summary"
    Write-Host "  URL           : $loadUrl"
    Write-Host "  Total 2xx req : $totalOk"
    Write-Host ("  Approx req/s  : {0:N0}" -f ($totalOk / [Math]::Max(1, $LoadSeconds)))

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
    if ($jobs.Count -gt 0) {
        $running = $jobs | Where-Object { $_.State -eq "Running" }
        if ($running.Count -gt 0) {
            Stop-Job -Job $running -ErrorAction SilentlyContinue
            Receive-Job -Job $running -ErrorAction SilentlyContinue | Out-Null
        }
    }
    if ($server -and !$server.HasExited) {
        Stop-Process -Id $server.Id -Force -ErrorAction SilentlyContinue
    }
}
