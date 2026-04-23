# 发送消息管线架构说明

> 本文档说明 `chat-service` 发送消息主链路的 4 层架构模型、时序关系和必须保持的不变量。适合新成员 onboarding 或排查生产异常时建立全局视图。

---

## 一、4 层模型

```
┌───────────────────────────────────────────────────────────────────┐
│  Layer 1 — Request Thread(请求线程,可丢弃)                     │
│  职责:                                                           │
│    • 业务校验(入房 / 禁言 / 内容非空 / 回复合法性)              │
│    • 限流(MessageRateLimiter)                                  │
│    • 审核(MessageAuditChain)                                   │
│    • Handler 路由 + body 构建                                    │
│    • 进入 stripe 锁后调度 Layer 2/3/4                            │
│  故障语义:任意校验失败直接抛 ClientException 返回客户端           │
└───────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌───────────────────────────────────────────────────────────────────┐
│  Layer 2 — Room Stripe Lock(房间级串行域)                       │
│  组件:RoomSerialLock(ReentrantLock[64])                        │
│  职责:                                                           │
│    • 对同一 roomId 的 "INCR → build → XADD → submit" 四步加锁     │
│    • tryLock(3s)超时即快失败 → BUSY_ROOM                        │
│  不变量:                                                         │
│    同一 roomId 的任意两次 send,其 Layer 3 的 submit 调用顺序     │
│    严格等于 msgSeqId 的分配顺序                                   │
└───────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌───────────────────────────────────────────────────────────────────┐
│  Layer 3 — Durable Handoff(持久化接力)                          │
│  组件:MessagePersistServiceImpl                                  │
│  职责:                                                           │
│    • saveAsync:向 Redis Stream XADD 投递 + MAXLEN trim          │
│    • saveSync:Redis 不可用时降级,直连 DB insert                 │
│    • 返回 PersistResult(acceptedBy=STREAM|DB)                   │
│  确认语义:                                                       │
│    XADD 返回成功 = durable accepted = 调用方可以放心广播          │
└───────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌───────────────────────────────────────────────────────────────────┐
│  Layer 4 — Side-effect Mailbox(副作用邮箱,per-room FIFO)       │
│  组件:RoomSideEffectMailbox(N shard × 单线程 executor)          │
│  职责:                                                           │
│    • 按 roomId 哈希到 shard                                      │
│    • 每个 shard 单线程顺序执行:                                  │
│        safeAddWindow → safeBroadcast                             │
│        → safeTouchMember → safeIncrementUnread                   │
│    • 任意单项异常被各自 catch 吸收,不串扰其他副作用               │
│  故障语义:                                                       │
│    队列满/shutdown → CompletableFuture 立即失败,不阻塞调用方     │
└───────────────────────────────────────────────────────────────────┘
```

---

## 二、时序图(用户消息路径)

```
Client            RequestThread   StripeLock   MessagePersist   Mailbox   Worker(shard)
  │                     │             │              │             │            │
  │─sendMsg(req)───────►│             │              │             │            │
  │                     │─rateLimit──►│(Redis INCR)  │             │            │
  │                     │◄────PASS────│             │              │            │
  │                     │─audit──────►│              │             │            │
  │                     │◄─────OK─────│              │             │            │
  │                     │─acquire(roomId)──►│        │             │            │
  │                     │◄────Handle──────────│      │             │            │
  │                     │         (critical section)│             │            │
  │                     │─INCR msgSeq─────►│         │             │            │
  │                     │◄─────42───────────│        │             │            │
  │                     │─saveAsync──────────────────►│            │            │
  │                     │      (Redis Stream XADD)   │             │            │
  │                     │◄─────PersistResult─────────│             │            │
  │                     │─dispatchUserMessageAccepted────────────►│            │
  │                     │                                         │(enqueue)   │
  │                     │◄─────CompletableFuture(pending)─────────│            │
  │                     │─release Handle──►│         │             │            │
  │◄───return broadcastMsg──│              │         │             │            │
  │                                                  │             │─execute───►│
  │                                                  │             │            │─addWindow
  │                                                  │             │            │─broadcast ws
  │                                                  │             │            │─touchMember
  │                                                  │             │            │─incrUnread
                                                                                 │(done)
```

**关键观察**:

- 请求线程在 `release Handle` 后就返回客户端,**不等待** Layer 4 的副作用完成
- Layer 3 的 XADD 是同步的,这是 "durable accept" 的边界 — 用户看到的 RT 包含 XADD 耗时,但不包含广播/窗口/未读数耗时
- Stripe 锁在整个"INCR → XADD → submit"期间持有,保证同房间这三步的全局顺序

---

## 三、核心不变量

以下不变量**必须**在任何改动后继续成立。每条旁边注明守护机制。

### I-1:同房间广播顺序 == msgSeqId 分配顺序

**守护机制**:
- Stripe lock 保证 `INCR → submit` 原子化
- Mailbox 单线程 shard 保证同房间 submit 顺序 == worker 执行顺序

**测试**:`RoomSerialLockTest#serializeCriticalSectionPerRoom`(16 线程 × 100 任务,验证 [1..1600] 连续)
**测试**:`RoomSideEffectMailboxConcurrencyTest#sameRoomSubmitsShouldExecuteInStrictFifoOrder`(2000 任务,验证 [0..1999] 连续)

### I-2:副作用失败不影响主路径

**守护机制**:
- 4 项副作用各自 `safeXxx` 包裹 catch Throwable
- 单项失败只增 counter,不 rethrow 到 worker 线程

**测试**:`MessageSideEffectServiceImplTest` 5 用例覆盖四种单项失败 + whenComplete 失败

### I-3:Mailbox 入队不阻塞调用方

**守护机制**:
- Mailbox 使用 `execute` + `AbortPolicy`,队列满抛 `RejectedExecutionException`
- submit 包装为 `CompletableFuture`,异常被装入 future 返回

**测试**:`RoomSideEffectMailboxConcurrencyTest#submitShouldFailFastWhenQueueFull`(queueCapacity=2 + blocker,submit <100ms)

**为什么重要**:生产线程在 stripe 锁内调用 submit,如果阻塞,同房间所有后续请求会被钉死在 stripe,引发跨房间线程池雪崩。

### I-4:Stripe 锁等待有上限

**守护机制**:
- `tryLock(3000ms)` 超时即抛 `StripeLockTimeoutException`
- 上层映射为 `BUSY_ROOM` ClientException,前端可 toast 重试

**测试**:`RoomSerialLockTest#acquireShouldTimeOutWhenLockHeldByOtherThread`

### I-5:线程池 shutdown 期间 submit 不会静默丢失

**守护机制**:
- Mailbox submit 开头显式判断 `executor.isShutdown()`
- Stripe 锁等待期间线程被中断立即抛 `StripeLockInterruptedException`

**测试**:`RoomSideEffectMailboxConcurrencyTest#submitAfterShutdownShouldFailImmediately`
**测试**:`RoomSerialLockTest#interruptedWaiterShouldAbort`

### I-6:系统消息与用户消息在同一房间共享顺序

**守护机制**:
- `SystemMessageServiceImpl` 与 `ChatServiceImpl` 共用同一把 `RoomSerialLock` 实例
- 两者都走同一个 mailbox shard

**反例**:如果系统消息用另一把锁,会出现"入房通知被用户消息插队"的视觉错位。

---

## 四、指标地图

| 指标名 | 类型 | 告警方向 | 排查建议 |
|---|---|---|---|
| `chat.stripe.lock.wait.duration` | Timer | p99 > 100ms | 同房间热点,考虑放大 stripe 数或降低临界区粒度 |
| `chat.stripe.lock.timeout` | Counter | 非 0 | 房间堵塞,链路有病态抖动(Redis / DB) |
| `chat.sideeffect.fail.window_add` | Counter | >0 | Redis ZADD 失败,检查 Redis 连接/内存 |
| `chat.sideeffect.fail.broadcast` | Counter | >0 | WS 发送失败,检查 channel 状态 |
| `chat.sideeffect.fail.touch_member` | Counter | 非关键,可忽略小量 | Redis 抖动 |
| `chat.sideeffect.fail.unread_increment` | Counter | >0 | Redis INCR 失败 |
| `chat.sideeffect.fail.whenComplete_*` | Counter | 应长期为 0 | 代码 bug,whenComplete 回调自身异常 |
| `chat.mailbox.rejected` | Counter | >0 | Mailbox 队列满或已 shutdown |
| `chat.reply.not_found` | Counter | 稳态 <1/min | replyMsg DB miss,若显著升高考虑加窗口 fallback |
| `chat.send.rate_limited` | Counter | 业务正常值 | 观察 user_global / user_room / room_global 分布 |

---

## 五、组件职责清单

| 类 | 职责 | 不应做 |
|---|---|---|
| `ChatServiceImpl` | 业务校验、限流、审核、调度 | 不应直接写窗口或广播 |
| `SystemMessageServiceImpl` | 系统消息入口,drop-on-timeout 语义 | 不应抛异常给上层 |
| `RoomSerialLock` | 房间级临界区守护 + 指标 | 不应持有业务状态 |
| `MessagePersistServiceImpl` | Durable handoff(XADD/降级) | 不应设置 `createTime`(调用方负责) |
| `MessageSideEffectServiceImpl` | 4 项副作用编排 + 失败隔离 | 不应让任何异常穿过 `safeXxx` 边界 |
| `RoomSideEffectMailbox` | Shard 调度 + FIFO + 反压 | 不应阻塞 submit 调用方 |
| `MessageRateLimiter` | 三维度限流(user/user-room/room) | 不应在业务代码之外被 bypass |

---

## 六、阅读顺序建议

如果你是第一次上手这个模块,按这个顺序读代码最快建立全局:

1. `ChatServiceImpl.sendMsg` — 主入口,从上到下
2. `RoomSerialLock` — 理解为什么需要串行域
3. `MessagePersistServiceImpl.saveAsync` — 理解 durable accept 的边界
4. `MessageSideEffectServiceImpl.dispatchUserMessageAccepted` — 理解 mailbox task 的组成
5. `RoomSideEffectMailbox.submit` — 理解反压与 shard 路由
6. 测试:`RoomSideEffectMailboxConcurrencyTest` — 不变量的活文档
