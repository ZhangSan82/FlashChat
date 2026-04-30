param(
    [ValidateSet("smoke", "baseline", "ws", "mixed", "stress", "breaking", "room-hotspot", "ten-room", "room-send-receive")]
    [string]$Scenario,
    [string]$BaseUrl = "http://localhost:8081",
    [string]$WsUrl = "ws://localhost:8090",
    [ValidateSet("auto", "local", "docker")]
    [string]$Runner = "auto",
    [string]$RunLabel = "",
    [double]$P95ThresholdMs = 500,
    [double]$P99ThresholdMs = 1000,
    [double]$ErrorRateThreshold = 0.01,
    [double]$SendSuccessRateThreshold = 0.85,
    [double]$WsBroadcastP95ThresholdMs = 500,
    [switch]$AutoCaptureDiagnostics,
    [ValidateSet("none", "room-hotspot")]
    [string]$ActuatorGuard = "none",
    [int]$RuntimePollSec = 10,
    [int]$RuntimeGuardConsecutiveBreaches = 2,
    [double]$MaxRuntimeMailboxBacklog = 0,
    [double]$MaxRuntimeHikariPending = 0,
    [double]$MaxRuntimeProcessCpu = 0.85,
    [switch]$AbortOnRuntimeGuardBreach,
    [hashtable]$EnvOverrides = @{}
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Convert-ToDockerReachableUrl {
    param(
        [string]$Url
    )

    if ([string]::IsNullOrWhiteSpace($Url)) {
        return $Url
    }

    $builder = [System.UriBuilder]::new($Url)
    if ($builder.Host -in @("localhost", "127.0.0.1", "::1")) {
        $builder.Host = "host.docker.internal"
    }
    return $builder.Uri.AbsoluteUri.TrimEnd('/')
}

function Get-MetricValue {
    param(
        [object]$Summary,
        [string]$MetricName,
        [string]$Key
    )

    $metricsProp = $Summary.PSObject.Properties["metrics"]
    if (-not $metricsProp) {
        return $null
    }

    $metricProp = $metricsProp.Value.PSObject.Properties[$MetricName]
    if (-not $metricProp) {
        return $null
    }

    $metric = $metricProp.Value
    if (-not $metric) {
        return $null
    }

    $valueContainer = $metric
    $valuesProp = $metric.PSObject.Properties["values"]
    if ($valuesProp) {
        $valueContainer = $valuesProp.Value
    }

    $keyProp = $valueContainer.PSObject.Properties[$Key]
    if ($keyProp) {
        return $keyProp.Value
    }

    if ($Key -eq "rate") {
        $fallbackRate = $valueContainer.PSObject.Properties["value"]
        if ($fallbackRate) {
            return $fallbackRate.Value
        }
    }

    return $null
}

function Get-ActuatorMetricDocument {
    param(
        [string]$MetricName
    )

    try {
        return Invoke-RestMethod -UseBasicParsing -Uri "http://localhost:8081/actuator/metrics/$MetricName" -TimeoutSec 5
    } catch {
        return $null
    }
}

function Get-ActuatorMeasurementAggregate {
    param(
        [object]$MetricDocument,
        [string]$Statistic = "",
        [ValidateSet("max", "sum", "first")]
        [string]$Mode = "max"
    )

    if (-not $MetricDocument) {
        return $null
    }

    $measurementsProp = $MetricDocument.PSObject.Properties["measurements"]
    if (-not $measurementsProp) {
        return $null
    }

    $measurements = @($measurementsProp.Value)
    if ($Statistic) {
        $measurements = @($measurements | Where-Object { $_.statistic -eq $Statistic })
    }

    $values = @(
        $measurements |
            Where-Object { $null -ne $_.value } |
            ForEach-Object { [double]$_.value }
    )

    if (-not $values -or $values.Count -eq 0) {
        return $null
    }

    switch ($Mode) {
        "sum" { return ($values | Measure-Object -Sum).Sum }
        "first" { return $values[0] }
        default { return ($values | Measure-Object -Maximum).Maximum }
    }
}

function Get-ActuatorHealthDocument {
    try {
        return Invoke-RestMethod -UseBasicParsing -Uri "http://localhost:8081/actuator/health" -TimeoutSec 5
    } catch {
        return $null
    }
}

function Get-RuntimeBaseline {
    $baseline = [ordered]@{
        mailboxRejected = 0.0
        stripeLockTimeout = 0.0
        xaddFail = 0.0
        persistFallback = 0.0
        redisDegradation = 0.0
    }

    $baseline.mailboxRejected = Get-ActuatorMeasurementAggregate -MetricDocument (Get-ActuatorMetricDocument "chat.mailbox.submit.rejected") -Statistic "COUNT" -Mode "sum"
    $baseline.stripeLockTimeout = Get-ActuatorMeasurementAggregate -MetricDocument (Get-ActuatorMetricDocument "chat.stripe.lock.timeout") -Statistic "COUNT" -Mode "sum"
    $baseline.xaddFail = Get-ActuatorMeasurementAggregate -MetricDocument (Get-ActuatorMetricDocument "chat.msg.xadd.fail") -Statistic "COUNT" -Mode "sum"
    $baseline.persistFallback = Get-ActuatorMeasurementAggregate -MetricDocument (Get-ActuatorMetricDocument "chat.msg.persist.fallback") -Statistic "COUNT" -Mode "sum"
    $baseline.redisDegradation = Get-ActuatorMeasurementAggregate -MetricDocument (Get-ActuatorMetricDocument "cache.redis.degradation") -Statistic "COUNT" -Mode "sum"
    return $baseline
}

function Get-RuntimeSnapshot {
    param(
        [hashtable]$Baseline
    )

    $health = Get-ActuatorHealthDocument
    $cpuDoc = Get-ActuatorMetricDocument "process.cpu.usage"
    $pendingDoc = Get-ActuatorMetricDocument "hikaricp.connections.pending"
    $backlogDoc = Get-ActuatorMetricDocument "chat.mailbox.backlog"
    $mailboxRejectedDoc = Get-ActuatorMetricDocument "chat.mailbox.submit.rejected"
    $lockTimeoutDoc = Get-ActuatorMetricDocument "chat.stripe.lock.timeout"
    $xaddFailDoc = Get-ActuatorMetricDocument "chat.msg.xadd.fail"
    $persistFallbackDoc = Get-ActuatorMetricDocument "chat.msg.persist.fallback"
    $redisDegradationDoc = Get-ActuatorMetricDocument "cache.redis.degradation"

    $mailboxRejected = Get-ActuatorMeasurementAggregate -MetricDocument $mailboxRejectedDoc -Statistic "COUNT" -Mode "sum"
    $lockTimeout = Get-ActuatorMeasurementAggregate -MetricDocument $lockTimeoutDoc -Statistic "COUNT" -Mode "sum"
    $xaddFail = Get-ActuatorMeasurementAggregate -MetricDocument $xaddFailDoc -Statistic "COUNT" -Mode "sum"
    $persistFallback = Get-ActuatorMeasurementAggregate -MetricDocument $persistFallbackDoc -Statistic "COUNT" -Mode "sum"
    $redisDegradation = Get-ActuatorMeasurementAggregate -MetricDocument $redisDegradationDoc -Statistic "COUNT" -Mode "sum"

    return [ordered]@{
        time = (Get-Date).ToString("s")
        health = if ($health) { $health.status } else { "UNKNOWN" }
        dbHealth = if ($health -and $health.components -and $health.components.db) { $health.components.db.status } else { "UNKNOWN" }
        redisHealth = if ($health -and $health.components -and $health.components.redis) { $health.components.redis.status } else { "UNKNOWN" }
        processCpuUsage = Get-ActuatorMeasurementAggregate -MetricDocument $cpuDoc -Statistic "VALUE" -Mode "first"
        hikariPending = Get-ActuatorMeasurementAggregate -MetricDocument $pendingDoc -Statistic "VALUE" -Mode "max"
        mailboxBacklog = Get-ActuatorMeasurementAggregate -MetricDocument $backlogDoc -Statistic "VALUE" -Mode "max"
        mailboxRejectedDelta = if ($null -ne $mailboxRejected -and $null -ne $Baseline.mailboxRejected) { $mailboxRejected - $Baseline.mailboxRejected } else { $null }
        stripeLockTimeoutDelta = if ($null -ne $lockTimeout -and $null -ne $Baseline.stripeLockTimeout) { $lockTimeout - $Baseline.stripeLockTimeout } else { $null }
        xaddFailDelta = if ($null -ne $xaddFail -and $null -ne $Baseline.xaddFail) { $xaddFail - $Baseline.xaddFail } else { $null }
        persistFallbackDelta = if ($null -ne $persistFallback -and $null -ne $Baseline.persistFallback) { $persistFallback - $Baseline.persistFallback } else { $null }
        redisDegradationDelta = if ($null -ne $redisDegradation -and $null -ne $Baseline.redisDegradation) { $redisDegradation - $Baseline.redisDegradation } else { $null }
    }
}

function Get-RuntimeBreachReasons {
    param(
        [hashtable]$Snapshot
    )

    $reasons = New-Object System.Collections.Generic.List[string]

    if ($Snapshot.health -ne "UP") {
        $reasons.Add("health_not_up")
    }
    if ($Snapshot.dbHealth -notin @("UP", "UNKNOWN")) {
        $reasons.Add("db_health_$($Snapshot.dbHealth)")
    }
    if ($Snapshot.redisHealth -notin @("UP", "UNKNOWN")) {
        $reasons.Add("redis_health_$($Snapshot.redisHealth)")
    }
    if ($null -ne $Snapshot.processCpuUsage -and $Snapshot.processCpuUsage -gt $MaxRuntimeProcessCpu) {
        $reasons.Add("process_cpu_high")
    }
    if ($null -ne $Snapshot.hikariPending -and $Snapshot.hikariPending -gt $MaxRuntimeHikariPending) {
        $reasons.Add("hikari_pending")
    }
    if ($null -ne $Snapshot.mailboxBacklog -and $Snapshot.mailboxBacklog -gt $MaxRuntimeMailboxBacklog) {
        $reasons.Add("mailbox_backlog")
    }
    if ($null -ne $Snapshot.mailboxRejectedDelta -and $Snapshot.mailboxRejectedDelta -gt 0) {
        $reasons.Add("mailbox_rejected")
    }
    if ($null -ne $Snapshot.stripeLockTimeoutDelta -and $Snapshot.stripeLockTimeoutDelta -gt 0) {
        $reasons.Add("stripe_lock_timeout")
    }
    if ($null -ne $Snapshot.xaddFailDelta -and $Snapshot.xaddFailDelta -gt 0) {
        $reasons.Add("redis_xadd_fail")
    }
    if ($null -ne $Snapshot.persistFallbackDelta -and $Snapshot.persistFallbackDelta -gt 0) {
        $reasons.Add("persist_fallback")
    }
    if ($null -ne $Snapshot.redisDegradationDelta -and $Snapshot.redisDegradationDelta -gt 0) {
        $reasons.Add("redis_degradation")
    }

    return $reasons
}

function Append-JsonLine {
    param(
        [string]$FilePath,
        [object]$Value
    )

    ($Value | ConvertTo-Json -Depth 10 -Compress) | Add-Content -LiteralPath $FilePath
}

function Flush-ProcessLog {
    param(
        [string]$Path,
        [ref]$Cursor,
        [switch]$AsError
    )

    if (-not $Path -or -not (Test-Path $Path)) {
        return
    }

    $content = $null
    $stream = $null
    $reader = $null
    try {
        $stream = [System.IO.File]::Open($Path, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read, [System.IO.FileShare]::ReadWrite)
        if ($stream.Length -le $Cursor.Value) {
            return
        }

        $stream.Seek($Cursor.Value, [System.IO.SeekOrigin]::Begin) | Out-Null
        $reader = New-Object System.IO.StreamReader($stream, [System.Text.Encoding]::UTF8, $true)
        $content = $reader.ReadToEnd()
        $Cursor.Value = $stream.Position
    } finally {
        if ($reader) {
            $reader.Dispose()
        } elseif ($stream) {
            $stream.Dispose()
        }
    }

    if ([string]::IsNullOrWhiteSpace($content)) {
        return
    }

    $lines = $content -split "`r?`n"
    foreach ($line in $lines) {
        if ([string]::IsNullOrWhiteSpace($line)) {
            continue
        }
        if ($AsError) {
            Write-Host $line
        } else {
            Write-Host $line
        }
    }
}

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$resultRoot = Join-Path $repoRoot "perf\results"
$diagScript = Join-Path $PSScriptRoot "Capture-PerfDiagnostics.ps1"
$dockerWorkDir = "/work"
$repoLocalK6 = Join-Path $repoRoot "tools\k6\current\k6.exe"

New-Item -ItemType Directory -Force -Path $resultRoot | Out-Null

$localK6Path = $null
if ($env:K6_BIN -and (Test-Path $env:K6_BIN)) {
    $localK6Path = (Resolve-Path $env:K6_BIN).Path
} elseif (Test-Path $repoLocalK6) {
    $localK6Path = (Resolve-Path $repoLocalK6).Path
} else {
    $localK6 = Get-Command k6 -ErrorAction SilentlyContinue
    if ($localK6) {
        $localK6Path = $localK6.Source
    }
}
$dockerCmd = Get-Command docker -ErrorAction SilentlyContinue

switch ($Runner) {
    "local" {
        if (-not $localK6Path) {
            throw "Runner=local but k6 is not installed, K6_BIN is not set, and tools\\k6\\current\\k6.exe was not found."
        }
    }
    "docker" {
        if (-not $dockerCmd) {
            throw "Runner=docker but docker is not installed or not available in PATH."
        }
    }
    "auto" {
        if (-not $localK6Path -and -not $dockerCmd) {
            throw "Neither local k6 nor docker is available."
        }
    }
}

$scriptMap = @{
    smoke = "perf\k6\smoke.js"
    baseline = "perf\k6\http-baseline.js"
    ws = "perf\k6\ws-broadcast.js"
    mixed = "perf\k6\mixed.js"
    stress = "perf\k6\stress.js"
    breaking = "perf\k6\breaking.js"
    "room-hotspot" = "perf\k6\room-hotspot.js"
    "ten-room" = "perf\k6\ten-room.js"
    "room-send-receive" = "perf\k6\room-send-receive.js"
}

$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$labelSafe = ""
if (-not [string]::IsNullOrWhiteSpace($RunLabel)) {
    $labelSafe = ($RunLabel -replace "[^a-zA-Z0-9_-]", "-").Trim("-")
}

$dirName = if ($labelSafe) { "$timestamp-$Scenario-$labelSafe" } else { "$timestamp-$Scenario" }
$runDir = Join-Path $resultRoot $dirName
New-Item -ItemType Directory -Force -Path $runDir | Out-Null

$summaryPath = Join-Path $runDir "summary.json"
$consoleLog = Join-Path $runDir "k6-console.log"
$briefPath = Join-Path $runDir "brief.txt"
$runtimeSamplesPath = Join-Path $runDir "runtime-samples.jsonl"
$runtimeGuardEventsPath = Join-Path $runDir "runtime-guard-events.jsonl"
$hotspotSummaryPath = Join-Path $runDir "room-hotspot-summary.json"
$tenRoomSummaryPath = Join-Path $runDir "ten-room-summary.json"
$appRunContextSource = Join-Path $repoRoot "perf\run\local-perf.json"
$appRunContextPath = Join-Path $runDir "app-run-context.json"
$scriptPath = Join-Path $repoRoot $scriptMap[$Scenario]
$runDirInContainer = "$dockerWorkDir/$($runDir.Substring($repoRoot.Length).TrimStart('\').Replace('\','/'))"
$summaryPathInContainer = "$dockerWorkDir/$($summaryPath.Substring($repoRoot.Length).TrimStart('\').Replace('\','/'))"
$scriptPathInContainer = "$dockerWorkDir/$($scriptPath.Substring($repoRoot.Length).TrimStart('\').Replace('\','/'))"

if (Test-Path $appRunContextSource) {
    Copy-Item -LiteralPath $appRunContextSource -Destination $appRunContextPath -Force
}

$selectedRunner = $Runner
if ($selectedRunner -eq "auto") {
    $selectedRunner = if ($localK6Path) { "local" } else { "docker" }
}

$effectiveBaseUrl = $BaseUrl
$effectiveWsUrl = $WsUrl
if ($selectedRunner -eq "docker") {
    $effectiveBaseUrl = Convert-ToDockerReachableUrl -Url $BaseUrl
    $effectiveWsUrl = Convert-ToDockerReachableUrl -Url $WsUrl
}

$envAssignments = @{
    BASE_URL = $effectiveBaseUrl
    WS_URL = $effectiveWsUrl
    REPORT_DIR = if ($selectedRunner -eq "docker") { $runDirInContainer } else { $runDir }
    K6_WEB_DASHBOARD = "false"
}

foreach ($pair in $EnvOverrides.GetEnumerator()) {
    $envAssignments[$pair.Key] = [string]$pair.Value
}

Write-Host "Running k6 scenario '$Scenario' with runner '$selectedRunner'..."
Write-Host "BASE_URL=$effectiveBaseUrl"
Write-Host "WS_URL=$effectiveWsUrl"

$baselineCounters = $null
if ($ActuatorGuard -ne "none") {
    $baselineCounters = Get-RuntimeBaseline
}

$runProcess = $null
$stdoutLog = $null
$stderrLog = $null
$stdoutCursor = 0L
$stderrCursor = 0L
$dockerContainerName = $null
$guardAborted = $false
$guardAbortReasons = @()
$capturedRuntimeDiagnostics = $false
$consecutiveGuardBreaches = 0
$exitCode = 0

if ($selectedRunner -eq "local") {
    $stdoutLog = Join-Path $runDir "local-stdout.log"
    $stderrLog = Join-Path $runDir "local-stderr.log"
    $runArgs = @(
        "run"
        "--summary-export", $summaryPath
        $scriptPath
    )

    foreach ($pair in $envAssignments.GetEnumerator()) {
        [Environment]::SetEnvironmentVariable($pair.Key, $pair.Value, "Process")
    }

    $runProcess = Start-Process `
        -FilePath $localK6Path `
        -ArgumentList $runArgs `
        -WorkingDirectory $repoRoot `
        -RedirectStandardOutput $stdoutLog `
        -RedirectStandardError $stderrLog `
        -PassThru
} else {
    $stdoutLog = Join-Path $runDir "docker-stdout.log"
    $stderrLog = Join-Path $runDir "docker-stderr.log"
    $dockerContainerName = ("flashchat-k6-{0}-{1}" -f $timestamp.ToLower(), $Scenario.ToLower()) -replace "[^a-z0-9-]", "-"
    if ($labelSafe) {
        $dockerContainerName = "$dockerContainerName-$($labelSafe.ToLower())"
    }
    if ($dockerContainerName.Length -gt 63) {
        $dockerContainerName = $dockerContainerName.Substring(0, 63).TrimEnd('-')
    }

    $dockerArgs = @(
        "run", "--rm",
        "--name", $dockerContainerName,
        "--add-host", "host.docker.internal:host-gateway",
        "-v", "${repoRoot}:${dockerWorkDir}",
        "-w", $dockerWorkDir
    )

    foreach ($pair in $envAssignments.GetEnumerator()) {
        $dockerArgs += @("-e", "$($pair.Key)=$($pair.Value)")
    }

    $dockerArgs += @(
        "grafana/k6:latest",
        "run",
        "--summary-export", $summaryPathInContainer,
        $scriptPathInContainer
    )

    $runProcess = Start-Process `
        -FilePath "docker" `
        -ArgumentList $dockerArgs `
        -WorkingDirectory $repoRoot `
        -RedirectStandardOutput $stdoutLog `
        -RedirectStandardError $stderrLog `
        -PassThru
}

$lastPollAt = Get-Date
while (-not $runProcess.HasExited) {
    Start-Sleep -Seconds 1
    $runProcess.Refresh()
    Flush-ProcessLog -Path $stdoutLog -Cursor ([ref]$stdoutCursor)
    Flush-ProcessLog -Path $stderrLog -Cursor ([ref]$stderrCursor) -AsError

    if ($ActuatorGuard -eq "none") {
        continue
    }

    if (((Get-Date) - $lastPollAt).TotalSeconds -lt $RuntimePollSec) {
        continue
    }

    $lastPollAt = Get-Date
    $snapshot = Get-RuntimeSnapshot -Baseline $baselineCounters
    Append-JsonLine -FilePath $runtimeSamplesPath -Value $snapshot
    Write-Host ("Runtime poll: health={0} db={1} redis={2} cpu={3} hikariPending={4} mailboxBacklog={5}" -f `
        $snapshot.health, `
        $snapshot.dbHealth, `
        $snapshot.redisHealth, `
        $snapshot.processCpuUsage, `
        $snapshot.hikariPending, `
        $snapshot.mailboxBacklog)

    $reasons = @(Get-RuntimeBreachReasons -Snapshot $snapshot)
    if ($reasons.Count -eq 0) {
        $consecutiveGuardBreaches = 0
        continue
    }

    $consecutiveGuardBreaches += 1
    $event = [ordered]@{
        time = (Get-Date).ToString("s")
        consecutiveBreaches = $consecutiveGuardBreaches
        reasons = $reasons
        snapshot = $snapshot
    }
    Append-JsonLine -FilePath $runtimeGuardEventsPath -Value $event

    if ($AutoCaptureDiagnostics -and -not $capturedRuntimeDiagnostics) {
        Write-Host "Runtime guard breached. Capturing diagnostics..."
        & powershell -ExecutionPolicy Bypass -File $diagScript -Reason "$Scenario-runtime-guard"
        $capturedRuntimeDiagnostics = $true
    }

    if ($AbortOnRuntimeGuardBreach -and $consecutiveGuardBreaches -ge $RuntimeGuardConsecutiveBreaches) {
        $guardAborted = $true
        $guardAbortReasons = $reasons
        Write-Host "Runtime guard requested abort: $($reasons -join ', ')"

        if ($selectedRunner -eq "docker" -and $dockerContainerName) {
            & docker kill $dockerContainerName | Out-Null
        } elseif ($runProcess -and -not $runProcess.HasExited) {
            Stop-Process -Id $runProcess.Id -Force -ErrorAction SilentlyContinue
        }
        break
    }
}

if ($runProcess -and -not $runProcess.HasExited) {
    try {
        $runProcess.WaitForExit(15000) | Out-Null
    } catch {
    }
}

$runProcess.Refresh()
Flush-ProcessLog -Path $stdoutLog -Cursor ([ref]$stdoutCursor)
Flush-ProcessLog -Path $stderrLog -Cursor ([ref]$stderrCursor) -AsError
$exitCode = $runProcess.ExitCode

if ($selectedRunner -eq "local") {
    foreach ($pair in $envAssignments.GetEnumerator()) {
        [Environment]::SetEnvironmentVariable($pair.Key, $null, "Process")
    }
}

$stdoutText = if ($stdoutLog -and (Test-Path $stdoutLog)) { Get-Content $stdoutLog -Raw } else { "" }
$stderrText = if ($stderrLog -and (Test-Path $stderrLog)) { Get-Content $stderrLog -Raw } else { "" }
$combinedText = @($stdoutText, $stderrText) -join ""
$combinedText | Set-Content -LiteralPath $consoleLog
if (-not [string]::IsNullOrWhiteSpace($combinedText)) {
    Write-Host $combinedText
}

$setupGuardFailed = $false
$setupGuardMessage = $null
$setupGuardMatch = [regex]::Match($combinedText, 'TEN_ROOM_SHARD_GUARD_FAILED[^\r\n]*')
if ($setupGuardMatch.Success) {
    $setupGuardFailed = $true
    $setupGuardMessage = $setupGuardMatch.Value.Trim()
}

$summary = $null
$hotspotSummary = $null
$tenRoomSummary = $null
if (Test-Path $summaryPath) {
    try {
        $summary = Get-Content $summaryPath -Raw -Encoding UTF8 | ConvertFrom-Json
    } catch {
        $summary = $null
    }
}
if (Test-Path $hotspotSummaryPath) {
    try {
        $hotspotSummary = Get-Content $hotspotSummaryPath -Raw -Encoding UTF8 | ConvertFrom-Json
    } catch {
        $hotspotSummary = $null
    }
}
if (Test-Path $tenRoomSummaryPath) {
    try {
        $tenRoomSummary = Get-Content $tenRoomSummaryPath -Raw -Encoding UTF8 | ConvertFrom-Json
    } catch {
        $tenRoomSummary = $null
    }
}
if (-not $summary -and -not $hotspotSummary -and -not $tenRoomSummary -and -not $guardAborted -and -not $setupGuardFailed) {
    throw "k6 summary was not produced: $summaryPath"
}

$brief = New-Object System.Collections.Generic.List[string]
$brief.Add("scenario=$Scenario")
$brief.Add("runner=$selectedRunner")
$brief.Add("summary=$summaryPath")
$brief.Add("console=$consoleLog")
if ($hotspotSummary) {
    $brief.Add("room_hotspot_summary=$hotspotSummaryPath")
}
if ($tenRoomSummary) {
    $brief.Add("ten_room_summary=$tenRoomSummaryPath")
}
$brief.Add("guard_aborted=$guardAborted")
if ($guardAborted -and $guardAbortReasons.Count -gt 0) {
    $brief.Add("guard_abort_reasons=$($guardAbortReasons -join ',')")
}
$brief.Add("setup_guard_failed=$setupGuardFailed")
if ($setupGuardMessage) {
    $brief.Add("setup_guard_message=$setupGuardMessage")
}

$shouldCapture = $false
if ($summary) {
    $p95 = Get-MetricValue -Summary $summary -MetricName "http_req_duration" -Key "p(95)"
    $p99 = Get-MetricValue -Summary $summary -MetricName "http_req_duration" -Key "p(99)"
    $errorRate = Get-MetricValue -Summary $summary -MetricName "http_req_failed" -Key "rate"
    $checksRate = Get-MetricValue -Summary $summary -MetricName "checks" -Key "rate"
    $businessFailures = Get-MetricValue -Summary $summary -MetricName "business_failures" -Key "count"
    $httpReqRate = Get-MetricValue -Summary $summary -MetricName "http_reqs" -Key "rate"
    $sendSuccessRate = Get-MetricValue -Summary $summary -MetricName "send_success_rate" -Key "rate"
    $sendSuccessesPerSec = Get-MetricValue -Summary $summary -MetricName "send_successes" -Key "rate"
    $sendFailures = Get-MetricValue -Summary $summary -MetricName "send_failures" -Key "count"
    $sendRateLimitedCount = Get-MetricValue -Summary $summary -MetricName "send_rate_limited" -Key "count"
    $roomGlobalLimited = Get-MetricValue -Summary $summary -MetricName "send_room_global_limited" -Key "count"
    $userRoomLimited = Get-MetricValue -Summary $summary -MetricName "send_user_room_limited" -Key "count"
    $userGlobalLimited = Get-MetricValue -Summary $summary -MetricName "send_user_global_limited" -Key "count"
    $wsBroadcastP95 = Get-MetricValue -Summary $summary -MetricName "room_ws_broadcast_latency" -Key "p(95)"
    $wsBroadcastP99 = Get-MetricValue -Summary $summary -MetricName "room_ws_broadcast_latency" -Key "p(99)"
    $wsConnectFailures = Get-MetricValue -Summary $summary -MetricName "room_ws_connect_failures" -Key "count"

    $brief.Add("http_req_duration_p95_ms=$p95")
    $brief.Add("http_req_duration_p99_ms=$p99")
    $brief.Add("http_req_failed_rate=$errorRate")
    $brief.Add("http_reqs_per_sec=$httpReqRate")
    $brief.Add("checks_rate=$checksRate")
    $brief.Add("business_failures=$businessFailures")
    if ($null -ne $sendSuccessRate) { $brief.Add("send_success_rate=$sendSuccessRate") }
    if ($null -ne $sendSuccessesPerSec) { $brief.Add("send_successes_per_sec=$sendSuccessesPerSec") }
    if ($null -ne $sendFailures) { $brief.Add("send_failures=$sendFailures") }
    if ($null -ne $sendRateLimitedCount) { $brief.Add("send_rate_limited=$sendRateLimitedCount") }
    if ($null -ne $roomGlobalLimited) { $brief.Add("send_room_global_limited=$roomGlobalLimited") }
    if ($null -ne $userRoomLimited) { $brief.Add("send_user_room_limited=$userRoomLimited") }
    if ($null -ne $userGlobalLimited) { $brief.Add("send_user_global_limited=$userGlobalLimited") }
    if ($null -ne $wsBroadcastP95) { $brief.Add("room_ws_broadcast_p95_ms=$wsBroadcastP95") }
    if ($null -ne $wsBroadcastP99) { $brief.Add("room_ws_broadcast_p99_ms=$wsBroadcastP99") }
    if ($null -ne $wsConnectFailures) { $brief.Add("room_ws_connect_failures=$wsConnectFailures") }

    if ($null -ne $p95 -and [double]$p95 -gt $P95ThresholdMs) {
        $shouldCapture = $true
    }
    if ($null -ne $p99 -and [double]$p99 -gt $P99ThresholdMs) {
        $shouldCapture = $true
    }
    if ($null -ne $errorRate -and [double]$errorRate -gt $ErrorRateThreshold) {
        $shouldCapture = $true
    }
    if ($null -ne $businessFailures -and [double]$businessFailures -gt 0) {
        $shouldCapture = $true
    }
    if ($null -ne $sendSuccessRate -and [double]$sendSuccessRate -lt $SendSuccessRateThreshold) {
        $shouldCapture = $true
    }
    if ($null -ne $wsBroadcastP95 -and [double]$wsBroadcastP95 -gt $WsBroadcastP95ThresholdMs) {
        $shouldCapture = $true
    }
}

if ($hotspotSummary) {
    if ($null -ne $hotspotSummary.httpReqP95Ms) { $brief.Add("http_req_duration_p95_ms=$($hotspotSummary.httpReqP95Ms)") }
    if ($null -ne $hotspotSummary.httpReqP99Ms) { $brief.Add("http_req_duration_p99_ms=$($hotspotSummary.httpReqP99Ms)") }
    if ($null -ne $hotspotSummary.httpReqRate) { $brief.Add("http_reqs_per_sec=$($hotspotSummary.httpReqRate)") }
    if ($null -ne $hotspotSummary.httpReqFailedRate) { $brief.Add("http_req_failed_rate=$($hotspotSummary.httpReqFailedRate)") }
    if ($null -ne $hotspotSummary.sendSuccessRate) { $brief.Add("send_success_rate=$($hotspotSummary.sendSuccessRate)") }
    if ($null -ne $hotspotSummary.sendSuccessesPerSec) { $brief.Add("send_successes_per_sec=$($hotspotSummary.sendSuccessesPerSec)") }
    if ($null -ne $hotspotSummary.sendFailures) { $brief.Add("send_failures=$($hotspotSummary.sendFailures)") }
    if ($null -ne $hotspotSummary.sendRateLimited) { $brief.Add("send_rate_limited=$($hotspotSummary.sendRateLimited)") }
    if ($null -ne $hotspotSummary.sendRoomGlobalLimited) { $brief.Add("send_room_global_limited=$($hotspotSummary.sendRoomGlobalLimited)") }
    if ($null -ne $hotspotSummary.sendUserRoomLimited) { $brief.Add("send_user_room_limited=$($hotspotSummary.sendUserRoomLimited)") }
    if ($null -ne $hotspotSummary.sendUserGlobalLimited) { $brief.Add("send_user_global_limited=$($hotspotSummary.sendUserGlobalLimited)") }
    if ($null -ne $hotspotSummary.wsBroadcastP95Ms) { $brief.Add("room_ws_broadcast_p95_ms=$($hotspotSummary.wsBroadcastP95Ms)") }
    if ($null -ne $hotspotSummary.wsBroadcastP99Ms) { $brief.Add("room_ws_broadcast_p99_ms=$($hotspotSummary.wsBroadcastP99Ms)") }
    if ($null -ne $hotspotSummary.wsConnectFailures) { $brief.Add("room_ws_connect_failures=$($hotspotSummary.wsConnectFailures)") }

    if ($null -ne $hotspotSummary.httpReqP95Ms -and [double]$hotspotSummary.httpReqP95Ms -gt $P95ThresholdMs) {
        $shouldCapture = $true
    }
    if ($null -ne $hotspotSummary.httpReqP99Ms -and [double]$hotspotSummary.httpReqP99Ms -gt $P99ThresholdMs) {
        $shouldCapture = $true
    }
    if ($null -ne $hotspotSummary.httpReqFailedRate -and [double]$hotspotSummary.httpReqFailedRate -gt $ErrorRateThreshold) {
        $shouldCapture = $true
    }
    if ($null -ne $hotspotSummary.sendSuccessRate -and [double]$hotspotSummary.sendSuccessRate -lt $SendSuccessRateThreshold) {
        $shouldCapture = $true
    }
    if ($null -ne $hotspotSummary.wsBroadcastP95Ms -and [double]$hotspotSummary.wsBroadcastP95Ms -gt $WsBroadcastP95ThresholdMs) {
        $shouldCapture = $true
    }
}

if ($tenRoomSummary) {
    if ($null -ne $tenRoomSummary.totalHttpP95Ms) { $brief.Add("http_req_duration_p95_ms=$($tenRoomSummary.totalHttpP95Ms)") }
    if ($null -ne $tenRoomSummary.totalHttpP99Ms) { $brief.Add("http_req_duration_p99_ms=$($tenRoomSummary.totalHttpP99Ms)") }
    if ($null -ne $tenRoomSummary.totalHttpReqRate) { $brief.Add("http_reqs_per_sec=$($tenRoomSummary.totalHttpReqRate)") }
    if ($null -ne $tenRoomSummary.totalHttpReqFailedRate) { $brief.Add("http_req_failed_rate=$($tenRoomSummary.totalHttpReqFailedRate)") }
    if ($null -ne $tenRoomSummary.totalSendSuccessRate) { $brief.Add("send_success_rate=$($tenRoomSummary.totalSendSuccessRate)") }
    if ($null -ne $tenRoomSummary.totalSendSuccessesPerSec) { $brief.Add("send_successes_per_sec=$($tenRoomSummary.totalSendSuccessesPerSec)") }
    if ($null -ne $tenRoomSummary.totalSendFailures) { $brief.Add("send_failures=$($tenRoomSummary.totalSendFailures)") }
    if ($null -ne $tenRoomSummary.sendRateLimited) { $brief.Add("send_rate_limited=$($tenRoomSummary.sendRateLimited)") }
    if ($null -ne $tenRoomSummary.sendRoomGlobalLimited) { $brief.Add("send_room_global_limited=$($tenRoomSummary.sendRoomGlobalLimited)") }
    if ($null -ne $tenRoomSummary.sendUserRoomLimited) { $brief.Add("send_user_room_limited=$($tenRoomSummary.sendUserRoomLimited)") }
    if ($null -ne $tenRoomSummary.sendUserGlobalLimited) { $brief.Add("send_user_global_limited=$($tenRoomSummary.sendUserGlobalLimited)") }
    if ($null -ne $tenRoomSummary.totalWsBroadcastP95Ms) { $brief.Add("room_ws_broadcast_p95_ms=$($tenRoomSummary.totalWsBroadcastP95Ms)") }
    if ($null -ne $tenRoomSummary.totalWsBroadcastP99Ms) { $brief.Add("room_ws_broadcast_p99_ms=$($tenRoomSummary.totalWsBroadcastP99Ms)") }
    if ($null -ne $tenRoomSummary.roomWsConnectFailures) { $brief.Add("room_ws_connect_failures=$($tenRoomSummary.roomWsConnectFailures)") }
    if ($null -ne $tenRoomSummary.roomSkew) { $brief.Add("room_skew=$($tenRoomSummary.roomSkew)") }

    if ($null -ne $tenRoomSummary.totalHttpP95Ms -and [double]$tenRoomSummary.totalHttpP95Ms -gt $P95ThresholdMs) {
        $shouldCapture = $true
    }
    if ($null -ne $tenRoomSummary.totalHttpP99Ms -and [double]$tenRoomSummary.totalHttpP99Ms -gt $P99ThresholdMs) {
        $shouldCapture = $true
    }
    if ($null -ne $tenRoomSummary.totalHttpReqFailedRate -and [double]$tenRoomSummary.totalHttpReqFailedRate -gt $ErrorRateThreshold) {
        $shouldCapture = $true
    }
    if ($null -ne $tenRoomSummary.totalSendSuccessRate -and [double]$tenRoomSummary.totalSendSuccessRate -lt $SendSuccessRateThreshold) {
        $shouldCapture = $true
    }
    if ($null -ne $tenRoomSummary.totalWsBroadcastP95Ms -and [double]$tenRoomSummary.totalWsBroadcastP95Ms -gt $WsBroadcastP95ThresholdMs) {
        $shouldCapture = $true
    }
}

$brief | Set-Content -LiteralPath $briefPath

if ($AutoCaptureDiagnostics -and $shouldCapture -and -not $capturedRuntimeDiagnostics) {
    Write-Host "Threshold breached. Capturing diagnostics..."
    & powershell -ExecutionPolicy Bypass -File $diagScript -Reason "k6-$Scenario-threshold-breached"
}

Write-Host ""
Write-Host "Results directory:"
Write-Host $runDir

exit $exitCode
