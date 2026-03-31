param(
    [string]$ReportPath = "target/reports/quality-report.md",
    [switch]$SkipBench
)

$ErrorActionPreference = "Stop"

function Invoke-Step {
    param(
        [string]$Name,
        [string]$Command
    )

    Write-Host "==> $Name"
    Write-Host "    $Command"
    Invoke-Expression $Command
    if ($LASTEXITCODE -ne 0) {
        throw "Step '$Name' failed with exit code $LASTEXITCODE"
    }
}

function Get-TestSummary {
    param([string]$SurefireDir)

    $summary = [ordered]@{
        Tests = 0
        Failures = 0
        Errors = 0
        Skipped = 0
    }

    if (-not (Test-Path $SurefireDir)) {
        return $summary
    }

    Get-ChildItem -Path $SurefireDir -Filter "TEST-*.xml" | ForEach-Object {
        [xml]$xml = Get-Content $_.FullName
        $suite = $xml.testsuite
        $summary.Tests += [int]$suite.tests
        $summary.Failures += [int]$suite.failures
        $summary.Errors += [int]$suite.errors
        $summary.Skipped += [int]$suite.skipped
    }

    return $summary
}

function Get-JaCoCoSummary {
    param([string]$JacocoCsv)

    $result = [ordered]@{
        InstructionCoverage = "N/A"
        BranchCoverage = "N/A"
    }

    if (-not (Test-Path $JacocoCsv)) {
        return $result
    }

    $rows = Import-Csv $JacocoCsv
    $missedInstructions = ($rows | Measure-Object -Property INSTRUCTION_MISSED -Sum).Sum
    $coveredInstructions = ($rows | Measure-Object -Property INSTRUCTION_COVERED -Sum).Sum
    $missedBranches = ($rows | Measure-Object -Property BRANCH_MISSED -Sum).Sum
    $coveredBranches = ($rows | Measure-Object -Property BRANCH_COVERED -Sum).Sum

    $instructionTotal = $missedInstructions + $coveredInstructions
    if ($instructionTotal -gt 0) {
        $instructionPct = [math]::Round(($coveredInstructions * 100.0) / $instructionTotal, 2)
        $result.InstructionCoverage = "$instructionPct%"
    }

    $branchTotal = $missedBranches + $coveredBranches
    if ($branchTotal -gt 0) {
        $branchPct = [math]::Round(($coveredBranches * 100.0) / $branchTotal, 2)
        $result.BranchCoverage = "$branchPct%"
    }

    return $result
}

function Get-BenchmarkSummary {
    param([string]$BenchmarkResultPath)

    if (-not (Test-Path $BenchmarkResultPath)) {
        return @("(benchmark output not found)")
    }

    $lines = Get-Content $BenchmarkResultPath
    $benchmarkLines = $lines | Where-Object { $_ -match "^com\.fastjava\.bench\.(HttpParserMicroBench|StaticFileSendfileMicroBench)\." }
    if (-not $benchmarkLines -or $benchmarkLines.Count -eq 0) {
        return @("(no benchmark summary lines found)")
    }

    return $benchmarkLines
}

$root = Split-Path -Parent $PSScriptRoot
Push-Location $root
try {
    Invoke-Step -Name "Run tests with coverage (mvn clean verify)" -Command "mvn clean verify"

    $benchmarkResultFile = "target/benchmarks/microbench-results.txt"
    if (-not $SkipBench) {
        if (-not (Test-Path "target/benchmarks")) {
            New-Item -Path "target/benchmarks" -ItemType Directory | Out-Null
        }

        $benchCommand = "mvn ""-Dtest=HttpParserMicroBench,StaticFileSendfileMicroBench"" test | Tee-Object -FilePath $benchmarkResultFile"
        Invoke-Step -Name "Run developer-fast microbenchmarks" -Command $benchCommand
    }

    $testSummary = Get-TestSummary -SurefireDir "target/surefire-reports"
    $coverage = Get-JaCoCoSummary -JacocoCsv "target/site/jacoco/jacoco.csv"
    $benchmarkLines = if ($SkipBench) { @("(skipped)") } else { Get-BenchmarkSummary -BenchmarkResultPath $benchmarkResultFile }

    $reportDir = Split-Path -Parent $ReportPath
    if (-not [string]::IsNullOrWhiteSpace($reportDir) -and -not (Test-Path $reportDir)) {
        New-Item -Path $reportDir -ItemType Directory | Out-Null
    }

    $timestamp = Get-Date -Format "yyyy-MM-dd HH:mm:ss zzz"
    $report = @(
        "# FastJava Quality and Benchmark Report",
        "",
        "Generated: $timestamp",
        "",
        "## Test Summary",
        "- Tests: $($testSummary.Tests)",
        "- Failures: $($testSummary.Failures)",
        "- Errors: $($testSummary.Errors)",
        "- Skipped: $($testSummary.Skipped)",
        "",
        "## Coverage Summary (JaCoCo)",
        "- Instruction coverage: $($coverage.InstructionCoverage)",
        "- Branch coverage: $($coverage.BranchCoverage)",
        "- Detailed report: target/site/jacoco/index.html",
        "",
        "## Benchmark Summary (Microbench)",
        "Developer-fast profile: parser microbench + static-file sendfile vs buffered benchmark",
        ""
    )

    $report += $benchmarkLines

    $report | Set-Content -Path $ReportPath -Encoding UTF8
    Write-Host "==> Report written to $ReportPath"
}
finally {
    Pop-Location
}
