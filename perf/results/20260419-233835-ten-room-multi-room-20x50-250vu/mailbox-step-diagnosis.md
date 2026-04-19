# Mailbox Step Diagnosis

- ResultDir: D:\javaproject\FlashChat\perf\results\20260419-233835-ten-room-multi-room-20x50-250vu
- CreatedAt: 2026-04-19T23:46:07

## Queue Signals

| metric | count | avgMs | maxMs | totalMs |
| --- | ---: | ---: | ---: | ---: |
| chat.mailbox.queue.wait.duration | 365881 | 7.107 | 159.079 | 2600407.291 |
| chat.mailbox.task.duration | 365881 | 1.842 | 53.782 | 673971.757 |

## Side Effect Step Timers

| metric | count | avgMs | maxMs | totalMs |
| --- | ---: | ---: | ---: | ---: |
| window_add | 365881 | 1.786 | 53.682 | 653370.698 |
| broadcast | 365881 | 0.049 | 16.435 | 17809.193 |
| touch | 365881 | 0.001 | 0.631 | 388.917 |
| unread | 0 | - | 0 | 0 |
| window_update | 0 | - | 0 | 0 |
| window_remove | 0 | - | 0 | 0 |
| broadcast_reaction | 0 | - | 0 | 0 |
| broadcast_state | 0 | - | 0 | 0 |

## Broadcast Sub-Phase Timers

| metric | count | avgMs | maxMs | totalMs |
| --- | ---: | ---: | ---: | ---: |
| serialize | 365899 | 0.008 | 9.343 | 3070.988 |
| write | 365899 | 0.01 | 9.792 | 3821.822 |
| flush | 365899 | 0.023 | 16.417 | 8256.148 |

## Window Add Sub-Phase Timers

| metric | count | avgMs | maxMs | totalMs |
| --- | ---: | ---: | ---: | ---: |
| serialize | 365881 | 0.005 | 9.501 | 2000.429 |
| redis | 365881 | 1.776 | 53.676 | 649735.245 |

## Send Sync Path Timers

| metric | count | avgMs | maxMs | totalMs |
| --- | ---: | ---: | ---: | ---: |
| stripe.lock.wait | 365881 | 0.397 | 53.268 | 145391.708 |
| send.pre_lock | 365881 | 2.126 | 53.528 | 777780.556 |
| send.rate_limit | 365881 | 2.088 | 53.505 | 763853.164 |
| send.audit | 365881 | 0.005 | 0.264 | 1720.013 |
| send.reply_validate | 0 | - | 0 | 0 |
| send.critical | 365881 | 1.627 | 53.627 | 595198.166 |
| msg.xadd | 365881 | 1.563 | 53.618 | 571919.579 |
| msg.id.slow_path | 8233 | 2.226 | 17.996 | 18324.206 |

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
