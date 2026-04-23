param(
    [ValidateSet("local", "docker")]
    [string]$Runner = "local",
    [int]$AppWarmupSec = 60
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$runScript = Join-Path $PSScriptRoot "Run-MultiRoom-20x50-200.ps1"
$inspectScript = Join-Path $PSScriptRoot "Inspect-MailboxStepMetrics.ps1"

& $runScript -Runner $Runner -AppWarmupSec $AppWarmupSec
& $inspectScript
