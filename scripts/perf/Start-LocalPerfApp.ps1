param(
    [ValidateSet("shared-host", "app-only")]
    [string]$Topology = "shared-host",
    [string]$Profile = "local-perf",
    [switch]$Build,
    [int]$HealthTimeoutSec = 120,
    [string]$JarPath,
    [string[]]$AppArgs = @()
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Wait-ForHealth {
    param(
        [string]$HealthUrl,
        [int]$TimeoutSec
    )

    $deadline = (Get-Date).AddSeconds($TimeoutSec)
    while ((Get-Date) -lt $deadline) {
        try {
            $resp = Invoke-RestMethod -Uri $HealthUrl -TimeoutSec 5
            if ($resp.status -eq "UP") {
                return $true
            }
        } catch {
            Start-Sleep -Seconds 2
            continue
        }
        Start-Sleep -Seconds 2
    }
    return $false
}

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$runDir = Join-Path $repoRoot "perf\run"
$logDir = Join-Path $repoRoot "perf\logs"
$pidFile = Join-Path $runDir "local-perf.pid"
$metaFile = Join-Path $runDir "local-perf.json"

New-Item -ItemType Directory -Force -Path $runDir | Out-Null
New-Item -ItemType Directory -Force -Path $logDir | Out-Null

if (Test-Path $pidFile) {
    $existingPid = [int](Get-Content $pidFile -Raw)
    $existingProcess = Get-Process -Id $existingPid -ErrorAction SilentlyContinue
    if ($existingProcess) {
        throw "Aggregation service is already running with PID $existingPid. Stop it first."
    }
    Remove-Item -LiteralPath $pidFile -Force
}

if (-not $JarPath) {
    $JarPath = Join-Path $repoRoot "services\aggregation-service\target\FlashChat.jar"
}

if ($Build -or -not (Test-Path $JarPath)) {
    if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) {
        throw "mvn is not installed or not available in PATH."
    }
    Write-Host "Building FlashChat root reactor package for aggregation-service..."
    Push-Location $repoRoot
    try {
        # Build from the root reactor so framework modules (for example
        # FlashChat-framework-user that contains AccountBannedEvent) are also
        # available to downstream service modules during compilation.
        & mvn clean package -DskipTests
        if ($LASTEXITCODE -ne 0) {
            throw "aggregation-service build failed with exit code $LASTEXITCODE"
        }
    } finally {
        Pop-Location
    }
}

if (-not (Test-Path $JarPath)) {
    throw "Jar not found: $JarPath"
}

$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$stdoutLog = Join-Path $logDir "flashchat-local-perf-$timestamp.out.log"
$stderrLog = Join-Path $logDir "flashchat-local-perf-$timestamp.err.log"
$gcLog = Join-Path $logDir "flashchat-local-perf-$timestamp.gc.log"
$gcLogArg = "-Xlog:gc*:file=${gcLog}:time,uptime,level,tags"

switch ($Topology) {
    "shared-host" {
        $heapMb = 768
        $maxRamMb = 1408
        $maxDirectMb = 256
        $maxMetaMb = 256
    }
    "app-only" {
        $heapMb = 1024
        $maxRamMb = 1792
        $maxDirectMb = 320
        $maxMetaMb = 256
    }
    default {
        throw "Unsupported topology: $Topology"
    }
}

$javaArgs = @(
    "-Dfile.encoding=UTF-8"
    "-Duser.timezone=Asia/Shanghai"
    "-Dspring.profiles.active=$Profile"
    "-XX:+UseG1GC"
    "-XX:ActiveProcessorCount=2"
    "-XX:MaxRAM=$($maxRamMb)m"
    "-Xms$($heapMb)m"
    "-Xmx$($heapMb)m"
    "-XX:MaxDirectMemorySize=$($maxDirectMb)m"
    "-XX:MaxMetaspaceSize=$($maxMetaMb)m"
    "-XX:+HeapDumpOnOutOfMemoryError"
    "-XX:HeapDumpPath=$logDir"
    $gcLogArg
    "-jar"
    $JarPath
)

if ($AppArgs -and $AppArgs.Count -gt 0) {
    $javaArgs += $AppArgs
}

Write-Host "Starting aggregation-service with profile '$Profile'..."
Write-Host "Topology mode: $Topology"
Write-Host "Heap/Xmx: $heapMb MB"
Write-Host "ActiveProcessorCount: 2"
if ($AppArgs -and $AppArgs.Count -gt 0) {
    Write-Host "App args: $($AppArgs -join ' ')"
}

$process = Start-Process `
    -FilePath "java" `
    -ArgumentList $javaArgs `
    -WorkingDirectory $repoRoot `
    -RedirectStandardOutput $stdoutLog `
    -RedirectStandardError $stderrLog `
    -PassThru

$process.Id | Set-Content -LiteralPath $pidFile

$meta = [ordered]@{
    pid = $process.Id
    profile = $Profile
    topology = $Topology
    jarPath = $JarPath
    stdoutLog = $stdoutLog
    stderrLog = $stderrLog
    gcLog = $gcLog
    startedAt = (Get-Date).ToString("s")
    javaArgs = $javaArgs
    appArgs = $AppArgs
}
$meta | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath $metaFile

if (-not (Wait-ForHealth -HealthUrl "http://localhost:8081/actuator/health" -TimeoutSec $HealthTimeoutSec)) {
    throw "Application did not become healthy within $HealthTimeoutSec seconds. See logs: $stdoutLog and $stderrLog"
}

Write-Host ""
Write-Host "Aggregation service is healthy."
Write-Host "HTTP:      http://localhost:8081"
Write-Host "WebSocket: ws://localhost:8090/?token=<satoken>"
Write-Host "Actuator:  http://localhost:8081/actuator"
Write-Host "PID:       $($process.Id)"
Write-Host "STDOUT:    $stdoutLog"
Write-Host "STDERR:    $stderrLog"
Write-Host "GC log:    $gcLog"
