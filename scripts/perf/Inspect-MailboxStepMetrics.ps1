param(
    [string]$BaseUrl = "http://localhost:8081",
    [string]$ResultDir = "",
    [string]$OutputPrefix = "mailbox-step-diagnosis"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Resolve-ResultDirectory {
    param(
        [string]$RequestedResultDir,
        [string]$RepoRoot
    )

    if ($RequestedResultDir) {
        return (Resolve-Path $RequestedResultDir).Path
    }

    $resultRoot = Join-Path $RepoRoot "perf\\results"
    $latest = Get-ChildItem -Path $resultRoot -Directory |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1

    if (-not $latest) {
        throw "No perf result directory found under $resultRoot"
    }

    return $latest.FullName
}

function Build-MetricUri {
    param(
        [string]$MetricName,
        [hashtable]$Tags = @{}
    )

    $uri = "$BaseUrl/actuator/metrics/$MetricName"
    if ($Tags.Count -eq 0) {
        return $uri
    }

    $queryParts = @()
    foreach ($entry in $Tags.GetEnumerator()) {
        $queryParts += "tag={0}:{1}" -f [uri]::EscapeDataString($entry.Key), [uri]::EscapeDataString([string]$entry.Value)
    }
    return "${uri}?{0}" -f ($queryParts -join "&")
}

function Get-MetricDocument {
    param(
        [string]$MetricName,
        [hashtable]$Tags = @{}
    )

    $uri = Build-MetricUri -MetricName $MetricName -Tags $Tags
    try {
        return Invoke-RestMethod -UseBasicParsing -Uri $uri -TimeoutSec 8
    } catch {
        return [pscustomobject][ordered]@{
            name = $MetricName
            tags = $Tags
            error = $_.Exception.Message
            baseUnit = $null
            measurements = @()
            availableTags = @()
        }
    }
}

function Get-OptionalPropertyValue {
    param(
        [object]$Object,
        [string]$PropertyName
    )

    if ($null -eq $Object) {
        return $null
    }

    $property = $Object.PSObject.Properties[$PropertyName]
    if ($null -eq $property) {
        return $null
    }

    return $property.Value
}

function Convert-ToMilliseconds {
    param(
        [double]$Value,
        [string]$BaseUnit
    )

    switch ($BaseUnit) {
        "seconds" { return [math]::Round($Value * 1000, 3) }
        "milliseconds" { return [math]::Round($Value, 3) }
        "microseconds" { return [math]::Round($Value / 1000, 3) }
        "nanoseconds" { return [math]::Round($Value / 1000000, 3) }
        default { return [math]::Round($Value, 3) }
    }
}

function Get-MeasurementValue {
    param(
        [object]$MetricDocument,
        [string]$Statistic
    )

    $measurements = Get-OptionalPropertyValue -Object $MetricDocument -PropertyName "measurements"
    if (-not $MetricDocument -or -not $measurements) {
        return $null
    }

    $match = @($measurements | Where-Object { $_.statistic -eq $Statistic } | Select-Object -First 1)
    if ($match.Count -eq 0) {
        return $null
    }

    return [double]$match[0].value
}

function Summarize-TimerMetric {
    param(
        [object]$MetricDocument,
        [string]$DisplayName
    )

    $errorProperty = if ($null -ne $MetricDocument) {
        $MetricDocument.PSObject.Properties["error"]
    } else {
        $null
    }

    if ($null -ne $errorProperty -and $MetricDocument.error) {
        return [pscustomobject][ordered]@{
            name = $DisplayName
            available = $false
            error = $MetricDocument.error
        }
    }

    $baseUnitValue = Get-OptionalPropertyValue -Object $MetricDocument -PropertyName "baseUnit"
    $baseUnit = if ($baseUnitValue) { [string]$baseUnitValue } else { "" }
    $count = Get-MeasurementValue -MetricDocument $MetricDocument -Statistic "COUNT"
    $total = Get-MeasurementValue -MetricDocument $MetricDocument -Statistic "TOTAL_TIME"
    $max = Get-MeasurementValue -MetricDocument $MetricDocument -Statistic "MAX"

    $totalMs = if ($null -ne $total) { Convert-ToMilliseconds -Value $total -BaseUnit $baseUnit } else { $null }
    $maxMs = if ($null -ne $max) { Convert-ToMilliseconds -Value $max -BaseUnit $baseUnit } else { $null }
    $avgMs = if ($null -ne $count -and $count -gt 0 -and $null -ne $totalMs) {
        [math]::Round($totalMs / $count, 3)
    } else {
        $null
    }

    return [pscustomobject][ordered]@{
        name = $DisplayName
        available = $true
        count = if ($null -ne $count) { [long]$count } else { 0L }
        avgMs = $avgMs
        maxMs = $maxMs
        totalMs = $totalMs
        baseUnit = $baseUnit
    }
}

function Summarize-CounterMetric {
    param(
        [object]$MetricDocument,
        [string]$DisplayName
    )

    $errorProperty = if ($null -ne $MetricDocument) {
        $MetricDocument.PSObject.Properties["error"]
    } else {
        $null
    }

    if ($null -ne $errorProperty -and $MetricDocument.error) {
        return [pscustomobject][ordered]@{
            name = $DisplayName
            available = $false
            error = $MetricDocument.error
        }
    }

    $count = Get-MeasurementValue -MetricDocument $MetricDocument -Statistic "COUNT"
    return [pscustomobject][ordered]@{
        name = $DisplayName
        available = $true
        count = if ($null -ne $count) { [long]$count } else { 0L }
    }
}

function Get-DominantMetric {
    param(
        [object[]]$Summaries
    )

    $candidates = @(
        $Summaries |
            Where-Object { $_.available -and $_.count -gt 0 -and $null -ne $_.avgMs } |
            Sort-Object { [double]$_.avgMs } -Descending
    )

    if ($candidates.Count -eq 0) {
        return $null
    }

    return $candidates[0]
}

function New-MarkdownTable {
    param(
        [object[]]$Rows
    )

    $lines = @(
        "| metric | count | avgMs | maxMs | totalMs |",
        "| --- | ---: | ---: | ---: | ---: |"
    )

    foreach ($row in $Rows) {
        if (-not $row.available) {
            $lines += "| {0} | - | - | - | - |" -f $row.name
            continue
        }

        $lines += "| {0} | {1} | {2} | {3} | {4} |" -f `
            $row.name, `
            $row.count, `
            $(if ($null -ne $row.avgMs) { $row.avgMs } else { "-" }), `
            $(if ($null -ne $row.maxMs) { $row.maxMs } else { "-" }), `
            $(if ($null -ne $row.totalMs) { $row.totalMs } else { "-" })
    }

    return $lines -join [Environment]::NewLine
}

function New-MarkdownCounterTable {
    param(
        [object[]]$Rows
    )

    $lines = @(
        "| metric | count |",
        "| --- | ---: |"
    )

    foreach ($row in $Rows) {
        if (-not $row.available) {
            $lines += "| {0} | - |" -f $row.name
            continue
        }

        $lines += "| {0} | {1} |" -f $row.name, $row.count
    }

    return $lines -join [Environment]::NewLine
}

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\\..")).Path
$resolvedResultDir = Resolve-ResultDirectory -RequestedResultDir $ResultDir -RepoRoot $repoRoot

$queueWaitSummary = Summarize-TimerMetric `
    -MetricDocument (Get-MetricDocument -MetricName "chat.mailbox.queue.wait.duration") `
    -DisplayName "chat.mailbox.queue.wait.duration"
$taskSummary = Summarize-TimerMetric `
    -MetricDocument (Get-MetricDocument -MetricName "chat.mailbox.task.duration") `
    -DisplayName "chat.mailbox.task.duration"

$stepNames = @(
    "window_add",
    "broadcast",
    "touch",
    "unread",
    "window_update",
    "window_remove",
    "broadcast_reaction",
    "broadcast_state"
)

$stepSummaries = @()
foreach ($step in $stepNames) {
    $stepSummaries += Summarize-TimerMetric `
        -MetricDocument (Get-MetricDocument -MetricName "chat.side_effect.step.duration" -Tags @{ step = $step }) `
        -DisplayName $step
}

$broadcastPhaseSummaries = @()
$broadcastPhaseSummaries += Summarize-TimerMetric `
    -MetricDocument (Get-MetricDocument -MetricName "flashchat.broadcast.serialize.duration") `
    -DisplayName "serialize"
$broadcastPhaseSummaries += Summarize-TimerMetric `
    -MetricDocument (Get-MetricDocument -MetricName "flashchat.broadcast.write.duration") `
    -DisplayName "write"
$broadcastPhaseSummaries += Summarize-TimerMetric `
    -MetricDocument (Get-MetricDocument -MetricName "flashchat.broadcast.flush.duration") `
    -DisplayName "flush"
$broadcastPhaseSummaries += Summarize-TimerMetric `
    -MetricDocument (Get-MetricDocument -MetricName "flashchat.broadcast.write.complete.duration") `
    -DisplayName "write_complete"
$broadcastPhaseSummaries += Summarize-TimerMetric `
    -MetricDocument (Get-MetricDocument -MetricName "flashchat.broadcast.batch.complete.duration") `
    -DisplayName "batch_complete"

$broadcastCounterSummaries = @()
$broadcastCounterSummaries += Summarize-CounterMetric `
    -MetricDocument (Get-MetricDocument -MetricName "flashchat.broadcast.write.failure") `
    -DisplayName "write_failure"

$windowAddPhaseSummaries = @()
$windowAddPhaseSummaries += Summarize-TimerMetric `
    -MetricDocument (Get-MetricDocument -MetricName "flashchat.window.add.serialize.duration") `
    -DisplayName "serialize"
$windowAddPhaseSummaries += Summarize-TimerMetric `
    -MetricDocument (Get-MetricDocument -MetricName "flashchat.window.add.redis.duration") `
    -DisplayName "redis"

# ===== 同步路径埋点(sendMsg 临界区 / 锁前 / XADD / 降级分配)=====
# 用途:判定瓶颈是否已下移到 sendMsg 同步路径本身(非 mailbox)。
# 现成 Timer: chat.stripe.lock.wait.duration / chat.msg.xadd.duration
# 现成 Counter: chat.msg.xadd.fail / chat.stripe.lock.timeout(只有 COUNT,avg/max 为 -)
# 新增 Timer(ChatServiceImpl/MsgIdGenerator):
#   flashchat.send.pre_lock.duration      入口 → acquireRoomLockOrBusy 之前
#   flashchat.send.critical.duration      try-with-resources 真实临界区(不含等锁)
#   flashchat.msg.id.slow_path.duration   tryNextId 段耗尽走 Redis Lua 的分支
# ===== send-sync 路径诊断(sendMsg 锁前 / 真正临界区 / XADD / 慢路径 ID 分配) =====
# 目标: 判断瓶颈是否已经从 mailbox 主链路下移到 sendMsg 同步路径本身。
# Timer:
#   chat.stripe.lock.wait.duration         acquireRoomLockOrBusy 等锁时间
#   flashchat.send.pre_lock.duration       入口 -> acquireRoomLockOrBusy 之前的总耗时
#   flashchat.send.rate_limit.duration     rate limit 子步骤
#   flashchat.send.audit.duration          audit chain 子步骤
#   flashchat.send.reply_validate.duration reply 校验子步骤
#   flashchat.send.critical.duration       锁内真正临界区(ID 分配 + XADD + mailbox submit, 不含等锁)
#   chat.msg.xadd.duration                 XADD 本身耗时
#   flashchat.msg.id.slow_path.duration    tryNextId 段耗尽后走 Redis 慢路径的耗时
# Counter:
#   chat.msg.xadd.fail / chat.stripe.lock.timeout / chat.send.rate_limited
#   chat.reply.not_found / chat.msg.persist.fallback
$sendSyncPathTimerSummaries = @(
    (Summarize-TimerMetric `
        -MetricDocument (Get-MetricDocument -MetricName "chat.stripe.lock.wait.duration") `
        -DisplayName "stripe.lock.wait"),
    (Summarize-TimerMetric `
        -MetricDocument (Get-MetricDocument -MetricName "flashchat.send.pre_lock.duration") `
        -DisplayName "send.pre_lock"),
    (Summarize-TimerMetric `
        -MetricDocument (Get-MetricDocument -MetricName "flashchat.send.rate_limit.duration") `
        -DisplayName "send.rate_limit"),
    (Summarize-TimerMetric `
        -MetricDocument (Get-MetricDocument -MetricName "flashchat.send.audit.duration") `
        -DisplayName "send.audit"),
    (Summarize-TimerMetric `
        -MetricDocument (Get-MetricDocument -MetricName "flashchat.send.reply_validate.duration") `
        -DisplayName "send.reply_validate"),
    (Summarize-TimerMetric `
        -MetricDocument (Get-MetricDocument -MetricName "flashchat.send.critical.duration") `
        -DisplayName "send.critical"),
    (Summarize-TimerMetric `
        -MetricDocument (Get-MetricDocument -MetricName "chat.msg.xadd.duration") `
        -DisplayName "msg.xadd"),
    (Summarize-TimerMetric `
        -MetricDocument (Get-MetricDocument -MetricName "chat.msg.xadd_window.merged.duration") `
        -DisplayName "msg.xadd_window.merged"),
    (Summarize-TimerMetric `
        -MetricDocument (Get-MetricDocument -MetricName "flashchat.msg.id.slow_path.duration") `
        -DisplayName "msg.id.slow_path")
)

$sendSyncPathCounterSummaries = @(
    (Summarize-CounterMetric `
        -MetricDocument (Get-MetricDocument -MetricName "chat.msg.xadd.fail") `
        -DisplayName "msg.xadd.fail"),
    (Summarize-CounterMetric `
        -MetricDocument (Get-MetricDocument -MetricName "chat.stripe.lock.timeout") `
        -DisplayName "stripe.lock.timeout"),
    (Summarize-CounterMetric `
        -MetricDocument (Get-MetricDocument -MetricName "chat.send.rate_limited") `
        -DisplayName "send.rate_limited"),
    (Summarize-CounterMetric `
        -MetricDocument (Get-MetricDocument -MetricName "chat.reply.not_found") `
        -DisplayName "reply.not_found"),
    (Summarize-CounterMetric `
        -MetricDocument (Get-MetricDocument -MetricName "chat.msg.persist.fallback") `
        -DisplayName "msg.persist.fallback")
)

$dominantStep = Get-DominantMetric -Summaries $stepSummaries
$dominantBroadcastPhase = Get-DominantMetric -Summaries $broadcastPhaseSummaries
$dominantWindowAddPhase = Get-DominantMetric -Summaries $windowAddPhaseSummaries
$dominantSendSyncTimer = Get-DominantMetric -Summaries $sendSyncPathTimerSummaries

$findings = New-Object System.Collections.Generic.List[string]
$suggestions = New-Object System.Collections.Generic.List[string]

if ($queueWaitSummary.available -and $taskSummary.available -and
    $null -ne $queueWaitSummary.avgMs -and $null -ne $taskSummary.avgMs -and
    $queueWaitSummary.avgMs -gt $taskSummary.avgMs) {
    $findings.Add("Queue wait is larger than task execution time. This points to shard queueing.")
    $suggestions.Add("Check room-to-shard distribution and hot-room collisions on the same shard.")
}

if ($dominantStep) {
    switch ($dominantStep.name) {
        "broadcast" {
            $findings.Add("Broadcast is the slowest side-effect step by average latency.")
            if ($dominantBroadcastPhase) {
                $findings.Add("The slowest broadcast phase is $($dominantBroadcastPhase.name).")
                switch ($dominantBroadcastPhase.name) {
                    "write" { $suggestions.Add("Investigate room fan-out, Netty write-out cost, and slow-client skips.") }
                    "flush" { $suggestions.Add("Investigate flush jitter, event-loop pressure, and outbound buffers.") }
                    "serialize" { $suggestions.Add("Investigate JSON serialization cost and allocation pressure.") }
                    "write_complete" { $suggestions.Add("Investigate Netty outbound queue drain time, channel future completion tail, socket backpressure, and listener-side receive jitter.") }
                    "batch_complete" { $suggestions.Add("Investigate whole-batch fan-out completion tail, per-room recipient count, event-loop scheduling, and slow-client impact on the last completed channel.") }
                }
            }
        }
        "window_add" {
            $findings.Add("window_add is the slowest side-effect step.")
            if ($dominantWindowAddPhase) {
                $findings.Add("The slowest window_add phase is $($dominantWindowAddPhase.name).")
                switch ($dominantWindowAddPhase.name) {
                    "serialize" { $suggestions.Add("Investigate message payload size, JSON allocation pressure, and duplicate serialization.") }
                    "redis" { $suggestions.Add("Investigate Redis RTT, pipeline cost, and window-key hotspots.") }
                }
            } else {
                $suggestions.Add("Investigate Redis RTT, pipeline cost, and window-key hotspots.")
            }
        }
        "unread" {
            $findings.Add("unread is the slowest side-effect step.")
            $suggestions.Add("Investigate unread fan-out and HINCRBY/EXPIRE pipeline cost.")
        }
        "touch" {
            $findings.Add("touch appears slowest, but this is rarely the true root cause.")
            $suggestions.Add("Recheck broadcast, window_add, and unread metrics before optimizing touchMember.")
        }
        default {
            $findings.Add("$($dominantStep.name) is the slowest side-effect step.")
        }
    }
} else {
    $findings.Add("No valid step-level timer samples were captured.")
}

if ($dominantSendSyncTimer) {
    $findings.Add("$($dominantSendSyncTimer.name) is the slowest send-sync timer.")
    switch ($dominantSendSyncTimer.name) {
        "stripe.lock.wait" { $suggestions.Add("Investigate room-to-stripe collisions and lock hold time in sendMsg critical sections.") }
        "send.pre_lock" { $suggestions.Add("Break send.pre_lock into rate limit, audit, reply validation, and other pre-lock phases.") }
        "send.rate_limit" { $suggestions.Add("Investigate rate-limit Redis cost and whether limiter calls can be reduced or batched.") }
        "send.audit" { $suggestions.Add("Investigate message audit chain latency and any synchronous network or regex-heavy checks.") }
        "send.reply_validate" { $suggestions.Add("Investigate reply validation DB reads and whether reply lookup can hit window/cache first.") }
        "send.critical" { $suggestions.Add("Investigate critical-section work split between ID allocation, XADD/XADD+window, and mailbox submit.") }
        "msg.xadd" { $suggestions.Add("Investigate Redis XADD latency, stream trimming cost, and contention with mailbox/window writes.") }
        "msg.xadd_window.merged" { $suggestions.Add("Investigate merged Lua RTT, stream/window key contention, JSON payload size, and whether window work has shifted out of mailbox into send critical path.") }
        "msg.id.slow_path" { $suggestions.Add("Investigate msg ID slow-path Lua latency and consider segment size or Redis contention.") }
    }
}

$mergedXaddWindowSummary = @($sendSyncPathTimerSummaries | Where-Object { $_.name -eq "msg.xadd_window.merged" } | Select-Object -First 1)
$windowAddStepSummary = @($stepSummaries | Where-Object { $_.name -eq "window_add" } | Select-Object -First 1)
$legacyXaddSummary = @($sendSyncPathTimerSummaries | Where-Object { $_.name -eq "msg.xadd" } | Select-Object -First 1)

if ($mergedXaddWindowSummary.Count -gt 0 -and $mergedXaddWindowSummary[0].available) {
    $findings.Add("Merged XADD+window timer is active; user-message window_add cost may now be counted on the send path instead of mailbox side effects.")

    $windowAddCount = if ($windowAddStepSummary.Count -gt 0) { $windowAddStepSummary[0].count } else { $null }
    $legacyXaddCount = if ($legacyXaddSummary.Count -gt 0) { $legacyXaddSummary[0].count } else { $null }

    if (($null -eq $windowAddCount -or $windowAddCount -eq 0) -and ($null -eq $legacyXaddCount -or $legacyXaddCount -eq 0)) {
        $findings.Add("Mailbox window_add and legacy msg.xadd timers are both zero while merged XADD+window is present. The old timer view would under-report current send-path Redis work.")
    }
}

$writeCompleteSummary = @($broadcastPhaseSummaries | Where-Object { $_.name -eq "write_complete" } | Select-Object -First 1)
$batchCompleteSummary = @($broadcastPhaseSummaries | Where-Object { $_.name -eq "batch_complete" } | Select-Object -First 1)
$writeIssueSummary = @($broadcastPhaseSummaries | Where-Object { $_.name -eq "write" } | Select-Object -First 1)

if ($writeCompleteSummary.Count -gt 0 -and $writeIssueSummary.Count -gt 0 -and
    $writeCompleteSummary[0].available -and $writeIssueSummary[0].available -and
    $null -ne $writeCompleteSummary[0].avgMs -and $null -ne $writeIssueSummary[0].avgMs -and
    $writeCompleteSummary[0].avgMs -gt ($writeIssueSummary[0].avgMs * 2.0)) {
    $findings.Add("Broadcast async write completion is much slower than write issue time. Hidden tail likely exists after ch.write() returns, inside Netty/event-loop/socket drain.")
}

if ($batchCompleteSummary.Count -gt 0 -and $batchCompleteSummary[0].available -and
    $null -ne $batchCompleteSummary[0].avgMs -and $batchCompleteSummary[0].avgMs -gt 5) {
    $findings.Add("Broadcast batch completion has a measurable tail beyond issue/flush timing. Server-side fan-out may still be pending after mailbox task returns.")
}

if ($queueWaitSummary.available -and $null -ne $queueWaitSummary.maxMs -and $queueWaitSummary.maxMs -gt 500) {
    $suggestions.Add("queue.wait.max is above 500ms. Capture backlog trend and a fresh diagnostics bundle.")
}

$summary = [ordered]@{
    createdAt = (Get-Date).ToString("s")
    resultDir = $resolvedResultDir
    queueWait = $queueWaitSummary
    mailboxTask = $taskSummary
    dominantStep = $dominantStep
    dominantBroadcastPhase = $dominantBroadcastPhase
    dominantWindowAddPhase = $dominantWindowAddPhase
    dominantSendSyncTimer = $dominantSendSyncTimer
    sideEffectSteps = $stepSummaries
    broadcastPhases = $broadcastPhaseSummaries
    broadcastCounters = $broadcastCounterSummaries
    windowAddPhases = $windowAddPhaseSummaries
    sendSyncPath = $sendSyncPathTimerSummaries
    sendSyncPathTimers = $sendSyncPathTimerSummaries
    sendSyncPathCounters = $sendSyncPathCounterSummaries
    findings = @($findings)
    suggestions = @($suggestions)
}

$jsonPath = Join-Path $resolvedResultDir "$OutputPrefix.json"
$mdPath = Join-Path $resolvedResultDir "$OutputPrefix.md"
$summary | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $jsonPath -Encoding utf8

$findingLines = @($findings | Where-Object { $_ })
if ($findingLines.Count -eq 0) {
    $findingLines = @("- No findings were produced.")
} else {
    $findingLines = @($findingLines | ForEach-Object { "- $_" })
}

$suggestionLines = @($suggestions | Where-Object { $_ })
if ($suggestionLines.Count -eq 0) {
    $suggestionLines = @("- No suggestions were produced.")
} else {
    $suggestionLines = @($suggestionLines | ForEach-Object { "- $_" })
}

$markdown = @"
# Mailbox Step Diagnosis

- ResultDir: $resolvedResultDir
- CreatedAt: $((Get-Date).ToString("s"))

## Queue Signals

$(New-MarkdownTable -Rows @($queueWaitSummary, $taskSummary))

## Side Effect Step Timers

$(New-MarkdownTable -Rows $stepSummaries)

## Broadcast Sub-Phase Timers

$(New-MarkdownTable -Rows $broadcastPhaseSummaries)

## Broadcast Counters

$(New-MarkdownCounterTable -Rows $broadcastCounterSummaries)

## Window Add Sub-Phase Timers

$(New-MarkdownTable -Rows $windowAddPhaseSummaries)

## Send Sync Path Timers

$(New-MarkdownTable -Rows $sendSyncPathTimerSummaries)

## Send Sync Path Counters

$(New-MarkdownCounterTable -Rows $sendSyncPathCounterSummaries)

## Findings

$([string]::Join([Environment]::NewLine, $findingLines))

## Suggestions

$([string]::Join([Environment]::NewLine, $suggestionLines))
"@

$markdown | Set-Content -LiteralPath $mdPath -Encoding utf8

Write-Host "Mailbox diagnosis written to:"
Write-Host $mdPath
