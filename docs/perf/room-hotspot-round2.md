# FlashChat Hot-Room Round 2 Playbook

## 1. Goal

This round does **not** repeat the previous conclusion that `room-global` exists.

This round targets:

- single hot-room capacity
- widening only the **room-level global message limit**
- preserving the existing user-level protections
- finding the **first real resource bottleneck**

This is **not**:

- total system throughput
- multi-room distributed capacity
- a full-protection-off chaos run

## 2. Code-Confirmed Rate-Limit Structure

### Real config locations

- Base runtime defaults: [application.yaml](/d:/javaproject/FlashChat/services/aggregation-service/src/main/resources/application.yaml:88)
- Base local perf profile: [application-local-perf.yaml](/d:/javaproject/FlashChat/services/aggregation-service/src/main/resources/application-local-perf.yaml:61)
- Room-specific round-2 profiles:
  - [application-local-perf-room-2000.yaml](/d:/javaproject/FlashChat/services/aggregation-service/src/main/resources/application-local-perf-room-2000.yaml)
  - [application-local-perf-room-4000.yaml](/d:/javaproject/FlashChat/services/aggregation-service/src/main/resources/application-local-perf-room-4000.yaml)
  - [application-local-perf-room-8000.yaml](/d:/javaproject/FlashChat/services/aggregation-service/src/main/resources/application-local-perf-room-8000.yaml)
  - [application-local-perf-room-12000.yaml](/d:/javaproject/FlashChat/services/aggregation-service/src/main/resources/application-local-perf-room-12000.yaml)
  - [application-local-perf-room-20000.yaml](/d:/javaproject/FlashChat/services/aggregation-service/src/main/resources/application-local-perf-room-20000.yaml)
  - [application-local-perf-room-unlimited.yaml](/d:/javaproject/FlashChat/services/aggregation-service/src/main/resources/application-local-perf-room-unlimited.yaml)

### Real limiter implementation

- Properties class: [RateLimitProperties.java](/d:/javaproject/FlashChat/services/chat-service/src/main/java/com/flashchat/chatservice/ratelimit/RateLimitProperties.java:12)
- Redis Lua limiter: [RedisMessageRateLimiter.java](/d:/javaproject/FlashChat/services/chat-service/src/main/java/com/flashchat/chatservice/ratelimit/RedisMessageRateLimiter.java:20)
- Lua script: [rate_limit_multi.lua](/d:/javaproject/FlashChat/services/chat-service/src/main/resources/lua/rate_limit_multi.lua:1)
- Result enum: [RateLimitResult.java](/d:/javaproject/FlashChat/services/chat-service/src/main/java/com/flashchat/chatservice/ratelimit/RateLimitResult.java:11)
- Call site: [ChatServiceImpl.java](/d:/javaproject/FlashChat/services/chat-service/src/main/java/com/flashchat/chatservice/service/impl/ChatServiceImpl.java:116)

### Confirmed dimensions

- `user-global`
- `user-room`
- `room-global`

### What was not found in code

- no separate IP-level limiter for message send
- no separate system-wide total message limiter beyond the `enabled` master switch and the three dimensions above
- no dedicated login/register flood limiter in the current repo

Notes:

- WebSocket handshake extracts IP in [HttpHeadersHandler.java](/d:/javaproject/FlashChat/services/chat-service/src/main/java/com/flashchat/chatservice/websocket/handlers/HttpHeadersHandler.java:21), but the current code does not use it as a send limiter.
- File upload has a TODO for future rate-limiting, not a finished limiter path.

## 3. What We Keep vs. What We Relax

### Keep

- `flashchat.rate-limit.enabled=true`
- `user-global`
- `user-room`
- room membership checks
- room status checks
- message audit chain
- `RoomSerialLock`
- `RoomSideEffectMailbox`
- Redis Stream persistence handshake
- DB persistence fallback
- WebSocket push path

### Relax only for this round

- `flashchat.rate-limit.room-global.max-count`

### Unlimited mode semantics

- `local-perf-room-unlimited` is **equivalent unlimited**, not a true code-path disable.
- Implementation basis:
  - the limiter stays enabled
  - `user-global` and `user-room` still apply
  - only `room-global.max-count` is raised to `2147483647`

This is intentional because the goal is:

- keep sender-level abuse protection
- remove only the room-level cap that masked the first real bottleneck last round

## 4. Real Hot-Room Send Chain

### HTTP entry

- Controller: [ChatController.java](/d:/javaproject/FlashChat/services/chat-service/src/main/java/com/flashchat/chatservice/controller/ChatController.java:25)
- Endpoint: `POST /api/FlashChat/v1/chat/msg`

### Main service path

- [ChatServiceImpl.sendMsg](/d:/javaproject/FlashChat/services/chat-service/src/main/java/com/flashchat/chatservice/service/impl/ChatServiceImpl.java:98)

### Confirmed order

1. room existence / membership / mute validation
2. message send limiter
3. message-type validation and audit
4. optional reply lookup
5. `RoomSerialLock.acquire(roomId)`
6. msg id allocation
7. `MessagePersistServiceImpl.saveAsync()` or `saveSync()`
8. build broadcast DTO
9. `MessageSideEffectServiceImpl.dispatchUserMessageAccepted(...)`
10. `RoomSideEffectMailbox` shard queue
11. add-to-window + unread + touch-member
12. `RoomChannelManager.broadcastToRoom(...)`

### Key downstream classes

- Lock: [RoomSerialLock.java](/d:/javaproject/FlashChat/services/chat-service/src/main/java/com/flashchat/chatservice/service/dispatch/RoomSerialLock.java:26)
- Persist: [MessagePersistServiceImpl.java](/d:/javaproject/FlashChat/services/chat-service/src/main/java/com/flashchat/chatservice/service/impl/MessagePersistServiceImpl.java:45)
- Side effects: [MessageSideEffectServiceImpl.java](/d:/javaproject/FlashChat/services/chat-service/src/main/java/com/flashchat/chatservice/service/impl/MessageSideEffectServiceImpl.java:33)
- WS broadcast: [RoomChannelManager.java](/d:/javaproject/FlashChat/services/chat-service/src/main/java/com/flashchat/chatservice/websocket/manager/RoomChannelManager.java:465)

## 5. Profiles

| Profile | Room-global limit | Purpose |
| --- | --- | --- |
| `local-perf-room-2000` | `2000/min` | baseline recheck, confirm same cap as last round |
| `local-perf-room-4000` | `4000/min` | first widening step |
| `local-perf-room-8000` | `8000/min` | mid-tier widening |
| `local-perf-room-12000` | `12000/min` | high-tier widening |
| `local-perf-room-20000` | `20000/min` | near-unbounded room cap for this workload |
| `local-perf-room-unlimited` | `2147483647/min` | equivalent-unlimited short top run |

## 6. k6 Assets

### Script

- [room-hotspot.js](/d:/javaproject/FlashChat/perf/k6/room-hotspot.js)

### What it does

- single shared hot room
- configurable participant count
- configurable sender ramp
- dedicated WS listener scenario
- HTTP send + `history/new/ack` companion flow
- send failure reason classification
- WS broadcast latency tracking from message embedded timestamps

### Important custom metrics

- `send_success_rate`
- `send_successes`
- `send_failures`
- `send_rate_limited`
- `send_room_global_limited`
- `send_user_room_limited`
- `send_user_global_limited`
- `send_room_busy_failures`
- `send_system_busy_failures`
- `send_timeout_failures`
- `send_transport_failures`
- `room_ws_broadcast_latency`
- `room_ws_connect_failures`

## 7. Runtime Guards

The runner now supports a room-hotspot actuator guard in [Invoke-K6Scenario.ps1](/d:/javaproject/FlashChat/scripts/perf/Invoke-K6Scenario.ps1).

### Runtime guard watches

- actuator health
- DB health
- Redis health
- `process.cpu.usage`
- `hikaricp.connections.pending`
- `chat.mailbox.backlog`
- `chat.mailbox.submit.rejected`
- `chat.stripe.lock.timeout`
- `chat.msg.xadd.fail`
- `chat.msg.persist.fallback`
- `cache.redis.degradation`

### Post-run thresholds

- HTTP `p95`
- HTTP `p99`
- HTTP error rate
- send success rate
- WS broadcast p95

### Diagnostics

When thresholds or guards breach, the scripts capture:

- actuator health
- actuator threaddump
- JVM metrics
- Hikari metrics
- mailbox / lock / broadcast / redis metrics
- `jcmd` / `jstack` when permissions allow
- process info
- listening ports and TCP connections

## 8. Standard Execution Order

### A. Baseline recheck

```powershell
.\scripts\perf\Run-Room-2000.ps1
```

Expected meaning:

- confirms the same room-level cap still appears
- validates the new round uses the same hot-room model

Continue only if:

- results are consistent with last round
- there is no unexpected DB/Redis health issue

### B. Stair-step widening

```powershell
.\scripts\perf\Run-Room-4000.ps1
.\scripts\perf\Run-Room-8000.ps1
.\scripts\perf\Run-Room-12000.ps1
.\scripts\perf\Run-Room-20000.ps1
```

Recommended interpretation:

- use the same hot-room shape first
- watch whether success throughput rises roughly with the wider room limit
- stop attributing failures to `room-global` once another signal appears first

Continue to the next tier only if:

- DB health is still `UP`
- Redis health is still `UP`
- mailbox backlog is not growing
- `chat.stripe.lock.timeout` stays at `0`
- `chat.mailbox.submit.rejected` stays at `0`
- no repeated DB/Redis timeout pattern appears

### C. Short top run

```powershell
.\scripts\perf\Run-Room-Unlimited-Short.ps1
```

Meaning:

- this is the short top run for first-resource-bottleneck discovery
- it preserves sender-level protection and only removes the room-global cap

## 9. Stop Conditions

### Recommend stop immediately when any condition is sustained

- send success rate drops below the run threshold and keeps falling
- HTTP `p95` or `p99` keeps climbing instead of stabilizing
- `chat.mailbox.backlog > 0` and keeps growing
- `chat.stripe.lock.timeout > 0`
- `chat.mailbox.submit.rejected > 0`
- DB health or Redis health becomes non-`UP`
- `chat.msg.xadd.fail > 0`
- `chat.msg.persist.fallback > 0`
- `hikaricp.connections.pending > 0` and stays there
- WS broadcast latency jumps into an unacceptable band
- repeated timeout failures appear

### Risky but not automatic stop by itself

- process CPU stays high but stable, without backlog or lock timeout
- HTTP p95 rises moderately but still recovers during ramp-down

## 10. One-Click Scripts

- [Run-RoomHotspot.ps1](/d:/javaproject/FlashChat/scripts/perf/Run-RoomHotspot.ps1)
- [Run-Room-2000.ps1](/d:/javaproject/FlashChat/scripts/perf/Run-Room-2000.ps1)
- [Run-Room-4000.ps1](/d:/javaproject/FlashChat/scripts/perf/Run-Room-4000.ps1)
- [Run-Room-8000.ps1](/d:/javaproject/FlashChat/scripts/perf/Run-Room-8000.ps1)
- [Run-Room-12000.ps1](/d:/javaproject/FlashChat/scripts/perf/Run-Room-12000.ps1)
- [Run-Room-20000.ps1](/d:/javaproject/FlashChat/scripts/perf/Run-Room-20000.ps1)
- [Run-Room-Unlimited-Short.ps1](/d:/javaproject/FlashChat/scripts/perf/Run-Room-Unlimited-Short.ps1)

### Common usage

```powershell
.\scripts\perf\Run-RoomHotspot.ps1 -Tier 12000
```

### With build

```powershell
.\scripts\perf\Run-RoomHotspot.ps1 -Tier 12000 -Build
```

### With infra start

```powershell
.\scripts\perf\Run-RoomHotspot.ps1 -Tier 12000 -StartInfra -WithMonitoring
```

### With short top run and runtime abort

```powershell
.\scripts\perf\Run-Room-Unlimited-Short.ps1
```

### Only print the generated command

```powershell
.\scripts\perf\Run-RoomHotspot.ps1 -Tier 8000 -PrepareOnly
```

## 11. Result Directories

Each run produces a tier-tagged result folder under `perf/results`, for example:

- `20260414-xxxxxx-room-hotspot-room-2000`
- `20260414-xxxxxx-room-hotspot-room-4000`
- `20260414-xxxxxx-room-hotspot-room-unlimited-short`

Important files:

- `summary.json`
- `k6-console.log`
- `brief.txt`
- `room-hotspot-summary.json`
- `runtime-samples.jsonl`
- `runtime-guard-events.jsonl`

## 12. Rollback

### Back to the original local perf profile

```powershell
.\scripts\perf\Stop-LocalPerfApp.ps1
.\scripts\perf\Start-LocalPerfApp.ps1 -Topology shared-host -Profile local-perf
```

### Back to the original room cap

```powershell
.\scripts\perf\Stop-LocalPerfApp.ps1
.\scripts\perf\Start-LocalPerfApp.ps1 -Topology shared-host -Profile local-perf-room-2000
```

No business logic was changed for this round. Rollback is only a profile switch.
