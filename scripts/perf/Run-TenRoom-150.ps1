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
    [switch]$AbortOnRuntimeGuardBreach
)

& (Join-Path $PSScriptRoot "Run-TenRoom.ps1") `
    -SenderVus 150 `
    -Topology $Topology `
    -Runner $Runner `
    -Build:$Build `
    -StartInfra:$StartInfra `
    -WithMonitoring:$WithMonitoring `
    -SkipAppRestart:$SkipAppRestart `
    -PrepareOnly:$PrepareOnly `
    -AbortOnRuntimeGuardBreach:$AbortOnRuntimeGuardBreach
