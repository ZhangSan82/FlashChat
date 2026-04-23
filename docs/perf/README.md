# FlashChat Local Perf Playbook

## Scope

This playbook targets local performance testing for the real runtime entry:

- Entry: `services/aggregation-service`
- HTTP: `http://localhost:8081`
- WebSocket: `ws://localhost:8090/?token=<satoken>`
- Auth header: `satoken`
- Local monitoring only: `Prometheus + Grafana` run on the local machine and are not part of the future 2c4g server budget.

## Next Round

For the next hot-room capacity round, use:

- [room-hotspot-round2.md](/d:/javaproject/FlashChat/docs/perf/room-hotspot-round2.md)
- [ten-room-multiroom.md](/d:/javaproject/FlashChat/docs/perf/ten-room-multiroom.md)

That playbook focuses on:

- preserving user-level protections
- only widening the room-level global send limit
- finding the first real resource bottleneck after the room-level limiter is no longer the first cap
- validating 10 active rooms under `fresh + unlimited` to distinguish single-room ceiling from system-wide total throughput

## Prerequisites

- Java 17
- Maven 3.9+
- Docker Desktop
- `k6` in `PATH` or Docker available

The runner script supports two modes:

- local binary: `k6` in `PATH`
- Docker fallback: `grafana/k6:latest`

If the local machine does not have `k6`, the script can run with Docker directly. The first Docker-based run will pull the `grafana/k6:latest` image.

## Confirmed Runtime Topology

Confirmed:

- First-round perf target should be `aggregation-service` single process.
- `MySQL` and `Redis` should run as local Docker dependencies.
- Monitoring should stay local and separate from the future server budget.

Inference:

- `shared-host` mode is the conservative mode for future `2c4g single host` planning.
- `app-only` mode is useful when you want to estimate the application ceiling without counting MySQL/Redis on the same box.

## Files Added For Perf

- `services/aggregation-service/src/main/resources/application-local-perf.yaml`
  - Aggregation-service perf profile with lower logging noise, more actuator endpoints, disabled AI calls, relaxed rate-limit, and percentile histograms.
- `docker/perf/docker-compose.yml`
  - Local MySQL + Redis compose with resource caps.
- `monitoring/docker-compose.local-perf.yml`
  - Local-only Prometheus + Grafana.
- `monitoring/prometheus/prometheus-local-perf.yml`
  - Scrape config for local perf profile.
- `monitoring/grafana/provisioning/**`
  - Auto-provision datasource and dashboard.
- `monitoring/grafana/dashboards/flashchat-local-perf.json`
  - Starter dashboard for JVM, HTTP, Hikari, WS, mailbox, broadcast, and Redis degradation metrics.
- `scripts/perf/*.ps1`
  - Start, stop, diagnose, and execute perf scenarios.
- `perf/k6/*.js`
  - Smoke, HTTP baseline, WS broadcast, mixed workload, stress, and hot-room breaking point scenarios.
- `perf/results`, `perf/diagnostics`, `perf/logs`, `perf/run`
  - Output directories.

## Startup Order

### 1. Start dependencies

```powershell
.\scripts\perf\Start-PerfInfra.ps1 -WithMonitoring
```

Services:

- MySQL: `localhost:13306`
- Redis: `localhost:6379`
- Prometheus: `http://localhost:9090`
- Grafana: `http://localhost:3001`

Grafana default credentials:

- username: `admin`
- password: `flashchat123`

### 2. Start the application

Conservative single-host mode:

```powershell
.\scripts\perf\Start-LocalPerfApp.ps1 -Topology shared-host
```

Application-only ceiling mode:

```powershell
.\scripts\perf\Start-LocalPerfApp.ps1 -Topology app-only
```

Optional build before startup:

```powershell
.\scripts\perf\Start-LocalPerfApp.ps1 -Topology shared-host -Build
```

## JVM And Resource Simulation

### Shared-host mode

Use this when the future deployment is expected to be:

- Java app
- MySQL
- Redis
- same `2c4g` machine

Default application constraints in `shared-host` mode:

- `-XX:ActiveProcessorCount=2`
- `-Xms768m -Xmx768m`
- `-XX:MaxRAM=1408m`
- `-XX:MaxDirectMemorySize=256m`
- `-XX:MaxMetaspaceSize=256m`
- `G1GC`
- GC log enabled

Dependency limits from compose:

- MySQL: `1.0 CPU`, `1536m`
- Redis: `0.5 CPU`, `512m`

### App-only mode

Use this to estimate the Java process ceiling without counting MySQL and Redis into the same budget:

- `-XX:ActiveProcessorCount=2`
- `-Xms1024m -Xmx1024m`
- `-XX:MaxRAM=1792m`

## Smoke Run

```powershell
.\scripts\perf\Invoke-K6Scenario.ps1 -Scenario smoke -Runner docker -AutoCaptureDiagnostics
```

What smoke covers:

- `/actuator/health`
- `/actuator/metrics/*`
- `/actuator/prometheus`
- `/api/FlashChat/v1/account/auto-register`
- `/api/FlashChat/v1/account/check`
- `/api/FlashChat/v1/account/set-password`
- `/api/FlashChat/v1/account/logout`
- `/api/FlashChat/v1/account/login`
- `/api/FlashChat/v1/account/me`
- `/api/FlashChat/v1/room/create`
- `/api/FlashChat/v1/room/join`
- `/api/FlashChat/v1/chat/msg`
- `/api/FlashChat/v1/chat/history`
- `/api/FlashChat/v1/chat/new`
- `/api/FlashChat/v1/chat/ack`
- `/api/FlashChat/v1/chat/unread`
- WebSocket login, ping/pong, and broadcast receive

## Baseline Run

Suggested first baseline:

```powershell
.\scripts\perf\Invoke-K6Scenario.ps1 -Scenario baseline -Runner docker -AutoCaptureDiagnostics -EnvOverrides @{ K6_VUS = '12'; K6_DURATION = '5m' }
```

This is a distributed multi-room baseline, not a hot-room test.

## WebSocket Broadcast Run

```powershell
.\scripts\perf\Invoke-K6Scenario.ps1 -Scenario ws -Runner docker -AutoCaptureDiagnostics -EnvOverrides @{ WS_LISTENER_COUNT = '10' }
```

This focuses on:

- WS handshake
- identity recovery by token
- ping/pong
- room member online receive path
- HTTP send -> WS broadcast receive

## Mixed Workload Run

Suggested first mixed workload:

```powershell
.\scripts\perf\Invoke-K6Scenario.ps1 -Scenario mixed -Runner docker -AutoCaptureDiagnostics -EnvOverrides @{
  CHAT_VUS = '12'
  ROOM_VUS = '4'
  AUTH_VUS = '2'
  SECONDARY_VUS = '2'
  K6_DURATION = '8m'
}
```

Workload split:

- `60%` chat-heavy flow: send/history/new/ack/unread
- `20%` room-heavy flow: create/join/members/my-rooms/pricing
- `10%` auth-heavy flow: auto-register/check/set-password/logout/login/me
- `10%` secondary flow: game active query and account read path

Reason:

- Chat chain is the real hotspot in this repo.
- Room APIs are meaningful but not as hot as send/history/ack.
- Login/register exists but should not dominate the steady-state workload.
- Game path exists and is suitable as a low-ratio auxiliary branch.

## Stress Run

Suggested first stress run:

```powershell
.\scripts\perf\Invoke-K6Scenario.ps1 -Scenario stress -Runner docker -AutoCaptureDiagnostics -EnvOverrides @{
  STRESS_TARGET_1 = '10'
  STRESS_TARGET_2 = '25'
  STRESS_TARGET_3 = '45'
}
```

This is still a distributed-room model.

## Breaking Point Run

Suggested first hot-room breaking run:

```powershell
.\scripts\perf\Invoke-K6Scenario.ps1 -Scenario breaking -Runner docker -AutoCaptureDiagnostics -EnvOverrides @{
  HOT_ROOM_PARTICIPANTS = '30'
  BREAK_TARGET_1 = '10'
  BREAK_TARGET_2 = '20'
  BREAK_TARGET_3 = '35'
  BREAK_TARGET_4 = '50'
}
```

This intentionally targets the hot-room path and is the quickest way to expose:

- `RoomSerialLock` pressure
- mailbox backlog or rejection
- broadcast latency growth
- Redis stream / DB persistence fallback

## Output And Where To Look

### k6 results

Each run creates:

- `perf/results/<timestamp>-<scenario>/summary.json`
- `perf/results/<timestamp>-<scenario>/k6-console.log`
- `perf/results/<timestamp>-<scenario>/brief.txt`

### App logs

- STDOUT and STDERR: `perf/logs/`
- GC logs: `perf/logs/`

### PID and runtime metadata

- `perf/run/local-perf.pid`
- `perf/run/local-perf.json`

### Diagnostic snapshots

- `perf/diagnostics/<timestamp>-<reason>/`

## Automatic Diagnostics

When `-AutoCaptureDiagnostics` is enabled, the runner calls `Capture-PerfDiagnostics.ps1` if:

- `http_req_duration p95` is above the threshold
- or `http_req_failed rate` is above the threshold
- or `business_failures > 0`

Manual capture:

```powershell
.\scripts\perf\Capture-PerfDiagnostics.ps1 -Reason manual
```

Heap dump when needed:

```powershell
.\scripts\perf\Capture-PerfDiagnostics.ps1 -Reason oom-risk -IncludeHeapDump
```

Collected evidence includes:

- `jcmd VM.flags`
- `jcmd VM.command_line`
- `jcmd GC.heap_info`
- `jcmd Thread.print`
- `jcmd GC.class_histogram`
- `jstack -l`
- process info
- TCP connections on `8081/8090`
- actuator health, metrics, threaddump, prometheus snapshot

## Metrics To Watch

Use Grafana or Prometheus for the following metrics first:

- `http_server_requests_seconds_*`
- `jvm_memory_used_bytes`
- `process_cpu_usage`
- `hikaricp_connections_active`
- `hikaricp_connections_pending`
- `websocket_online_users`
- `flashchat_broadcast_duration_seconds_*`
- `chat_mailbox_backlog`
- `chat_mailbox_submit_rejected_total`
- `chat_stripe_lock_timeout_total`
- `chat_msg_xadd_fail_total`
- `chat_msg_persist_fallback_total`
- `cache_redis_degradation_total`

## Suggested Acceptance Thresholds

### Acceptable

- success rate `>= 99.5%`
- HTTP `p95 < 200ms`
- HTTP `p99 < 500ms`
- no sustained `hikaricp.connections.pending`
- no `chat.mailbox.submit.rejected`
- no `chat.stripe.lock.timeout`
- WS handshake success `>= 99%`
- no Full GC during stable baseline

### Risky

- success rate `98% - 99.5%`
- HTTP `p95 200ms - 500ms`
- HTTP `p99 500ms - 1000ms`
- CPU sustained `70% - 85%`
- Hikari pending visible in bursts
- broadcast p95 keeps climbing
- Redis degradation or persist fallback becomes non-zero

### Not Ready

- success rate `< 98%`
- HTTP `p95 > 500ms`
- HTTP `p99 > 1000ms`
- repeated Full GC
- `chat.mailbox.submit.rejected > 0`
- `chat.stripe.lock.timeout > 0`
- WS success `< 95%`

## Capacity Estimation Method

Use two numbers, not one:

- distributed-room stable throughput
- hot-room first bottleneck throughput

Then derive:

- conservative capacity = `first warning point x 50%`
- normal capacity = `long stable no-warning throughput x 70%`
- optimistic capacity = `short peak throughput x 85%`

Likely bottleneck order in this repo:

1. hot-room serial lock and side-effect mailbox
2. MySQL message persistence and history query path
3. WS room broadcast fan-out
4. Redis stream, unread, and rate-limit path

## Shutdown

Stop application:

```powershell
.\scripts\perf\Stop-LocalPerfApp.ps1
```

Stop infra:

```powershell
.\scripts\perf\Stop-PerfInfra.ps1 -WithMonitoring
```
