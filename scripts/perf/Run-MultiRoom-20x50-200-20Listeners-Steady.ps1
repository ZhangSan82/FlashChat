param(
    [ValidateSet("shared-host", "app-only")]
    [string]$Topology = "shared-host",
    [ValidateSet("auto", "local", "docker")]
    [string]$Runner = "local",
    [switch]$Build,
    [switch]$StartInfra,
    [switch]$WithMonitoring,
    [switch]$SkipAppRestart,
    [switch]$PrepareOnly,
    [switch]$AbortOnRuntimeGuardBreach,
    [int]$AppWarmupSec = 60
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Get-KeyValueFileMap {
    param(
        [string]$Path
    )

    $result = @{}
    if (-not (Test-Path $Path)) {
        return $result
    }

    foreach ($line in Get-Content $Path) {
        if ($line -match '^(?<key>[^=]+)=(?<value>.*)$') {
            $result[$matches.key] = $matches.value
        }
    }

    return $result
}

$runScript = Join-Path $PSScriptRoot "Run-TenRoom.ps1"
$inspectScript = Join-Path $PSScriptRoot "Inspect-MailboxStepMetrics.ps1"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$runLabel = "multi-room-20x50-200vu-20listeners-steady"

& $runScript `
    -SenderVus 200 `
    -Topology $Topology `
    -Runner $Runner `
    -Build:$Build `
    -StartInfra:$StartInfra `
    -WithMonitoring:$WithMonitoring `
    -SkipAppRestart:$SkipAppRestart `
    -PrepareOnly:$PrepareOnly `
    -AbortOnRuntimeGuardBreach:$AbortOnRuntimeGuardBreach `
    -AppWarmupSec $AppWarmupSec `
    -MailboxShardCount 12 `
    -RoomCount 20 `
    -ParticipantsPerRoom 50 `
    -WsListenersPerRoom 20 `
    -TitlePrefix "multi-room-20x50" `
    -SetupShardGuardMaxRoomsPerShard 3 `
    -SetupShardGuardMinActiveShards 10 `
    -SetupShardGuardMaxEmptyShards 2 `
    -SetupShardGuardMaxRetries 8 `
    -ResetInfraOnSetupShardGuardRetry `
    -RunLabelOverride $runLabel `
    -EnvOverrides @{
        TEN_ROOM_LISTENER_MODE = 'steady'
        TEN_ROOM_LISTENER_STAGGER_SEC = '30'
        TEN_ROOM_LISTENER_MAX_DURATION = '7m30s'
        TEN_ROOM_SENDER_START_TIME = '35s'
    }
$runExitCode = $LASTEXITCODE

if ($PrepareOnly) {
    exit $runExitCode
}

$latestRun = Get-ChildItem (Join-Path $repoRoot "perf\results") -Directory |
    Where-Object { $_.Name -like "*ten-room*$runLabel*" } |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1

$setupGuardFailed = $false
if ($latestRun) {
    $briefMap = Get-KeyValueFileMap -Path (Join-Path $latestRun.FullName "brief.txt")
    if ($briefMap.ContainsKey("setup_guard_failed")) {
        [void][bool]::TryParse([string]$briefMap["setup_guard_failed"], [ref]$setupGuardFailed)
    }
}

if ($setupGuardFailed) {
    Write-Warning "Skipping mailbox step inspection because the latest run was rejected by the setup shard guard."
} else {
    & $inspectScript
}

exit $runExitCode
