# 20 房间 x 50 人多房间压测记录

## 1. 文档目的

这份文档汇总了 `20` 个房间、每房 `50` 人模型下的多轮压测结果，并完整记录：

- 我是怎么测试的
- k6 脚本是怎么建模的
- 房间内消息是怎么发送的
- 用户是怎么分配到房间和发送循环里的
- 房间与房间之间有没有顺序和依赖
- 每一轮的关键指标、瓶颈表现和结论
- `mailbox shard=4` 与 `mailbox shard=8` 的前后对比

这轮测试的目标不是单热点房间，而是更接近“更多房间 + 更大房间”的系统总容量验证。

## 2. 测试口径

统一口径如下：

- `fresh`：每轮尽量 fresh 重启应用
- `unlimited`：只把 `room-global.max-count` 提到极大值，不关闭其他保护
- profile：`local-perf,local-perf-room-unlimited`
- JVM：`2核4G` 本地模拟口径
- `ACK/HISTORY`：弱化，避免引入额外变量
- `room-global`：不应成为本轮主瓶颈

执行脚本：

- 专用入口：
  - `scripts/perf/Run-MultiRoom-20x50-120.ps1`
  - `scripts/perf/Run-MultiRoom-20x50-150.ps1`
  - `scripts/perf/Run-MultiRoom-20x50-160.ps1`
  - `scripts/perf/Run-MultiRoom-20x50-175.ps1`
- 通用执行器：
  - `scripts/perf/Run-TenRoom.ps1`
- 场景脚本：
  - `perf/k6/ten-room.js`
  - `perf/k6/lib/ten-room-init.js`

## 3. 我是怎么测试的

### 3.1 启动方式

每轮通过 `Run-MultiRoom-20x50-*.ps1` 调用 `Run-TenRoom.ps1`，由后者统一完成：

- 停旧应用并启动新应用
- 应用预热 `60s`
- 调用 `Invoke-K6Scenario.ps1`
- 打开运行时采样与阈值守护
- 自动产出 `summary.json`、`ten-room-summary.json`、运行时采样和诊断文件

`Run-TenRoom.ps1` 中和这轮模型直接相关的参数位置：

- 房间数：`TEN_ROOM_COUNT`
  - [Run-TenRoom.ps1](/d:/javaproject/FlashChat/scripts/perf/Run-TenRoom.ps1:116)
- 每房人数：`TEN_ROOM_PARTICIPANTS_PER_ROOM`
  - [Run-TenRoom.ps1](/d:/javaproject/FlashChat/scripts/perf/Run-TenRoom.ps1:117)
- 每房 WS 监听数：`TEN_ROOM_WS_LISTENERS_PER_ROOM`
  - [Run-TenRoom.ps1](/d:/javaproject/FlashChat/scripts/perf/Run-TenRoom.ps1:118)
- 发送节奏：`TEN_ROOM_SEND_THINK_MS=200`
  - [Run-TenRoom.ps1](/d:/javaproject/FlashChat/scripts/perf/Run-TenRoom.ps1:125)
- `ACK/HISTORY` 弱化：`999999`
  - [Run-TenRoom.ps1](/d:/javaproject/FlashChat/scripts/perf/Run-TenRoom.ps1:126)
  - [Run-TenRoom.ps1](/d:/javaproject/FlashChat/scripts/perf/Run-TenRoom.ps1:127)
- 预热等待：`AppWarmupSec`
  - [Run-TenRoom.ps1](/d:/javaproject/FlashChat/scripts/perf/Run-TenRoom.ps1:190)

### 3.2 k6 压测阶段

这轮统一使用 3 段：

- ramp-up：`30s`
- hold：`5m`
- ramp-down：`20s`

对应脚本位置：

- [ten-room.js](/d:/javaproject/FlashChat/perf/k6/ten-room.js:50)

### 3.3 运行时采样

每轮都会抓这些运行时指标：

- `health / dbHealth / redisHealth`
- `processCpuUsage`
- `mailboxBacklog`
- `hikariPending`
- `stripeLockTimeoutDelta`
- `xaddFailDelta`
- `persistFallbackDelta`
- `redisDegradationDelta`

所以判断瓶颈时，不是只看 k6 的 HTTP 结果，还会同时看运行时的 `mailboxBacklog`、WS 广播延迟和系统健康状态。

## 4. 脚本是怎么建模的

### 4.1 房间是怎么初始化的

初始化逻辑在 [ten-room-init.js](/d:/javaproject/FlashChat/perf/k6/lib/ten-room-init.js:17)：

- 先循环创建 `roomCount` 个房间
- 每个房间：
  - 先 `autoRegister()` 一个 host
  - host 调用 `createRoom()`
  - 再循环注册其余 guest
  - guest 逐个 `joinRoom()`

关键代码：

- 房间数和每房人数
  - [ten-room-init.js](/d:/javaproject/FlashChat/perf/k6/lib/ten-room-init.js:18)
  - [ten-room-init.js](/d:/javaproject/FlashChat/perf/k6/lib/ten-room-init.js:19)
- host 建房
  - [ten-room-init.js](/d:/javaproject/FlashChat/perf/k6/lib/ten-room-init.js:27)
- `maxMembers = participantsPerRoom + 5`
  - [ten-room-init.js](/d:/javaproject/FlashChat/perf/k6/lib/ten-room-init.js:30)
- guest 入房
  - [ten-room-init.js](/d:/javaproject/FlashChat/perf/k6/lib/ten-room-init.js:39)

在 `20x50` 模型下：

- 房间数：`20`
- 每房人数：`50`
- 总参与者：`1000`

### 4.2 房间内消息是怎么发送的

发送逻辑在 [ten-room.js](/d:/javaproject/FlashChat/perf/k6/ten-room.js:272)。

每个 sender VU 的行为是：

1. 先确定自己固定归属的房间
2. 再在该房间内轮转选一个参与者 token
3. 调用 `/api/FlashChat/v1/chat/msg`
4. 然后 sleep `TEN_ROOM_SEND_THINK_MS`

发送函数：

- [attemptSendMessage](/d:/javaproject/FlashChat/perf/k6/ten-room.js:197)
- 真正调用 `/chat/msg` 的地方在 [ten-room.js](/d:/javaproject/FlashChat/perf/k6/ten-room.js:203)

### 4.3 用户是怎么分配发送消息的

#### sender 与房间的绑定

房间绑定逻辑：

- [getSenderRoomIndex](/d:/javaproject/FlashChat/perf/k6/ten-room.js:240)

公式是：

```js
(__VU - 1) % roomCount
```

这意味着：

- sender 是固定绑定某个房间的
- 不会跨房间乱跳

#### sender 在房间内使用哪个用户身份

参与者选择逻辑：

- [getSenderParticipantIndex](/d:/javaproject/FlashChat/perf/k6/ten-room.js:244)

它会根据：

- 这个 VU 在本房间的序号
- 当前迭代次数 `__ITER`

做一个取模轮转。

所以效果是：

- 同一个房间内，不是永远一个用户在发
- 而是房间内多个参与者 token 轮流承担发送
- 这样可以尽量避免过早撞上 `user-room` 限流

### 4.4 房间与房间的发送顺序、联系

房间与房间之间没有全局发送顺序，也没有业务依赖。

也就是说：

- `room_01` 的发送不会等待 `room_02`
- `room_05` 的消息顺序和 `room_12` 没有因果关系
- 房间之间只是并发地共享应用的全局资源

房间之间真正的“联系”来自共享资源，而不是业务流程：

- 共享 JVM
- 共享 Netty / WebSocket 写出能力
- 共享数据库和 Redis
- 更关键的是：共享 `RoomSideEffectMailbox`

### 4.5 房间内顺序是谁保证的

房间内顺序不是靠 k6 保证，而是靠服务端保证。

核心机制：

- `RoomSerialLock`
- `RoomSideEffectMailbox`

`RoomSerialLock`：

- 固定 `64` 个 stripe
  - [RoomSerialLock.java](/d:/javaproject/FlashChat/services/chat-service/src/main/java/com/flashchat/chatservice/service/dispatch/RoomSerialLock.java:37)
- 用于保证同一 `roomId` 在关键区间内串行化
  - [RoomSerialLock.java](/d:/javaproject/FlashChat/services/chat-service/src/main/java/com/flashchat/chatservice/service/dispatch/RoomSerialLock.java:95)

`RoomSideEffectMailbox`：

- `roomId -> shard`
  - [RoomSideEffectMailbox.java](/d:/javaproject/FlashChat/services/chat-service/src/main/java/com/flashchat/chatservice/service/dispatch/RoomSideEffectMailbox.java:169)
- 每个 shard 单线程串行执行
  - [RoomSideEffectMailbox.java](/d:/javaproject/FlashChat/services/chat-service/src/main/java/com/flashchat/chatservice/service/dispatch/RoomSideEffectMailbox.java:79)
- backlog 指标：`chat.mailbox.backlog`
  - [RoomSideEffectMailbox.java](/d:/javaproject/FlashChat/services/chat-service/src/main/java/com/flashchat/chatservice/service/dispatch/RoomSideEffectMailbox.java:93)

这也是为什么在 `20x50` 模型里，会看到有些房间吞吐明显高一点、有些低一点：

- 不是房间逻辑不一样
- 而是不同房间会哈希到同一个 mailbox shard
- 共享同一个 shard 的房间更容易形成“分档”

## 5. 原始结果汇总

### 5.1 `20x50x120`

结果目录：

- [20260415-133549-ten-room-multi-room-20x50-120vu](/d:/javaproject/FlashChat/perf/results/20260415-133549-ten-room-multi-room-20x50-120vu)

关键数据：

- 总吞吐：`398.61 msg/s`
- 成功率：`100%`
- HTTP `p95=18.96ms`
- HTTP `p99=45.95ms`
- WS `p95=30ms`
- WS `p99=79ms`
- `roomSkew=1.43`
- `mailboxBacklog` 基本为 `0`，偶发小值 `1/2/3/13`

结论：

- 健康
- 明显处于稳定区
- 没有触发广播链路失控

### 5.2 `20x50x150`

结果目录：

- [20260415-135934-ten-room-multi-room-20x50-150vu](/d:/javaproject/FlashChat/perf/results/20260415-135934-ten-room-multi-room-20x50-150vu)

关键数据：

- 总吞吐：`487.29 msg/s`
- 成功率：`100%`
- HTTP `p95=25.61ms`
- HTTP `p99=62.27ms`
- WS `p95=66ms`
- WS `p99=345ms`
- `roomSkew=1.30`
- `mailboxBacklog` 最高约 `90`

结论：

- 健康
- 比 `120 VU` 有明显提升
- 仍然在稳定区
- 是 `shard=4` 时代最像稳定上沿的一轮

### 5.3 `20x50x160`，`shard=4`

结果目录：

- [20260415-141131-ten-room-multi-room-20x50-160vu](/d:/javaproject/FlashChat/perf/results/20260415-141131-ten-room-multi-room-20x50-160vu)

关键数据：

- 总吞吐：`503.23 msg/s`
- 成功率：`100%`
- HTTP `p95=46.35ms`
- HTTP `p99=120.13ms`
- WS `p95=1900ms`
- WS `p99=5449ms`
- `roomSkew=1.30`
- `mailboxBacklogMax=1746`

补充证据：

- 启动日志确认旧版是 `shards=4`
  - [flashchat-local-perf-20260415-141020.out.log](/d:/javaproject/FlashChat/perf/logs/flashchat-local-perf-20260415-141020.out.log:24)
- 旧日志中出现大量 `RoomMailbox提交被拒绝`

结论：

- 吞吐相比 `150 VU` 只多了一点
- 但 WS 广播已经明显失控
- 这轮已经不是稳定档

### 5.4 `20x50x175`，`shard=4`

结果目录：

- [20260415-132341-ten-room-multi-room-20x50-175vu](/d:/javaproject/FlashChat/perf/results/20260415-132341-ten-room-multi-room-20x50-175vu)

关键数据：

- 总吞吐：`515.90 msg/s`
- 成功率：`100%`
- HTTP `p95=88.11ms`
- HTTP `p99=151.91ms`
- WS `p95=6389ms`
- WS `p99=7980ms`
- `roomSkew=1.262`
- `mailboxBacklogMax=3075`

补充证据：

- 启动日志确认旧版是 `shards=4`
  - [flashchat-local-perf-20260415-132230.out.log](/d:/javaproject/FlashChat/perf/logs/flashchat-local-perf-20260415-132230.out.log:24)
- 旧日志里 `RoomMailbox提交被拒绝` 命中约 `14663` 次

结论：

- 明显越线
- 旧版的第一瓶颈就是 `mailbox / WS 广播处理链路`

## 6. `mailbox shard=8` 改动说明

这次不是只调压测脚本，而是直接改了副作用分片配置：

- 新增配置类：
  - [MailboxProperties.java](/d:/javaproject/FlashChat/services/chat-service/src/main/java/com/flashchat/chatservice/config/MailboxProperties.java)
- 注册配置：
  - [FlashChatPropertiesConfig.java](/d:/javaproject/FlashChat/services/chat-service/src/main/java/com/flashchat/chatservice/config/FlashChatPropertiesConfig.java)
- `RoomSideEffectMailbox` 改成可配置 shard 数和队列容量：
  - [RoomSideEffectMailbox.java](/d:/javaproject/FlashChat/services/chat-service/src/main/java/com/flashchat/chatservice/service/dispatch/RoomSideEffectMailbox.java)
- 本地 perf profile 默认改成 `shard-count: 8`
  - [application-local-perf.yaml](/d:/javaproject/FlashChat/services/aggregation-service/src/main/resources/application-local-perf.yaml)

这次调整的核心不是“让单个 shard 多线程”，而是：

- 仍然保持单 shard 串行
- 只是把不同房间分散到更多 shard 上

这意味着：

- 同房间顺序语义还在
- 多房间场景的互相争抢会减少

## 7. `mailbox shard=8` 后的新结果

### 7.1 `20x50x160`，`shard=8` 第 1 轮

结果目录：

- [20260415-172204-ten-room-multi-room-20x50-160vu](/d:/javaproject/FlashChat/perf/results/20260415-172204-ten-room-multi-room-20x50-160vu)

关键数据：

- 总吞吐：`526.91 msg/s`
- 成功率：`100%`
- HTTP `p95=38.41ms`
- HTTP `p99=82.66ms`
- WS `p95=94ms`
- WS `p99=710ms`
- `roomSkew=1.297`
- `mailboxBacklogMax=404`

结论：

- 相比旧版 `160 VU`，吞吐小幅提升
- 更重要的是 WS 从秒级回到了百毫秒级
- mailbox 背压明显减轻

### 7.2 `20x50x160`，`shard=8` 第 2 轮

结果目录：

- [20260415-173759-ten-room-multi-room-20x50-160vu](/d:/javaproject/FlashChat/perf/results/20260415-173759-ten-room-multi-room-20x50-160vu)

关键数据：

- 总吞吐：`501.98 msg/s`
- 成功率：`100%`
- HTTP `p95=53.05ms`
- HTTP `p99=116.42ms`
- WS `p95=213ms`
- WS `p99=643ms`
- `roomSkew=1.306`
- `mailboxBacklogMax=296`

结论：

- 吞吐比第 1 轮低一点，但仍显著优于旧版 `160`
- 没有再出现旧版那种 mailbox 彻底堆穿的情况
- 说明 `shard=8` 后，`160 VU` 已经从“越线档”回到了“可用档”

### 7.3 `20x50x175`，`shard=8` 第 1 轮

结果目录：

- [20260415-180031-ten-room-multi-room-20x50-175vu](/d:/javaproject/FlashChat/perf/results/20260415-180031-ten-room-multi-room-20x50-175vu)

关键数据：

- 总吞吐：`566.26 msg/s`
- 成功率：`100%`
- HTTP `p95=37.01ms`
- HTTP `p99=74.41ms`
- WS `p95=108ms`
- WS `p99=398ms`
- `roomSkew=1.268`
- `mailboxBacklogMax=231`

结论：

- 这是 `shard=8` 下非常好的样本
- 相比旧版 `175 VU`，几乎是从“不可用”拉回到了“可用”

### 7.4 `20x50x175`，`shard=8` 第 2 轮

结果目录：

- [20260415-181319-ten-room-multi-room-20x50-175vu](/d:/javaproject/FlashChat/perf/results/20260415-181319-ten-room-multi-room-20x50-175vu)

关键数据：

- 总吞吐：`530.52 msg/s`
- 成功率：`100%`
- HTTP `p95=134.55ms`
- HTTP `p99=248.90ms`
- WS `p95=1177ms`
- WS `p99=3309ms`
- `roomSkew=1.267`
- `mailboxBacklogMax=1074`

补充证据：

- [local-stderr.log](/d:/javaproject/FlashChat/perf/results/20260415-181319-ten-room-multi-room-20x50-175vu/local-stderr.log) 里出现了 `room_ws_broadcast_latency` 阈值越线

结论：

- 同样是 `shard=8 + 175 VU`
- 这轮已经表现出明显临界区抖动
- 说明 `175 VU` 还不是稳态甜点位

### 7.5 `20x50x175`，`shard=8` 第 3 轮

结果目录：

- [20260415-201650-ten-room-multi-room-20x50-175vu](/d:/javaproject/FlashChat/perf/results/20260415-201650-ten-room-multi-room-20x50-175vu)

关键数据：

- 总吞吐：`465.27 msg/s`
- 成功率：`100%`
- HTTP `p95=262.40ms`
- HTTP `p99=344.79ms`
- WS `p95=240ms`
- WS `p99=429ms`
- `roomSkew=1.277`
- `mailboxBacklogMax=120`

结论：

- 这轮不是 mailbox 再次爆掉，而是整体节奏变慢
- 吞吐掉下来了，但 WS 没回到秒级失控
- 说明 `175 VU` 处于边缘区，结果会受临界区抖动影响

### 7.6 `20x50x175`，`shard=8` 第 4 轮

结果目录：

- [20260415-202849-ten-room-multi-room-20x50-175vu](/d:/javaproject/FlashChat/perf/results/20260415-202849-ten-room-multi-room-20x50-175vu)

关键数据：

- 总吞吐：`530.92 msg/s`
- 成功率：`99.935%`
- HTTP `p95=57.40ms`
- HTTP `p99=100.85ms`
- WS `p95=271ms`
- WS `p99=704ms`
- `roomSkew=1.275`
- `mailboxBacklogMax=400`
- `sendTransportFailures=159`

补充证据：

- [local-stderr.log](/d:/javaproject/FlashChat/perf/results/20260415-202849-ten-room-multi-room-20x50-175vu/local-stderr.log) 里可以看到一批 `POST /chat/msg request timeout`

结论：

- 这轮更接近“中间档”
- 不像第 1 轮那么好，也没有第 2 轮那么坏
- 说明 `175 VU` 在 `shard=8` 下仍然会出现明显波动

## 8. 结果表

### 8.1 `20x50x160`

| 档位 | 结果目录 | 吞吐 msg/s | HTTP p95 | WS p95 | mailboxBacklogMax | 结论 |
| --- | --- | ---: | ---: | ---: | ---: | --- |
| `shard=4` | `20260415-141131...160vu` | `503.23` | `46.35ms` | `1900ms` | `1746` | 越线 |
| `shard=8` 第 1 轮 | `20260415-172204...160vu` | `526.91` | `38.41ms` | `94ms` | `404` | 可用 |
| `shard=8` 第 2 轮 | `20260415-173759...160vu` | `501.98` | `53.05ms` | `213ms` | `296` | 可用 |

### 8.2 `20x50x175`

| 档位 | 结果目录 | 吞吐 msg/s | HTTP p95 | WS p95 | mailboxBacklogMax | 备注 |
| --- | --- | ---: | ---: | ---: | ---: | --- |
| `shard=4` | `20260415-132341...175vu` | `515.90` | `88.11ms` | `6389ms` | `3075` | 旧版失控 |
| `shard=8` 第 1 轮 | `20260415-180031...175vu` | `566.26` | `37.01ms` | `108ms` | `231` | 最佳样本 |
| `shard=8` 第 2 轮 | `20260415-181319...175vu` | `530.52` | `134.55ms` | `1177ms` | `1074` | 临界区放大 |
| `shard=8` 第 3 轮 | `20260415-201650...175vu` | `465.27` | `262.40ms` | `240ms` | `120` | 节奏变慢 |
| `shard=8` 第 4 轮 | `20260415-202849...175vu` | `530.92` | `57.40ms` | `271ms` | `400` | 有发送超时 |

## 9. 现在的第一瓶颈到底是什么

当前能确认的更准确表述是：

- 第一瓶颈不是单纯“HTTP 发消息接口”
- 也不是 DB/Hikari/Redis 先炸
- 而是：`消息副作用处理链路`
- 其中最早暴露出来的压力信号，就是 `RoomSideEffectMailbox` 的排队和波动

也就是说，更准确的说法应是：

> 当前第一瓶颈是“消息副作用处理链路的背压”，其中 `RoomSideEffectMailbox` 的堆积和抖动是最早、最明显的外在信号，随后体现为 WS 广播延迟恶化，以及在边缘区出现的偶发发送超时。

这条链路的核心代码：

- 发消息主链路：
  - [ChatServiceImpl.java](/d:/javaproject/FlashChat/services/chat-service/src/main/java/com/flashchat/chatservice/service/impl/ChatServiceImpl.java)
- 副作用投递：
  - [MessageSideEffectServiceImpl.java](/d:/javaproject/FlashChat/services/chat-service/src/main/java/com/flashchat/chatservice/service/impl/MessageSideEffectServiceImpl.java)
- mailbox：
  - [RoomSideEffectMailbox.java](/d:/javaproject/FlashChat/services/chat-service/src/main/java/com/flashchat/chatservice/service/dispatch/RoomSideEffectMailbox.java)
- WS 广播：
  - [RoomChannelManager.java](/d:/javaproject/FlashChat/services/chat-service/src/main/java/com/flashchat/chatservice/websocket/manager/RoomChannelManager.java)

## 10. 为什么结果会波动这么大

因为 `175 VU` 现在已经处在 `20x50` 模型的临界区。

在这个区间里：

- 一轮可能比较顺，mailbox 只轻度堆积
- 另一轮可能更早出现超时或中度背压
- 然后整轮吞吐和尾延迟就会明显分叉

这不是脚本坏了，而是典型的“容量边缘区特征”。

从 `shard=8` 的 4 轮 `175 VU` 看，波动区间大致是：

- 最好样本：`566 msg/s`
- 中间样本：`530~531 msg/s`
- 较差样本：`465 msg/s`

所以：

- 最好的那轮不能直接当稳定值
- 更合适的做法是看多轮复跑后的中位数和波动区间

## 11. 当前结论

### 11.1 `mailbox shard=8` 的效果

已确认有效，而且不是偶然：

- `20x50x160` 从“越线档”拉回到了“可用档”
- `20x50x175` 从旧版的“明显失控”拉回到了“边缘可用档”
- 旧版最大问题是大量 `RoomMailbox提交被拒绝`
- 新版没有再出现旧版那种大规模拒绝

### 11.2 当前容量判断

在 `20x50` 模型下：

- `120 VU`：稳定
- `150 VU`：稳定
- `160 VU`：在 `shard=8` 后已经可用，且两轮都比较健康
- `175 VU`：在 `shard=8` 后明显改善，但仍处于临界区，复跑波动较大

所以当前更合理的口径是：

- 保守稳定值：`~500 msg/s`
- 正常稳定值：`~520-530 msg/s`
- 上沿参考值：`~560 msg/s`
- 不建议把 `175 VU` 的最佳样本直接当成长期稳定值

## 12. 一句话结论

`20 房间 x 50 人` 模型下，`mailbox shard=8` 已经显著改善了旧版的 `mailbox / WS 广播` 瓶颈：`160 VU` 被拉回到可用区，`175 VU` 不再像旧版那样直接失控，但它仍然处于容量边缘，稳定总吞吐更适合按 `~520-530 msg/s` 评估，而不是直接取最佳样本 `~566 msg/s`。
