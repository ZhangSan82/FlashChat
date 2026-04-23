param(
    [switch]$WithMonitoring,
    [switch]$RemoveVolumes
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$perfCompose = Join-Path $repoRoot "docker\perf\docker-compose.yml"
$monitoringCompose = Join-Path $repoRoot "monitoring\docker-compose.local-perf.yml"

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    throw "docker is not installed or not available in PATH."
}

$downArgs = @("compose", "-f", $perfCompose, "down")
if ($RemoveVolumes) {
    $downArgs += "-v"
}

Write-Host "Stopping MySQL and Redis for local perf..."
& docker @downArgs

if ($WithMonitoring) {
    $monitoringDownArgs = @("compose", "-f", $monitoringCompose, "down")
    if ($RemoveVolumes) {
        $monitoringDownArgs += "-v"
    }

    Write-Host "Stopping local Prometheus and Grafana..."
    & docker @monitoringDownArgs
}

Write-Host "Perf infra stopped."
