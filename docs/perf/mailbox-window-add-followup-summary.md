# Mailbox / `window_add` 后续优化阶段性补充说明

## 1. 文档目的

这份文档是对下面两份已有结论文档的补充：

- [unread-optimization-results.md](./unread-optimization-results.md)
- [mailbox-shard-tests-summary.md](./mailbox-shard-tests-summary.md)

它只关注 unread 优化之后的新阶段，不重复旧结论，重点回答 4 个问题：

1. unread 退出主矛盾之后，瓶颈迁移到了哪里。
2. `MessageWindowServiceImpl.addToWindow()` 的优化是否真的生效。
3. 近期 `230 / 250 VU` 样本里，哪些结果能作为正式依据，哪些不能。
4. 当前继续推进时，应该优先优化什么。

本文涉及的结果，统一基于：

- 模型：`20 rooms x 50 participants`
- 压测入口：mailbox probe
- 时间范围：`2026-04-16` unread 优化之后的后续轮次

## 2. 当前已经固定下来的事实

- unread 的旧实现确实是写时 fan-out，且位于 mailbox 副作用任务内部。
- unread 已改为读时 DB 统计，旧逻辑保留为注释，没有删除。
- unread 优化已经显著降低了 queue wait、backlog 和 WS 延迟，当前不再是主矛盾。
- `window_add` 仍然是当前 mailbox 内最慢的活跃步骤。
- `window_add` 的主耗时相位不是序列化，而是 Redis 写路径。
- `broadcast.flush` 不是当前主瓶颈，它的平均耗时量级明显低于 `window_add.redis`。
- `250 VU` 已经能打出很高的上沿样本，但目前仍应视为高位临界档，而不是稳定档。

## 3. 这阶段做过的关键改动

### 3.1 `window_add` 子步骤埋点

为了区分 `window_add` 到底慢在 Java 侧还是 Redis 侧，补了两个指标：

- `flashchat.window.add.serialize.duration`
- `flashchat.window.add.redis.duration`

对应代码位于：

- [MessageWindowServiceImpl.java](../../services/chat-service/src/main/java/com/flashchat/chatservice/service/impl/MessageWindowServiceImpl.java)
- [Inspect-MailboxStepMetrics.ps1](../../scripts/perf/Inspect-MailboxStepMetrics.ps1)

### 3.2 `addToWindow()` 的低风险写路径优化

随后对 `MessageWindowServiceImpl.addToWindow()` 做了两项低风险优化：

- `ZREMRANGEBYRANK` 从“每条消息都 trim”改为“每 8 条消息 trim 一次”
- `EXPIRE` 从“每条消息都刷新 TTL”改为“每 60 秒刷新一次”

对应代码位于：

- [MessageWindowServiceImpl.java](../../services/chat-service/src/main/java/com/flashchat/chatservice/service/impl/MessageWindowServiceImpl.java)

### 3.3 压测初始化从“房间级重建”改成“整轮筛选 + 重试”

为了消除 room-to-shard 碰撞带来的样本污染，压测初始化策略经历了两步：

1. 先尝试“创建房间后 close 重建”，结果证明会引入额外房间生命周期副作用，污染样本。
2. 之后改成“整轮创建 host-only rooms -> 先看 shard 布局 -> 不合格整轮废弃并重试”。

当前正式样本应以第二种方案为准。

相关脚本位于：

- [ten-room-init.js](../../perf/k6/lib/ten-room-init.js)
- [Run-TenRoom.ps1](../../scripts/perf/Run-TenRoom.ps1)
- [Invoke-K6Scenario.ps1](../../scripts/perf/Invoke-K6Scenario.ps1)

### 3.4 修复 WS listener 映射错误

旧版 `ten-room.js` 用全局 `__VU` 给 listener 分配房间，在多 scenario 模式下会导致：

- 部分房间没有 listener，`wsMessagesReceived / wsBroadcastP95 / wsBroadcastP99 = null`
- 部分房间出现 2 个 listener，`wsMessagesReceived ≈ sendSuccesses * 2`

后续已改成基于 `k6/execution` 的 scenario-local 编号，并补了实际 listener 登录计数。

修复后，新的正式样本都应该满足：

- `totalWsListenerLogins = 20`
- `roomsMissingWsListener = []`
- `roomsWithExtraWsListeners = []`

对应文件位于：

- [ten-room.js](../../perf/k6/ten-room.js)
- [Summarize-TenRoomResults.ps1](../../scripts/perf/Summarize-TenRoomResults.ps1)

## 4. 哪些结果可以作为正式依据

### 4.1 可以作为正式依据的样本

以下结果可作为这阶段分析的主要依据：

- [20260416-170616-ten-room-multi-room-20x50-230vu](../../perf/results/20260416-170616-ten-room-multi-room-20x50-230vu)
- [20260416-173834-ten-room-multi-room-20x50-230vu](../../perf/results/20260416-173834-ten-room-multi-room-20x50-230vu)
- [20260416-182923-ten-room-multi-room-20x50-230vu](../../perf/results/20260416-182923-ten-room-multi-room-20x50-230vu)
- [20260416-202534-ten-room-multi-room-20x50-250vu](../../perf/results/20260416-202534-ten-room-multi-room-20x50-250vu)
- [20260416-203826-ten-room-multi-room-20x50-250vu](../../perf/results/20260416-203826-ten-room-multi-room-20x50-250vu)

### 4.2 只能部分引用的样本

以下样本仍有参考价值，但只建议用于“吞吐/HTTP/mailbox 内部指标”，不建议继续拿它们做 WS 侧正式结论：

- [20260416-193354-ten-room-multi-room-20x50-250vu](../../perf/results/20260416-193354-ten-room-multi-room-20x50-250vu)
- [20260416-194822-ten-room-multi-room-20x50-250vu](../../perf/results/20260416-194822-ten-room-multi-room-20x50-250vu)

原因是它们发生在 WS listener 修复之前，WS 房间级观测仍受旧 bug 影响。

### 4.3 不应作为正式依据的样本

以下类型的样本不建议纳入正式结论：

- `setup shard guard` 拒绝的样本
- “创建房间后 close 重建”阶段产生的污染样本

典型例子：

- [20260416-163230-ten-room-multi-room-20x50-230vu](../../perf/results/20260416-163230-ten-room-multi-room-20x50-230vu)

## 5. 结果时间线与阶段性判断

### 5.1 unread 退出主矛盾后，干净的 `230 VU` 基线

在 `window_add` 优化之前，比较典型的干净 `230 VU` 样本是：

| 结果目录 | 吞吐 | HTTP p95 | WS p95 | queue wait avg | mailbox task avg | window_add.redis avg |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| [20260416-170616](../../perf/results/20260416-170616-ten-room-multi-room-20x50-230vu) | `609.23 msg/s` | `212.65ms` | `174ms` | `1.548ms` | `0.993ms` | `0.931ms` |

这一轮的意义是：

- unread 已经不是问题
- `window_add` 已经成为最慢 side-effect step
- `window_add` 的主要成本已经明确在 Redis，而不在序列化

### 5.2 `window_add` 频率优化后的 `230 VU`

做完“trim 每 8 条一次 + expire 每 60 秒一次”后，出现了两轮很关键的 `230 VU` 结果：

| 结果目录 | 吞吐 | HTTP p95 | WS p95 | queue wait avg | mailbox task avg | window_add.redis avg |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| [20260416-173834](../../perf/results/20260416-173834-ten-room-multi-room-20x50-230vu) | `709.70 msg/s` | `166.42ms` | `129ms` | `0.311ms` | `0.534ms` | `0.481ms` |
| [20260416-182923](../../perf/results/20260416-182923-ten-room-multi-room-20x50-230vu) | `753.67 msg/s` | `55.18ms` | `44ms` | `0.207ms` | `0.527ms` | `0.482ms` |

这两轮证明了两件事：

1. `window_add` 优化不是“运气样本”，而是真实有效。
2. `window_add.redis` 从 `0.931ms` 降到大约 `0.48ms`，是这阶段最重要的性能收益证据。

### 5.3 修复 WS listener 之后的可信 `250 VU`

修完 WS listener 映射之后，最新两轮 `250 VU` 可以作为当前最可信的高压样本：

| 结果目录 | 吞吐 | HTTP p95 | WS p95 | totalWsListenerLogins | roomsMissingWsListener | roomsWithExtraWsListeners |
| --- | ---: | ---: | ---: | ---: | --- | --- |
| [20260416-202534](../../perf/results/20260416-202534-ten-room-multi-room-20x50-250vu) | `689.01 msg/s` | `232.92ms` | `194ms` | `20` | `[]` | `[]` |
| [20260416-203826](../../perf/results/20260416-203826-ten-room-multi-room-20x50-250vu) | `771.81 msg/s` | `73.23ms` | `172ms` | `20` | `[]` | `[]` |

这说明：

- WS 观测链路现在已经基本可信
- 之前那种 `null` / `2x` 房间级统计，不应再出现在正式样本里

## 6. 当前瓶颈到底是什么

### 6.1 系统级瓶颈：mailbox shard 排队与并行度利用

从最新 `250 VU` 两轮看，系统级瓶颈已经可以更明确地写成：

> 高压下真正限制上沿的，是 mailbox 的 shard 并行度，以及 room-to-shard 布局导致的局部排队。

这两轮正好体现了两种不同的差法：

#### 样本 A：热点 shard 排队放大

[20260416-203826](../../perf/results/20260416-203826-ten-room-multi-room-20x50-250vu)：

- `queue wait avg = 15.359ms`
- `mailbox task avg = 1.415ms`
- `window_add.redis avg = 1.354ms`

这轮吞吐其实很高，但 `queue wait` 已经远大于 `task`，说明热点 shard 上出现了明显排队。

#### 样本 B：active shard 不足，并行度浪费

[20260416-202534](../../perf/results/20260416-202534-ten-room-multi-room-20x50-250vu) 的 shard 布局是：

- `0:3 1:4 2:4 3:3 4:4 5:0 6:0 7:2`

也就是：

- `maxRoomsPerShard` 虽然没有超过 `4`
- 但 `shard 5` 和 `shard 6` 完全空了
- 实际只吃到了 `6` 个活跃 shard

这轮里 mailbox 内部指标并不差：

- `queue wait avg = 0.29ms`
- `mailbox task avg = 0.595ms`
- `window_add.redis avg = 0.544ms`

但全局吞吐仍然只有 `689.01 msg/s`。  
这不是 mailbox 单任务变慢，而是整体并行度没有吃满。

### 6.2 shard 内部最重的步骤：`window_add.redis`

不管看 `230` 还是 `250`，当前 shard 内部最慢的活跃步骤都还是 `window_add`，而且它的主耗时都在 Redis 相位。

几组关键样本：

| 结果目录 | mailbox task avg | window_add avg | window_add.redis avg |
| --- | ---: | ---: | ---: |
| [20260416-170616](../../perf/results/20260416-170616-ten-room-multi-room-20x50-230vu) | `0.993ms` | `0.942ms` | `0.931ms` |
| [20260416-173834](../../perf/results/20260416-173834-ten-room-multi-room-20x50-230vu) | `0.534ms` | `0.491ms` | `0.481ms` |
| [20260416-182923](../../perf/results/20260416-182923-ten-room-multi-room-20x50-230vu) | `0.527ms` | `0.491ms` | `0.482ms` |
| [20260416-202534](../../perf/results/20260416-202534-ten-room-multi-room-20x50-250vu) | `0.595ms` | `0.553ms` | `0.544ms` |
| [20260416-203826](../../perf/results/20260416-203826-ten-room-multi-room-20x50-250vu) | `1.415ms` | `1.364ms` | `1.354ms` |

这组数说明：

- `window_add` 仍然几乎决定了 mailbox task 的主体成本
- `window_add` 里几乎全部成本仍然在 Redis
- 我们前一轮优化已经很有效，但还没有把这个点彻底打掉

### 6.3 不是当前主瓶颈的点

#### unread

最新样本里 unread 仍然是：

- `count = 0`
- `avgMs = null`

所以 unread 当前不应再继续投入分析时间。

#### broadcast.flush

最新强样本里 `broadcast.flush` 大致在：

- `0.014ms`
- `0.015ms`
- `0.021ms`

这个量级和 `window_add.redis` 相比差了一个数量级以上，当前不应把它当主线。

## 7. 当前怎么定义 `230 / 250 VU`

### 7.1 `230 VU`

当前版本下，`230 VU` 已经不再只是“偶发好样本档”，而是明显强于 unread 优化前的状态。

但它是否已经能完全视为“稳定档”，仍应保守一些，原因是：

- 仍然会受 shard 布局影响
- 目前还没有足够多轮次证明它在严格 guard 下长期稳定

### 7.2 `250 VU`

当前版本下，`250 VU` 已经能打出较高上沿：

- 可信样本已到 `771.81 msg/s`
- 旧样本上沿甚至接近 `800 msg/s`

但工程判断上仍应把它定义为：

> 高位上沿 / 临界档，而不是稳定容量值。

原因不是系统完全顶不动，而是：

- 布局差时会明显掉到 `689 msg/s` 左右
- 布局好时能冲到 `770+`
- 波动仍然偏大

## 8. 当前最合理的工程结论

到这一步，可以把当前结论压缩成下面这几条：

1. unread 优化已经完成历史使命，不再是当前主矛盾。
2. `window_add` 优化已经证明有效，尤其是 `window_add.redis` 已经明显下降。
3. 当前全局瓶颈是 mailbox shard 排队和 shard 并行度利用问题。
4. 当前局部热点仍然是 `MessageWindowServiceImpl.addToWindow()` 的 Redis 写路径。
5. 当前 `250 VU` 可以视为新的上沿探索档，但还不应直接当成稳定容量值。

## 9. 建议的下一步

按优先级，我建议下一步只做两条主线，不要再分散：

### 9.1 测试侧

继续收紧 shard guard，避免把“空 shard 样本”放进正式结果：

- 保留 `maxRoomsPerShard <= 4`
- 新增 `activeShardCount >= 7`
  - 或等价写法：`emptyShardCount <= 1`

并在结果汇总里固定输出：

- `activeShardCount`
- `emptyShardCount`
- `perShardRoomCount`
- `perShardSendSuccessesPerSec`

### 9.2 代码侧

继续围绕 `MessageWindowServiceImpl.addToWindow()` 做下一刀：

- 保留现在的“每 8 条 trim 一次 / 每 60 秒 expire 一次”策略
- 考虑把 `ZADD + 条件 trim + 条件 expire` 合成 Lua

原因很简单：

- 频率优化已经做完一轮
- 当前剩下的主耗时仍集中在 Redis 侧
- 这时最合理的后续方向，就是继续压 `window_add.redis`

## 10. 后续压测建议

后续继续验证时，建议只跑 mailbox probe：

```powershell
powershell -ExecutionPolicy Bypass -File .\scripts\perf\Run-MultiRoom-20x50-MailboxProbe.ps1 -SenderVus <VU> -Runner local -Build
```

建议的顺序是：

1. 先跑 `230 VU`，确认在更严格 guard 下能否继续稳定复现高样本。
2. 再跑 `250 VU`，继续观察上沿是否能在更干净的 shard 布局下更稳定复现。

