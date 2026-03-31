<#
.SYNOPSIS
  One-click performance pipeline:
  1) Runs cross-framework benchmark (FastJava/Undertow/Tomcat/Netty)
  2) Starts FastJava isolated profile server
  3) Warms up + drives load while capturing JFR
  4) Produces JSON + Markdown hotspot summary in one run directory

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File .\scripts\oneclick-perf-pipeline.ps1

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File .\scripts\oneclick-perf-pipeline.ps1 -Concurrency 48 -JfrDurationSeconds 50
#>
Param(
    [int]$CrossWarmupRequests = 10000,
    [int]$CrossBenchmarkRequests = 150000,
    [int]$CrossConcurrency = 16,
    [int]$CrossRounds = 5,

    [int]$ProfilePort = 19080,
    [int]$WarmupRequests = 200000,
    [int]$CaptureBatchRequests = 300000,
    [int]$Concurrency = 16,
    [int]$JfrDurationSeconds = 40,

    [string]$OutputRoot = "benchmarks/webserver-comparison/results/oneclick"
)

$ErrorActionPreference = "Stop"

function Wait-PortReady {
    param(
        [string]$ListenAddress,
        [int]$Port,
        [int]$TimeoutSeconds = 15
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        try {
            $tcp = New-Object System.Net.Sockets.TcpClient
            $tcp.Connect($ListenAddress, $Port)
            $tcp.Close()
            return $true
        } catch {
            Start-Sleep -Milliseconds 200
        }
    }
    return $false
}

function TopFrame {
    param($sampleEvent)
    if ($null -eq $sampleEvent.values -or $null -eq $sampleEvent.values.stackTrace -or
        $null -eq $sampleEvent.values.stackTrace.frames -or $sampleEvent.values.stackTrace.frames.Count -lt 1) {
        return $null
    }
    return $sampleEvent.values.stackTrace.frames[0]
}

function New-JfrSummary {
    param(
        [string]$JfrFile,
        [string]$SummaryJson,
        [string]$SummaryMd
    )

    $exec = jfr print --events jdk.ExecutionSample --json $JfrFile | ConvertFrom-Json
    $execRows = @()
    foreach ($e in $exec.recording.events) {
        $f = TopFrame $e
        if ($null -eq $f) { continue }
        $cls = ($f.method.type.name -replace '/', '.')
        $execRows += [pscustomobject]@{ method = "$cls.$($f.method.name)"; cls = $cls }
    }
    $execTotal = [double]$execRows.Count
    $execTop = @()
    if ($execTotal -gt 0) {
        $execTop = $execRows | Group-Object method | Sort-Object Count -Descending | Select-Object -First 20 |
            ForEach-Object { [pscustomobject]@{ name = $_.Name; samples = $_.Count; pct = [math]::Round(100 * $_.Count / $execTotal, 2) } }
    }

    $alloc = jfr print --events jdk.ObjectAllocationSample --json $JfrFile | ConvertFrom-Json
    $aCls = @()
    $aSite = @()
    $allocTotal = 0.0
    foreach ($e in $alloc.recording.events) {
        $w = [double]$e.values.weight
        $allocTotal += $w
        $cls = ($e.values.objectClass.name -replace '/', '.')
        if ([string]::IsNullOrEmpty($cls)) { $cls = '<unknown>' }
        $aCls += [pscustomobject]@{ n = $cls; w = $w }
        $f = TopFrame $e
        if ($null -ne $f) {
            $sc = ($f.method.type.name -replace '/', '.')
            $aSite += [pscustomobject]@{ n = "$sc.$($f.method.name)"; w = $w }
        }
    }

    $topClasses = @()
    $topSites = @()
    if ($allocTotal -gt 0) {
        $topClasses = $aCls | Group-Object n | ForEach-Object {
            $s = ($_.Group | Measure-Object w -Sum).Sum
            [pscustomobject]@{ name = $_.Name; weight = [long]$s; pct = [math]::Round(100 * $s / $allocTotal, 2) }
        } | Sort-Object weight -Descending | Select-Object -First 20

        $topSites = $aSite | Group-Object n | ForEach-Object {
            $s = ($_.Group | Measure-Object w -Sum).Sum
            [pscustomobject]@{ name = $_.Name; weight = [long]$s; pct = [math]::Round(100 * $s / $allocTotal, 2) }
        } | Sort-Object weight -Descending | Select-Object -First 20
    }

    $summary = [pscustomobject]@{
        generatedAt = (Get-Date).ToString('s')
        jfr = $JfrFile
        executionSamples = [int]$execTotal
        executionTop = $execTop
        allocationTotalWeight = [long]$allocTotal
        allocationTopClasses = $topClasses
        allocationTopSites = $topSites
    }

    $summary | ConvertTo-Json -Depth 8 | Set-Content $SummaryJson -Encoding utf8

    $md = @()
    $md += "# One-Click Perf Summary"
    $md += ""
    $md += "- Generated: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')"
    $md += "- JFR: $JfrFile"
    $md += "- Execution samples: $([int]$execTotal)"
    $md += ""
    $md += "## Top CPU Methods"
    foreach ($row in $execTop) {
        $md += "- $($row.name): $($row.samples) samples ($($row.pct)%)"
    }
    $md += ""
    $md += "## Top Allocation Classes"
    foreach ($row in $topClasses[0..([Math]::Min(9, [Math]::Max(0, $topClasses.Count - 1)) )]) {
        $md += "- $($row.name): $($row.pct)%"
    }
    $md += ""
    $md += "## Top Allocation Sites"
    foreach ($row in $topSites[0..([Math]::Min(9, [Math]::Max(0, $topSites.Count - 1)) )]) {
        $md += "- $($row.name): $($row.pct)%"
    }
    Set-Content $SummaryMd -Value ($md -join "`r`n") -Encoding utf8
}

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
Set-Location $repoRoot

$stamp = Get-Date -Format "yyyyMMdd-HHmmss"
$runDir = Join-Path $OutputRoot $stamp
New-Item -ItemType Directory -Path $runDir -Force | Out-Null

Write-Host "[1/6] Build and classpath setup..."
mvn -q -DskipTests test-compile
mvn -q "-Dmdep.includeScope=test" "-Dmdep.outputFile=target/webserver-bench-classpath.txt" dependency:build-classpath

$cpFile = "target/webserver-bench-classpath.txt"
if (!(Test-Path $cpFile)) {
    throw "Missing classpath file: $cpFile"
}
$runtimeClasspath = ("target/test-classes;target/classes;" + (Get-Content $cpFile -Raw)).Trim()

Write-Host "[2/6] Cross-framework benchmark run..."
powershell -ExecutionPolicy Bypass -File .\scripts\run-webserver-comparison.ps1 `
    -WarmupRequests $CrossWarmupRequests `
    -BenchmarkRequests $CrossBenchmarkRequests `
    -Concurrency $CrossConcurrency `
    -Rounds $CrossRounds

$crossJson = Join-Path $runDir "cross-latest-results.json"
$crossMd = Join-Path $runDir "cross-latest-results.md"
Copy-Item "benchmarks/webserver-comparison/results/latest-results.json" $crossJson -Force
Copy-Item "benchmarks/webserver-comparison/results/latest-results.md" $crossMd -Force

$serverProc = $null
$loadJob = $null

try {
    Write-Host "[3/6] Start FastJava profile server..."
    $javaArgs = @(
        "--add-modules", "jdk.incubator.vector",
        "-Dfastjava.access.log.enabled=false",
        "-Dfastjava.tracing.enabled=false",
        "-cp", $runtimeClasspath,
        "com.fastjava.bench.profile.FastJavaIsolatedProfileServer", "$ProfilePort"
    )
    $serverProc = Start-Process -FilePath "java" -ArgumentList $javaArgs -PassThru -NoNewWindow

    if (-not (Wait-PortReady -ListenAddress "127.0.0.1" -Port $ProfilePort -TimeoutSeconds 15)) {
        throw "FastJava profile server failed to listen on $ProfilePort"
    }

    $url = "http://127.0.0.1:$ProfilePort/hello"

    Write-Host "[4/6] Warmup load..."
    java -cp $runtimeClasspath com.fastjava.bench.profile.SimpleHttpLoadGenerator $url $WarmupRequests $Concurrency 0 | Out-Host

    Write-Host "[5/6] Start JFR and drive load..."
    $jfrFile = Join-Path $runDir "fastjava-profile.jfr"
    $heapFile = Join-Path $runDir "fastjava-heap.txt"

    & jcmd $serverProc.Id JFR.start "name=oneclick-$stamp" settings=profile "duration=${JfrDurationSeconds}s" "filename=$jfrFile"
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to start JFR recording"
    }

    $deadlineTicks = (Get-Date).AddSeconds($JfrDurationSeconds + 2).Ticks
    $loadJob = Start-Job -ScriptBlock {
        param($cp, $targetUrl, $conc, $batchReq, $endTicks)
        while ((Get-Date).Ticks -lt $endTicks) {
            try {
                & java -cp $cp com.fastjava.bench.profile.SimpleHttpLoadGenerator $targetUrl $batchReq $conc 0 | Out-Null
            } catch {
                # keep load loop resilient
            }
        }
    } -ArgumentList $runtimeClasspath, $url, $Concurrency, $CaptureBatchRequests, $deadlineTicks

    Start-Sleep -Seconds ($JfrDurationSeconds + 3)
    if ($loadJob) {
        Wait-Job $loadJob -Timeout 30 | Out-Null
        Remove-Job $loadJob -Force -ErrorAction SilentlyContinue
        $loadJob = $null
    }

    & jcmd $serverProc.Id GC.class_histogram | Out-File -FilePath $heapFile -Encoding utf8

    if (!(Test-Path $jfrFile)) {
        throw "Expected JFR file missing: $jfrFile"
    }

    Write-Host "[6/6] Analyze JFR and write summaries..."
    $summaryJson = Join-Path $runDir "fastjava-jfr-summary.json"
    $summaryMd = Join-Path $runDir "fastjava-jfr-summary.md"
    New-JfrSummary -JfrFile $jfrFile -SummaryJson $summaryJson -SummaryMd $summaryMd

    Write-Host ""
    Write-Host "Pipeline complete"
    Write-Host "RUN_DIR=$runDir"
    Write-Host "CROSS_JSON=$crossJson"
    Write-Host "JFR_FILE=$jfrFile"
    Write-Host "JFR_SUMMARY_JSON=$summaryJson"

} finally {
    if ($loadJob) {
        Stop-Job $loadJob -ErrorAction SilentlyContinue | Out-Null
        Remove-Job $loadJob -Force -ErrorAction SilentlyContinue
    }
    if ($serverProc -and -not $serverProc.HasExited) {
        Stop-Process -Id $serverProc.Id -Force -ErrorAction SilentlyContinue
    }
}
