param(
    [ValidateSet(175, 185, 190, 200, 230, 250, 300, 400, 450)]
    [int]$SenderVus,
    [ValidateSet("local", "docker")]
    [string]$Runner = "local",
    [switch]$Build,
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

$runScript = Join-Path $PSScriptRoot ("Run-MultiRoom-20x50-{0}.ps1" -f $SenderVus)
$inspectScript = Join-Path $PSScriptRoot "Inspect-MailboxStepMetrics.ps1"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path

if (-not (Test-Path $runScript)) {
    throw "Mailbox probe run script not found: $runScript"
}

& $runScript -Runner $Runner -Build:$Build -AppWarmupSec $AppWarmupSec
$runExitCode = $LASTEXITCODE

$latestRun = Get-ChildItem (Join-Path $repoRoot "perf\results") -Directory |
    Where-Object { $_.Name -like "*ten-room*multi-room-20x50-$($SenderVus)vu*" } |
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
