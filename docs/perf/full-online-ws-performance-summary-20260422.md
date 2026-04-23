# FlashChat 全员在线 WS 压测总结

更新时间：2026-04-22

## 1. 结论先行

本轮两周压测已经得到几个稳定结论：

- `unread` 已退出主矛盾，旧的写时 fan-out 不再是当前瓶颈。
- 用户消息的 `window_add` 已成功从 mailbox 搬到 `send` 主链路，并通过 `XADD + window_add + trim + expire` 的合并 Lua 执行。
- 当前服务端最重要的两个阶段热点已经收敛为：
  - `send` 侧：`msg.xadd_window.merged`
  - `broadcast` 侧：`broadcast.batch_complete` / `broadcast.write_complete`
- 在本地 `2C4G`、同机 `app + Redis + MySQL + k6` 的环境里，`1000 listeners` 的全员在线端到端 `WS P95/P99` 不能作为服务端改动的硬验收标准。
- 不是服务端广播完全不行，而是“同机端到端尾延迟”会被 fan-out 规模、k6 listener 调度、本机 TCP 栈和资源竞争明显放大。
- 目前最可信的本地同机基线是：
  - 健康基线：`120 VU / 20 listeners per room`
  - 高压基线：`200 VU / 25 listeners per room`

一句话总结：

**服务端链路已经比较健康，真正一直飘的是同机端到端 `server -> listener` 尾延迟。**

## 2. 测试环境与口径

本次结论全部来自本地 `local-perf` 环境。

- 机器口径：`2C4G`
- 应用：`aggregation-service`
- Redis：本地 Docker `flashchat-redis-perf`
- MySQL：本地 Docker `flashchat-mysql-perf`
- 压测端：同机 `k6`
- mailbox shard count：`12`
- rate limit：`disabled`
- topology：`shared-host`
- 房间模型：`20 rooms * 50 members`

### 2.1 使用的脚本与命令

后期主要使用的包装脚本有：

- `scripts/perf/Run-MultiRoom-20x50-120-20Listeners-Steady.ps1`
- `scripts/perf/Run-MultiRoom-20x50-200-25Listeners-Steady.ps1`
- `scripts/perf/Run-TenRoom.ps1`

典型命令示例：

`120 VU / 20 listeners per room / steady`

```powershell
powershell -ExecutionPolicy Bypass -File ".\scripts\perf\Run-MultiRoom-20x50-120-20Listeners-Steady.ps1" -Runner local -Build
```

`200 VU / 25 listeners per room / steady`

```powershell
powershell -ExecutionPolicy Bypass -File ".\scripts\perf\Run-MultiRoom-20x50-200-25Listeners-Steady.ps1" -Runner local -Build
```

底层统一都会走：

- `Run-TenRoom.ps1`
- `20 rooms`
- `50 members/room`
- `steady listener mode`
- `mailbox shard count = 12`
- `rate limit disabled`
- fixed-topology + shard guard

### 2.2 结果文件分别看什么

每轮结果目录下，重点文件如下：

- `brief.txt`
  - 快速看总体结果，适合第一眼判断这轮是否值得继续分析
- `ten-room-summary.json`
  - 看完整聚合数据
  - 重点字段包括：
    - `totalSendSuccessesPerSec`
    - `totalHttpP95Ms / P99Ms`
    - `totalWsBroadcastP95Ms / P99Ms`
    - `totalSendToServerGapP95Ms / P99Ms`
    - `totalWsServerToListenerP95Ms / P99Ms`
    - `totalWsListenerLogins`
    - `roomWsConnectFailures`
- `mailbox-step-diagnosis.md`
  - 看服务端阶段耗时
  - 这是当前判断代码改动是否真的生效的核心文件
- `summary.json`
  - k6 原始聚合输出
- `k6-console.log`
  - 看 setup、listener、scenario 过程中的异常
- `app-run-context.json`
  - 看应用启动上下文、pid、profile
- `runtime-samples.jsonl`
  - 看运行时采样、health、cpu、backlog
- `runtime-guard-events.jsonl`
  - 看是否触发过 runtime guard
- `perf/logs/flashchat-local-perf-*.out.log`
  - 看应用日志
  - 用来判断是否有 `MISCONF`、`saveSync`、降级等污染
- `perf/logs/flashchat-local-perf-*.gc.log`
  - 看 GC 是否污染样本

### 2.3 steady 全员在线口径

后期稳定样本统一采用 `steady` listener 模式：

- listener 先登录并保持在线
- `TEN_ROOM_LISTENER_STAGGER_SEC = 30`
- `TEN_ROOM_LISTENER_MAX_DURATION = 7m30s`
- `TEN_ROOM_SENDER_START_TIME = 35s`

这样做的目的，是把“握手风暴”与“稳态全员在线广播”拆开看。

### 2.4 当前重点指标

服务端 SLI：

- `msg.xadd_window.merged`
- `chat.mailbox.queue.wait.duration`
- `chat.mailbox.task.duration`
- `flashchat.broadcast.write.complete.duration`
- `flashchat.broadcast.batch.complete.duration`
- `msg.xadd.fail`
- `msg.persist.fallback`
- `flashchat.broadcast.write.failure`

客户端 SLI：

- `totalWsBroadcastP95Ms / P99Ms`
- `totalWsServerToListenerP95Ms / P99Ms`
- `totalSendToServerGapP95Ms / P99Ms`

## 3. 测试方法演进

### 3.1 早期阶段：先解决旧瓶颈

早期多房间压测主要解决了两件事：

- `unread` 写时 fan-out 导致的 mailbox 膨胀
- `window_add.redis` 作为 mailbox 副作用热点

对应结果是：

- `unread` 已切到读时 DB 统计
- 用户消息 `window_add` 已从 mailbox 副作用移到 `send` 主链路
- mailbox 从“副作用大杂烩”收缩为“广播 + touch”为主

### 3.2 中期阶段：解决 1000 listener 握手风暴

`50 listeners/room * 20 rooms = 1000 listeners`

在早期 `constant-vus`、无退避 listener 模型下，曾出现非常明显的握手/重连风暴：

- `20260420-152726-ten-room-multi-room-20x50-100vu-50listeners`
  - `roomWsConnectFailures = 119981`
  - `totalWsListenerLogins = 2259`
- `20260420-161307-ten-room-multi-room-20x50-100vu-50listeners`
  - `roomWsConnectFailures = 351877`
  - `totalWsListenerLogins = 2703`

这说明：

- 不是“有 12 万个独立用户掉线”
- 而是同一批 listener 在不断失败、重连、再失败

根因链条已经确认：

- 服务端 WS 握手路径本身偏重
- 当时本地小配置下 `worker-threads=2`，WS 业务线程池 `2/4/256`
- listener 失败后立即重试，没有 backoff
- 普通重连还会放大成一次 DB 恢复成本

后续已经做了两类修正：

- 服务端：
  - 普通重连优先走内存快路径
  - 避免每次都 `restoreRoomMemberships()`
- 压测脚本：
  - 增加 listener backoff/jitter
  - 最终切到 `steady` 模式

### 3.3 后期阶段：拆开“服务端 WS”与“客户端 WS”

后期新增了服务端广播尾部埋点：

- `broadcast.write`
- `broadcast.write_complete`
- `broadcast.batch_complete`

这样可以回答两个不同问题：

- 服务端自己广播得快不快？
- 用户端最终多久收到？

这两者现在必须分开看。

## 4. 有效样本与无效样本

### 4.1 有效样本定义

本次总结中，默认把满足以下条件的样本视为“有效样本”：

- `setup_guard_failed = false`
- `send_success_rate = 1`
- listener 登录数达到理论值
- 消息送达数与 `sendSuccesses * listenersPerRoom` 一致
- `msg.xadd.fail = 0`
- `msg.persist.fallback = 0`
- 没有 `MISCONF`、`saveSync` 降级
- 没有 `Heap Inspection Initiated GC` 这类诊断污染

### 4.2 本次正式采信的关键样本

#### A. 100 VU / 50 listeners / backoff

样本：

- `20260420-222409-ten-room-multi-room-20x50-100vu-50listeners-backoff`

结论：

- 这是“从握手风暴过渡到可用稳态”的关键样本
- connect failures 已从几十万级降到 `866`
- 但它仍然属于“过渡态”，不是最终 steady 验收基线

#### B. 100 VU / 50 listeners / steady

样本：

- `20260421-114736-ten-room-multi-room-20x50-100vu-50listeners-steady`

结论：

- `1000 listeners` 可全部在线
- `50/50` 全量送达
- 是本地同机全员在线第一次真正干净的 steady 样本

关键指标：

- `send_successes_per_sec = 310.30 msg/s`
- `HTTP P95/P99 = 16.22 / 59.87 ms`
- `WS P95/P99 = 93 / 316 ms`

#### C. 120 VU / 50 listeners / steady

样本：

- `20260421-180042-ten-room-multi-room-20x50-120vu-50listeners-steady`

结论：

- 这是“1000 listeners 全员在线 steady”下最有代表性的服务端高压样本之一
- 服务端 SLI 仍然健康
- 但客户端端到端 WS tail 非常大

关键指标：

- `send_successes_per_sec = 299.34 msg/s`
- `HTTP P95/P99 = 21.18 / 65.57 ms`
- `WS P95/P99 = 921 / 3835 ms`
- `server->listener P95/P99 = 896 / 3243 ms`
- `listenerLogins = 1000`
- `write_failure = 6`
- `msg.xadd.fail = 0`
- `msg.persist.fallback = 0`

#### D. 120 VU / 20 listeners / steady

样本：

- `20260422-123535-ten-room-multi-room-20x50-120vu-20listeners-steady`

结论：

- 这是当前最健康、最稳定的同机端到端基线
- 同样是 steady 模式，但 listener 从 `1000` 降到 `400` 后，WS tail 明显收敛

关键指标：

- `send_successes_per_sec = 397.65 msg/s`
- `HTTP P95/P99 = 8.69 / 19.86 ms`
- `WS P95/P99 = 17 / 56 ms`
- `server->listener P95/P99 = 14 / 49 ms`
- `listenerLogins = 400`
- 严格 `20/20` 全量送达

#### E. 200 VU / 25 listeners / steady

有效样本：

- `20260422-130028-ten-room-multi-room-20x50-200vu-25listeners-steady`
- `20260422-132716-ten-room-multi-room-20x50-200vu-25listeners-steady`

结论：

- 这是当前更高负载下的有效 steady 样本
- 两轮都干净，差异属于正常抖动范围
- 最新有效样本应看 `20260422-132716`

`20260422-130028`

- `send_successes_per_sec = 618.11 msg/s`
- `HTTP P95/P99 = 10.62 / 26.18 ms`
- `WS P95/P99 = 141 / 321 ms`
- `server->listener P95/P99 = 138 / 318 ms`
- 严格 `25/25` 全量送达

`20260422-132716`

- `send_successes_per_sec = 626.18 msg/s`
- `HTTP P95/P99 = 12.01 / 34.09 ms`
- `WS P95/P99 = 122 / 301 ms`
- `server->listener P95/P99 = 115 / 293 ms`
- 严格 `25/25` 全量送达

### 4.3 无效或不应作为正式结论的样本

#### A. 诊断脚本污染样本

例如：

- `20260421-123858-ten-room-multi-room-20x50-120vu-50listeners-steady`
- `20260421-133630-ten-room-multi-room-20x50-120vu-50listeners-steady`

这类样本的问题是：

- runtime guard 触发后自动执行 `jcmd GC.class_histogram`
- 引发 `Heap Inspection Initiated GC`
- 直接污染了 `server->listener` 尾延迟

这类样本后续已经通过脚本修改规避，不再作为当前结论依据。

#### B. Redis MISCONF 污染样本

例如：

- `20260422-131140-ten-room-multi-room-20x50-200vu-25listeners-steady`

问题是：

- 中途发生 `MISCONF Redis is configured to save RDB snapshots...`
- 部分消息进入 `saveSync` 降级
- `msg.xadd.fail = 55`
- `msg.persist.fallback = 1914`
- 本地 ID 慢路径次数异常升高

这轮虽然表面数字更好看，但业务路径已经不一致，不能用于正式比较。

## 5. 为什么 1000 listeners 不行

这里要非常准确地表述。

**不是“系统永远扛不住 1000 在线用户”。**

真正成立的结论是：

**在当前本地 `2C4G`、同机 `app + Redis + MySQL + k6` 环境下，`1000 listeners` 的全员在线端到端 WS 指标不稳定，不适合作为服务端改动的绝对验收标准。**

### 5.1 第一层原因：早期握手风暴

早期 `1000 listeners` 不稳定，首先是因为握手层本身被打穿了。

表现：

- `roomWsConnectFailures` 爆炸
- `totalWsListenerLogins` 明显大于理论 listener 数

原因：

- 小机 WS 握手线程池太小
- listener 无 backoff 即时重试
- 重连路径过重

这部分问题已经通过：

- 快路径重连优化
- listener backoff
- steady 模式

基本压下来了。

### 5.2 第二层原因：full-online fan-out 放大了 delivery tail

即使握手风暴被压下，`1000 listeners` 仍然会把以下尾部放大：

- `broadcast.write_complete`
- `broadcast.batch_complete`
- Netty event-loop / socket drain
- 本机 TCP 栈调度
- k6 listener 收包和 JS 回调调度

以 `20260421-180042` 为例：

- `listenerLogins = 1000`
- `50/50` 全量送达
- `msg.xadd_window.merged avg = 0.999ms`
- `mailbox.queue.wait avg = 0.217ms`
- `broadcast.batch_complete avg = 4.52ms`
- 但：
  - `WS P95 = 921ms`
  - `WS P99 = 3835ms`

这说明：

- 服务端同步链路没有崩
- mailbox 也没有失控
- 但客户端端到端 `server->listener` 尾巴被 full-online fan-out 和同机环境明显放大

### 5.3 第三层原因：同机压测把服务端与客户端测量混在了一起

当前所有本地样本里，以下组件都在同一台 `2C4G` 上：

- Java 应用
- Redis
- MySQL
- k6 sender
- k6 listeners

因此客户端 `WS P95/P99` 其实测的是整个链路：

- 服务端写出
- 本机 TCP
- 内核调度
- k6 listener 收包
- JS 回调调度

所以：

- 它能说明整体用户体验尾部
- 但不能直接等价成“服务端广播代码本身慢”

## 6. 当前最可信的服务端性能结论

### 6.1 send 主链路

当前 send 主链路最重的是：

- `send.critical`
- 其中主体是 `msg.xadd_window.merged`

这代表：

- `XADD`
- `window_add`
- `trim`
- `expire`

已经被合并到一次 Redis Lua 里。

在有效样本里，这段大体处于：

- `0.57ms ~ 1.00ms`

典型值：

- `120 VU / 20 listeners`：`0.570ms`
- `120 VU / 50 listeners`：`0.999ms`
- `200 VU / 25 listeners`：`0.664ms ~ 0.733ms`

这说明：

- 合并 Lua 是健康的
- 当前它是 send 侧最重但仍可接受的核心成本

### 6.2 mailbox

当前 mailbox 已不再承担用户消息 `window_add`，主要负责：

- `broadcast`
- `touch`

在有效样本里：

- `queue wait` 大约 `0.10ms ~ 0.22ms`
- `task duration` 大约 `0.14ms ~ 0.36ms`

这说明：

- mailbox 当前没有明显堆积
- unread 优化和 `window_add` 前移是成功的

### 6.3 服务端广播

当前服务端广播的真实热点是：

- `broadcast.write_complete`
- `broadcast.batch_complete`

典型值：

`120 VU / 20 listeners`

- `write_complete avg = 0.624ms`
- `batch_complete avg = 0.817ms`

`200 VU / 25 listeners`

- `write_complete avg = 1.191ms ~ 1.670ms`
- `batch_complete avg = 1.492ms ~ 2.059ms`

`120 VU / 50 listeners`

- `write_complete avg = 3.842ms`
- `batch_complete avg = 4.52ms`

这说明：

- 服务端广播完成时间本身是毫秒级
- 随 listener 数量上升会变重
- 但并没有恶化到秒级

## 7. 当前最可信的客户端端到端结论

### 7.1 120 VU / 20 listeners

这是当前最适合作为本地同机“端到端健康基线”的样本：

- `20260422-123535-ten-room-multi-room-20x50-120vu-20listeners-steady`

它说明：

- 本地同机环境下，如果总 listener 降到 `400`
- 客户端端到端 `WS P95/P99` 可以稳定控制在很健康的范围

### 7.2 200 VU / 25 listeners

这是当前更高负载下的本地同机有效样本：

- 最新有效样本：`20260422-132716`

它说明：

- 即使 `200 VU`
- 只要 listener 总数控制在 `500`
- 系统依然可以稳定做到：
  - `626 msg/s`
  - `HTTP P95 12ms`
  - `WS P95 122ms`
  - `WS P99 301ms`

### 7.3 120 VU / 50 listeners

这条线说明的不是“服务端完全不行”，而是：

- 当 listener 总数到 `1000`
- 本地同机端到端尾延迟开始明显偏大

因此它更适合：

- 作为 full-online 高压线看服务端 SLI

而不适合：

- 继续拿客户端 `WS P95/P99` 绝对值当唯一验收标准

## 8. 当前阶段耗时结构总览

### 8.1 最新有效样本：20260422-132716

#### Send 主链路

- `send.pre_lock avg = 0.023ms`
- `stripe.lock.wait avg = 0.130ms`
- `send.audit avg = 0.004ms`
- `send.critical avg = 0.770ms`
- `msg.xadd_window.merged avg = 0.733ms`
- `msg.id.slow_path avg = 0.588ms`，但只发生 `5518` 次

#### Mailbox / Broadcast

- `queue wait avg = 0.149ms`
- `task avg = 0.160ms`
- `broadcast avg = 0.157ms`
- `broadcast.write avg = 0.125ms`
- `broadcast.flush avg = 0.019ms`
- `broadcast.write_complete avg = 1.191ms`
- `broadcast.batch_complete avg = 1.492ms`
- `write_failure = 0`

### 8.2 高压 full-online 样本：20260421-180042

#### Send 主链路

- `send.pre_lock avg = 0.083ms`
- `stripe.lock.wait avg = 0.204ms`
- `send.audit avg = 0.005ms`
- `send.critical avg = 1.047ms`
- `msg.xadd_window.merged avg = 0.999ms`

#### Mailbox / Broadcast

- `queue wait avg = 0.217ms`
- `task avg = 0.360ms`
- `broadcast.write avg = 0.297ms`
- `broadcast.write_complete avg = 3.842ms`
- `broadcast.batch_complete avg = 4.52ms`
- `write_failure = 6`

## 9. 当前最真实的项目状态

到目前为止，项目已经从“瓶颈不清楚”进展到“问题边界很清楚”：

- `unread` 不再是当前主瓶颈
- `window_add` 已从 mailbox 成功搬出
- 握手风暴问题已经识别并通过 steady/backoff 收敛
- 当前服务端热点已经很明确：
  - `msg.xadd_window.merged`
  - `broadcast.batch_complete`
  - `broadcast.write_complete`

真正还没有被本地同机环境完全说清的，不是服务端链路，而是：

- 当 listener 数继续升高到 `1000`
- 在同一台 `2C4G` 机器上
- 用户最终端到端 `WS P95/P99` 会被放大到什么程度

这个问题本质上已经不是纯业务代码问题，而是：

- full-online fan-out 规模
- 同机网络/调度
- k6 listener 接收侧

共同叠加后的结果。

## 10. 后续测试建议

### 10.1 服务端回归验收

后续代码改动优先看这些指标：

- `msg.xadd_window.merged`
- `chat.mailbox.queue.wait.duration`
- `chat.mailbox.task.duration`
- `broadcast.write_complete`
- `broadcast.batch_complete`
- `msg.xadd.fail`
- `msg.persist.fallback`
- `write_failure`

### 10.2 客户端端到端验证

本地同机建议固定两条基线：

- 健康基线：`120 VU / 20 listeners per room`
- 高压基线：`200 VU / 25 listeners per room`

### 10.3 对 1000 listeners 的正确用法

`1000 listeners` 这条线建议继续保留，但用途要收窄：

- 用来观察 full-online 情况下服务端 SLI 是否失控
- 不再把同机端到端 `WS P95/P99` 当作唯一的硬门槛

### 10.4 如果要回答“真实用户端多久收到”

最好的下一步不是继续在同机环境死磕，而是：

- 把 k6 挪到另一台机器
- 或者至少把 listener 数继续控制在较低口径上做趋势判断

## 11. 最终结论

这两周压测的最终结论不是“没有进展”，而是：

- 服务端路径已经被拆清楚了
- 旧瓶颈已经解决了
- 新热点已经明确了
- 同机全员在线端到端尾延迟为什么难看，也已经说清楚了

可以把当前状态概括成三句话：

- **服务端 send 路径健康，核心成本是 `msg.xadd_window.merged`。**
- **服务端 broadcast 路径健康，核心成本是 `broadcast.batch_complete / write_complete`。**
- **`1000 listeners` 不是“系统绝对不行”，而是“本地同机端到端 WS 指标不适合作为最终服务端验收结论”。**
