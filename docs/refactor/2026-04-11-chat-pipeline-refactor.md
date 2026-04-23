# 发送消息管线重构总结报告

**日期**:2026-04-11
**范围**:`services/chat-service` — 用户消息 / 系统消息发送主链路
**基线**:commit `f73e7dd^`(重构开始前)
**当前**:master HEAD(本轮收敛)

---

## 一、背景

原始 `ChatServiceImpl.sendMsg` 是一条**同步串行管线**:请求线程一路从"校验 → 持久化 → 写滑动窗口 → WebSocket 广播 → 未读数"走到底。这条链路在单机 2C4G 的目标部署环境下存在以下结构性问题:

1. **请求线程承担所有副作用** — 任何一步抖动(Redis ZADD、WS 推送、未读数 Redis 调用)都会直接拖长用户请求 RT
2. **副作用失败污染主路径** — 广播或窗口写失败会把一条已经成功入库的消息以 500 返回给客户端,前端重试会产生重复消息
3. **同房间消息乱序风险** — 无房间级串行化,msgSeqId 分配顺序与 mailbox 提交顺序可能倒置,导致广播顺序与窗口 ZADD 顺序反向
4. **缺乏可观测性** — 副作用失败只有一个 try-catch Exception 的 ERROR 日志,无法区分"窗口写失败"还是"未读数失败"
5. **限流器接好了 bean 但没接入调用点** — `MessageRateLimiter` 自始至终没被任何业务代码调用过
6. **系统消息路径与用户消息路径各写一遍副作用代码** — 重复、不一致

---

## 二、架构前后对比

### Before(`f73e7dd^`)

```
Request Thread ──────────────────────────────────────────►
  validate → audit → msgId → INCR → build → saveAsync(XADD)
          → addToWindow → broadcastToRoom → touchMember → incrementUnread
                                                                ▲
                               所有副作用直接在请求线程上同步执行
```

- 无 stripe lock
- 无 mailbox
- 无副作用隔离
- 无限流

### After(HEAD)

```
Request Thread                  Room Mailbox (per-shard single thread)
──────────────────────          ──────────────────────────────────────
validate + rateLimit                    ┌───────► safeAddWindow
  + audit                               │
  ↓                                     ├───────► safeBroadcast
RoomSerialLock.acquire                  │
  ↓                                     ├───────► safeTouchMember
  INCR → build → XADD ──► submit(room) ─┤
  ↓                                     └───────► safeIncrementUnread
release lock
  ↓                          每一项 safe 都有独立 counter,
return to client             单项失败不影响其他副作用
```

- 4 层职责分离:Request / Stripe Lock / Durable Handoff / Side-effect Mailbox
- 同房间 FIFO 不变量由 stripe lock + mailbox 单线程 shard 双重保证
- 副作用失败被吸收在各自 `safeXxx` 中,通过 Micrometer 上报

---

## 三、改动清单(按模块)

### 3.1 RoomSideEffectMailbox(新增)

**新增文件**:`services/chat-service/src/main/java/com/flashchat/chatservice/service/dispatch/RoomSideEffectMailbox.java`

**职责**:按 roomId 哈希到固定数量 shard,每个 shard 一个单线程 executor,保证同房间副作用 FIFO。

**关键设计决策**:

| 决策点 | 选型 | 理由 |
|---|---|---|
| 入队语义 | `execute()` + `AbortPolicy` | 替代最初的 `queue.put` 阻塞,防止 stripe 锁内调用方被反压反向拖死 |
| 返回类型 | `CompletableFuture<Void>` | 队列满/shutdown 时立即返回已失败 future,调用方可观测但不阻塞 |
| Error 处理 | `catch Error` 后 rethrow | OOM 等致命错误不能被吞,必须终止进程 |
| shutdown 竞态 | submit 开头显式 `isShutdown()` 判断 | 防止"入队成功但 worker 已停"的空窗导致任务永远不执行 |

**不变量**(由 `RoomSideEffectMailboxConcurrencyTest` 锁死):

1. 同房间并发 submit 的执行顺序严格等于 submit 顺序
2. 队列满时 submit 在 100ms 内返回失败 future,不阻塞调用线程
3. shutdown 后 submit 在 50ms 内返回 `RejectedExecutionException`

---

### 3.2 RoomSerialLock(从零实现 → 重构两次)

**文件**:`services/chat-service/src/main/java/com/flashchat/chatservice/service/dispatch/RoomSerialLock.java`

**演进路径**:

| 阶段 | 实现 | 问题 |
|---|---|---|
| Before(原始) | 不存在,发送链路无房间级串行 | 同房间 msgSeqId 与 mailbox submit 顺序可能倒置 |
| 中间版本 | `Object[64]` + `synchronized` | 无法超时、无法中断、无观测 |
| 当前(HEAD) | `ReentrantLock[64]` + `tryLock(timeout)` + `AutoCloseable Handle` | 超时可配、支持中断、Timer/Counter 可观测 |

**关键 API**:

```java
try (RoomSerialLock.Handle ignored = roomSerialLock.acquire(roomId)) {
    // 临界区:INCR → build → XADD → mailbox.submit
}
// 默认 3s 超时,超时抛 StripeLockTimeoutException
// 被中断抛 StripeLockInterruptedException
```

**收益**:

1. `tryLock(3s)` 上限:Redis/XADD 抖动不再无限拖死请求线程
2. 可中断:线程池 shutdown 时等待线程能立即退出
3. `chat.stripe.lock.wait.duration` / `chat.stripe.lock.timeout` 指标暴露 stripe 竞争热点
4. `h ^ (h >>> 16)` 混淆 hash,降低短连号 roomId 的 stripe 碰撞

**测试覆盖**:`RoomSerialLockTest` 5 个用例 — 同 stripe、分布均匀性、16×100 串行正确性、tryLock 超时、wait 中断。

---

### 3.3 ChatServiceImpl.sendMsg(重构主入口)

**文件**:`services/chat-service/src/main/java/com/flashchat/chatservice/service/impl/ChatServiceImpl.java`

#### Before(f73e7dd^)

```java
public ChatBroadcastMsgRespDTO sendMsg(SendMsgReqDTO request) {
    // 校验 + audit + reply check
    ...
    // 无锁
    Long msgSeqId = msgIdGenerator.tryNextId();
    MessageDO messageDO = build(...);
    messagePersistServiceImpl.saveAsync(messageDO);    // XADD
    // 所有副作用都在请求线程上同步执行
    messageWindowService.addToWindow(roomId, msgSeqId, broadcastMsg);
    roomChannelManager.broadcastToRoom(roomId, ws);    // 可能阻塞
    roomChannelManager.touchMember(roomId, accountId);
    unreadService.incrementUnread(roomId, accountId);  // Redis
    return broadcastMsg;
}
```

#### After

```java
// 1. 公共前置校验(含新接入的限流)
validateRoomCanSendMsg(roomId);
if (!inRoom) throw ClientException("你不在该房间中");
if (muted) throw ClientException("你已被禁言");

// 1.5 限流 — 最早的廉价 Redis 调用,放在 audit/XADD/锁之前
RateLimitResult r = messageRateLimiter.checkLimit(accountId, roomId);
if (!r.isPassed()) {
    rateLimitedCounter.increment();
    throw new ClientException(r.getMessage());
}

// 2-5 校验 + audit + reply check(略)

// 锁外预计算:msgId/nowMillis/createTime 与 msgSeqId 无关,提前算好
String msgId = UUID.randomUUID().toString().replace("-", "");
long nowMillis = System.currentTimeMillis();
LocalDateTime createTime = LocalDateTime.now();

// 房间级临界区:INCR → build → XADD → submit 四步串行
try (RoomSerialLock.Handle ignored = acquireRoomLockOrBusy(roomId)) {
    Long msgSeqId = msgIdGenerator.tryNextId();
    MessageDO messageDO = build(...createTime...);
    persistResult = (msgSeqId != null)
            ? messagePersistServiceImpl.saveAsync(messageDO)
            : messagePersistServiceImpl.saveSync(fallbackBuild());
    broadcastMsg = buildBroadcast(...nowMillis...);
    messageSideEffectService.dispatchUserMessageAccepted(
            roomId, accountId, msgSeqId, broadcastMsg);
}
// 锁外 log,不占用临界区
log.info("[发消息] room={} dbId={} acceptedBy={}", ...);
return broadcastMsg;
```

#### 改动点清单

| 改动 | 位置 | 理由 |
|---|---|---|
| 接入限流 | L109-120 | 限流漏接,原始代码 import 了 `MessageRateLimiter` 但从未调用。放在最早位置,被限流请求不占下游资源 |
| 引入 stripe lock | L169 | 无锁时同房间 msgSeqId 分配与 mailbox submit 顺序可能倒置 |
| 锁外预计算 | L162-165 | `msgId`/`nowMillis`/`createTime` 不依赖 msgSeqId,放锁内纯属浪费临界区 |
| `createTime` 由调用方持有 | L165, L185 | 消除 `MessagePersistServiceImpl.saveAsync` 内部的双写,统一时刻源 |
| 副作用异步分发 | L222 | 调用 `messageSideEffectService.dispatchUserMessageAccepted`,请求线程不再跑 4 项副作用 |
| PII 降级 | L228-229 | 原始 `content` 出现在 INFO,现降为 DEBUG |
| reply race 计数器 | L65-68, L148 | `chat.reply.not_found` 监控已知的 XADD→Consumer 入库时间窗口 miss |
| BUSY_ROOM 映射 | `acquireRoomLockOrBusy` 辅助方法 | stripe 超时 → `ClientException("房间当前繁忙")`,前端可 toast + 重试 |

---

### 3.4 MessageSideEffectServiceImpl(新增)

**文件**:`services/chat-service/src/main/java/com/flashchat/chatservice/service/impl/MessageSideEffectServiceImpl.java`

**职责**:将"写窗口 / 广播 / 更新活跃 / 未读数"四项副作用封装为单个 mailbox task。四项独立 `safeXxx` 方法,单项异常被各自吸收,不串扰其他副作用。

**指标**(9 个 Counter):

| 指标名 | 含义 |
|---|---|
| `chat.sideeffect.fail.window_add` | 滑动窗口 ZADD 失败 |
| `chat.sideeffect.fail.broadcast` | WebSocket 广播失败 |
| `chat.sideeffect.fail.touch_member` | 活跃时间更新失败 |
| `chat.sideeffect.fail.unread_increment` | 未读数 Redis INCR 失败 |
| `chat.sideeffect.fail.system_window_add` | 系统消息窗口失败 |
| `chat.sideeffect.fail.system_broadcast` | 系统消息广播失败 |
| `chat.sideeffect.fail.whenComplete_user` | 用户消息 whenComplete 阶段失败 |
| `chat.sideeffect.fail.whenComplete_system` | 系统消息 whenComplete 阶段失败 |
| `chat.mailbox.rejected` | mailbox 反压/shutdown 拒单次数 |

---

### 3.5 SystemMessageServiceImpl

**改动**:

- 共用同一把 `RoomSerialLock` → 系统消息与用户消息在同一房间 msgSeqId 顺序严格一致
- **drop-on-timeout 语义** — 系统消息后台触发、无用户等待,stripe 超时/中断时 WARN 并 `return`,不抛上层
- `msgId`/`nowMillis`/`createTime` 同样锁外预计算
- 副作用走 `dispatchSystemMessageAccepted` 同一套 mailbox

---

### 3.6 MessagePersistServiceImpl

**改动**:`saveAsync` 移除内部 `setCreateTime(LocalDateTime.now())`,调用方负责。避免主体 `createTime` 与 DB/Stream/广播之间出现微秒级漂移。

---

## 四、测试结果

**命令**:`mvn -o -pl services/chat-service clean test`
**结果**:`Tests run: 21, Failures: 0, Errors: 0, Skipped: 1` — BUILD SUCCESS

| 测试类 | 用例数 | 覆盖点 |
|---|---|---|
| `RoomSerialLockTest` | 5 | 同 stripe / 分布 / 串行化(16×100) / tryLock 超时 / 中断 |
| `RoomSideEffectMailboxTest` | 4 | 基础 submit / shutdown / 异常隔离 / 顺序 |
| `RoomSideEffectMailboxConcurrencyTest` | 3 | 严格 FIFO / 反压非阻塞 / shutdown 竞态 |
| `MessagePersistServiceImplTest` | 2 | saveAsync / saveSync |
| `MessageSideEffectServiceImplTest` | 5 | 四项副作用隔离 + whenComplete |
| `SystemMessageServiceImplTest` | 1 | 系统消息主路径 |
| `MsgIdGeneratorTest` | — | 原有覆盖 |

---

## 五、风险评估 & 已接受的权衡

### 已接受的 race

1. **replyMsg 查 DB miss**
   消息刚 `saveAsync` 被 durable accept,但 Stream Consumer 还没攒批入库,此时恰好有用户回复该条 → DB miss → `ClientException("引用的消息不存在")`。
   - 时间窗口 ≤200ms(BLOCK 超时 + 批处理延迟)
   - 用户重试即可,不产生脏数据
   - `chat.reply.not_found` 计数器监控,若显著升高再考虑加窗口 fallback

2. **系统消息 stripe 超时丢弃**
   系统消息无用户等待,超时选择丢弃 + WARN 而非抛异常。
   - 代价:极端抖动下偶发"入房通知丢失"
   - 收益:不会反压业务主链路

### 真正的风险

| 风险 | 现状 | 缓解 |
|---|---|---|
| 单机 mailbox 队列容量 | 默认大小未经压测 | 待 P0 压测给出容量曲线 |
| stripe 数 64 是否够用 | 房间数 <<64 时无冲突,>>64 后有碰撞 | Timer 指标可观察,必要时加 stripe |
| shutdown 时在队任务会丢 | 当前接受 | 如需强保证需加 graceful drain |

---

## 六、后续工作(未完成,不在本轮)

1. 线上压测(1k QPS × 30min)+ Redis 抖动注入
2. Grafana 面板 + 告警阈值
3. Stream Consumer 侧审计(批量落库 / ack / DLQ)
4. aggregation-service / game-service 同类改造

---

## 七、Git 参考

```text
f73e7dd 发送信息重构,完全异步化            # 4 层架构引入
abd4b8f 发送信息重构,完全异步化            # P0/P1 收敛
```

重构涉及 21+ 文件、~1900 insertions(参见 `git show f73e7dd --stat`)。
