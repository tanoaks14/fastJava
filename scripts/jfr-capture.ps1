<#
.SYNOPSIS
    Attach to a running FastJava JVM, start a JFR recording, then dump
    a heap class histogram.  Files are written to target/profiles/.

.PARAMETER ProcessPattern
    String to match against jcmd's process list (class name fragment).
    Default: "FastJavaIsolatedProfileServer"

.PARAMETER ProcessId
    Explicit JVM PID to attach to. If provided, ProcessPattern is ignored.

.PARAMETER DurationSeconds
    How long to record JFR data. Default 30.

.PARAMETER OutputDir
    Directory to write JFR and heap files. Default: "target/profiles".

.PARAMETER Name
    Base name for output files. Default: "profile-<timestamp>".

.PARAMETER EventProfile
    JFR event profile to use: "default" or "profile". Default: "profile".
    "profile"  - includes allocation and CPU sampling (more overhead).
    "default"  - lighter weight, no allocation events.

.EXAMPLE
    # Start FastJavaIsolatedProfileServer in another terminal first, then:
    .\scripts\jfr-capture.ps1 -DurationSeconds 30

.EXAMPLE
    # Custom name & 60 s capture
    .\scripts\jfr-capture.ps1 -DurationSeconds 60 -Name "sse-skip-on"
#>
Param(
    [string]$ProcessPattern  = "FastJavaIsolatedProfileServer",
    [int]$ProcessId          = 0,
    [int]$DurationSeconds    = 30,
    [string]$OutputDir       = "target/profiles",
    [string]$Name            = "",
    [ValidateSet("default","profile")]
    [string]$EventProfile    = "profile",
    [string]$ExecutionSamplePeriod = "",
    [string]$NativeSamplePeriod = ""
)

$ErrorActionPreference = "Stop"

# ── Find PID ──────────────────────────────────────────────────────────────────
$procId = ""
if ($ProcessId -gt 0) {
    $procId = "$ProcessId"
    Write-Host "Using explicit PID $procId"
}
else {
    $jcmdOut = & jcmd -l 2>&1
    $matchLine = $jcmdOut | Where-Object { $_ -match $ProcessPattern } | Select-Object -First 1
    if (-not $matchLine) {
        Write-Error "No running JVM matched pattern '$ProcessPattern'.`nRunning JVMs:`n$($jcmdOut -join "`n")"
        exit 1
    }
    $procId = ($matchLine -split '\s+')[0].Trim()
    Write-Host "Found PID $procId  ($matchLine)"
}

# ── Prepare output dir ────────────────────────────────────────────────────────
if (!(Test-Path $OutputDir)) { New-Item -ItemType Directory -Path $OutputDir | Out-Null }
if ($Name -eq "") { $Name = "profile-" + (Get-Date -Format "yyyyMMdd-HHmmss") }

$jfrFile  = Join-Path $OutputDir "$Name.jfr"
$heapFile = Join-Path $OutputDir "$Name-heap.txt"

# Use absolute path for JFR (jcmd needs it)
$jfrAbs = (Resolve-Path -LiteralPath (Split-Path $jfrFile -Parent)).Path + "\" + (Split-Path $jfrFile -Leaf)

$settingsRef = $EventProfile
$generatedSettingsFile = ""
if (![string]::IsNullOrWhiteSpace($ExecutionSamplePeriod) -or ![string]::IsNullOrWhiteSpace($NativeSamplePeriod)) {
    $generatedSettingsFile = Join-Path $OutputDir ("{0}.jfc" -f $Name)
    $configureArgs = @("configure", "--input", $EventProfile, "--output", $generatedSettingsFile)
    if (![string]::IsNullOrWhiteSpace($ExecutionSamplePeriod)) {
        $configureArgs += "jdk.ExecutionSample#period=$ExecutionSamplePeriod"
    }
    if (![string]::IsNullOrWhiteSpace($NativeSamplePeriod)) {
        $configureArgs += "jdk.NativeMethodSample#period=$NativeSamplePeriod"
    }
    & jfr @configureArgs | Out-Null
    if ($LASTEXITCODE -ne 0 -or !(Test-Path $generatedSettingsFile)) {
        throw "Failed to generate custom JFR settings file"
    }
    $settingsRef = (Resolve-Path -LiteralPath $generatedSettingsFile).Path
}

# ── Start JFR recording ───────────────────────────────────────────────────────
Write-Host ""
Write-Host "Starting JFR recording ($settingsRef, ${DurationSeconds}s) → $jfrAbs"
& jcmd $procId JFR.start `
    name=$Name `
    settings=$settingsRef `
    "duration=${DurationSeconds}s" `
    "filename=$jfrAbs"

if ($LASTEXITCODE -ne 0) {
    Write-Error "JFR.start failed (exit $LASTEXITCODE). Is the server started with --add-opens / JFR support?"
    exit 1
}

# ── Wait for JFR to finish ────────────────────────────────────────────────────
Write-Host "Recording in progress for $DurationSeconds seconds..."
$pollInterval = [Math]::Min(5, $DurationSeconds)
$elapsed = 0
while ($elapsed -lt $DurationSeconds) {
    Start-Sleep -Seconds $pollInterval
    $elapsed += $pollInterval
    $remaining = $DurationSeconds - $elapsed
    if ($remaining -gt 0) {
        Write-Host ("  {0}s elapsed, {1}s remaining..." -f $elapsed, $remaining)
    }
}

# ── Dump heap class histogram ─────────────────────────────────────────────────
Write-Host ""
Write-Host "Dumping heap class histogram → $heapFile"
& jcmd $procId GC.class_histogram | Out-File -FilePath $heapFile -Encoding utf8

# ── Summary ───────────────────────────────────────────────────────────────────
Write-Host ""
Write-Host "=== Capture complete ==="
Write-Host "  JFR file    : $jfrAbs"
Write-Host "  Heap histo  : $heapFile"
if ($generatedSettingsFile -ne "") {
    Write-Host "  JFR config  : $generatedSettingsFile"
}
Write-Host ""
Write-Host "Next step - read results with:"
Write-Host "  .\scripts\read-profile.ps1 -JfrFile $jfrAbs -HeapFile $heapFile"
