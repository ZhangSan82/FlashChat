# FlashChat Hot-Room Result Template

## Metadata

- Date:
- Commit:
- Branch:
- Operator:
- Topology:
- Runner:
- Monitoring:

## Round Table

| Tier | Profile | Room-global max-count | Participants | WS listeners | Sender stages | Send success rate | Send success msg/s | Send failures | Rate-limited count | HTTP p95 | HTTP p99 | WS broadcast p95 | CPU | Hikari pending | Mailbox backlog | Lock timeout delta | XADD fail delta | Persist fallback delta | First abnormal signal | Round conclusion |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 2000 |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |
| 4000 |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |
| 8000 |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |
| 12000 |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |
| 20000 |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |
| unlimited-short |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |  |

## Failure Breakdown

| Tier | room-global limit hits | user-room limit hits | user-global limit hits | room busy | system busy | timeout | transport | other business | unknown |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 2000 |  |  |  |  |  |  |  |  |  |
| 4000 |  |  |  |  |  |  |  |  |  |
| 8000 |  |  |  |  |  |  |  |  |  |
| 12000 |  |  |  |  |  |  |  |  |  |
| 20000 |  |  |  |  |  |  |  |  |  |
| unlimited-short |  |  |  |  |  |  |  |  |  |

## Evidence

- Result directories:
- Diagnostics directories:
- Grafana panels reviewed:
- Prometheus queries used:
- GC logs:

## Bottleneck Decision

- First non-room-global bottleneck candidate:
- Why:
- Was the bottleneck resource-based or protection-based:
- Can the next tier continue:
- Recommended next action:
