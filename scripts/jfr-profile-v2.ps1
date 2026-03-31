#!/usr/bin/env pwsh
<#
.SYNOPSIS
    JFR profiling for FastJava, Undertow, Tomcat, and Netty.

.PARAMETER ServerName  
    Server to profile: "FastJava", "Undertow", "Tomcat", "Netty"

.PARAMETER RequestCount
    Requests to send. Default: 100000

.PARAMETER Concurrency
    Thread count. Default: 32

.PARAMETER JfrDuration
    JFR capture duration in seconds. Default: 40

.PARAMETER OutputDir
    Output directory. Default: "target/profiles/jfr-data"
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
Write-Host "JFR Profiler for $ServerName"
Write-Host "Requests: $RequestCount, Concurrency: $Concurrency, Duration: $JfrDuration seconds"
Write-Host ""

Write-Host "Building Maven artifacts..."
$prevPref = $ErrorActionPreference
$ErrorActionPreference = "Continue"
mvn -q clean test-compile
mvn -q dependency:build-classpath "-Dmdep.includeScope=test" "-Dmdep.outputFile=target/webserver-bench-classpath.txt"
$ErrorActionPreference = $prevPref

$cpFile = "target/webserver-bench-classpath.txt"
if (!(Test-Path $cpFile)) {
    Write-Error "Classpath file not found"
    exit 1
}

$classPath = "target/test-classes;target/classes;" + (Get-Content $cpFile -Raw)
$javaExe = Join-Path $env:JAVA_HOME "bin\java.exe"

if (!(Test-Path $javaExe)) {
    Write-Error "Java not found"
    exit 1
}

$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$profileDir = Join-Path $OutputDir $ServerName | ForEach-Object { Join-Path $_ $timestamp }
New-Item -ItemType Directory -Path $profileDir -Force | Out-Null

$jfrFile = Join-Path $profileDir "profile.jfr"
$jfrFile = (Resolve-Path (Split-Path $jfrFile -Parent)).Path + "\profile.jfr"

$jfrOption = "-XX:StartFlightRecording=name=perf,settings=profile,duration=$($JfrDuration)s,filename=$jfrFile"

Write-Host "Starting $ServerName server with JFR..."
Write-Host "JFR output: $jfrFile"
Write-Host ""

$procArgs = @(
    "--add-modules", "jdk.incubator.vector",
    $jfrOption,
    "-Dcom.sun.management.jmxremote",
    "-Dcom.sun.management.jmxremote.port=9010",
    "-Dcom.sun.management.jmxremote.authenticate=false",
    "-Dcom.sun.management.jmxremote.ssl=false",
    "-cp", $classPath,
    "com.fastjava.bench.comparison.WebServerComparisonBenchmark",
    "--single-server",
    $ServerName
)

$logFile = Join-Path $profileDir "server.log"
$proc = Start-Process -FilePath $javaExe -ArgumentList $procArgs -NoNewWindow -PassThru -RedirectStandardOutput $logFile
$procId = $proc.Id

Write-Host "Server PID: $procId"
Write-Host ""

Start-Sleep -Seconds 3

$waitTime = $JfrDuration + 10
Write-Host "JFR recording for $waitTime seconds..."
Start-Sleep -Seconds $waitTime

Write-Host ""
Write-Host "Capturing heap histogram..."
$heapFile = Join-Path $profileDir "heap.txt"
try {
    $output = & jcmd $procId GC.class_histogram 2>&1
    $output | Out-File -FilePath $heapFile -Encoding utf8
    Write-Host "Heap saved: $heapFile"
} catch {
    Write-Host "Could not capture heap"
}

Write-Host ""
Write-Host "Waiting for server to finish..."
$timeout = 60
$elapsed = 0
while ($elapsed -lt $timeout -and !$proc.HasExited) {
    Start-Sleep -Seconds 2
    $elapsed += 2
}

if (!$proc.HasExited) {
    Stop-Process -Id $procId -Force -ErrorAction SilentlyContinue
}

Write-Host ""
Write-Host "Profile complete!"
Write-Host "Output: $profileDir"
Write-Host ""
Get-ChildItem -Path $profileDir
Write-Host ""
