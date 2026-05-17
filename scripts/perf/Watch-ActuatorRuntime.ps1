param(
    [int]$DurationSec = 300,
    [int]$IntervalSec = 10,
    [string]$BaseUrl = "http://localhost:8081",
    [string]$OutputPath = ""
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Get-Metric {
    param(
        [string]$MetricName,
        [hashtable]$Tags = @{}
    )

    try {
        $tagQuery = ""
        foreach ($entry in $Tags.GetEnumerator()) {
            $tagQuery += "&tag={0}:{1}" -f [uri]::EscapeDataString($entry.Key), [uri]::EscapeDataString([string]$entry.Value)
        }
        $uri = "$BaseUrl/actuator/metrics/$MetricName"
        if ($tagQuery) {
            $uri += "?" + $tagQuery.TrimStart("&")
        }
        return Invoke-RestMethod -UseBasicParsing -Uri $uri -TimeoutSec 5
    } catch {
        return $null
    }
}

function Get-Health {
    try {
        return Invoke-RestMethod -UseBasicParsing -Uri "$BaseUrl/actuator/health" -TimeoutSec 5
    } catch {
        return $null
    }
}

function Get-Measurement {
    param(
        [object]$Document,
        [string]$Statistic = "",
        [ValidateSet("first", "sum", "max")]
        [string]$Mode = "first"
    )

    if (-not $Document -or -not $Document.measurements) {
        return $null
    }

    $measurements = @($Document.measurements)
    if ($Statistic) {
        $measurements = @($measurements | Where-Object { $_.statistic -eq $Statistic })
    }

    $values = @(
        $measurements |
            Where-Object { $null -ne $_.value } |
            ForEach-Object { [double]$_.value }
    )

    if ($values.Count -eq 0) {
        return $null
    }

    switch ($Mode) {
        "sum" { return ($values | Measure-Object -Sum).Sum }
        "max" { return ($values | Measure-Object -Maximum).Maximum }
        default { return $values[0] }
    }
}

if (-not $OutputPath) {
    $repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
    $watchDir = Join-Path $repoRoot "perf\results\manual-runtime"
    New-Item -ItemType Directory -Force -Path $watchDir | Out-Null
    $OutputPath = Join-Path $watchDir ("{0}-runtime-watch.jsonl" -f (Get-Date -Format "yyyyMMdd-HHmmss"))
}

$baseline = [ordered]@{
    stripeLockTimeout = Get-Measurement (Get-Metric "chat.stripe.lock.timeout") "COUNT" "sum"
    xaddFail = Get-Measurement (Get-Metric "chat.msg.xadd.fail") "COUNT" "sum"
    persistFallback = Get-Measurement (Get-Metric "chat.msg.persist.fallback") "COUNT" "sum"
    redisDegradation = Get-Measurement (Get-Metric "cache.redis.degradation") "COUNT" "sum"
    broadcastWriteFailure = Get-Measurement (Get-Metric "flashchat.broadcast.write.failure") "COUNT" "sum"
    broadcastSkipSlowClient = Get-Measurement (Get-Metric "flashchat.broadcast.skip.slow_client") "COUNT" "sum"
    channelNotWritable = Get-Measurement (Get-Metric "flashchat.broadcast.channel.not_writable") "COUNT" "sum"
    mysqlInsertBatchFailure = Get-Measurement (Get-Metric "flashchat.mysql.insert.batch.failure") "COUNT" "sum"
}

$deadline = (Get-Date).AddSeconds($DurationSec)
while ((Get-Date) -lt $deadline) {
    $health = Get-Health
    $snapshot = [ordered]@{
        time = (Get-Date).ToString("s")
        health = if ($health) { $health.status } else { "UNKNOWN" }
        dbHealth = if ($health -and $health.components -and $health.components.db) { $health.components.db.status } else { "UNKNOWN" }
        redisHealth = if ($health -and $health.components -and $health.components.redis) { $health.components.redis.status } else { "UNKNOWN" }
        processCpuUsage = Get-Measurement (Get-Metric "process.cpu.usage") "VALUE" "first"
        systemCpuUsage = Get-Measurement (Get-Metric "system.cpu.usage") "VALUE" "first"
        jvmHeapUsedBytes = Get-Measurement (Get-Metric "jvm.memory.used" @{ area = "heap" }) "VALUE" "first"
        jvmHeapCommittedBytes = Get-Measurement (Get-Metric "jvm.memory.committed" @{ area = "heap" }) "VALUE" "first"
        jvmHeapMaxBytes = Get-Measurement (Get-Metric "jvm.memory.max" @{ area = "heap" }) "VALUE" "first"
        jvmGcPauseCount = Get-Measurement (Get-Metric "jvm.gc.pause") "COUNT" "sum"
        jvmGcPauseTotalSec = Get-Measurement (Get-Metric "jvm.gc.pause") "TOTAL_TIME" "sum"
        jvmGcPauseMaxSec = Get-Measurement (Get-Metric "jvm.gc.pause") "MAX" "max"
        jvmGcOverhead = Get-Measurement (Get-Metric "jvm.gc.overhead") "VALUE" "first"
        hikariActive = Get-Measurement (Get-Metric "hikaricp.connections.active") "VALUE" "max"
        hikariPending = Get-Measurement (Get-Metric "hikaricp.connections.pending") "VALUE" "max"
        mailboxBacklog = Get-Measurement (Get-Metric "chat.mailbox.backlog") "VALUE" "max"
        mailboxQueueWaitMax = Get-Measurement (Get-Metric "chat.mailbox.queue.wait.duration") "MAX" "max"
        mailboxTaskMax = Get-Measurement (Get-Metric "chat.mailbox.task.duration") "MAX" "max"
        nettyEventLoopPendingTasksMax = Get-Measurement (Get-Metric "flashchat.netty.eventloop.pending.tasks") "MAX" "max"
        nettyEventLoopPendingTasksCount = Get-Measurement (Get-Metric "flashchat.netty.eventloop.pending.tasks") "COUNT" "sum"
        nettyEventLoopPendingTasksTotal = Get-Measurement (Get-Metric "flashchat.netty.eventloop.pending.tasks") "TOTAL" "sum"
        sideEffectBroadcastMax = Get-Measurement (Get-Metric "chat.side_effect.step.duration" @{ step = "broadcast" }) "MAX" "first"
        sideEffectWindowAddMax = Get-Measurement (Get-Metric "chat.side_effect.step.duration" @{ step = "window_add" }) "MAX" "first"
        sideEffectUnreadMax = Get-Measurement (Get-Metric "chat.side_effect.step.duration" @{ step = "unread" }) "MAX" "first"
        broadcastWriteMax = Get-Measurement (Get-Metric "flashchat.broadcast.write.duration") "MAX" "first"
        broadcastFlushMax = Get-Measurement (Get-Metric "flashchat.broadcast.flush.duration") "MAX" "first"
        broadcastWriteFailureDelta = (Get-Measurement (Get-Metric "flashchat.broadcast.write.failure") "COUNT" "sum") - $baseline.broadcastWriteFailure
        broadcastSkipSlowClientDelta = (Get-Measurement (Get-Metric "flashchat.broadcast.skip.slow_client") "COUNT" "sum") - $baseline.broadcastSkipSlowClient
        channelNotWritableDelta = (Get-Measurement (Get-Metric "flashchat.broadcast.channel.not_writable") "COUNT" "sum") - $baseline.channelNotWritable
        mysqlInsertBatchCount = Get-Measurement (Get-Metric "flashchat.mysql.insert.batch.duration") "COUNT" "sum"
        mysqlInsertBatchTotalSec = Get-Measurement (Get-Metric "flashchat.mysql.insert.batch.duration") "TOTAL_TIME" "sum"
        mysqlInsertBatchMaxSec = Get-Measurement (Get-Metric "flashchat.mysql.insert.batch.duration") "MAX" "max"
        mysqlInsertBatchFailureDelta = (Get-Measurement (Get-Metric "flashchat.mysql.insert.batch.failure") "COUNT" "sum") - $baseline.mysqlInsertBatchFailure
        stripeLockTimeoutDelta = (Get-Measurement (Get-Metric "chat.stripe.lock.timeout") "COUNT" "sum") - $baseline.stripeLockTimeout
        xaddFailDelta = (Get-Measurement (Get-Metric "chat.msg.xadd.fail") "COUNT" "sum") - $baseline.xaddFail
        persistFallbackDelta = (Get-Measurement (Get-Metric "chat.msg.persist.fallback") "COUNT" "sum") - $baseline.persistFallback
        redisDegradationDelta = (Get-Measurement (Get-Metric "cache.redis.degradation") "COUNT" "sum") - $baseline.redisDegradation
        cacheHitRatioLocal = Get-Measurement (Get-Metric "cache.hit.ratio" @{ level = "local" }) "VALUE" "first"
        cacheHitRatioRedis = Get-Measurement (Get-Metric "cache.hit.ratio" @{ level = "redis" }) "VALUE" "first"
        cacheHitRatioTotal = Get-Measurement (Get-Metric "cache.hit.ratio" @{ level = "total" }) "VALUE" "first"
        cacheLookupLocalHit = Get-Measurement (Get-Metric "cache.lookup" @{ level = "local"; result = "hit" }) "COUNT" "sum"
        cacheLookupLocalMiss = Get-Measurement (Get-Metric "cache.lookup" @{ level = "local"; result = "miss" }) "COUNT" "sum"
        cacheLookupRedisHit = Get-Measurement (Get-Metric "cache.lookup" @{ level = "redis"; result = "hit" }) "COUNT" "sum"
        cacheLookupRedisMiss = Get-Measurement (Get-Metric "cache.lookup" @{ level = "redis"; result = "miss" }) "COUNT" "sum"
        cacheLookupTotalHit = Get-Measurement (Get-Metric "cache.lookup" @{ level = "total"; result = "hit" }) "COUNT" "sum"
        cacheLookupTotalMiss = Get-Measurement (Get-Metric "cache.lookup" @{ level = "total"; result = "miss" }) "COUNT" "sum"
        websocketOnlineUsers = Get-Measurement (Get-Metric "websocket.online.users") "VALUE" "first"
        jvmGcPauseMax = Get-Measurement (Get-Metric "jvm.gc.pause") "MAX" "first"
    }

    ($snapshot | ConvertTo-Json -Compress) | Add-Content -LiteralPath $OutputPath
    Start-Sleep -Seconds $IntervalSec
}

Write-Host "Runtime watch captured to:"
Write-Host $OutputPath
