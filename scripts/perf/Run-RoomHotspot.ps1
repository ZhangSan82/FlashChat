param(
    [ValidateSet("2000", "4000", "8000", "12000", "20000", "unlimited")]
    [string]$Tier,
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
    [hashtable]$EnvOverrides = @{}
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

$profileMap = @{
    "2000" = "local-perf-room-2000"
    "4000" = "local-perf-room-4000"
    "8000" = "local-perf-room-8000"
    "12000" = "local-perf-room-12000"
    "20000" = "local-perf-room-20000"
    "unlimited" = "local-perf-room-unlimited"
}

$presetMap = @{
    "2000" = @{
        RunLabel = "room-2000"
        P95 = 350
        P99 = 800
        SendSuccessRate = 0.15
        WsBroadcastP95 = 350
        ErrorRate = 0.02
        Env = @{
            HOT_ROOM_PARTICIPANTS = "30"
            ROOM_WS_LISTENER_COUNT = "3"
            ROOM_STAGE_1 = "30s"
            ROOM_STAGE_2 = "60s"
            ROOM_STAGE_3 = "60s"
            ROOM_STAGE_4 = "45s"
            ROOM_STAGE_5 = "20s"
            ROOM_TARGET_1 = "10"
            ROOM_TARGET_2 = "20"
            ROOM_TARGET_3 = "35"
            ROOM_TARGET_4 = "50"
            ROOM_SEND_THINK_MS = "50"
            ROOM_HISTORY_EVERY = "3"
            ROOM_ACK_EVERY = "2"
            ROOM_WS_DURATION = "5m"
        }
    }
    "4000" = @{
        RunLabel = "room-4000"
        P95 = 400
        P99 = 900
        SendSuccessRate = 0.30
        WsBroadcastP95 = 400
        ErrorRate = 0.02
        Env = @{
            HOT_ROOM_PARTICIPANTS = "30"
            ROOM_WS_LISTENER_COUNT = "3"
            ROOM_STAGE_1 = "30s"
            ROOM_STAGE_2 = "60s"
            ROOM_STAGE_3 = "60s"
            ROOM_STAGE_4 = "45s"
            ROOM_STAGE_5 = "20s"
            ROOM_TARGET_1 = "10"
            ROOM_TARGET_2 = "20"
            ROOM_TARGET_3 = "35"
            ROOM_TARGET_4 = "50"
            ROOM_SEND_THINK_MS = "50"
            ROOM_HISTORY_EVERY = "3"
            ROOM_ACK_EVERY = "2"
            ROOM_WS_DURATION = "5m"
        }
    }
    "8000" = @{
        RunLabel = "room-8000"
        P95 = 450
        P99 = 1000
        SendSuccessRate = 0.60
        WsBroadcastP95 = 450
        ErrorRate = 0.02
        Env = @{
            HOT_ROOM_PARTICIPANTS = "30"
            ROOM_WS_LISTENER_COUNT = "3"
            ROOM_STAGE_1 = "30s"
            ROOM_STAGE_2 = "60s"
            ROOM_STAGE_3 = "60s"
            ROOM_STAGE_4 = "45s"
            ROOM_STAGE_5 = "20s"
            ROOM_TARGET_1 = "10"
            ROOM_TARGET_2 = "20"
            ROOM_TARGET_3 = "35"
            ROOM_TARGET_4 = "50"
            ROOM_SEND_THINK_MS = "50"
            ROOM_HISTORY_EVERY = "3"
            ROOM_ACK_EVERY = "2"
            ROOM_WS_DURATION = "5m"
        }
    }
    "12000" = @{
        RunLabel = "room-12000"
        P95 = 500
        P99 = 1100
        SendSuccessRate = 0.85
        WsBroadcastP95 = 500
        ErrorRate = 0.02
        Env = @{
            HOT_ROOM_PARTICIPANTS = "30"
            ROOM_WS_LISTENER_COUNT = "3"
            ROOM_STAGE_1 = "30s"
            ROOM_STAGE_2 = "60s"
            ROOM_STAGE_3 = "60s"
            ROOM_STAGE_4 = "45s"
            ROOM_STAGE_5 = "20s"
            ROOM_TARGET_1 = "10"
            ROOM_TARGET_2 = "20"
            ROOM_TARGET_3 = "35"
            ROOM_TARGET_4 = "50"
            ROOM_SEND_THINK_MS = "50"
            ROOM_HISTORY_EVERY = "3"
            ROOM_ACK_EVERY = "2"
            ROOM_WS_DURATION = "5m"
        }
    }
    "20000" = @{
        RunLabel = "room-20000"
        P95 = 550
        P99 = 1200
        SendSuccessRate = 0.90
        WsBroadcastP95 = 550
        ErrorRate = 0.02
        Env = @{
            HOT_ROOM_PARTICIPANTS = "30"
            ROOM_WS_LISTENER_COUNT = "3"
            ROOM_STAGE_1 = "30s"
            ROOM_STAGE_2 = "60s"
            ROOM_STAGE_3 = "60s"
            ROOM_STAGE_4 = "45s"
            ROOM_STAGE_5 = "20s"
            ROOM_TARGET_1 = "10"
            ROOM_TARGET_2 = "20"
            ROOM_TARGET_3 = "35"
            ROOM_TARGET_4 = "50"
            ROOM_SEND_THINK_MS = "50"
            ROOM_HISTORY_EVERY = "3"
            ROOM_ACK_EVERY = "2"
            ROOM_WS_DURATION = "5m"
        }
    }
    "unlimited" = @{
        RunLabel = "room-unlimited-short"
        P95 = 650
        P99 = 1500
        SendSuccessRate = 0.90
        WsBroadcastP95 = 650
        ErrorRate = 0.03
        Env = @{
            HOT_ROOM_PARTICIPANTS = "30"
            ROOM_WS_LISTENER_COUNT = "5"
            ROOM_STAGE_1 = "20s"
            ROOM_STAGE_2 = "40s"
            ROOM_STAGE_3 = "40s"
            ROOM_STAGE_4 = "30s"
            ROOM_STAGE_5 = "20s"
            ROOM_TARGET_1 = "20"
            ROOM_TARGET_2 = "40"
            ROOM_TARGET_3 = "60"
            ROOM_TARGET_4 = "80"
            ROOM_SEND_THINK_MS = "35"
            ROOM_HISTORY_EVERY = "4"
            ROOM_ACK_EVERY = "2"
            ROOM_WS_DURATION = "4m"
            ROOM_WS_HOLD_MS = "240000"
        }
    }
}

$profile = $profileMap[$Tier]
$effectiveProfile = "local-perf,$profile"
$roomGlobalMaxCountMap = @{
    "2000" = "2000"
    "4000" = "4000"
    "8000" = "8000"
    "12000" = "12000"
    "20000" = "20000"
    "unlimited" = "2147483647"
}
$roomGlobalMaxCount = $roomGlobalMaxCountMap[$Tier]
$appArgs = @("--flashchat.rate-limit.room-global.max-count=$roomGlobalMaxCount")
$preset = $presetMap[$Tier]
$mergedEnv = @{}
foreach ($pair in $preset.Env.GetEnumerator()) {
    $mergedEnv[$pair.Key] = $pair.Value
}
foreach ($pair in $EnvOverrides.GetEnumerator()) {
    $mergedEnv[$pair.Key] = [string]$pair.Value
}

$envLiteral = ConvertTo-PsHashtableLiteral -Table $mergedEnv
$commandParts = @(
    "& '$invokeScript'"
    "-Scenario 'room-hotspot'"
    "-Runner '$Runner'"
    "-RunLabel '$($preset.RunLabel)'"
    "-P95ThresholdMs $($preset.P95)"
    "-P99ThresholdMs $($preset.P99)"
    "-ErrorRateThreshold $($preset.ErrorRate)"
    "-SendSuccessRateThreshold $($preset.SendSuccessRate)"
    "-WsBroadcastP95ThresholdMs $($preset.WsBroadcastP95)"
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

Write-Host "Room hotspot tier: $Tier"
Write-Host "Profile: $profile"
Write-Host "Effective profiles: $effectiveProfile"
Write-Host "room-global.max-count override: $roomGlobalMaxCount"
Write-Host "Run label: $($preset.RunLabel)"
Write-Host "Runner: $Runner"
Write-Host "Topology: $Topology"

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
    & $startAppScript -Topology $Topology -Profile $effectiveProfile -Build:$Build -AppArgs $appArgs
}

& powershell -ExecutionPolicy Bypass -Command $commandText
$exitCode = $LASTEXITCODE

$latestRun = Get-ChildItem (Join-Path $repoRoot "perf\results") -Directory |
    Where-Object { $_.Name -like "*room-hotspot*$($preset.RunLabel)*" } |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1

if ($latestRun) {
    Write-Host ""
    Write-Host "Latest result directory:"
    Write-Host $latestRun.FullName
}

exit $exitCode
