param(
    [switch]$WithMonitoring,
    [switch]$ResetData
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$perfCompose = Join-Path $repoRoot "docker\perf\docker-compose.yml"
$monitoringCompose = Join-Path $repoRoot "monitoring\docker-compose.local-perf.yml"

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    throw "docker is not installed or not available in PATH."
}

if ($ResetData) {
    Write-Host "Resetting MySQL/Redis volumes..."
    docker compose -f $perfCompose down -v
}

Write-Host "Starting MySQL and Redis for local perf..."
docker compose -f $perfCompose up -d

if ($WithMonitoring) {
    Write-Host "Starting local Prometheus and Grafana..."
    docker compose -f $monitoringCompose up -d
}

Write-Host ""
Write-Host "Perf infra is up."
Write-Host "MySQL:      localhost:13306"
Write-Host "Redis:      localhost:6379"
if (-not $ResetData) {
    Write-Host "Note: if you changed files under frameworks/database or docker/perf/init, rerun this script with -ResetData so MySQL replays /docker-entrypoint-initdb.d."
}
if ($WithMonitoring) {
    Write-Host "Prometheus: http://localhost:9090"
    Write-Host "Grafana:    http://localhost:3001"
}
