param(
    [ValidateSet("shared-host", "app-only")]
    [string]$Topology = "shared-host",
    [ValidateSet("auto", "local", "docker")]
    [string]$Runner = "docker",
    [switch]$Build,
    [switch]$StartInfra,
    [switch]$WithMonitoring,
    [switch]$SkipAppRestart,
    [switch]$PrepareOnly,
    [switch]$AbortOnRuntimeGuardBreach,
    [int]$AppWarmupSec = 60,
    [int]$ParticipantCount = 120,
    [int]$WsListenerCount = 5
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function ConvertTo-PsHashtableLiteral {
    param(
        [hashtable]$Table
    )

    if (-not $Table -or $Table.Count -eq 0) {
        return "@{}"
    }

    $parts = @()
    foreach ($pair in ($Table.GetEnumerator() | Sort-Object Key)) {
        $escapedValue = ([string]$pair.Value).Replace("'", "''")
        $parts += ("{0} = '{1}'" -f $pair.Key, $escapedValue)
    }

    return "@{ " + ($parts -join "; ") + " }"
}

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$startInfraScript = Join-Path $PSScriptRoot "Start-PerfInfra.ps1"
$startAppScript = Join-Path $PSScriptRoot "Start-LocalPerfApp.ps1"
$stopAppScript = Join-Path $PSScriptRoot "Stop-LocalPerfApp.ps1"
$invokeScript = Join-Path $PSScriptRoot "Invoke-K6Scenario.ps1"

$profile = "local-perf"
$runLabel = "room-100vu-max"
$senderVus = 100

# Single-room max-throughput baseline:
# - sender ramps to 100 VUs
# - participants are padded to reduce sender/listener token reuse
# - history/ack noise is disabled to focus on send + mailbox + ws broadcast
$envTable = @{
    HOT_ROOM_PARTICIPANTS  = [string]$ParticipantCount
    ROOM_WS_LISTENER_COUNT = [string]$WsListenerCount
    ROOM_STAGE_1           = "20s"
    ROOM_STAGE_2           = "20s"
    ROOM_STAGE_3           = "30s"
    ROOM_STAGE_4           = "5m"
    ROOM_STAGE_5           = "20s"
    ROOM_TARGET_1          = "40"
    ROOM_TARGET_2          = "70"
    ROOM_TARGET_3          = [string]$senderVus
    ROOM_TARGET_4          = [string]$senderVus
    ROOM_TARGET_5          = "0"
    ROOM_SEND_THINK_MS     = "50"
    ROOM_HISTORY_EVERY     = "999999"
    ROOM_ACK_EVERY         = "999999"
    ROOM_WS_DURATION       = "7m"
    ROOM_WS_HOLD_MS        = "420000"
}

$envLiteral = ConvertTo-PsHashtableLiteral -Table $envTable
$commandParts = @(
    "& '$invokeScript'"
    "-Scenario 'room-hotspot'"
    "-Runner '$Runner'"
    "-RunLabel '$runLabel'"
    "-P95ThresholdMs 1500"
    "-P99ThresholdMs 3000"
    "-ErrorRateThreshold 0.02"
    "-SendSuccessRateThreshold 0.99"
    "-WsBroadcastP95ThresholdMs 1200"
    "-ActuatorGuard 'room-hotspot'"
    "-RuntimePollSec 10"
    "-RuntimeGuardConsecutiveBreaches 2"
    "-MaxRuntimeMailboxBacklog 0"
    "-MaxRuntimeHikariPending 0"
    "-MaxRuntimeProcessCpu 0.85"
    "-AutoCaptureDiagnostics"
)

if ($AbortOnRuntimeGuardBreach) {
    $commandParts += "-AbortOnRuntimeGuardBreach"
}

$commandParts += "-EnvOverrides $envLiteral"
$commandText = $commandParts -join " "

Write-Host "Single-room max throughput baseline"
Write-Host "Run label: $runLabel"
Write-Host "Runner: $Runner"
Write-Host "Topology: $Topology"
Write-Host "App profile: $profile"
Write-Host "Sender VUs: $senderVus"
Write-Host "Participant count: $ParticipantCount"
Write-Host "WS listener count: $WsListenerCount"
Write-Host ("Total room members (host included): {0}" -f ($ParticipantCount + 1))
Write-Host "Rate limit: disabled via local-perf profile"

if ($PrepareOnly) {
    Write-Host ""
    Write-Host "Prepared command:"
    Write-Host $commandText
    exit 0
}

if ($StartInfra) {
    & $startInfraScript -WithMonitoring:$WithMonitoring
}

if (-not $SkipAppRestart) {
    & $stopAppScript
    & $startAppScript -Topology $Topology -Profile $profile -Build:$Build
}

if ($AppWarmupSec -gt 0) {
    Write-Host "Waiting $AppWarmupSec seconds for app warm-up..."
    Start-Sleep -Seconds $AppWarmupSec
}

& powershell -ExecutionPolicy Bypass -Command $commandText
$exitCode = $LASTEXITCODE

$latestRun = Get-ChildItem (Join-Path $repoRoot "perf\results") -Directory |
    Where-Object { $_.Name -like "*room-hotspot*$runLabel*" } |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1

if ($latestRun) {
    Write-Host ""
    Write-Host "Latest result directory:"
    Write-Host $latestRun.FullName
}

exit $exitCode
