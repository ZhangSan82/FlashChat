param(
    [int]$TargetPid,
    [string]$Reason = "manual",
    [switch]$IncludeHeapDump,
    [switch]$IncludeClassHistogram
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Invoke-TextCapture {
    param(
        [string]$FilePath,
        [scriptblock]$Action
    )

    try {
        & $Action | Out-File -LiteralPath $FilePath -Encoding utf8
    } catch {
        $_ | Out-File -LiteralPath $FilePath -Encoding utf8
    }
}

function Invoke-JsonCapture {
    param(
        [string]$FilePath,
        [scriptblock]$Action
    )

    try {
        $value = & $Action
        $value | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $FilePath
    } catch {
        [ordered]@{
            error = $_.Exception.Message
        } | ConvertTo-Json | Set-Content -LiteralPath $FilePath
    }
}

function Save-Actuator {
    param(
        [string]$Url,
        [string]$FilePath
    )

    try {
        $resp = Invoke-RestMethod -Uri $Url -TimeoutSec 10
        $resp | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $FilePath
    } catch {
        [ordered]@{
            url = $Url
            error = $_.Exception.Message
        } | ConvertTo-Json | Set-Content -LiteralPath $FilePath
    }
}

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..\..")).Path
$runDir = Join-Path $repoRoot "perf\run"
$diagRoot = Join-Path $repoRoot "perf\diagnostics"
$pidFile = Join-Path $runDir "local-perf.pid"

New-Item -ItemType Directory -Force -Path $diagRoot | Out-Null

if (-not $TargetPid) {
    if (-not (Test-Path $pidFile)) {
        throw "PID not provided and perf/run/local-perf.pid does not exist."
    }
    $TargetPid = [int](Get-Content $pidFile -Raw)
}

$reasonSafe = ($Reason -replace "[^a-zA-Z0-9_-]", "-")
$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$captureDir = Join-Path $diagRoot "$timestamp-$reasonSafe"
New-Item -ItemType Directory -Force -Path $captureDir | Out-Null

$meta = [ordered]@{
    pid = $TargetPid
    reason = $Reason
    createdAt = (Get-Date).ToString("s")
    includeHeapDump = [bool]$IncludeHeapDump
    includeClassHistogram = [bool]$IncludeClassHistogram
}
$meta | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $captureDir "meta.json")

Invoke-JsonCapture -FilePath (Join-Path $captureDir "process.json") -Action {
    Get-Process -Id $TargetPid | Select-Object Id, ProcessName, CPU, StartTime, WorkingSet64, PagedMemorySize64, VirtualMemorySize64, Threads, Handles
}

Invoke-JsonCapture -FilePath (Join-Path $captureDir "process-commandline.json") -Action {
    Get-CimInstance Win32_Process -Filter "ProcessId = $TargetPid" | Select-Object ProcessId, Name, CommandLine, ExecutablePath
}

Invoke-TextCapture -FilePath (Join-Path $captureDir "tcp-connections.txt") -Action {
    Get-NetTCPConnection -ErrorAction SilentlyContinue |
        Where-Object { $_.LocalPort -in 8081, 8090 } |
        Sort-Object LocalPort, State |
        Format-Table -AutoSize -Property State, LocalAddress, LocalPort, RemoteAddress, RemotePort, OwningProcess
}

Invoke-TextCapture -FilePath (Join-Path $captureDir "ports-listening.txt") -Action {
    Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue |
        Where-Object { $_.LocalPort -in 8081, 8090 } |
        Format-Table -AutoSize -Property LocalAddress, LocalPort, OwningProcess
}

if (Get-Command jcmd -ErrorAction SilentlyContinue) {
    Invoke-TextCapture -FilePath (Join-Path $captureDir "jcmd-vm-flags.txt") -Action { jcmd $TargetPid VM.flags }
    Invoke-TextCapture -FilePath (Join-Path $captureDir "jcmd-command-line.txt") -Action { jcmd $TargetPid VM.command_line }
    Invoke-TextCapture -FilePath (Join-Path $captureDir "jcmd-heap-info.txt") -Action { jcmd $TargetPid GC.heap_info }
    Invoke-TextCapture -FilePath (Join-Path $captureDir "jcmd-thread-print.txt") -Action { jcmd $TargetPid Thread.print }

    # GC.class_histogram may trigger "Heap Inspection Initiated GC" and pollute perf samples.
    # Keep it opt-in for manual memory investigations instead of capturing it by default.
    if ($IncludeClassHistogram) {
        Invoke-TextCapture -FilePath (Join-Path $captureDir "jcmd-class-histogram.txt") -Action { jcmd $TargetPid GC.class_histogram }
    }
}

if (Get-Command jstack -ErrorAction SilentlyContinue) {
    Invoke-TextCapture -FilePath (Join-Path $captureDir "jstack.txt") -Action { jstack -l $TargetPid }
}

Save-Actuator -Url "http://localhost:8081/actuator/health" -FilePath (Join-Path $captureDir "actuator-health.json")
Save-Actuator -Url "http://localhost:8081/actuator/threaddump" -FilePath (Join-Path $captureDir "actuator-threaddump.json")
Save-Actuator -Url "http://localhost:8081/actuator/metrics/jvm.memory.used" -FilePath (Join-Path $captureDir "actuator-metric-jvm-memory-used.json")
Save-Actuator -Url "http://localhost:8081/actuator/metrics/process.cpu.usage" -FilePath (Join-Path $captureDir "actuator-metric-process-cpu-usage.json")
Save-Actuator -Url "http://localhost:8081/actuator/metrics/http.server.requests" -FilePath (Join-Path $captureDir "actuator-metric-http-server-requests.json")
Save-Actuator -Url "http://localhost:8081/actuator/metrics/hikaricp.connections.active" -FilePath (Join-Path $captureDir "actuator-metric-hikari-active.json")
Save-Actuator -Url "http://localhost:8081/actuator/metrics/hikaricp.connections.pending" -FilePath (Join-Path $captureDir "actuator-metric-hikari-pending.json")
Save-Actuator -Url "http://localhost:8081/actuator/metrics/chat.mailbox.backlog" -FilePath (Join-Path $captureDir "actuator-metric-chat-mailbox-backlog.json")
Save-Actuator -Url "http://localhost:8081/actuator/metrics/chat.mailbox.submit.rejected" -FilePath (Join-Path $captureDir "actuator-metric-chat-mailbox-submit-rejected.json")
Save-Actuator -Url "http://localhost:8081/actuator/metrics/chat.mailbox.task.duration" -FilePath (Join-Path $captureDir "actuator-metric-chat-mailbox-task-duration.json")
Save-Actuator -Url "http://localhost:8081/actuator/metrics/chat.mailbox.queue.wait.duration" -FilePath (Join-Path $captureDir "actuator-metric-chat-mailbox-queue-wait-duration.json")
Save-Actuator -Url "http://localhost:8081/actuator/metrics/chat.side_effect.step.duration" -FilePath (Join-Path $captureDir "actuator-metric-chat-side-effect-step-duration.json")
Save-Actuator -Url "http://localhost:8081/actuator/metrics/chat.stripe.lock.timeout" -FilePath (Join-Path $captureDir "actuator-metric-chat-stripe-lock-timeout.json")
Save-Actuator -Url "http://localhost:8081/actuator/metrics/chat.stripe.lock.wait.duration" -FilePath (Join-Path $captureDir "actuator-metric-chat-stripe-lock-wait-duration.json")
Save-Actuator -Url "http://localhost:8081/actuator/metrics/chat.send.rate_limited" -FilePath (Join-Path $captureDir "actuator-metric-chat-send-rate-limited.json")
Save-Actuator -Url "http://localhost:8081/actuator/metrics/chat.msg.xadd.fail" -FilePath (Join-Path $captureDir "actuator-metric-chat-msg-xadd-fail.json")
Save-Actuator -Url "http://localhost:8081/actuator/metrics/chat.msg.persist.fallback" -FilePath (Join-Path $captureDir "actuator-metric-chat-msg-persist-fallback.json")
Save-Actuator -Url "http://localhost:8081/actuator/metrics/flashchat.broadcast.duration" -FilePath (Join-Path $captureDir "actuator-metric-flashchat-broadcast-duration.json")
Save-Actuator -Url "http://localhost:8081/actuator/metrics/flashchat.broadcast.serialize.duration" -FilePath (Join-Path $captureDir "actuator-metric-flashchat-broadcast-serialize-duration.json")
Save-Actuator -Url "http://localhost:8081/actuator/metrics/flashchat.broadcast.write.duration" -FilePath (Join-Path $captureDir "actuator-metric-flashchat-broadcast-write-duration.json")
Save-Actuator -Url "http://localhost:8081/actuator/metrics/flashchat.broadcast.flush.duration" -FilePath (Join-Path $captureDir "actuator-metric-flashchat-broadcast-flush-duration.json")
Save-Actuator -Url "http://localhost:8081/actuator/metrics/flashchat.broadcast.write.complete.duration" -FilePath (Join-Path $captureDir "actuator-metric-flashchat-broadcast-write-complete-duration.json")
Save-Actuator -Url "http://localhost:8081/actuator/metrics/flashchat.broadcast.batch.complete.duration" -FilePath (Join-Path $captureDir "actuator-metric-flashchat-broadcast-batch-complete-duration.json")
Save-Actuator -Url "http://localhost:8081/actuator/metrics/flashchat.broadcast.write.failure" -FilePath (Join-Path $captureDir "actuator-metric-flashchat-broadcast-write-failure.json")
Save-Actuator -Url "http://localhost:8081/actuator/metrics/cache.redis.degradation" -FilePath (Join-Path $captureDir "actuator-metric-cache-redis-degradation.json")
Save-Actuator -Url "http://localhost:8081/actuator/metrics/websocket.online.users" -FilePath (Join-Path $captureDir "actuator-metric-websocket-online-users.json")
Save-Actuator -Url "http://localhost:8081/actuator/metrics/jvm.gc.pause" -FilePath (Join-Path $captureDir "actuator-metric-jvm-gc-pause.json")

try {
    Invoke-WebRequest -Uri "http://localhost:8081/actuator/prometheus" -OutFile (Join-Path $captureDir "actuator-prometheus.txt") -TimeoutSec 20
} catch {
    "Failed to capture /actuator/prometheus: $($_.Exception.Message)" | Set-Content -LiteralPath (Join-Path $captureDir "actuator-prometheus.txt")
}

if ($IncludeHeapDump -and (Get-Command jcmd -ErrorAction SilentlyContinue)) {
    $heapDumpPath = Join-Path $captureDir "heapdump.hprof"
    Invoke-TextCapture -FilePath (Join-Path $captureDir "jcmd-heap-dump.txt") -Action {
        jcmd $TargetPid GC.heap_dump $heapDumpPath
    }
}

Write-Host "Diagnostics captured to:"
Write-Host $captureDir
