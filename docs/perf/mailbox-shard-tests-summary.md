# Mailbox Shard 相关测试总结

## 1. 文档目的

这份文档只聚焦一条主线：

- `RoomSideEffectMailbox` 的 `shard` 从 `4` 调到 `8` 之后
- 在 `20 房间 x 50 人` 模型下
- 系统的吞吐、WS 广播延迟、mailbox 堆积、稳定性到底发生了什么变化

这份文档也补充了后续两类关键验证：

- mailbox step probe：到底是 mailbox 里的哪一步在拖慢
- unread 改造：把 unread 从写时 fan-out 改成读时 DB 统计之后，瓶颈有没有发生迁移

## 2. 统一测试口径

除特别说明外，本文所有结果都基于下面这套口径：

- 应用入口：`aggregation-service`
- profile：`local-perf,local-perf-room-unlimited`
- 资源口径：本地 `2核4G` 模拟
- `fresh`：每轮尽量 fresh 重启
- `unlimited`：只放宽 `room-global`，不关闭其他保护
- 压测模型：`20` 个房间、每房 `50` 人、总参与者 `1000`
- `ACK / HISTORY`：弱化
- sender：固定绑定房间，不跨房间跳转
- 房间内发送者：轮转使用房间内参与者 token，避免过早撞到 `user-room`

相关脚本：

- 通用执行器：[Run-TenRoom.ps1](/d:/javaproject/FlashChat/scripts/perf/Run-TenRoom.ps1)
- 20x50 压测入口：
  - [Run-MultiRoom-20x50-120.ps1](/d:/javaproject/FlashChat/scripts/perf/Run-MultiRoom-20x50-120.ps1)
  - [Run-MultiRoom-20x50-150.ps1](/d:/javaproject/FlashChat/scripts/perf/Run-MultiRoom-20x50-150.ps1)
  - [Run-MultiRoom-20x50-160.ps1](/d:/javaproject/FlashChat/scripts/perf/Run-MultiRoom-20x50-160.ps1)
  - [Run-MultiRoom-20x50-175.ps1](/d:/javaproject/FlashChat/scripts/perf/Run-MultiRoom-20x50-175.ps1)
  - [Run-MultiRoom-20x50-175-MailboxProbe.ps1](/d:/javaproject/FlashChat/scripts/perf/Run-MultiRoom-20x50-175-MailboxProbe.ps1)
  - [Run-MultiRoom-20x50-190.ps1](/d:/javaproject/FlashChat/scripts/perf/Run-MultiRoom-20x50-190.ps1)
- k6 场景：
  - [ten-room.js](/d:/javaproject/FlashChat/perf/k6/ten-room.js)
  - [ten-room-init.js](/d:/javaproject/FlashChat/perf/k6/lib/ten-room-init.js)

## 3. 与 mailbox 相关的代码改动

### 3.1 shard 数从 4 调到 8

关键代码：

- [RoomSideEffectMailbox.java](/d:/javaproject/FlashChat/services/chat-service/src/main/java/com/flashchat/chatservice/service/dispatch/RoomSideEffectMailbox.java)
- [MailboxProperties.java](/d:/javaproject/FlashChat/services/chat-service/src/main/java/com/flashchat/chatservice/config/MailboxProperties.java)
- [FlashChatPropertiesConfig.java](/d:/javaproject/FlashChat/services/chat-service/src/main/java/com/flashchat/chatservice/config/FlashChatPropertiesConfig.java)
- [application-local-perf.yaml](/d:/javaproject/FlashChat/services/aggregation-service/src/main/resources/application-local-perf.yaml)

设计语义没有变：

- `roomId -> fixed shard`
- 每个 shard 单线程
- 同房间副作用仍然串行执行

变化是：

- `4 shard -> 8 shard`
- 降低多房间模型下热点房间碰撞到同一个 shard 的概率

### 3.2 mailbox 步骤级埋点

为了确认“mailbox 里到底哪一步最慢”，补了如下指标：

- `chat.mailbox.queue.wait.duration`
- `chat.mailbox.task.duration`
- `chat.side_effect.step.duration`
  - `window_add`
  - `broadcast`
  - `touch`
  - `unread`
- `flashchat.broadcast.serialize.duration`
- `flashchat.broadcast.write.duration`
- `flashchat.broadcast.flush.duration`

相关代码：

- [RoomSideEffectMailbox.java](/d:/javaproject/FlashChat/services/chat-service/src/main/java/com/flashchat/chatservice/service/dispatch/RoomSideEffectMailbox.java)
- [MessageSideEffectServiceImpl.java](/d:/javaproject/FlashChat/services/chat-service/src/main/java/com/flashchat/chatservice/service/impl/MessageSideEffectServiceImpl.java)
- [RoomChannelManager.java](/d:/javaproject/FlashChat/services/chat-service/src/main/java/com/flashchat/chatservice/websocket/manager/RoomChannelManager.java)
- 诊断脚本：[Inspect-MailboxStepMetrics.ps1](/d:/javaproject/FlashChat/scripts/perf/Inspect-MailboxStepMetrics.ps1)

### 3.3 unread 改造

后续为了验证 unread 是否是旧瓶颈的一部分，又做了一个最小改动：

- 保留原来的代码和注释，不直接删除
- 停止在发送副作用路径里执行 unread 的写时 fan-out
- unread 改为基于 `room_id + last_ack_msg_id + status=0` 的读时 DB 统计

相关代码：

- [UnreadServiceImpl.java](/d:/javaproject/FlashChat/services/chat-service/src/main/java/com/flashchat/chatservice/service/impl/UnreadServiceImpl.java)
- [MessageSideEffectServiceImpl.java](/d:/javaproject/FlashChat/services/chat-service/src/main/java/com/flashchat/chatservice/service/impl/MessageSideEffectServiceImpl.java)

## 4. 基础结果：shard=4 与 shard=8 的对比

### 4.1 `20x50x160`

| shard | 结果目录 | 总吞吐 msg/s | HTTP p95 | HTTP p99 | WS p95 | WS p99 | roomSkew | mailboxBacklogMax | 结论 |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| `4` | [20260415-141131-ten-room-multi-room-20x50-160vu](/d:/javaproject/FlashChat/perf/results/20260415-141131-ten-room-multi-room-20x50-160vu) | `503.228` | `46.346ms` | `120.134ms` | `1900ms` | `5449ms` | `1.302` | `1746` | 越线 |
| `8` 第 1 轮 | [20260415-172204-ten-room-multi-room-20x50-160vu](/d:/javaproject/FlashChat/perf/results/20260415-172204-ten-room-multi-room-20x50-160vu) | `526.906` | `38.409ms` | `82.657ms` | `94ms` | `710ms` | `1.297` | `404` | 可用 |
| `8` 第 2 轮 | [20260415-173759-ten-room-multi-room-20x50-160vu](/d:/javaproject/FlashChat/perf/results/20260415-173759-ten-room-multi-room-20x50-160vu) | `501.977` | `53.048ms` | `116.417ms` | `213ms` | `643ms` | `1.306` | `296` | 可用 |

结论：

- `shard=4` 时，`160 VU` 已经明显越线
- `shard=8` 后，`160 VU` 被拉回到了可运行区
- 这证明 shard 增加确实在缓解 mailbox / 副作用链路的局部拥塞

### 4.2 `20x50x175`，仅看 shard=8 之前

| shard | 结果目录 | 总吞吐 msg/s | 成功率 | HTTP p95 | HTTP p99 | WS p95 | WS p99 | roomSkew | mailboxBacklogMax | 备注 |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| `4` | [20260415-132341-ten-room-multi-room-20x50-175vu](/d:/javaproject/FlashChat/perf/results/20260415-132341-ten-room-multi-room-20x50-175vu) | `515.899` | `1.0` | `88.109ms` | `151.906ms` | `6389ms` | `7980.43ms` | `1.262` | `3075` | 旧版明显失控 |
| `8` 第 1 轮 | [20260415-180031-ten-room-multi-room-20x50-175vu](/d:/javaproject/FlashChat/perf/results/20260415-180031-ten-room-multi-room-20x50-175vu) | `566.255` | `1.0` | `37.006ms` | `74.406ms` | `108ms` | `398ms` | `1.268` | `231` | 最好样本 |
| `8` 第 2 轮 | [20260415-181319-ten-room-multi-room-20x50-175vu](/d:/javaproject/FlashChat/perf/results/20260415-181319-ten-room-multi-room-20x50-175vu) | `530.524` | `1.0` | `134.552ms` | `248.899ms` | `1177ms` | `3309ms` | `1.267` | `1074` | 临界区抖动 |
| `8` 第 3 轮 | [20260415-201650-ten-room-multi-room-20x50-175vu](/d:/javaproject/FlashChat/perf/results/20260415-201650-ten-room-multi-room-20x50-175vu) | `465.273` | `1.0` | `262.403ms` | `344.791ms` | `240ms` | `429ms` | `1.277` | `120` | 节奏变慢 |
| `8` 第 4 轮 | [20260415-202849-ten-room-multi-room-20x50-175vu](/d:/javaproject/FlashChat/perf/results/20260415-202849-ten-room-multi-room-20x50-175vu) | `530.918` | `0.999349` | `57.398ms` | `100.852ms` | `271ms` | `704ms` | `1.275` | `400` | 有 transport timeout |

结论：

- `shard=8` 相比 `shard=4` 已明显改善
- 但 `175 VU` 仍在临界区，波动很大
- 这一阶段还不能把 `175 VU` 当成稳定值

## 5. mailbox probe 证明了什么

### 5.1 旧版 probe：真正先慢的不是 broadcast

旧 probe 结果：

- [20260415-222452-ten-room-multi-room-20x50-175vu](/d:/javaproject/FlashChat/perf/results/20260415-222452-ten-room-multi-room-20x50-175vu)
- [20260415-224051-ten-room-multi-room-20x50-175vu](/d:/javaproject/FlashChat/perf/results/20260415-224051-ten-room-multi-room-20x50-175vu)
- [20260415-225738-ten-room-multi-room-20x50-175vu](/d:/javaproject/FlashChat/perf/results/20260415-225738-ten-room-multi-room-20x50-175vu)

其中一轮典型值：

- `queue wait avg = 1378.656ms`
- `task duration avg = 5.613ms`
- `unread avg = 2.946ms`
- `window_add avg = 2.614ms`
- `broadcast avg = 0.041ms`

结论很明确：

- 先慢的是 `mailbox queue wait`
- 单个任务里最重的是 `unread`
- 其次是 `window_add`
- 不是 `broadcast write/flush` 本身先慢

### 5.2 旧版根因的准确表述

旧版真正的问题不是“广播写不出去”，而是：

1. 一条消息进入 mailbox 后，会在同一个串行任务中依次执行：
   - `window_add`
   - `broadcast`
   - `touch`
   - `unread`
2. `unread` 虽然排在最后，但它会显著拉长整个任务时长
3. 一个任务变长，下一条消息的任务就更晚开始
4. 于是 shard 队列等待上升
5. 最终放大成 WS 秒级延迟

所以旧版的第一瓶颈应表述为：

> mailbox shard 排队是最早暴露出来的现象，而排队背后的主要耗时来源是 `UnreadService.incrementUnread()`，其次是 `MessageWindowService.addToWindow()`。

## 6. unread 改造后的结果

### 6.1 改造内容

本轮最关键的验证是：

- 停止在发送副作用路径中执行 unread 的写时 fan-out
- unread 改成基于 `room_id + last_ack_msg_id + status=0` 的读时 DB 统计

注意：

- 这不是顺序调整
- 不是把 `window_add` 挪到了 `unread` 前面
- 原本顺序就是 `window_add -> broadcast -> touch -> unread`
- 只是移除了尾部的 unread fan-out 写入

### 6.2 改造后的 `20x50x175`

| 类型 | 结果目录 | 总吞吐 msg/s | HTTP p95 | HTTP p99 | WS p95 | WS p99 | 关键说明 |
| --- | --- | ---: | ---: | ---: | ---: | ---: | --- |
| 改造后 Probe 第 1 轮 | [20260416-002210-ten-room-multi-room-20x50-175vu](/d:/javaproject/FlashChat/perf/results/20260416-002210-ten-room-multi-room-20x50-175vu) | `534.739` | `60.407ms` | `288.092ms` | `79ms` | `379.34ms` | 首轮有效结果，WS 已从秒级恢复到百毫秒级 |
| 改造后 Probe 第 2 轮 | [20260416-003417-ten-room-multi-room-20x50-175vu](/d:/javaproject/FlashChat/perf/results/20260416-003417-ten-room-multi-room-20x50-175vu) | `559.471` | `44.988ms` | `104.562ms` | `57ms` | `167ms` | 当前最优样本 |

### 6.3 改造前后最关键的对比

改造前典型 probe：
- [20260415-225738-ten-room-multi-room-20x50-175vu](/d:/javaproject/FlashChat/perf/results/20260415-225738-ten-room-multi-room-20x50-175vu)

关键指标：
- `queue wait avg = 1378.656ms`
- `task duration avg = 5.613ms`
- `unread avg = 2.946ms`
- `window_add avg = 2.614ms`
- WS `p95 = 6608ms`

改造后最好样本：
- [20260416-003417-ten-room-multi-room-20x50-175vu](/d:/javaproject/FlashChat/perf/results/20260416-003417-ten-room-multi-room-20x50-175vu)

关键指标：
- `queue wait avg = 2.194ms`
- `task duration avg = 1.394ms`
- `unread = 0`
- `window_add avg = 1.34ms`
- WS `p95 = 57ms`

结论：

1. unread 写时 fan-out 被证明是旧版瓶颈的重要组成部分。
2. unread 退出发送副作用路径后，mailbox queue wait 几乎被打平。
3. broadcast 写出本身仍然不是第一根因。
4. 当前新的最慢活跃步骤已经变成 `window_add`。

## 7. 为什么之前没有这么严重，现在 `190 VU` 会直接炸

### 7.1 不是顺序问题

必须先说明：

- 不是“加入窗口与统计未读数之间的顺序错了”
- 不是“优化 unread 后把顺序改坏了”

原因是：

- 原来顺序本来就是 `window_add -> broadcast -> touch -> unread`
- 现在只是移除了 `unread`
- 前面的顺序完全没变

所以这不是顺序 bug。

### 7.2 真实原因：旧瓶颈消失后，新瓶颈暴露了

更准确的解释是：

1. 旧版里，`unread` 在同一个 mailbox 串行任务的尾部，但它显著拉长了整个任务时长。
2. 任务一旦变长，shard 队列就更容易堆积。
3. 去掉 unread fan-out 后，每个任务明显变短了。
4. worker 更快处理完一个任务，系统就有能力把更多真实流量继续推到 `window_add`。
5. 于是 `window_add` 成为新的主要活跃成本。

所以现在的现象不是：

- “优化 unread 以后系统变坏了”

而是：

- “优化 unread 以后，系统终于有能力撞到下一个瓶颈了”

### 7.3 `190 VU` 的直接证据

结果目录：
- [20260416-093906-ten-room-multi-room-20x50-190vu](/d:/javaproject/FlashChat/perf/results/20260416-093906-ten-room-multi-room-20x50-190vu)

这轮不是 setup 失败，而是：

- setup 成功
- 已经进入真实发送阶段
- 运行约 30 秒后开始大量：
  - `POST /chat/msg request timeout`
- 随后 runtime poll 变成：
  - `health=UNKNOWN`
  - `dbHealth=UNKNOWN`
  - `redisHealth=UNKNOWN`
- 再往后出现：
  - `connectex: actively refused`

并且这轮没有产出：

- `summary.json`
- `ten-room-summary.json`

所以它不能作为正式容量结果引用，但可以作为“越线信号”的证据。

### 7.4 对 `190 VU` 的正确结论

`190 VU` 这轮说明的不是“只把 WS 延迟打高了”，而是：

- 在 unread 退出发送副作用路径后
- 更高的流量开始持续打到 `window_add` 和后续整体请求处理链路
- 系统已经不再只是轻度 mailbox 堆积
- 而是进入了请求超时、健康检查失败、端口拒绝连接的服务级不稳定区

因此，当前版本下：

- `175 VU`：已经可以跑出较健康结果
- `190 VU`：已经明显越线

## 8. 更新后的阶段性判断

基于 mailbox shard 相关全部结果，目前更准确的判断是：

1. `shard=4 -> 8` 是有效优化，能明显缓解多房间模型下的局部 shard 碰撞。
2. 旧版第一瓶颈不是 broadcast，而是 mailbox queue wait；而 queue wait 背后的主要耗时来源是 `unread`，其次是 `window_add`。
3. unread 改成读时 DB 统计之后，`20x50x175` 的健康度显著改善，WS 延迟从秒级回到了百毫秒级。
4. 当前新的第一候选瓶颈已经迁移到 `MessageWindowService.addToWindow()`。
5. `20x50x190` 已明显越线，说明当前版本的稳定上沿已经逼近 `175 ~ 190 VU` 之间。

## 9. 当前最合理的工程结论

当前这版代码下，可以把结论写成：

> 旧版第一瓶颈是 unread 写时 fan-out；把 unread 从发送副作用路径中移除后，mailbox queue wait 和 WS 广播延迟都大幅改善，系统在 `20x50x175` 下已经能稳定运行；但这次优化也把新的瓶颈暴露了出来，当前最慢的活跃步骤已经迁移到 `MessageWindowService.addToWindow()`，而 `20x50x190` 已经进入请求超时和服务级不稳定区。

## 10. 下一步建议

按优先级建议：

1. 优先分析和优化 `MessageWindowService.addToWindow()`
2. 继续用 `20x50x175 mailbox probe` 做 A/B 验证
3. 如果 `window_add` 优化后仍有局部 shard 过热，再评估 `8 -> 12`
4. 在当前版本下，不建议把 `190 VU` 当作稳定档位继续使用
