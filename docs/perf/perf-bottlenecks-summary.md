# FlashChat 压测过程中遇到的瓶颈汇总

## 1. 文档目的

这份文档汇总本次本地 `2核4G` 模拟压测过程中遇到的主要瓶颈，明确说明：

- 瓶颈出现在什么测试场景下
- 表现是什么
- 造成原因是什么
- 这是“保护阈值瓶颈”还是“真实资源瓶颈”
- 对应的结果目录和代码依据在哪里

说明：

- 单热点房间容量和多房间总容量是两类不同问题，不能混为一谈。
- 某些“瓶颈”其实是业务保护主动拦截，不代表系统真实资源已经到顶。

---

## 2. 总结先看

本轮压测过程中，实际遇到过 4 类关键瓶颈：

1. 单热点房间的 `room-global` 房间级限流瓶颈
2. 单热点房间在放宽 `room-global` 后暴露出的 `user-room` 用户-房间级限流瓶颈
3. 单热点房间在 `fresh + unlimited` 口径下的真实容量拐点，表现为延迟主导型瓶颈
4. `10` 房间并发发送下的全局 `mailbox / WS 广播处理链路` 积压瓶颈

另外，还遇到过几类**测试执行层问题**，例如账号初始化注册冲突、Windows 进程停止权限问题、Docker `k6` 宿主机连通问题。这些不是业务容量瓶颈，但会影响测试执行。

---

## 3. 瓶颈总表

| 类别 | 场景 | 主要现象 | 根因 | 是否真实资源瓶颈 |
| --- | --- | --- | --- | --- |
| 房间级保护瓶颈 | 单热点房间 `breaking` 初轮 | 成功吞吐约被封顶在 `~33.3 msg/s` | `room-global.max-count=2000/60000ms` 先命中 | 否 |
| 用户-房间级保护瓶颈 | 单热点房间放宽 `room-global` 后、参与者较少时 | `sendUserRoomLimited` 大量增加 | `user-room.max-count=60/10000ms` 命中 | 否 |
| 单热点房间真实容量拐点 | `fresh + unlimited`，`45 / 60 / 75 VU` | 吞吐在 `~220 msg/s` 附近趋平，但 HTTP/WS 延迟快速恶化 | 热房间消息处理链路的延迟主导型饱和，GC 是最稳定压力信号 | 是 |
| 多房间全局广播链路瓶颈 | `10` 房间并发，`200 VU` | 总吞吐可到 `~686 msg/s`，但 WS 广播 `p95/p99` 秒级，`mailboxBacklog` 明显堆积 | 副作用队列与 WS 广播链路排队积压 | 是 |

---

## 4. 详细瓶颈说明

### 4.1 单热点房间的 `room-global` 限流瓶颈

#### 出现场景

- 场景：单热点房间 `breaking`
- 结果目录：`perf/results/20260413-234351-breaking`

#### 表现

- 热点房间发送成功吞吐被明显封顶
- 实际成功发送大约只在 `~33.3 msg/s`
- 大量发送被业务快速拒绝

#### 原因

在压测专用配置里，房间级全局限流开启：

- `flashchat.rate-limit.room-global.window-ms = 60000`
- `flashchat.rate-limit.room-global.max-count = 2000`

对应配置见：

- `services/aggregation-service/src/main/resources/application-local-perf.yaml:61-71`

当单个热点房间发送量超过 `2000/min` 后，会在业务层直接被限流器拦截，而不是继续走下游链路。

调用链：

- `ChatServiceImpl.sendMsg()`  
  `services/chat-service/src/main/java/com/flashchat/chatservice/service/impl/ChatServiceImpl.java:99`
- 限流检查  
  `services/chat-service/src/main/java/com/flashchat/chatservice/service/impl/ChatServiceImpl.java:116`
- 限流结果枚举  
  `services/chat-service/src/main/java/com/flashchat/chatservice/ratelimit/RateLimitResult.java:13-16`

#### 结论

这是**保护阈值瓶颈**，不是 JVM、DB、Redis、WS 的真实资源上限。

---

### 4.2 放宽 `room-global` 后暴露出的 `user-room` 限流瓶颈

#### 出现场景

- 场景：单热点房间，`room-global` 已放宽，且 `ACK / HISTORY` 基本关闭，但参与者数仍偏少
- 典型结果目录：`perf/results/20260414-125857-room-hotspot-room-20000`

#### 表现

- `sendSuccessesPerSec` 上去了一部分
- 但 `sendUserRoomLimited` 仍然大量出现
- 无法继续把热点房间推到真实资源上限

#### 原因

虽然 `room-global` 已经放宽，但用户-房间级限流仍然保留：

- `flashchat.rate-limit.user-room.window-ms = 10000`
- `flashchat.rate-limit.user-room.max-count = 60`

对应配置见：

- `services/aggregation-service/src/main/resources/application-local-perf.yaml:66-68`

当热点房间参与者太少、发送者过于集中时，同一个用户在同一房间内发送太频繁，会先命中 `user-room` 限流。

#### 结论

这也是**保护阈值瓶颈**，不是系统真实资源瓶颈。  
后续通过增加房间参与者数，保留保护不变，才避开了这层限制。

---

### 4.3 单热点房间的真实容量拐点

#### 出现场景

统一口径：

- `fresh + unlimited`
- 只放开 `room-global`
- 保留 `user-global`、`user-room`

关键结果目录：

- `perf/results/20260414-171709-room-hotspot-fresh-unlimited-stable-45vu-r2`
- `perf/results/20260414-173153-room-hotspot-fresh-unlimited-stable-60vu-r2`
- `perf/results/20260414-174334-room-hotspot-fresh-unlimited-stable-75vu-r2`

#### 表现

统一口径结果：

| 档位 | sendSuccessesPerSec | HTTP p95 | HTTP p99 | WS p95 | WS p99 |
| --- | ---: | ---: | ---: | ---: | ---: |
| 45 VU | `210.75 msg/s` | `312.28ms` | `391.37ms` | `170ms` | `234ms` |
| 60 VU | `220.29 msg/s` | `415.87ms` | `508.99ms` | `240ms` | `312ms` |
| 75 VU | `206.56 msg/s` | `581.67ms` | `752.91ms` | `390ms` | `526ms` |

特征非常典型：

- `45 -> 60 VU` 吞吐只多一点点，但尾延迟明显变差
- `60 -> 75 VU` 吞吐反而回落，但延迟继续恶化

#### 原因

这一阶段已经不再是限流主导，因为：

- 没有明显 `room-global` 命中
- 没有明显 `Hikari pending`
- 没有明显 `Redis XADD fail`
- 没有 `persist fallback`
- 没有明显 `mailbox backlog`
- 没有 `stripe lock timeout`

对应归纳文档见：

- `docs/perf/room-hotspot-round2-findings.md`

这意味着瓶颈更像是**单热点房间消息处理链路的延迟主导型饱和**：

- 同房间串行处理压力上升
- 对象分配 / 序列化 / 消息处理成本抬高
- GC 暂停成为最稳定的放大器

GC 证据：

- `perf/logs/flashchat-local-perf-20260414-131523.gc.log`
- `docs/perf/room-hotspot-round2-findings.md`

这一阶段观察到过：

- `MMU target violated`
- `G1 Humongous Allocation`
- `Evacuation Failure: Allocation`
- `jvm.gc.pause max ≈ 0.423s`

#### 结论

这是**单热点房间的真实容量拐点**。

可落地结论：

- 单热点房间真实容量上沿：`~220 msg/s`
- 单热点房间建议稳定值：`~210 msg/s`

---

### 4.4 10 房间并发场景下的全局 `mailbox / 广播处理链路` 瓶颈

#### 出现场景

统一口径：

- `10` 个房间同时活跃
- 每房间 `20` 个参与者
- `fresh + unlimited`
- `ACK / HISTORY` 基本弱化

关键结果目录：

- `perf/results/20260415-101604-ten-room-ten-room-100vu`
- `perf/results/20260415-105130-ten-room-ten-room-175vu`
- `perf/results/20260415-102633-ten-room-ten-room-200vu`
- `perf/results/20260415-110425-ten-room-ten-room-200vu`

#### 表现

关键结果：

| 档位 | 总吞吐 | HTTP p95 | HTTP p99 | WS p95 | WS p99 | 结论 |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| 100 VU | `361.83 msg/s` | `14.43ms` | `36.70ms` | `21ms` | `63ms` | 健康 |
| 175 VU | `599.11 msg/s` | `58.26ms` | `96.66ms` | `233ms` | `590ms` | 可视为稳定档 |
| 200 VU 第 1 次 | `674.17 msg/s` | `69.96ms` | `136.60ms` | `2938ms` | `5310ms` | 明显冲顶 |
| 200 VU 第 2 次 | `685.99 msg/s` | `55.03ms` | `116.23ms` | `3529ms` | `4574ms` | 再次复现冲顶现象 |

可以看到：

- 总吞吐显著高于单热点房间的 `~220 msg/s`
- 说明 `220 msg/s` 只是单房间上限，不是系统总上限
- 但在 `200 VU` 时，WS 广播延迟已经秒级恶化，不适合当稳定运行点

#### 原因

这轮最关键的运行时证据不是 DB 或 Redis，而是 `mailbox backlog`：

- `perf/results/20260415-110425-ten-room-ten-room-200vu/runtime-samples.jsonl`

采样里观察到：

- `mailboxBacklog: 21`
- `mailboxBacklog: 946`
- `mailboxBacklog: 2790`
- `mailboxBacklog: 2804`
- 后续才开始回落

这说明消息虽然已经被 `sendMsg()` 主链路接受，但“后续副作用”开始明显排队。

真实代码链路如下：

1. `sendMsg()` 主链路接收消息并完成 durable handoff  
   `services/chat-service/src/main/java/com/flashchat/chatservice/service/impl/ChatServiceImpl.java:99`  
   `services/chat-service/src/main/java/com/flashchat/chatservice/service/impl/ChatServiceImpl.java:186`  
   `services/chat-service/src/main/java/com/flashchat/chatservice/service/impl/ChatServiceImpl.java:239`

2. 成功后把副作用提交给 `RoomSideEffectMailbox`  
   `services/chat-service/src/main/java/com/flashchat/chatservice/service/impl/MessageSideEffectServiceImpl.java:95-103`  
   `services/chat-service/src/main/java/com/flashchat/chatservice/service/dispatch/RoomSideEffectMailbox.java:118`

3. `RoomSideEffectMailbox` 默认是：
   - `4` 个 shard  
     `services/chat-service/src/main/java/com/flashchat/chatservice/service/dispatch/RoomSideEffectMailbox.java:46`
   - 每个 shard 单线程串行执行
   - 每个队列容量 `1024`  
     `services/chat-service/src/main/java/com/flashchat/chatservice/service/dispatch/RoomSideEffectMailbox.java:47`
   - backlog 指标  
     `services/chat-service/src/main/java/com/flashchat/chatservice/service/dispatch/RoomSideEffectMailbox.java:92`

4. 副作用里包含：
   - 写消息窗口
   - WebSocket 广播
   - 更新活跃时间
   - 更新未读  
   `services/chat-service/src/main/java/com/flashchat/chatservice/service/impl/MessageSideEffectServiceImpl.java:99-103`

     5. WebSocket 广播真正执行位置：
        - `broadcastToRoom()`  
          `services/chat-service/src/main/java/com/flashchat/chatservice/websocket/manager/RoomChannelManager.java:465`
        - `doBroadcast()`  
          `services/chat-service/src/main/java/com/flashchat/chatservice/websocket/manager/RoomChannelManager.java:490`

                              FlashChat 消息发送与推送链路

         ┌────────────────────────────────────────────────────────────────────┐
         │ 1. 客户端发消息                                                     │
         │    HTTP POST /chat/msg                                             │
         └────────────────────────────────────────────────────────────────────┘
                                         │
                                         ▼
         ┌────────────────────────────────────────────────────────────────────┐
         │ 2. 发送主链路 sendMsg()                                             │
         │    - 参数校验                                                       │
         │    - 鉴权                                                           │
         │    - 房间/用户检查                                                  │
         │    - 消息接收/主流程处理                                            │
         │    - 返回发送成功                                                   │
         │                                                                    │
         │ 这一段决定：                                                        │
         │ - sendSuccessRate                                                  │
         │ - HTTP p95 / p99                                                   │
         └────────────────────────────────────────────────────────────────────┘
                                         │
                                         │ 主链路成功后
                                         │ 不等所有后续动作做完
                                         ▼
         ┌────────────────────────────────────────────────────────────────────┐
         │ 3. MessageSideEffectServiceImpl                                     │
         │    把“后续副作用”投递到 RoomSideEffectMailbox                       │
         │                                                                    │
         │ 副作用包括：                                                        │
         │ - 写消息窗口                                                        │
         │ - WebSocket 广播                                                    │
         │ - 更新活跃时间                                                      │
         │ - 更新未读                                                          │
         └────────────────────────────────────────────────────────────────────┘
                                         │
                                         ▼
         ┌────────────────────────────────────────────────────────────────────┐
         │ 4. RoomSideEffectMailbox                                            │
         │    = 后厨排队区 / 异步处理队列                                      │
         │                                                                    │
         │ 特点：                                                              │
         │ - 默认 4 个 shard                                                   │
         │ - 每个 shard 单线程串行消费                                         │
         │ - 每个队列容量 1024                                                 │
         │                                                                    │
         │ 这里决定：                                                          │
         │ - 是否开始排队                                                      │
         │ - chat.mailbox.backlog 是否上涨                                     │
         │ - chat.mailbox.submit.rejected 是否出现                             │
         └────────────────────────────────────────────────────────────────────┘
                                             │
                             ┌───────────────┼───────────────┐
                             │               │               │
                             ▼               ▼               ▼
                     ┌────────────┐   ┌────────────┐   ┌────────────┐
                     │ 写消息窗口 │   │ 更新未读   │   │ 更新活跃时间│
                     └────────────┘   └────────────┘   └────────────┘
                                             │
                                             ▼
         ┌────────────────────────────────────────────────────────────────────┐
         │ 5. RoomChannelManager                                               │
         │    WebSocket 广播                                                   │
         │    - write                                                          │
         │    - flush                                                          │
         │    - 判断 channel.isWritable                                        │
         └────────────────────────────────────────────────────────────────────┘
                                         │
                                         ▼
         ┌────────────────────────────────────────────────────────────────────┐
         │ 6. 在线用户收到消息                                                 │
         │                                                                    │
         │ 这一段决定：                                                        │
         │ - ws broadcast p95 / p99                                           │
         │ - “消息多久真正到达用户”                                            │
         └────────────────────────────────────────────────────────────────────┘


#### 结论

这是目前确认到的**新的全局真实瓶颈**：

- 不是房间限流
- 不是 `user-room`
- 不是 HTTP send 主链路
- 不是 DB 先打满
- 不是 Redis 先失败

而是：

- **消息副作用处理队列积压**
- **WebSocket 广播处理链路排队**
- 进而导致 **客户端收到消息的延迟失控**

也就是说：

- 系统还能“收进消息”
- 但开始“发不快到在线用户”

可落地结论：

- 10 房间建议稳定总吞吐：`~600 msg/s`
- 10 房间冲顶上沿：`~686 msg/s`
- `200 VU` 更适合看上限，不适合当稳定值

---

## 5. 测试执行层问题（非业务容量瓶颈）

这些问题不是系统真实容量瓶颈，但在压测过程中确实出现过，影响了执行稳定性。

### 5.1 批量初始化时账号注册唯一键冲突

#### 出现场景

- `175 VU` 初次运行失败
- 结果目录：`perf/results/20260415-104146-ten-room-ten-room-175vu`

#### 表现

- 压测没有进入发送阶段
- `setup()` 阶段直接失败
- `send_success_rate = 0`

#### 原因

应用日志显示：

- `Duplicate entry 'FC-2nTgsM' for key 't_account.uk_account_id'`

根因是：

- 注册逻辑通过随机算法生成 `accountId`
- 再结合 BloomFilter 预判是否存在
- 极低概率下仍可能撞到 DB 唯一键

相关代码：

- `autoRegister()`  
  `services/user-service/src/main/java/com/flashchat/userservice/service/impl/AccountServiceImpl.java:90`
- `doRegister()`  
  `services/user-service/src/main/java/com/flashchat/userservice/service/impl/AccountServiceImpl.java:485`
- `generateUniqueAccountId()`  
  `services/user-service/src/main/java/com/flashchat/userservice/service/impl/AccountServiceImpl.java:614`

#### 处理方式

没有修改业务逻辑，而是在压测脚本初始化层增加了有限重试：

- `perf/k6/lib/flow.js`
- `perf/k6/lib/config.js`

---

### 5.2 Windows 进程停止权限问题

#### 出现场景

- 重跑 `175 VU` 前尝试自动停旧应用

#### 表现

- `Stop-Process -Force` 返回 `Access denied`

#### 原因

- Windows 当前会话对旧 `java.exe` 进程的停止权限不足
- 不是应用性能问题

#### 处理方式

压测执行器增加了复用逻辑：

- 如果当前运行中的应用已经是 `local-perf + local-perf-room-unlimited + shared-host`
- 则直接复用当前进程继续测试

相关脚本：

- `scripts/perf/Run-TenRoom.ps1`

---

### 5.3 Docker `k6` 无法访问宿主机应用

#### 出现场景

- 本地尝试使用 Docker 版 `k6` 运行时

#### 表现

- 容器侧访问宿主机 `8081/8090` 失败
- 出现 `connection refused`

#### 原因

- 当前 Windows + Docker Desktop 网络环境下，容器到宿主机应用的连通性不稳定
- 不是业务代码性能问题

#### 处理方式

后续实压统一使用 repo 内的本地 `k6.exe`：

- `tools/k6/current/k6.exe`

---

## 6. 最终可复用结论

### 单热点房间

- 建议稳定值：`~210 msg/s`
- 真实容量上沿：`~220 msg/s`
- 第一真实瓶颈：延迟主导型饱和，GC 是最稳定压力信号

### 10 房间分散流量

- 建议稳定总吞吐：`~600 msg/s`
- 冲顶上沿：`~686 msg/s`
- 第一真实瓶颈：`mailbox / WebSocket 广播处理链路` 积压

### 不应混淆的两件事

1. 单房间 `220 msg/s` 不是系统总上限  
2. `200 VU` 多房间的 `~686 msg/s` 不是建议长期稳定值

---

## 7. 推荐后续优化方向

如果只做最优先的 3 个优化，建议按下面顺序：

1. 优化 `RoomSideEffectMailbox` 与广播处理链路
   - 重点看 shard 数、队列容量、任务拆分策略、广播与未读是否需要进一步解耦

2. 继续细化 WS 广播指标
   - 区分“进入 mailbox 前”“mailbox 等待中”“真正 flush 到 channel 时”的耗时

3. 对单热点房间链路继续做对象分配 / 序列化 / GC 压力优化
   - 单房间瓶颈仍然是和多房间总容量不同的一类问题，不能被总吞吐掩盖
