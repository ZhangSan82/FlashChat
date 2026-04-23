param(
    [string]$RunDir,
    [string]$OutputJson,
    [string]$OutputMarkdown
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Get-GcSummary {
    param(
        [string]$GcLogPath
    )

    if (-not $GcLogPath -or -not (Test-Path $GcLogPath)) {
        return $null
    }

    $content = Get-Content $GcLogPath
    $pauseMatches = @(
        $content |
            Select-String -Pattern " ([0-9]+(?:\.[0-9]+)?)ms$" |
            ForEach-Object {
                if ($_.Matches.Count -gt 0) {
                    [double]$_.Matches[0].Groups[1].Value
                }
            }
    )

    return [ordered]@{
        gcLogPath = $GcLogPath
        mmuViolationCount = @($content | Select-String -Pattern "MMU target violated").Count
        evacuationFailureCount = @($content | Select-String -Pattern "Evacuation Failure").Count
        humongousAllocationCount = @($content | Select-String -Pattern "Humongous Allocation").Count
        maxPauseMs = if ($pauseMatches.Count -gt 0) { ($pauseMatches | Measure-Object -Maximum).Maximum } else { $null }
    }
}

function Get-NestedValue {
    param(
        [object]$Object,
        [string[]]$Path
    )

    $current = $Object
    foreach ($segment in $Path) {
        if ($null -eq $current) {
            return $null
        }

        $prop = $current.PSObject.Properties[$segment]
        if (-not $prop) {
            return $null
        }
        $current = $prop.Value
    }

    return $current
}

if (-not $RunDir) {
    throw "Provide -RunDir."
}

if (-not (Test-Path $RunDir)) {
    throw "Run directory not found: $RunDir"
}

$summaryPath = Join-Path $RunDir "ten-room-summary.json"
if (-not (Test-Path $summaryPath)) {
    throw "ten-room-summary.json not found in $RunDir"
}

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$runtimeSummaryScript = Join-Path $PSScriptRoot "Summarize-RuntimeSamples.ps1"
$runtimeSamplesPath = Join-Path $RunDir "runtime-samples.jsonl"
$appRunContextPath = Join-Path $RunDir "app-run-context.json"

$summary = Get-Content $summaryPath -Raw | ConvertFrom-Json
$runtimeSummary = $null
if (Test-Path $runtimeSamplesPath) {
    $runtimeSummary = & $runtimeSummaryScript -RuntimeSamplesPath $runtimeSamplesPath | ConvertFrom-Json
}

$gcSummary = $null
if (Test-Path $appRunContextPath) {
    $appRunContext = Get-Content $appRunContextPath -Raw | ConvertFrom-Json
    $gcSummary = Get-GcSummary -GcLogPath $appRunContext.gcLog
}

$report = [ordered]@{
    runDir = $RunDir
    summaryPath = $summaryPath
    totalSendSuccesses = $summary.totalSendSuccesses
    totalSendSuccessesPerSec = $summary.totalSendSuccessesPerSec
    totalSendSuccessRate = $summary.totalSendSuccessRate
    totalHttpP95Ms = $summary.totalHttpP95Ms
    totalHttpP99Ms = $summary.totalHttpP99Ms
    setupTopologySource = $summary.setupTopologySource
    setupShardCount = $summary.setupShardCount
    setupShardCounts = $summary.setupShardCounts
    setupActualMaxRoomsPerShard = $summary.setupActualMaxRoomsPerShard
    setupActiveShardCount = $summary.setupActiveShardCount
    setupEmptyShardCount = $summary.setupEmptyShardCount
    totalWsListenerLogins = $summary.totalWsListenerLogins
    totalWsBroadcastP95Ms = $summary.totalWsBroadcastP95Ms
    totalWsBroadcastP99Ms = $summary.totalWsBroadcastP99Ms
    totalWsServerToListenerP95Ms = $summary.totalWsServerToListenerP95Ms
    totalWsServerToListenerP99Ms = $summary.totalWsServerToListenerP99Ms
    totalSendToServerGapP95Ms = $summary.totalSendToServerGapP95Ms
    totalSendToServerGapP99Ms = $summary.totalSendToServerGapP99Ms
    perRoomSendSuccessesPerSec = $summary.perRoomSendSuccessesPerSec
    perRoomSendSuccessRate = $summary.perRoomSendSuccessRate
    perRoomWsListenerLogins = $summary.perRoomWsListenerLogins
    perRoomWsBroadcastP95Ms = $summary.perRoomWsBroadcastP95Ms
    perRoomWsBroadcastP99Ms = $summary.perRoomWsBroadcastP99Ms
    perRoomWsServerToListenerP95Ms = $summary.perRoomWsServerToListenerP95Ms
    perRoomWsServerToListenerP99Ms = $summary.perRoomWsServerToListenerP99Ms
    perRoomSendToServerGapP95Ms = $summary.perRoomSendToServerGapP95Ms
    perRoomSendToServerGapP99Ms = $summary.perRoomSendToServerGapP99Ms
    roomSkew = $summary.roomSkew
    roomsMissingWsListener = $summary.roomsMissingWsListener
    roomsWithExtraWsListeners = $summary.roomsWithExtraWsListeners
    sendTimeoutFailures = $summary.sendTimeoutFailures
    sendTransportFailures = $summary.sendTransportFailures
    sendRateLimited = $summary.sendRateLimited
    sendRoomGlobalLimited = $summary.sendRoomGlobalLimited
    sendUserRoomLimited = $summary.sendUserRoomLimited
    runtimeSummary = $runtimeSummary
    gcSummary = $gcSummary
}

if (-not $OutputJson) {
    $OutputJson = Join-Path $RunDir "ten-room-report.json"
}
if (-not $OutputMarkdown) {
    $OutputMarkdown = Join-Path $RunDir "ten-room-report.md"
}

$report | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $OutputJson -Encoding utf8

$markdown = New-Object System.Collections.Generic.List[string]
$null = $markdown.Add('# Ten-Room Report')
$null = $markdown.Add('')
$null = $markdown.Add(('Run directory: {0}' -f $RunDir))
$null = $markdown.Add('')
$null = $markdown.Add('| Field | Value |')
$null = $markdown.Add('| --- | --- |')
$null = $markdown.Add(('| totalSendSuccesses | {0} |' -f $summary.totalSendSuccesses))
$null = $markdown.Add(('| totalSendSuccessesPerSec | {0} |' -f $summary.totalSendSuccessesPerSec))
$null = $markdown.Add(('| totalSendSuccessRate | {0} |' -f $summary.totalSendSuccessRate))
$null = $markdown.Add(('| totalHttpP95Ms | {0} |' -f $summary.totalHttpP95Ms))
$null = $markdown.Add(('| totalHttpP99Ms | {0} |' -f $summary.totalHttpP99Ms))
$null = $markdown.Add(('| setupTopologySource | {0} |' -f $summary.setupTopologySource))
$null = $markdown.Add(('| setupShardCount | {0} |' -f $summary.setupShardCount))
$null = $markdown.Add(('| setupShardCounts | {0} |' -f (($summary.setupShardCounts -join ', '))) )
$null = $markdown.Add(('| setupActualMaxRoomsPerShard | {0} |' -f $summary.setupActualMaxRoomsPerShard))
$null = $markdown.Add(('| setupActiveShardCount | {0} |' -f $summary.setupActiveShardCount))
$null = $markdown.Add(('| setupEmptyShardCount | {0} |' -f $summary.setupEmptyShardCount))
$null = $markdown.Add(('| totalWsListenerLogins | {0} |' -f $summary.totalWsListenerLogins))
$null = $markdown.Add(('| totalWsBroadcastP95Ms | {0} |' -f $summary.totalWsBroadcastP95Ms))
$null = $markdown.Add(('| totalWsBroadcastP99Ms | {0} |' -f $summary.totalWsBroadcastP99Ms))
$null = $markdown.Add(('| totalWsServerToListenerP95Ms | {0} |' -f $summary.totalWsServerToListenerP95Ms))
$null = $markdown.Add(('| totalWsServerToListenerP99Ms | {0} |' -f $summary.totalWsServerToListenerP99Ms))
$null = $markdown.Add(('| totalSendToServerGapP95Ms | {0} |' -f $summary.totalSendToServerGapP95Ms))
$null = $markdown.Add(('| totalSendToServerGapP99Ms | {0} |' -f $summary.totalSendToServerGapP99Ms))
$null = $markdown.Add(('| roomSkew | {0} |' -f $summary.roomSkew))
$null = $markdown.Add(('| sendTimeoutFailures | {0} |' -f $summary.sendTimeoutFailures))
$null = $markdown.Add(('| sendTransportFailures | {0} |' -f $summary.sendTransportFailures))
$null = $markdown.Add(('| roomsMissingWsListener | {0} |' -f (($summary.roomsMissingWsListener -join ', '))) )
$null = $markdown.Add(('| roomsWithExtraWsListeners | {0} |' -f (($summary.roomsWithExtraWsListeners -join ', '))) )
$null = $markdown.Add('')
$null = $markdown.Add('## Per Room Send Successes Per Second')
$null = $markdown.Add('')

foreach ($roomKey in ($summary.perRoomSendSuccessesPerSec.PSObject.Properties.Name | Sort-Object)) {
    $roomSendPerSec = $summary.perRoomSendSuccessesPerSec.PSObject.Properties[$roomKey].Value
    $roomSuccessRate = $summary.perRoomSendSuccessRate.PSObject.Properties[$roomKey].Value
    $roomListenerLogins = $summary.perRoomWsListenerLogins.PSObject.Properties[$roomKey].Value
    $roomWsP95 = $summary.perRoomWsBroadcastP95Ms.PSObject.Properties[$roomKey].Value
    $roomWsServerP95 = $summary.perRoomWsServerToListenerP95Ms.PSObject.Properties[$roomKey].Value
    $roomSendToServerGapP95 = $summary.perRoomSendToServerGapP95Ms.PSObject.Properties[$roomKey].Value
    $null = $markdown.Add(('- {0}: {1} msg/s, successRate={2}, listenerLogins={3}, wsP95={4}, wsServerP95={5}, sendToServerGapP95={6}' -f $roomKey, $roomSendPerSec, $roomSuccessRate, $roomListenerLogins, $roomWsP95, $roomWsServerP95, $roomSendToServerGapP95))
}

if ($runtimeSummary) {
    $null = $markdown.Add('')
    $null = $markdown.Add('## Runtime Summary')
    $null = $markdown.Add('')
    $null = $markdown.Add(('- sampleCount: {0}' -f $runtimeSummary.sampleCount))
    $null = $markdown.Add(('- processCpuUsage.max: {0}' -f (Get-NestedValue -Object $runtimeSummary -Path @('processCpuUsage', 'max'))))
    $null = $markdown.Add(('- mailboxBacklog.max: {0}' -f (Get-NestedValue -Object $runtimeSummary -Path @('mailboxBacklog', 'max'))))
    $null = $markdown.Add(('- hikariPending.max: {0}' -f (Get-NestedValue -Object $runtimeSummary -Path @('hikariPending', 'max'))))
}

if ($gcSummary) {
    $null = $markdown.Add('')
    $null = $markdown.Add('## GC Summary')
    $null = $markdown.Add('')
    $null = $markdown.Add(('- gcLogPath: {0}' -f $gcSummary.gcLogPath))
    $null = $markdown.Add(('- mmuViolationCount: {0}' -f $gcSummary.mmuViolationCount))
    $null = $markdown.Add(('- evacuationFailureCount: {0}' -f $gcSummary.evacuationFailureCount))
    $null = $markdown.Add(('- humongousAllocationCount: {0}' -f $gcSummary.humongousAllocationCount))
    $null = $markdown.Add(('- maxPauseMs: {0}' -f $gcSummary.maxPauseMs))
}

$markdown | Set-Content -LiteralPath $OutputMarkdown -Encoding utf8

Write-Host "Ten-room report written to:"
Write-Host $OutputJson
Write-Host $OutputMarkdown
