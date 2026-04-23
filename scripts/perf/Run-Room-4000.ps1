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
    [hashtable]$EnvOverrides = @{}
)

& (Join-Path $PSScriptRoot "Run-RoomHotspot.ps1") `
    -Tier "4000" `
    -Topology $Topology `
    -Runner $Runner `
    -Build:$Build `
    -StartInfra:$StartInfra `
    -WithMonitoring:$WithMonitoring `
    -SkipAppRestart:$SkipAppRestart `
    -PrepareOnly:$PrepareOnly `
    -AbortOnRuntimeGuardBreach:$AbortOnRuntimeGuardBreach `
    -EnvOverrides $EnvOverrides
