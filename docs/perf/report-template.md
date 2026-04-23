# FlashChat Perf Report Template

## 1. Test Metadata

- Date:
- Commit:
- Branch:
- Operator:
- Scenario:
- Profile:
- Topology:
- Monitoring mode:

## 2. Environment

- OS:
- CPU:
- Memory:
- Java version:
- Maven version:
- Docker version:
- k6 version:
- MySQL image:
- Redis image:

## 3. Commands

```powershell
# Infra

# App

# k6
```

## 4. Scenario Configuration

- VUs / stages:
- Duration:
- Think time:
- WS listener count:
- Hot-room participant count:
- Thresholds:

## 5. Key Results

| Metric | Value | Comment |
| --- | --- | --- |
| Success rate |  |  |
| HTTP p50 |  |  |
| HTTP p95 |  |  |
| HTTP p99 |  |  |
| Requests/s |  |  |
| CPU |  |  |
| RSS |  |  |
| Heap used |  |  |
| Full GC count |  |  |
| Hikari active |  |  |
| Hikari pending |  |  |
| WS online users |  |  |
| Broadcast p95 |  |  |
| Mailbox backlog |  |  |
| Stripe lock timeout |  |  |
| Redis degradation |  |  |
| Persist fallback |  |  |

## 6. Observations

- Main latency contributor:
- Main error contributor:
- First visible bottleneck:
- Whether baseline is stable:
- Whether hot-room path is stable:

## 7. Evidence

- `perf/results/...`
- `perf/diagnostics/...`
- Grafana panels:
- Prometheus queries:
- GC log path:

## 8. Capacity Estimate

- Conservative:
- Normal:
- Optimistic:
- Main assumption:
- Why:

## 9. Conclusion

- Acceptable / Risky / Not ready:
- Recommendation:
- Top 3 next optimizations:
