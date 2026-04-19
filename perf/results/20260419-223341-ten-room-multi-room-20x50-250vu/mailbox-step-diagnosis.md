# Mailbox Step Diagnosis

- ResultDir: D:\javaproject\FlashChat\perf\results\20260419-223341-ten-room-multi-room-20x50-250vu
- CreatedAt: 2026-04-19T22:43:28

## Queue Signals

| metric | count | avgMs | maxMs | totalMs |
| --- | ---: | ---: | ---: | ---: |
| chat.mailbox.queue.wait.duration | 359147 | 4.353 | 479.758 | 1563197.881 |
| chat.mailbox.task.duration | 359147 | 1.528 | 65.072 | 548698.249 |

## Side Effect Step Timers

| metric | count | avgMs | maxMs | totalMs |
| --- | ---: | ---: | ---: | ---: |
| window_add | 359147 | 1.472 | 65.027 | 528781.718 |
| broadcast | 359147 | 0.048 | 24.742 | 17331.609 |
| touch | 359147 | 0.001 | 0.869 | 314.745 |
| unread | 0 | - | 0 | 0 |
| window_update | 0 | - | 0 | 0 |
| window_remove | 0 | - | 0 | 0 |
| broadcast_reaction | 0 | - | 0 | 0 |
| broadcast_state | 0 | - | 0 | 0 |

## Broadcast Sub-Phase Timers

| metric | count | avgMs | maxMs | totalMs |
| --- | ---: | ---: | ---: | ---: |
| serialize | 359165 | 0.008 | 3.17 | 3038.879 |
| write | 359165 | 0.009 | 13.867 | 3156.719 |
| flush | 359165 | 0.024 | 24.65 | 8535.963 |

## Window Add Sub-Phase Timers

| metric | count | avgMs | maxMs | totalMs |
| --- | ---: | ---: | ---: | ---: |
| serialize | 359147 | 0.006 | 18.122 | 2113.612 |
| redis | 359147 | 1.462 | 65.013 | 525111.672 |

## Send Sync Path Timers

| metric | count | avgMs | maxMs | totalMs |
| --- | ---: | ---: | ---: | ---: |
| stripe.lock.wait | 359147 | 0.296 | 102.417 | 106319.7 |
| send.pre_lock | 359147 | 1.751 | 218.275 | 628740.563 |
| send.rate_limit | 359147 | 1.71 | 82.734 | 614006.816 |
| send.audit | 359147 | 0.005 | 0.407 | 1692.818 |
| send.reply_validate | 0 | - | 0 | 0 |
| send.critical | 359147 | 1.298 | 78.908 | 466160.176 |
| msg.xadd | 359147 | 1.245 | 78.873 | 447173.113 |
| msg.id.slow_path | 6846 | 1.992 | 23.317 | 13637.793 |

## Send Sync Path Counters

| metric | count |
| --- | ---: |
| msg.xadd.fail | 0 |
| stripe.lock.timeout | 0 |
| send.rate_limited | 0 |
| reply.not_found | 0 |
| msg.persist.fallback | 0 |

## Findings

- Queue wait is larger than task execution time. This points to shard queueing.
- window_add is the slowest side-effect step.
- The slowest window_add phase is redis.
- msg.id.slow_path is the slowest send-sync timer.

## Suggestions

- Check room-to-shard distribution and hot-room collisions on the same shard.
- Investigate Redis RTT, pipeline cost, and window-key hotspots.
- Investigate msg ID slow-path Lua latency and consider segment size or Redis contention.
