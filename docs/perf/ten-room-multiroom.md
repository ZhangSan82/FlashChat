# 10-Room Concurrent Messaging Perf

## Goal

This round validates total system throughput when `10` rooms are active at the same time.

It is intentionally different from the single hot-room round:

- the previous round measured the upper bound of **one** hot room
- this round measures **system-wide** throughput under distributed room traffic

The target is to answer:

1. how high `totalSendSuccessesPerSec` can go when `10` rooms are active together
2. whether each room gets a similar share of throughput
3. whether a new global bottleneck appears before a room-local bottleneck
4. whether the earlier `~210 / ~220 msg/s` result was only a single-room ceiling

## Clean Test Boundary

This series uses the same clean boundary as the latest hotspot round:

- `fresh`
- `unlimited`
- `room-global` must not become the bottleneck
- `ACK / HISTORY` are effectively disabled
- senders are fixed to one room and do not hop across rooms
- each room owns its own participant set

`user-global` and `user-room` protections remain enabled.

To avoid making `user-room` the main contradiction, the default sender cadence is intentionally reduced:

- `20` participants per room
- `TEN_ROOM_SEND_THINK_MS=200`

That keeps the per-user send rate below the current `user-room = 60 / 10s` protection in the default four tiers.

## Default Model

- room count: `10`
- participants per room: `20`
- total participants: `200`
- ws listeners per room: `1`
- sender binding: fixed by room, evenly distributed

Each room is initialized as:

- `1` host creates the room
- `19` guests join the room
- those `20` users are used only inside that room

## Four Tiers

1. `50 sender VU`
   - about `5` sender VUs per room
2. `100 sender VU`
   - about `10` sender VUs per room
3. `150 sender VU`
   - about `15` sender VUs per room
4. `200 sender VU`
   - about `20` sender VUs per room

Each tier uses:

- ramp-up: `30s`
- hold: `5m`
- cool-down: `20s`

## Execution

Fresh unlimited restart is built into the runner unless `-SkipAppRestart` is used.

### One-click

```powershell
.\scripts\perf\Run-TenRoom-50.ps1
.\scripts\perf\Run-TenRoom-100.ps1
.\scripts\perf\Run-TenRoom-150.ps1
.\scripts\perf\Run-TenRoom-200.ps1
```

### Generic

```powershell
.\scripts\perf\Run-TenRoom.ps1 -SenderVus 100
```

### Start infra first

```powershell
.\scripts\perf\Run-TenRoom.ps1 -SenderVus 50 -StartInfra -WithMonitoring
```

### Preview command only

```powershell
.\scripts\perf\Run-TenRoom.ps1 -SenderVus 150 -PrepareOnly
```

## Result Directory Naming

Directory naming follows:

```text
perf/results/<timestamp>-ten-room-<run-label>/
```

Example:

```text
perf/results/20260414-180000-ten-room-ten-room-100vu/
```

Each run directory contains:

- `summary.json`
- `ten-room-summary.json`
- `ten-room-report.json`
- `ten-room-report.md`
- `runtime-samples.jsonl`
- `runtime-guard-events.jsonl`
- `app-run-context.json`
- `docker-stdout.log`
- `docker-stderr.log`

## Summary Fields

`ten-room-summary.json` contains at least:

- `totalSendSuccesses`
- `totalSendSuccessesPerSec`
- `totalSendSuccessRate`
- `totalHttpP95Ms`
- `totalHttpP99Ms`
- `totalWsBroadcastP95Ms`
- `totalWsBroadcastP99Ms`
- `perRoomSendSuccessesPerSec`
- `perRoomSendSuccessRate`
- `perRoomWsBroadcastP95Ms`
- `perRoomWsBroadcastP99Ms`
- `roomSkew`
- `sendTimeoutFailures`
- `sendTransportFailures`

`ten-room-report.json` adds:

- runtime sample summary
- GC signal summary

## Runtime Sampling

Automatic runtime sampling is already built into `Invoke-K6Scenario.ps1`.

For manual sampling outside the runner:

```powershell
.\scripts\perf\Watch-ActuatorRuntime.ps1 -DurationSec 300 -IntervalSec 10
```

## How To Judge The Result

### A. Total throughput is much higher than `220 msg/s`

This means the earlier `~220 msg/s` was only a single hot-room ceiling, not the whole-system ceiling.

### B. Total throughput is only slightly higher than `220 msg/s`

This means a stronger global bottleneck exists, likely around allocation / GC / global websocket write pressure / global object churn.

### C. Total throughput grows, but room skew is large

This means scheduling is uneven across rooms, or a local hotspot is forming even inside a distributed model.

### D. Throughput grows, but HTTP / WS p95 rises sharply

This means the system is nearing a new total-capacity knee.

## Suggested Stop Conditions

- `totalHttpP95Ms` keeps climbing across tiers without meaningful throughput gain
- `totalWsBroadcastP95Ms` degrades sharply
- `sendTransportFailures` starts growing in bursts
- `roomSkew` becomes too large
- runtime samples show:
  - mailbox backlog rising
  - Hikari pending rising
  - stripe lock timeout appearing
  - Redis degradation / XADD fail / persist fallback becoming non-zero

## Rollback

This round does not require business-code changes.

Rollback is simply:

```powershell
.\scripts\perf\Stop-LocalPerfApp.ps1
.\scripts\perf\Start-LocalPerfApp.ps1 -Topology shared-host -Profile local-perf
```
