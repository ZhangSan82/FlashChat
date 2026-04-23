param(
    [string]$BaseUrl = "http://host.docker.internal:8081/actuator/health",
    [int]$TimeoutSec = 120,
    [int]$IntervalSec = 5
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$probeScript = "/work/perf/k6/docker-host-probe.js"
$deadline = (Get-Date).AddSeconds($TimeoutSec)
$attempt = 0

while ((Get-Date) -lt $deadline) {
    $attempt += 1
    Write-Host "Docker host probe attempt $attempt -> $BaseUrl"

    & docker run --rm `
        -v "${repoRoot}:/work" `
        -w /work `
        -e "PROBE_URL=$BaseUrl" `
        grafana/k6:latest `
        run --quiet $probeScript

    if ($LASTEXITCODE -eq 0) {
        Write-Host "Docker host probe succeeded."
        exit 0
    }

    Start-Sleep -Seconds $IntervalSec
}

throw "Docker host probe did not succeed within $TimeoutSec seconds: $BaseUrl"
