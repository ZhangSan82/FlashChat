param(
    [int]$TimeoutSec = 20
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$runDir = Join-Path $repoRoot "perf\run"
$pidFile = Join-Path $runDir "local-perf.pid"
$metaFile = Join-Path $runDir "local-perf.json"

if (-not (Test-Path $pidFile)) {
    Write-Host "No local perf PID file found."
    exit 0
}

$targetPid = [int](Get-Content $pidFile -Raw)
$process = Get-Process -Id $targetPid -ErrorAction SilentlyContinue

if (-not $process) {
    Write-Host "Process $targetPid is already stopped."
    Remove-Item -LiteralPath $pidFile -Force -ErrorAction SilentlyContinue
    exit 0
}

Write-Host "Stopping PID $targetPid..."
Stop-Process -Id $targetPid -ErrorAction SilentlyContinue

$deadline = (Get-Date).AddSeconds($TimeoutSec)
while ((Get-Date) -lt $deadline) {
    if (-not (Get-Process -Id $targetPid -ErrorAction SilentlyContinue)) {
        break
    }
    Start-Sleep -Seconds 1
}

if (Get-Process -Id $targetPid -ErrorAction SilentlyContinue) {
    Write-Host "Graceful stop timed out. Forcing PID $targetPid..."
    Stop-Process -Id $targetPid -Force
}

Remove-Item -LiteralPath $pidFile -Force -ErrorAction SilentlyContinue
if (Test-Path $metaFile) {
    Remove-Item -LiteralPath $metaFile -Force -ErrorAction SilentlyContinue
}

Write-Host "Aggregation service stopped."
