param(
    [ValidateSet(50, 100, 120, 150, 160, 175, 185, 190, 200, 230, 250, 300, 400, 450)]
    [int]$SenderVus,
    [ValidateSet("shared-host", "app-only")]
    [string]$Topology = "shared-host",
    [ValidateSet("auto", "local", "docker")]
    [string]$Runner = "docker",
    [switch]$Build,
    [switch]$StartInfra,
    [switch]$WithMonitoring,
    [switch]$SkipAppRestart,
    [switch]$PrepareOnly,
    [switch]$AutoCaptureDiagnostics,
    [switch]$AbortOnRuntimeGuardBreach,
    [int]$AppWarmupSec = 0,
    [int]$RoomCount = 10,
    [int]$ParticipantsPerRoom = 20,
    [int]$WsListenersPerRoom = 1,
    [int]$MailboxShardCount = 12,
    [string]$TitlePrefix = "ten-room",
    [string]$RunLabelOverride = "",
    [int]$SetupShardGuardMaxRoomsPerShard = 0,
    [int]$SetupShardGuardMinActiveShards = 0,
    [int]$SetupShardGuardMaxEmptyShards = 0,
    [int]$SetupShardGuardMaxRetries = 1,
    [switch]$ResetInfraOnSetupShardGuardRetry,
    [hashtable]$EnvOverrides = @{}
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Get-CurrentLocalPerfMeta {
    param(
        [string]$RepoRoot
    )

    $metaPath = Join-Path $RepoRoot "perf\run\local-perf.json"
    if (-not (Test-Path $metaPath)) {
        return $null
    }

    try {
        return Get-Content $metaPath -Raw | ConvertFrom-Json
    } catch {
        return $null
    }
}

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

function Write-JsonUtf8NoBom {
    param(
        [string]$Path,
        [object]$Value,
        [int]$Depth = 20
    )

    $directory = Split-Path -Path $Path -Parent
    if (-not [string]::IsNullOrWhiteSpace($directory)) {
        New-Item -ItemType Directory -Force -Path $directory | Out-Null
    }

    $json = $Value | ConvertTo-Json -Depth $Depth
    $utf8NoBom = [System.Text.UTF8Encoding]::new($false)
    [System.IO.File]::WriteAllText($Path, $json, $utf8NoBom)
}

function Get-LatestTenRoomRun {
    param(
        [string]$RepoRoot,
        [string]$RunLabel
    )

    return Get-ChildItem (Join-Path $RepoRoot "perf\results") -Directory |
        Where-Object { $_.Name -like "*ten-room*$RunLabel*" } |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
}

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

function Test-IsBetterTopologyScore {
    param(
        [pscustomobject]$Candidate,
        [pscustomobject]$CurrentBest
    )

    if (-not $CurrentBest) {
        return $true
    }

    foreach ($property in @("EmptyShardCount", "ActualMaxRoomsPerShard", "Spread")) {
        if ($Candidate.$property -lt $CurrentBest.$property) {
            return $true
        }
        if ($Candidate.$property -gt $CurrentBest.$property) {
            return $false
        }
    }

    return $Candidate.LastWriteTicks -gt $CurrentBest.LastWriteTicks
}

function Seed-FixedTopologyCacheFromLatestRun {
    param(
        [string]$RepoRoot,
        [string]$TitlePrefix,
        [int]$RoomCount,
        [int]$ParticipantsPerRoom,
        [int]$ShardCount,
        [string]$RoomDuration,
        [string]$CachePath,
        [string]$ExtractSetupDataScript
    )

    $candidateRuns = Get-ChildItem (Join-Path $RepoRoot "perf\results") -Directory |
        Where-Object { $_.Name -like "*ten-room*$TitlePrefix*" } |
        Sort-Object LastWriteTime -Descending

    $bestTopology = $null
    $bestCandidate = $null
    $bestScore = $null

    foreach ($candidate in $candidateRuns) {
        $briefPath = Join-Path $candidate.FullName "brief.txt"
        $summaryPath = Join-Path $candidate.FullName "summary.json"
        if (-not (Test-Path $briefPath) -or -not (Test-Path $summaryPath)) {
            continue
        }

        $briefMap = Get-KeyValueFileMap -Path $briefPath
        if ($briefMap.ContainsKey("setup_guard_failed")) {
            $setupGuardFailed = $false
            [void][bool]::TryParse([string]$briefMap["setup_guard_failed"], [ref]$setupGuardFailed)
            if ($setupGuardFailed) {
                continue
            }
        }

        try {
            $setupDataJson = & node $ExtractSetupDataScript $summaryPath 2>$null
        } catch {
            continue
        }

        if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($setupDataJson)) {
            continue
        }

        try {
            $topology = $setupDataJson | ConvertFrom-Json
        } catch {
            continue
        }

        $topologyRoomCount = if ($topology.PSObject.Properties["roomCount"]) {
            [int]$topology.roomCount
        } elseif ($topology.PSObject.Properties["rooms"]) {
            @($topology.rooms).Count
        } else {
            0
        }
        $topologyParticipantsPerRoom = if ($topology.PSObject.Properties["participantsPerRoom"]) {
            [int]$topology.participantsPerRoom
        } else {
            0
        }
        $topologyRoomDuration = if ($topology.PSObject.Properties["roomDuration"]) {
            [string]$topology.roomDuration
        } else {
            ""
        }
        $topologyShardCount = if ($topology.PSObject.Properties["shardCount"]) {
            [int]$topology.shardCount
        } else {
            0
        }

        if ($topologyRoomCount -ne $RoomCount -or
            $topologyParticipantsPerRoom -ne $ParticipantsPerRoom -or
            $topologyRoomDuration -ne $RoomDuration -or
            $topologyShardCount -ne $ShardCount) {
            continue
        }

        if (-not $topology.PSObject.Properties["shardCounts"]) {
            continue
        }

        $shardCounts = @($topology.shardCounts | ForEach-Object { [int]$_ })
        if ($shardCounts.Count -eq 0) {
            continue
        }

        $activeShardCounts = @($shardCounts | Where-Object { $_ -gt 0 })
        $actualMaxRoomsPerShard = if ($topology.PSObject.Properties["actualMaxRoomsPerShard"]) {
            [int]$topology.actualMaxRoomsPerShard
        } else {
            ($shardCounts | Measure-Object -Maximum).Maximum
        }
        $score = [pscustomobject]@{
            EmptyShardCount = @($shardCounts | Where-Object { $_ -le 0 }).Count
            ActualMaxRoomsPerShard = $actualMaxRoomsPerShard
            Spread = if ($activeShardCounts.Count -gt 0) {
                ($activeShardCounts | Measure-Object -Maximum).Maximum - ($activeShardCounts | Measure-Object -Minimum).Minimum
            } else {
                [int]::MaxValue
            }
            LastWriteTicks = $candidate.LastWriteTimeUtc.Ticks
        }

        if (Test-IsBetterTopologyScore -Candidate $score -CurrentBest $bestScore) {
            $bestTopology = $topology
            $bestCandidate = $candidate
            $bestScore = $score
        }
    }

    if ($bestTopology) {
        Write-JsonUtf8NoBom -Path $CachePath -Value $bestTopology -Depth 20
        Write-Host "Seeded fixed topology cache from successful run:"
        Write-Host $bestCandidate.FullName
        Write-Host ("Seed topology shard layout score: emptyShards={0}, maxRoomsPerShard={1}, spread={2}" -f $bestScore.EmptyShardCount, $bestScore.ActualMaxRoomsPerShard, $bestScore.Spread)
        return $true
    }

    return $false
}

function Start-TenRoomAttempt {
    param(
        [switch]$StartInfraForAttempt,
        [switch]$ResetInfraData,
        [switch]$UseBuild,
        [switch]$AllowReuseCurrentProcess
    )

    if ($ResetInfraData) {
        if ($SkipAppRestart) {
            throw "Cannot reset perf infra for shard-guard retry when -SkipAppRestart is enabled."
        }

        & $stopAppScript
        & $startInfraScript -ResetData -WithMonitoring:$WithMonitoring
    } elseif ($StartInfraForAttempt) {
        & $startInfraScript -WithMonitoring:$WithMonitoring
    }

    if (-not $SkipAppRestart) {
        try {
            if (-not $ResetInfraData) {
                & $stopAppScript
            }
            & $startAppScript -Topology $Topology -Profile $appProfile -Build:$UseBuild -AppArgs $appArgs
        } catch {
            $currentMeta = Get-CurrentLocalPerfMeta -RepoRoot $repoRoot
            $reuseAllowed = $false
            if ($AllowReuseCurrentProcess -and $currentMeta) {
                # If this attempt asked for a fresh build, reusing the previous process
                # would silently validate stale code and make perf conclusions unreliable.
                $reuseAllowed = (-not $UseBuild -and $currentMeta.profile -eq $appProfile -and $currentMeta.topology -eq $Topology)
            }

            if ($reuseAllowed) {
                Write-Warning "App restart failed, but current local perf app already matches profile/topology. Reusing existing process."
                Write-Warning ("Reuse app PID={0}, profile={1}, topology={2}" -f $currentMeta.pid, $currentMeta.profile, $currentMeta.topology)
            } else {
                throw
            }
        }
    }

    if ($AppWarmupSec -gt 0) {
        Write-Host "Waiting $AppWarmupSec seconds for app warm-up..."
        Start-Sleep -Seconds $AppWarmupSec
    }
}

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$startInfraScript = Join-Path $PSScriptRoot "Start-PerfInfra.ps1"
$startAppScript = Join-Path $PSScriptRoot "Start-LocalPerfApp.ps1"
$stopAppScript = Join-Path $PSScriptRoot "Stop-LocalPerfApp.ps1"
$invokeScript = Join-Path $PSScriptRoot "Invoke-K6Scenario.ps1"
$summarizeScript = Join-Path $PSScriptRoot "Summarize-TenRoomResults.ps1"
$extractSetupDataScript = Join-Path $PSScriptRoot "Extract-TenRoomSetupData.js"

$presetMap = @{
    50 = @{
        RunLabel = "ten-room-50vu"
        P95 = 280
        P99 = 500
        SendSuccessRate = 0.999
        WsBroadcastP95 = 180
        ErrorRate = 0.001
    }
    100 = @{
        RunLabel = "ten-room-100vu"
        P95 = 340
        P99 = 600
        SendSuccessRate = 0.999
        WsBroadcastP95 = 240
        ErrorRate = 0.001
    }
    120 = @{
        RunLabel = "ten-room-120vu"
        P95 = 380
        P99 = 650
        SendSuccessRate = 0.999
        WsBroadcastP95 = 280
        ErrorRate = 0.001
    }
    150 = @{
        RunLabel = "ten-room-150vu"
        P95 = 440
        P99 = 800
        SendSuccessRate = 0.998
        WsBroadcastP95 = 320
        ErrorRate = 0.002
    }
    160 = @{
        RunLabel = "ten-room-160vu"
        P95 = 470
        P99 = 850
        SendSuccessRate = 0.998
        WsBroadcastP95 = 340
        ErrorRate = 0.002
    }
    175 = @{
        RunLabel = "ten-room-175vu"
        P95 = 500
        P99 = 900
        SendSuccessRate = 0.998
        WsBroadcastP95 = 360
        ErrorRate = 0.002
    }
    185 = @{
        RunLabel = "ten-room-185vu"
        P95 = 515
        P99 = 930
        SendSuccessRate = 0.9978
        WsBroadcastP95 = 375
        ErrorRate = 0.002
    }
    190 = @{
        RunLabel = "ten-room-190vu"
        P95 = 530
        P99 = 950
        SendSuccessRate = 0.9975
        WsBroadcastP95 = 390
        ErrorRate = 0.002
    }
    200 = @{
        RunLabel = "ten-room-200vu"
        P95 = 560
        P99 = 1000
        SendSuccessRate = 0.997
        WsBroadcastP95 = 420
        ErrorRate = 0.002
    }
    230 = @{
        RunLabel = "ten-room-230vu"
        P95 = 650
        P99 = 1200
        SendSuccessRate = 0.996
        WsBroadcastP95 = 520
        ErrorRate = 0.004
    }
    250 = @{
        RunLabel = "ten-room-250vu"
        P95 = 800
        P99 = 1500
        SendSuccessRate = 0.994
        WsBroadcastP95 = 650
        ErrorRate = 0.006
    }
    300 = @{
        RunLabel = "ten-room-300vu"
        P95 = 1200
        P99 = 2200
        SendSuccessRate = 0.99
        WsBroadcastP95 = 900
        ErrorRate = 0.01
    }
    400 = @{
        RunLabel = "ten-room-400vu"
        P95 = 1800
        P99 = 3200
        SendSuccessRate = 0.985
        WsBroadcastP95 = 1400
        ErrorRate = 0.015
    }
    450 = @{
        RunLabel = "ten-room-450vu"
        P95 = 2400
        P99 = 4200
        SendSuccessRate = 0.98
        WsBroadcastP95 = 2000
        ErrorRate = 0.02
    }
}

$preset = $presetMap[$SenderVus]
if ($null -eq $preset) {
    throw "Unsupported SenderVus preset: $SenderVus"
}

# presetMap 的 value 是 hashtable，不是 PSCustomObject。
# 这里统一用键访问，避免在 StrictMode 下通过点属性读取时报错。
$presetRunLabel = [string]$preset["RunLabel"]
$presetP95 = $preset["P95"]
$presetP99 = $preset["P99"]
$presetErrorRate = $preset["ErrorRate"]
$presetSendSuccessRate = $preset["SendSuccessRate"]
$presetWsBroadcastP95 = $preset["WsBroadcastP95"]

$appProfile = "local-perf"
$appArgs = @()
$runLabel = if ([string]::IsNullOrWhiteSpace($RunLabelOverride)) { $presetRunLabel } else { $RunLabelOverride }
$setupShardGuardEnabled = $SetupShardGuardMaxRoomsPerShard -gt 0 -or $SetupShardGuardMinActiveShards -gt 0 -or $SetupShardGuardMaxEmptyShards -gt 0
$maxRunAttempts = if ($setupShardGuardEnabled) { [Math]::Max(1, $SetupShardGuardMaxRetries) } else { 1 }
$fixedTopologyRoomDuration = "DAY_3"
$fixedTopologyCacheDir = Join-Path $repoRoot "perf\run\fixed-topologies"
$fixedTopologyCacheName = ("{0}-{1}rooms-{2}members-{3}-{4}-{5}shards.json" -f $TitlePrefix, $RoomCount, $ParticipantsPerRoom, $Topology, $fixedTopologyRoomDuration.ToLowerInvariant(), $MailboxShardCount).
    Replace('\', '-').
    Replace('/', '-').
    Replace(':', '-').
    Replace(' ', '-')
$fixedTopologyCachePath = Join-Path $fixedTopologyCacheDir $fixedTopologyCacheName
$fixedTopologyCacheK6Path = if ($Runner -eq "docker") {
    "perf/run/fixed-topologies/$fixedTopologyCacheName"
} else {
    $fixedTopologyCachePath
}

if ($SkipAppRestart -and $maxRunAttempts -gt 1) {
    throw "Setup shard-guard retries are incompatible with -SkipAppRestart."
}

New-Item -ItemType Directory -Force -Path $fixedTopologyCacheDir | Out-Null
if (-not (Test-Path $fixedTopologyCachePath)) {
    [void](Seed-FixedTopologyCacheFromLatestRun `
        -RepoRoot $repoRoot `
        -TitlePrefix $TitlePrefix `
        -RoomCount $RoomCount `
        -ParticipantsPerRoom $ParticipantsPerRoom `
        -ShardCount $MailboxShardCount `
        -RoomDuration $fixedTopologyRoomDuration `
        -CachePath $fixedTopologyCachePath `
        -ExtractSetupDataScript $extractSetupDataScript)
}

$mergedEnv = @{
    TEN_ROOM_COUNT = [string]$RoomCount
    TEN_ROOM_PARTICIPANTS_PER_ROOM = [string]$ParticipantsPerRoom
    TEN_ROOM_WS_LISTENERS_PER_ROOM = [string]$WsListenersPerRoom
    TEN_ROOM_STAGE_1 = "30s"
    TEN_ROOM_STAGE_2 = "5m"
    TEN_ROOM_STAGE_3 = "20s"
    TEN_ROOM_TARGET_1 = [string]$SenderVus
    TEN_ROOM_TARGET_2 = [string]$SenderVus
    TEN_ROOM_TARGET_3 = "0"
    TEN_ROOM_SEND_THINK_MS = "200"
    TEN_ROOM_HISTORY_EVERY = "999999"
    TEN_ROOM_ACK_EVERY = "999999"
    TEN_ROOM_WS_DURATION = "7m"
    TEN_ROOM_SHARD_COUNT = [string]$MailboxShardCount
    TEN_ROOM_TITLE_PREFIX = $TitlePrefix
    TEN_ROOM_FIXED_TOPOLOGY_DURATION = $fixedTopologyRoomDuration
    TEN_ROOM_FIXED_TOPOLOGY_SAVE_PATH = $fixedTopologyCacheK6Path
}

if (Test-Path $fixedTopologyCachePath) {
    $mergedEnv["TEN_ROOM_FIXED_TOPOLOGY_PATH"] = $fixedTopologyCacheK6Path
}

if ($setupShardGuardEnabled) {
    if ($SetupShardGuardMaxRoomsPerShard -gt 0) {
        $mergedEnv["TEN_ROOM_MAX_ROOMS_PER_SHARD"] = [string]$SetupShardGuardMaxRoomsPerShard
    }
    if ($SetupShardGuardMinActiveShards -gt 0) {
        $mergedEnv["TEN_ROOM_MIN_ACTIVE_SHARDS"] = [string]$SetupShardGuardMinActiveShards
    }
    if ($SetupShardGuardMaxEmptyShards -gt 0) {
        $mergedEnv["TEN_ROOM_MAX_EMPTY_SHARDS"] = [string]$SetupShardGuardMaxEmptyShards
    }
}

foreach ($pair in $EnvOverrides.GetEnumerator()) {
    $mergedEnv[$pair.Key] = [string]$pair.Value
}

$envLiteral = ConvertTo-PsHashtableLiteral -Table $mergedEnv
$commandParts = @(
    "& '$invokeScript'"
    "-Scenario 'ten-room'"
    "-Runner '$Runner'"
    "-RunLabel '$runLabel'"
    "-P95ThresholdMs $presetP95"
    "-P99ThresholdMs $presetP99"
    "-ErrorRateThreshold $presetErrorRate"
    "-SendSuccessRateThreshold $presetSendSuccessRate"
    "-WsBroadcastP95ThresholdMs $presetWsBroadcastP95"
    "-ActuatorGuard 'room-hotspot'"
    "-RuntimePollSec 10"
    "-RuntimeGuardConsecutiveBreaches 2"
    "-MaxRuntimeMailboxBacklog 0"
    "-MaxRuntimeHikariPending 0"
    "-MaxRuntimeProcessCpu 0.85"
)

if ($AutoCaptureDiagnostics) {
    $commandParts += "-AutoCaptureDiagnostics"
}

if ($AbortOnRuntimeGuardBreach) {
    $commandParts += "-AbortOnRuntimeGuardBreach"
}

$commandParts += "-EnvOverrides $envLiteral"
$commandText = $commandParts -join " "

Write-Host "Ten-room run label: $runLabel"
Write-Host "Sender VUs: $SenderVus"
Write-Host "Runner: $Runner"
Write-Host "Topology: $Topology"
Write-Host "App profile: $appProfile"
Write-Host "Room count: $RoomCount"
Write-Host "Participants per room: $ParticipantsPerRoom"
Write-Host "WS listeners per room: $WsListenersPerRoom"
Write-Host "Mailbox shard count: $MailboxShardCount"
Write-Host "Rate limit: disabled via local-perf profile"
Write-Host "Fixed topology cache: $fixedTopologyCachePath"
Write-Host ("Fixed topology cache exists: {0}" -f (Test-Path $fixedTopologyCachePath))
if ($setupShardGuardEnabled) {
    Write-Host "Setup shard guard: enabled"
    Write-Host "Setup shard guard max rooms/shard: $SetupShardGuardMaxRoomsPerShard"
    Write-Host "Setup shard guard min active shards: $SetupShardGuardMinActiveShards"
    Write-Host "Setup shard guard max empty shards: $SetupShardGuardMaxEmptyShards"
    Write-Host "Setup shard guard max attempts: $maxRunAttempts"
    Write-Host "Setup shard guard retry resets infra: $ResetInfraOnSetupShardGuardRetry"
}

if ($PrepareOnly) {
    Write-Host ""
    Write-Host "Prepared command:"
    Write-Host $commandText
    exit 0
}

$exitCode = 0
$latestRun = $null
$setupGuardFailed = $false
$setupGuardMessage = $null

for ($attempt = 1; $attempt -le $maxRunAttempts; $attempt += 1) {
    $isRetry = $attempt -gt 1
    if ($isRetry) {
        Write-Warning ("Retrying ten-room run after setup shard-guard rejection ({0}/{1})..." -f $attempt, $maxRunAttempts)
    }

    Start-TenRoomAttempt `
        -StartInfraForAttempt:($attempt -eq 1 -and $StartInfra) `
        -ResetInfraData:($isRetry -and $ResetInfraOnSetupShardGuardRetry) `
        -UseBuild:($attempt -eq 1 -and $Build) `
        -AllowReuseCurrentProcess:($attempt -eq 1)

    & powershell -ExecutionPolicy Bypass -Command $commandText
    $exitCode = $LASTEXITCODE

    $latestRun = Get-LatestTenRoomRun -RepoRoot $repoRoot -RunLabel $runLabel
    $setupGuardFailed = $false
    $setupGuardMessage = $null

    if ($latestRun) {
        $briefMap = Get-KeyValueFileMap -Path (Join-Path $latestRun.FullName "brief.txt")
        if ($briefMap.ContainsKey("setup_guard_failed")) {
            [void][bool]::TryParse([string]$briefMap["setup_guard_failed"], [ref]$setupGuardFailed)
        }
        if ($briefMap.ContainsKey("setup_guard_message")) {
            $setupGuardMessage = [string]$briefMap["setup_guard_message"]
        }
    }

    if ($setupGuardFailed -and $attempt -lt $maxRunAttempts) {
        if ($setupGuardMessage) {
            Write-Warning $setupGuardMessage
        }
        if (-not $ResetInfraOnSetupShardGuardRetry) {
            Write-Warning "Shard guard retry will continue without infra reset. Enable -ResetInfraOnSetupShardGuardRetry for a cleaner retry."
        }
        continue
    }

    break
}

if ($latestRun) {
    if (-not $setupGuardFailed) {
        & $summarizeScript -RunDir $latestRun.FullName
        if (-not (Test-Path $fixedTopologyCachePath)) {
            [void](Seed-FixedTopologyCacheFromLatestRun `
                -RepoRoot $repoRoot `
                -TitlePrefix $TitlePrefix `
                -RoomCount $RoomCount `
                -ParticipantsPerRoom $ParticipantsPerRoom `
                -ShardCount $MailboxShardCount `
                -RoomDuration $fixedTopologyRoomDuration `
                -CachePath $fixedTopologyCachePath `
                -ExtractSetupDataScript $extractSetupDataScript)
        }
    } else {
        Write-Warning "Ten-room setup shard guard rejected the final attempt, so no throughput summary was produced."
        if ($setupGuardMessage) {
            Write-Warning $setupGuardMessage
        }
    }

    Write-Host ""
    Write-Host "Latest result directory:"
    Write-Host $latestRun.FullName
}

exit $exitCode
