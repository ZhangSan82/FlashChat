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
    [int]$AppWarmupSec = 60
)

& (Join-Path $PSScriptRoot "Run-TenRoom.ps1") `
    -SenderVus 185 `
    -Topology $Topology `
    -Runner $Runner `
    -Build:$Build `
    -StartInfra:$StartInfra `
    -WithMonitoring:$WithMonitoring `
    -SkipAppRestart:$SkipAppRestart `
    -PrepareOnly:$PrepareOnly `
    -AbortOnRuntimeGuardBreach:$AbortOnRuntimeGuardBreach `
    -AppWarmupSec $AppWarmupSec `
    -RoomCount 20 `
    -ParticipantsPerRoom 50 `
    -WsListenersPerRoom 1 `
    -TitlePrefix "multi-room-20x50" `
    -SetupShardGuardMaxRoomsPerShard 4 `
    -SetupShardGuardMaxRetries 8 `
    -ResetInfraOnSetupShardGuardRetry `
    -RunLabelOverride "multi-room-20x50-185vu"
