# Round 2 Hot-Room Findings

## Goal

Identify the first real resource bottleneck for a single hot room after relaxing only `flashchat.rate-limit.room-global.max-count`, while keeping `user-global` and `user-room` protections enabled.

## Effective Runs

All runs below used the `2-core / 4G` local simulation (`shared-host`) and `local-perf` JVM settings.

| Run | Result Dir | Profile / Shape | Key Result |
| --- | --- | --- | --- |
| full-30 | `perf/results/20260414-124957-room-hotspot-room-20000` | `room-20000`, 30 participants, full mixed chat workload | `sendSuccessesPerSec=74.71`, `http p95=158.99ms`, `ws p95=87ms`, no limiter hit |
| iso-30 | `perf/results/20260414-125857-room-hotspot-room-20000` | `room-20000`, 30 participants, `ACK/HISTORY` almost disabled | `sendSuccessesPerSec=112.46`, but `sendUserRoomLimited=11455` |
| iso-60 | `perf/results/20260414-130718-room-hotspot-room-20000` | `room-20000`, 60 participants, `ACK/HISTORY` almost disabled | `sendSuccessesPerSec=150.43`, `sendSuccessRate=99.97%`, only `12` user-room limits |
| iso-120-high | `perf/results/20260414-131533-room-hotspot-room-20000` | `room-20000`, 120 participants, `ACK/HISTORY` almost disabled, stages `20 -> 40 -> 70 -> 100`, think time `20ms` | `sendSuccessesPerSec=197.91`, `http p95=294.23ms`, `ws p95=211ms`, `8` transport timeouts |

## What Changed Across Runs

1. `room-global` stopped being the primary bottleneck once the tier moved beyond `8000`.
2. With `ACK/HISTORY` removed from the hot path, the next protection to surface was `user-room`.
3. Increasing participants from `30` to `60` avoided `user-room` saturation without relaxing user protections.
4. Under the stronger `120`-participant run, throughput increased again and no limiter counters were hit, but request timeouts and latency rose sharply.

## Post-Run Diagnostics

Diagnostics directory:

- `perf/diagnostics/20260414-132231-post-high-hotspot`

Captured evidence:

- `chat.mailbox.backlog = 0`
- `chat.mailbox.submit.rejected = 0`
- `chat.stripe.lock.timeout = 0`
- `hikaricp.connections.pending = 0`
- `chat.msg.xadd.fail = 0`
- `chat.msg.persist.fallback = 0`
- `cache.redis.degradation = 0`
- health remained `UP`, DB remained `UP`, Redis remained `UP`

Broadcast instrumentation also stayed light:

- `flashchat.broadcast.duration count = 62369`
- `flashchat.broadcast.duration max = 0.0012004s`

This means the server-side broadcast function itself was not the first hard bottleneck.

## GC Evidence

GC log:

- `perf/logs/flashchat-local-perf-20260414-131523.gc.log`

Observed during the strongest run:

- repeated `MMU target violated`
- repeated `G1 Evacuation Pause` over `200ms`
- `G1 Humongous Allocation`
- `Evacuation Failure: Allocation`
- max observed `jvm.gc.pause = 0.404s`

## Current Conclusion

The first real bottleneck is no longer a rate limiter, DB pool wait, Redis persistence failure, mailbox backlog, or room lock timeout.

The strongest evidence now points to:

1. allocation / GC pressure in the hot-room message path
2. client-visible latency growth in the end-to-end broadcast path

More precisely:

- `chat/msg` transport timeouts began to appear without any limiter counters increasing
- `http p95` rose from about `149ms` to about `294ms`
- client-observed `ws broadcast p95` rose from about `81ms` to about `211ms`
- but server-side `flashchat.broadcast.duration` remained sub-millisecond level at max

This suggests the first real saturation point is upstream of the actual broadcast call, likely in request handling / allocation / serialization / message processing, with GC pauses amplifying end-to-end delivery delay.

## Recommended Next Validation

If a third round is needed, the highest-value next test is:

- keep `room-global=20000`
- keep `user-global` and `user-room`
- keep `ACK/HISTORY` disabled
- keep participants `>= 120`
- raise sender stages again

The purpose would be to confirm whether throughput now flattens while `GC pause` and `chat/msg timeout` continue to worsen.

## Unified Fresh + Unlimited Steady-State Series

To remove residual-state noise and eliminate `room-global` clipping, the app was restarted before each run with:

- profile: `local-perf,local-perf-room-unlimited`
- startup arg: `--flashchat.rate-limit.room-global.max-count=2147483647`

This kept `user-global` and `user-room` protections, but made `room-global` effectively non-blocking.

### Results

| Run | Result Dir | sender target | sendSuccessesPerSec | http p95 | http p99 | ws p95 | ws p99 | transport failures |
| --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| fresh-unlimited-45 | `perf/results/20260414-171709-room-hotspot-fresh-unlimited-stable-45vu-r2` | 45 | 210.75 | 312.28ms | 391.37ms | 170ms | 234ms | 6 |
| fresh-unlimited-60 | `perf/results/20260414-173153-room-hotspot-fresh-unlimited-stable-60vu-r2` | 60 | 220.29 | 415.87ms | 508.99ms | 240ms | 312ms | 2 |
| fresh-unlimited-75 | `perf/results/20260414-174334-room-hotspot-fresh-unlimited-stable-75vu-r2` | 75 | 206.56 | 581.67ms | 752.91ms | 390ms | 526ms | 0 |

### Interpretation

1. Throughput no longer scales linearly after the 45-60 band.
2. The best observed throughput in this unified series is around `220 msg/s`, but the latency cost at 60 VU is already materially higher than 45 VU.
3. At 75 VU, successful throughput actually falls back to around `206.56 msg/s` while p95/p99 and WS broadcast latency worsen sharply.

This is the clearest capacity-knee signal from the entire round:

- **capacity knee**: around the `60 VU` steady-state band, roughly `~220 msg/s`
- **safe stable band**: around the `45 VU` steady-state band, roughly `~210 msg/s`

### Why 45 VU is the conservative stable recommendation

Compared with 60 VU:

- `45 VU` keeps `http p95` about `103ms` lower
- `45 VU` keeps `ws p95` about `70ms` lower
- `45 VU` keeps p99 lower as well
- `45 VU` still delivers roughly `210 msg/s`, which is close to the peak observed in the unified steady-state series

In other words, pushing from 45 to 60 yields only about `+9.5 msg/s`, but costs a disproportionate jump in tail latency.

### Final Runtime Evidence

Diagnostics after the unified series:

- `perf/diagnostics/20260414-175607-post-fresh-unlimited-series`

Post-series signals still showed:

- `chat.mailbox.backlog = 0`
- `chat.stripe.lock.timeout = 0`
- `hikaricp.connections.pending = 0`
- `chat.msg.xadd.fail = 0`
- `chat.msg.persist.fallback = 0`
- process health / DB / Redis all `UP`

GC remained the strongest shared stress signal:

- `jvm.gc.pause count = 154`
- `jvm.gc.pause total = 12.953s`
- `jvm.gc.pause max = 0.423s`

### Final Conclusion

For the current single-hot-room model under the local `2-core / 4G` simulation:

- **true steady-state capacity knee** is about `~220 msg/s`
- **recommended stable QPS / msg throughput** is about `~210 msg/s`

The first real bottleneck remains consistent with earlier analysis:

- not room-global limiting
- not Hikari pending
- not Redis XADD failure
- not persist fallback
- not mailbox backlog or stripe lock timeout

Instead, the system enters a latency-led saturation zone where:

- end-to-end request latency rises sharply
- client-observed broadcast delay rises sharply
- GC pause remains the most repeatable stress signal
