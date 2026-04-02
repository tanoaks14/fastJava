#!/usr/bin/env pwsh
<#
.SYNOPSIS
    Summarize key performance signals from a JFR recording.

.DESCRIPTION
    Prints park, GC, socket, and thread-churn statistics to the console.
    Can also write a text report beside the JFR file.

.PARAMETER JfrFile
    Path to a .jfr file, OR "latest" to find the newest recording under OutputDir.

.PARAMETER OutputDir
    Root directory used when JfrFile is "latest". Default: target/profiles/jfr-data

.PARAMETER TopN
    Number of top grouped entries to show for each section. Default: 10

.PARAMETER ReportFile
    Optional explicit report file path. If omitted, report is written to <jfr-dir>/jfr-summary.txt.

.EXAMPLE
    .\scripts\jfr-summary.ps1 -JfrFile latest

.EXAMPLE
    .\scripts\jfr-summary.ps1 -JfrFile target\profiles\jfr-data\FastJava\20260401-205841\profile.jfr -TopN 15
#>
Param(
    [string]$JfrFile = "latest",
    [string]$OutputDir = "target/profiles/jfr-data",
    [int]$TopN = 10,
    [string]$ReportFile = ""
)

$ErrorActionPreference = "Stop"

function Resolve-JfrFile {
    param([string]$File, [string]$Root)

    if ($File -ne "latest") {
        if (!(Test-Path $File)) {
            throw "JFR file not found: $File"
        }
        return (Resolve-Path $File).Path
    }

    $found = Get-ChildItem -Path $Root -Filter "*.jfr" -Recurse -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1

    if (-not $found) {
        throw "No .jfr files found under: $Root"
    }

    return $found.FullName
}

function Convert-ToMilliseconds {
    param([string]$DurationText)

    if ([string]::IsNullOrWhiteSpace($DurationText)) {
        return $null
    }

    $totalMs = 0.0
    $durationTokens = [regex]::Matches($DurationText, '([0-9]+(?:\.[0-9]+)?)\s*(ns|us|ms|s|m|h)')
    if ($durationTokens.Count -eq 0) {
        return $null
    }

    foreach ($m in $durationTokens) {
        $value = [double]$m.Groups[1].Value
        $unit = $m.Groups[2].Value
        switch ($unit) {
            "ns" { $totalMs += ($value / 1000000.0) }
            "us" { $totalMs += ($value / 1000.0) }
            "ms" { $totalMs += $value }
            "s" { $totalMs += ($value * 1000.0) }
            "m" { $totalMs += ($value * 60000.0) }
            "h" { $totalMs += ($value * 3600000.0) }
        }
    }

    return $totalMs
}

function Convert-ToBytes {
    param([string]$SizeText)

    if ([string]::IsNullOrWhiteSpace($SizeText)) {
        return $null
    }

    $m = [regex]::Match($SizeText, '([0-9]+)\s+bytes')
    if ($m.Success) {
        return [double]$m.Groups[1].Value
    }

    return $null
}

function Get-P95 {
    param([double[]]$Values)
    if ($Values.Count -eq 0) { return 0.0 }
    $sorted = $Values | Sort-Object
    $idx = [Math]::Floor(($sorted.Count - 1) * 0.95)
    return [double]$sorted[$idx]
}

function Get-P99 {
    param([double[]]$Values)
    if ($Values.Count -eq 0) { return 0.0 }
    $sorted = $Values | Sort-Object
    $idx = [Math]::Floor(($sorted.Count - 1) * 0.99)
    return [double]$sorted[$idx]
}

function Get-EventBlocks {
    param([string]$Text, [string]$EventName)

    $pattern = "(?ms)^" + [regex]::Escape($EventName) + "\s+\{.*?^\}"
    $matches = [regex]::Matches($Text, $pattern)
    $blocks = @()
    foreach ($match in $matches) {
        $blocks += , ($match.Value -split "`r?`n")
    }

    return $blocks
}

function Get-Field {
    param([string[]]$Block, [string]$FieldName)

    foreach ($line in $Block) {
        if ($line -match "^\s*$([regex]::Escape($FieldName))\s*=\s*(.+)$") {
            return $matches[1].Trim()
        }
    }

    return ""
}

function Get-ThreadPrefix {
    param([string]$ThreadName)

    if ([string]::IsNullOrWhiteSpace($ThreadName)) {
        return "<unknown>"
    }

    $m = [regex]::Match($ThreadName, '^"?([^"(]+)')
    $raw = if ($m.Success) { $m.Groups[1].Value.Trim() } else { $ThreadName.Trim('"') }
    $raw = $raw.Trim()

    $prefix = [regex]::Replace($raw, '-\d+$', '')
    if ([string]::IsNullOrWhiteSpace($prefix)) {
        return $raw
    }

    return $prefix
}

function Format-Top {
    param([object[]]$Items, [int]$Limit)

    if ($Items.Count -eq 0) {
        return @("  (no data)")
    }

    $rows = @()
    $rank = 1
    foreach ($item in ($Items | Select-Object -First $Limit)) {
        $rows += ("  {0,2}. {1,6}  {2}" -f $rank, $item.Count, $item.Name)
        $rank++
    }
    return $rows
}

$jfr = Resolve-JfrFile -File $JfrFile -Root $OutputDir
$summary = jfr summary "$jfr"
$eventFilter = "jdk.ThreadPark,jdk.GarbageCollection,jdk.GCPhasePause,jdk.SocketRead,jdk.SocketWrite,jdk.ThreadStart,jdk.ThreadEnd"
$allEvents = jfr print --events $eventFilter "$jfr"
$allText = ($allEvents -join [Environment]::NewLine)

$reportLines = New-Object System.Collections.Generic.List[string]
$reportLines.Add("JFR Summary")
$reportLines.Add("Recorded file: $jfr")
$reportLines.Add(("Generated at: {0}" -f (Get-Date -Format "s")))
$reportLines.Add("")

$header = $summary | Select-Object -First 4
foreach ($h in $header) {
    $reportLines.Add($h)
}
$reportLines.Add("")

# Thread parking
$parkBlocks = Get-EventBlocks -Text $allText -EventName "jdk.ThreadPark"
$parkDurations = @()
$parkClasses = @()
$parkThreads = @()
foreach ($block in $parkBlocks) {
    $d = Convert-ToMilliseconds (Get-Field -Block $block -FieldName "duration")
    if ($null -ne $d) { $parkDurations += $d }

    $pc = Get-Field -Block $block -FieldName "parkedClass"
    if (![string]::IsNullOrWhiteSpace($pc)) { $parkClasses += $pc }

    $thread = Get-Field -Block $block -FieldName "eventThread"
    if (![string]::IsNullOrWhiteSpace($thread)) { $parkThreads += (Get-ThreadPrefix $thread) }
}

$reportLines.Add("[ThreadPark]")
if ($parkDurations.Count -eq 0) {
    $reportLines.Add("  events: 0")
}
else {
    $sum = ($parkDurations | Measure-Object -Sum).Sum
    $avg = $sum / $parkDurations.Count
    $max = ($parkDurations | Measure-Object -Maximum).Maximum
    $reportLines.Add(("  events: {0}" -f $parkDurations.Count))
    $reportLines.Add(("  total-park-ms: {0:N2}" -f $sum))
    $reportLines.Add(("  avg-park-ms: {0:N2}" -f $avg))
    $reportLines.Add(("  p95-park-ms: {0:N2}" -f (Get-P95 $parkDurations)))
    $reportLines.Add(("  p99-park-ms: {0:N2}" -f (Get-P99 $parkDurations)))
    $reportLines.Add(("  max-park-ms: {0:N2}" -f $max))

    $reportLines.Add("  top-parked-class:")
    $topClass = $parkClasses | Group-Object | Sort-Object Count -Descending
    foreach ($line in (Format-Top -Items $topClass -Limit $TopN)) { $reportLines.Add($line) }

    $reportLines.Add("  top-parked-thread-prefix:")
    $topThread = $parkThreads | Group-Object | Sort-Object Count -Descending
    foreach ($line in (Format-Top -Items $topThread -Limit $TopN)) { $reportLines.Add($line) }
}
$reportLines.Add("")

# GC
$gcBlocks = Get-EventBlocks -Text $allText -EventName "jdk.GarbageCollection"
$pauseBlocks = Get-EventBlocks -Text $allText -EventName "jdk.GCPhasePause"
$gcDurations = @()
$gcCauses = @()
$pauseDurations = @()
foreach ($block in $gcBlocks) {
    $d = Convert-ToMilliseconds (Get-Field -Block $block -FieldName "duration")
    if ($null -ne $d) { $gcDurations += $d }
    $cause = Get-Field -Block $block -FieldName "cause"
    if (![string]::IsNullOrWhiteSpace($cause)) { $gcCauses += $cause }
}
foreach ($block in $pauseBlocks) {
    $d = Convert-ToMilliseconds (Get-Field -Block $block -FieldName "duration")
    if ($null -ne $d) { $pauseDurations += $d }
}

$reportLines.Add("[GC]")
$reportLines.Add(("  gc-events: {0}" -f $gcBlocks.Count))
if ($gcDurations.Count -gt 0) {
    $sum = ($gcDurations | Measure-Object -Sum).Sum
    $max = ($gcDurations | Measure-Object -Maximum).Maximum
    $reportLines.Add(("  total-gc-ms: {0:N2}" -f $sum))
    $reportLines.Add(("  avg-gc-ms: {0:N2}" -f ($sum / $gcDurations.Count)))
    $reportLines.Add(("  max-gc-ms: {0:N2}" -f $max))
}
$reportLines.Add(("  gc-pause-events: {0}" -f $pauseBlocks.Count))
if ($pauseDurations.Count -gt 0) {
    $sumPause = ($pauseDurations | Measure-Object -Sum).Sum
    $maxPause = ($pauseDurations | Measure-Object -Maximum).Maximum
    $reportLines.Add(("  total-gc-pause-ms: {0:N2}" -f $sumPause))
    $reportLines.Add(("  max-gc-pause-ms: {0:N2}" -f $maxPause))
}
$reportLines.Add("  top-gc-causes:")
$topCause = $gcCauses | Group-Object | Sort-Object Count -Descending
foreach ($line in (Format-Top -Items $topCause -Limit $TopN)) { $reportLines.Add($line) }
$reportLines.Add("")

# Socket
$socketReadBlocks = Get-EventBlocks -Text $allText -EventName "jdk.SocketRead"
$socketWriteBlocks = Get-EventBlocks -Text $allText -EventName "jdk.SocketWrite"

function Get-SocketStats {
    param([object[]]$Blocks, [string]$BytesField)

    $dur = @()
    $bytes = @()
    foreach ($block in $Blocks) {
        $d = Convert-ToMilliseconds (Get-Field -Block $block -FieldName "duration")
        if ($null -ne $d) { $dur += $d }

        $b = Convert-ToBytes (Get-Field -Block $block -FieldName $BytesField)
        if ($null -ne $b) { $bytes += $b }
    }

    return [pscustomobject]@{
        Count     = $Blocks.Count
        Durations = $dur
        Bytes     = $bytes
    }
}

$readStats = Get-SocketStats -Blocks $socketReadBlocks -BytesField "bytesRead"
$writeStats = Get-SocketStats -Blocks $socketWriteBlocks -BytesField "bytesWritten"

$reportLines.Add("[Socket]")
$reportLines.Add(("  socket-read-events: {0}" -f $readStats.Count))
if ($readStats.Durations.Count -gt 0) {
    $sum = ($readStats.Durations | Measure-Object -Sum).Sum
    $max = ($readStats.Durations | Measure-Object -Maximum).Maximum
    $reportLines.Add(("  socket-read-total-ms: {0:N2}" -f $sum))
    $reportLines.Add(("  socket-read-p95-ms: {0:N2}" -f (Get-P95 $readStats.Durations)))
    $reportLines.Add(("  socket-read-max-ms: {0:N2}" -f $max))
}
if ($readStats.Bytes.Count -gt 0) {
    $sumBytes = ($readStats.Bytes | Measure-Object -Sum).Sum
    $reportLines.Add(("  socket-read-total-bytes: {0:N0}" -f $sumBytes))
}

$reportLines.Add(("  socket-write-events: {0}" -f $writeStats.Count))
if ($writeStats.Durations.Count -gt 0) {
    $sum = ($writeStats.Durations | Measure-Object -Sum).Sum
    $max = ($writeStats.Durations | Measure-Object -Maximum).Maximum
    $reportLines.Add(("  socket-write-total-ms: {0:N2}" -f $sum))
    $reportLines.Add(("  socket-write-p95-ms: {0:N2}" -f (Get-P95 $writeStats.Durations)))
    $reportLines.Add(("  socket-write-max-ms: {0:N2}" -f $max))
}
if ($writeStats.Bytes.Count -gt 0) {
    $sumBytes = ($writeStats.Bytes | Measure-Object -Sum).Sum
    $reportLines.Add(("  socket-write-total-bytes: {0:N0}" -f $sumBytes))
}
$reportLines.Add("")

# Thread churn
$startBlocks = Get-EventBlocks -Text $allText -EventName "jdk.ThreadStart"
$endBlocks = Get-EventBlocks -Text $allText -EventName "jdk.ThreadEnd"
$startedPrefixes = @()
$endedPrefixes = @()

foreach ($block in $startBlocks) {
    $t = Get-Field -Block $block -FieldName "thread"
    if (![string]::IsNullOrWhiteSpace($t)) { $startedPrefixes += (Get-ThreadPrefix $t) }
}
foreach ($block in $endBlocks) {
    $t = Get-Field -Block $block -FieldName "thread"
    if (![string]::IsNullOrWhiteSpace($t)) { $endedPrefixes += (Get-ThreadPrefix $t) }
}

$reportLines.Add("[ThreadChurn]")
$reportLines.Add(("  thread-start-events: {0}" -f $startBlocks.Count))
$reportLines.Add(("  thread-end-events: {0}" -f $endBlocks.Count))
$reportLines.Add(("  net-thread-growth: {0}" -f ($startBlocks.Count - $endBlocks.Count)))
$reportLines.Add("  top-started-thread-prefix:")
$topStarted = $startedPrefixes | Group-Object | Sort-Object Count -Descending
foreach ($line in (Format-Top -Items $topStarted -Limit $TopN)) { $reportLines.Add($line) }
$reportLines.Add("  top-ended-thread-prefix:")
$topEnded = $endedPrefixes | Group-Object | Sort-Object Count -Descending
foreach ($line in (Format-Top -Items $topEnded -Limit $TopN)) { $reportLines.Add($line) }
$reportLines.Add("")

$report = $reportLines -join [Environment]::NewLine
Write-Host $report

if ([string]::IsNullOrWhiteSpace($ReportFile)) {
    $ReportFile = Join-Path (Split-Path $jfr -Parent) "jfr-summary.txt"
}

$report | Out-File -FilePath $ReportFile -Encoding utf8
Write-Host ""
Write-Host "Summary written: $ReportFile"
