<#
.SYNOPSIS
    Parse a JFR file and/or heap class histogram and print a human-readable
    summary of top CPU hotspots and top allocating classes.

.PARAMETER JfrFile
    Path to a .jfr file, OR "latest" to find the newest .jfr in OutputDir.

.PARAMETER HeapFile
    Path to a heap histogram text file (from jcmd GC.class_histogram).
    Optional – if omitted, only JFR is analysed.

.PARAMETER OutputDir
    Directory to search when JfrFile="latest". Default: "target/profiles".

.PARAMETER TopN
    How many entries to show in each table. Default 20.

.PARAMETER Mode
    What to show: "cpu", "alloc", or "both". Default "both".

.EXAMPLE
    .\scripts\read-profile.ps1 -JfrFile latest
.EXAMPLE
    .\scripts\read-profile.ps1 -JfrFile target/profiles/profile-20260331-120000.jfr `
                               -HeapFile target/profiles/profile-20260331-120000-heap.txt `
                               -TopN 25
#>
Param(
    [string]$JfrFile    = "latest",
    [string]$HeapFile   = "",
    [string]$OutputDir  = "target/profiles",
    [int]$TopN          = 20,
    [ValidateSet("cpu","alloc","both")]
    [string]$Mode       = "both"
)

$ErrorActionPreference = "Stop"

# ── Resolve JFR file ──────────────────────────────────────────────────────────
if ($JfrFile -eq "latest") {
    $found = Get-ChildItem -Path $OutputDir -Filter "*.jfr" -ErrorAction SilentlyContinue |
             Sort-Object LastWriteTime -Descending |
             Select-Object -First 1
    if (-not $found) { Write-Error "No .jfr files found in $OutputDir"; exit 1 }
    $JfrFile = $found.FullName
    Write-Host "Using latest JFR: $JfrFile"
}
if (!(Test-Path $JfrFile)) { Write-Error "JFR file not found: $JfrFile"; exit 1 }

# ── Helper: parse jfr print output ───────────────────────────────────────────
function Get-JfrEvents {
    param([string]$File, [string]$Events)
    $raw = & jfr print --events $Events "$File" 2>&1
    return $raw
}

function Parse-StackTopMethod {
    param([string[]]$Lines)
    # After "stackTrace {" the first non-blank line starting with whitespace is the top frame
    $inStack = $false
    $methods = @()
    foreach ($line in $Lines) {
        if ($line -match "stackTrace \{") { $inStack = $true; continue }
        if ($inStack) {
            if ($line -match "^\s+at\s+(.+)") {
                $methods += $Matches[1].Trim()
                $inStack = $false
            } elseif ($line -match "^\}") {
                $inStack = $false
            }
        }
    }
    return $methods
}

function Parse-AllocClass {
    param([string[]]$Lines)
    # Look for lines like: objectClass = "java.lang.String" or  class = java.lang.String
    $classes = @()
    foreach ($line in $Lines) {
        if ($line -match 'objectClass\s*=\s*"?([^"]+)"?' -or
            $line -match '\bclass\s*=\s*"?([^\s"{}]+)"?') {
            $classes += $Matches[1].Trim().TrimEnd(']').Trim()
        }
    }
    return $classes
}

function Show-TopN {
    param([string[]]$Items, [string]$Title, [int]$N)
    if ($Items.Count -eq 0) { Write-Host "  (no data)"; return }
    $grouped = $Items | Group-Object | Sort-Object Count -Descending | Select-Object -First $N
    $total   = $Items.Count
    Write-Host ""
    Write-Host $Title
    Write-Host ("-" * 72)
    $rank = 1
    foreach ($g in $grouped) {
        $pct = [int](($g.Count / $total) * 100)
        Write-Host ("{0,3}. {1,5} ({2,2}%)  {3}" -f $rank, $g.Count, $pct, $g.Name)
        $rank++
    }
    Write-Host ("-" * 72)
    Write-Host ("Total samples: {0}" -f $total)
}

# ── CPU profile ───────────────────────────────────────────────────────────────
if ($Mode -eq "cpu" -or $Mode -eq "both") {
    Write-Host ""
    Write-Host "=== CPU Hotspots (jdk.ExecutionSample) ==="
    $cpuLines   = Get-JfrEvents -File $JfrFile -Events "jdk.ExecutionSample"
    $cpuMethods = Parse-StackTopMethod -Lines $cpuLines
    Show-TopN -Items $cpuMethods -Title "Top $TopN methods at top-of-stack:" -N $TopN
}

# ── Allocation profile ────────────────────────────────────────────────────────
if ($Mode -eq "alloc" -or $Mode -eq "both") {
    Write-Host ""
    Write-Host "=== Allocation Hotspots (TLAB + outside-TLAB) ==="
    $allocEvents = "jdk.ObjectAllocationInNewTLAB,jdk.ObjectAllocationOutsideTLAB"
    $allocLines  = Get-JfrEvents -File $JfrFile -Events $allocEvents
    $allocFrames = Parse-StackTopMethod -Lines $allocLines
    $allocClass  = Parse-AllocClass -Lines $allocLines
    if ($allocClass.Count -gt 0) {
        Show-TopN -Items $allocClass -Title "Top $TopN allocated classes:" -N $TopN
    }
    if ($allocFrames.Count -gt 0) {
        Show-TopN -Items $allocFrames -Title "Top $TopN allocation sites (top-of-stack):" -N $TopN
    }
    if ($allocClass.Count -eq 0 -and $allocFrames.Count -eq 0) {
        Write-Host "  No allocation events found. Was the JFR recorded with settings=profile?"
    }
}

# ── Heap class histogram ──────────────────────────────────────────────────────
if ($HeapFile -ne "" -and (Test-Path $HeapFile)) {
    Write-Host ""
    Write-Host "=== Heap Class Histogram (live objects at capture time) ==="
    Write-Host ("-" * 72)
    $heapLines = Get-Content $HeapFile
    $dataLines = $heapLines | Where-Object { $_ -match '^\s*\d+:' }
    Write-Host ("{0,-6} {1,12} {2,14}  {3}" -f "Rank", "Instances", "Bytes", "Class")
    Write-Host ("-" * 72)
    $dataLines | Select-Object -First $TopN | ForEach-Object {
        if ($_ -match '^\s*(\d+):\s+(\d+)\s+(\d+)\s+(.+)$') {
            Write-Host ("{0,-6} {1,12} {2,14}  {3}" -f $Matches[1], $Matches[2], $Matches[3], $Matches[4].Trim())
        }
    }
    # Total line
    $totalLine = $heapLines | Where-Object { $_ -match 'Total' } | Select-Object -Last 1
    if ($totalLine) {
        Write-Host ("-" * 72)
        Write-Host $totalLine.Trim()
    }
} elseif ($HeapFile -ne "") {
    Write-Host ""
    Write-Host "(Heap file not found: $HeapFile)"
}

Write-Host ""
Write-Host "=== Done ==="
