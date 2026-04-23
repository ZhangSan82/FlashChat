# 发送消息管线重构 — 面试介绍与追问手册

> 本文档是针对 FlashChat 发送消息管线重构项目的面试话术储备。覆盖三种时长的自我陈述、高频追问及标准答案、可能的陷阱与加分回答。

---

## 一、三档自我陈述

### 30 秒版(电梯陈述)

> 我在 FlashChat(一个多房间实时聊天系统)里重构了发送消息的核心链路,把原来同步的"校验 → 落库 → 写窗口 → 广播 → 未读数"一条链,拆成了 4 层:请求线程 / 房间级串行锁 / Durable Handoff / Side-effect Mailbox。核心目标是**请求线程只承担必要的串行工作,4 项副作用异步 + 失败隔离**。改完后同房间消息的 FIFO 用 stripe lock + 单线程 shard 双重保证,整个链路加了 11 个 Micrometer 指标,并发不变量用 JUnit 压测锁死。

---

### 2 分钟版(技术介绍)

> FlashChat 的 chat-service 在重构前,`sendMsg` 是一条同步管线 — 请求线程从校验一路走到 WebSocket 广播和 Redis 未读数,任何一步抖动都会直接拖 RT,任何一步异常都会把一条已入库的消息以 500 返回客户端,造成前端重试 + 重复消息。
>
> 我把它拆成 4 层:
>
> **第一层**是请求线程,负责校验、限流、审核;
> **第二层**是 `RoomSerialLock`,64 条 `ReentrantLock[]` 做 stripe,对同一 roomId 的 "INCR msgSeqId → XADD → submit" 三步加锁,保证同房间 msgSeqId 分配顺序和下游提交顺序严格一致;
> **第三层**是 `MessagePersistServiceImpl`,通过 Redis Stream XADD 做 durable handoff — XADD 成功就是 durable accepted,请求线程可以返回客户端;
> **第四层**是 `RoomSideEffectMailbox`,按 roomId 哈希到 N 个 shard,每个 shard 一个单线程 executor,把"写窗口 / 广播 / 更新活跃 / 未读数"四项副作用顺序执行,每项独立 catch Throwable 做失败隔离。
>
> 重构中踩的主要坑是**反压不能阻塞调用方** — 生产线程在 stripe 锁内调用 `mailbox.submit`,如果 mailbox 用 `queue.put` 阻塞,队列满时会把 stripe 锁钉死,引发跨房间线程池雪崩。所以最终用 `execute` + `AbortPolicy`,队列满立即返回失败 `CompletableFuture`,调用方可观测但不阻塞。
>
> 正确性用三个压测用例锁死:16×100 的 stripe lock 串行化测试、2000 任务的 mailbox FIFO 测试、和 shutdown 竞态测试。整个 chat-service 21 个测试全绿。

---

### 5 分钟版(深入介绍)

覆盖 2 分钟版 + 以下扩展点:

**问题定义**
- 单机 2C4G 部署目标,不能为了高可用堆机器
- 原始链路在 Redis 抖动 / WS 慢客户端 / 未读数 RTT 任一抖动下都会拖 RT
- 副作用无可观测性,线上只能看到一个 `catch (Exception e)` 的 ERROR 日志

**为什么不用 MQ**
- 单机部署,引入 Kafka/RocketMQ 带来的运维复杂度远超收益
- Redis Stream 已经在链路里用于消息 ID 预分配,复用现有组件成本最低
- XADD 的 durable 语义足够当前业务要求的"接收即不丢"

**为什么需要 stripe lock**
- Mailbox 单线程 shard 已经保证 shard 内 FIFO,但**只在 submit 进入队列之后**才生效
- 两个请求线程可能分别拿到 msgSeqId=10 / 11,但 submit 调用顺序可以相反 — 这时 mailbox 执行顺序就是 11 在前 10 在后,广播乱序
- stripe lock 的作用是保证 "INCR → submit" 这两步在同一房间内被当作一个原子操作

**为什么是 ReentrantLock + tryLock 而不是 synchronized**
- `tryLock(3s)` 有超时上限 — 病态抖动时快失败给客户端 BUSY_ROOM,不无限拖死线程
- 支持中断 — 线程池 shutdown 时等待线程能及时退出
- 可观测 — `chat.stripe.lock.wait.duration` 暴露 stripe 竞争热点

**已接受的权衡**
- replyMsg DB miss 的 race(XADD 到 Consumer 入库的 ~200ms 窗口内查 DB):评估后刻意接受,代价是用户重试一次,收益是避免给 get 路径加复杂的窗口 fallback。加了 `chat.reply.not_found` counter 监控,若显著升高再考虑补
- 系统消息 stripe 超时是 drop 而不是抛 — 入房通知这类背景消息没有等待方,极端抖动下接受偶发丢失

**测试策略**
- 不变量驱动 — 不是测 "每个方法的行为",而是测 "这一层必须保持的性质"
- 高并发 JUnit:`CountDownLatch + synchronized submitLock` 做到"并发 submit 但序号分配和入队一步完成"

---

## 二、高频追问 + 标准答案

### Q1:stripe lock 为什么是 64 条?依据是什么?

**标准答案**:

> 64 是经验值,依据是目标部署场景 — 单机 2C4G,预期并发房间数约 10-50 个活跃房间,64 条 stripe 保证大概率不冲突。`h ^ (h >>> 16)` 的哈希混淆降低短连号 roomId 的碰撞。
>
> 如果房间数远超 64,stripe 碰撞会让不相关房间的请求相互阻塞。我加了 `chat.stripe.lock.wait.duration` Timer 来观察 — 生产环境 p99 wait > 10ms 就要考虑加 stripe 或者换成"按 roomId 动态分配 Lock + 弱引用清理"的方案。

**加分点**:能主动说 64 是经验值而不是银弹,并说出观测指标和替代方案。

---

### Q2:为什么不用 `synchronized (roomId.intern())`?

**标准答案**:

> 三个致命问题:
>
> 1. `String.intern()` 在 JVM 常驻字符串池,大量房间 ID 会造成 PermGen/Metaspace 泄漏
> 2. `synchronized` 没有超时和中断,链路任何一步抖动都会无限阻塞
> 3. 没有可观测性,生产出问题只能靠 jstack 猜

**陷阱**:有些面试官会反问"HashedWheelTimer 里也用类似模式,为什么他们敢用?"
答:Netty 的 HashedWheelTimer 用的是固定大小 bucket 数组,和我这里的固定 stripe 是同一种思路,不是 intern。

---

### Q3:mailbox 队列满抛 `RejectedExecutionException`,用户消息直接丢了吗?前端感知吗?

**标准答案**:

> 不会静默丢。`submit` 返回的是 `CompletableFuture<Void>`,我在调用方用 `whenComplete` 挂了兜底 — 失败会增 `chat.mailbox.rejected` counter 并打 WARN。
>
> 但要注意,主请求线程已经在 Layer 3 的 XADD 成功后就返回给客户端了 —— 从用户视角是"消息发送成功",但副作用(广播、未读数)可能丢。这是 durable-first 的权衡:我保证消息不丢(XADD 成功即入 Stream),但不保证副作用 100% 成功。
>
> 如果 `chat.mailbox.rejected` 不是 0,说明 mailbox 容量或 worker 数不够,应扩容而不是让请求线程阻塞。

**加分点**:区分"durable accept"和"side-effect delivery"两个语义层。

---

### Q4:Layer 4 广播失败了,前端用户看不到消息,怎么办?

**标准答案**:

> 这是重构里**刻意接受**的行为:广播失败不 rethrow,只增 `chat.sideeffect.fail.broadcast` counter。
>
> 补偿机制有两条兜底:
> 1. 消息已经通过 XADD 进入 Redis Stream,有独立的 Consumer 批量入库
> 2. 客户端断线重连 / 拉历史消息会经 `getHistoryMessages` 从 DB 读取
>
> 所以广播丢不等于消息丢 — 丢的只是"实时推送这一次",用户下次 ack 或刷新时会补回来。如果线上 `sideeffect.fail.broadcast` 持续非 0,那是 WS channel 的问题,需要进 channel 层排查,不应该让业务链路为此买单。

---

### Q5:为什么 `createTime` 由调用方传入,而不是 `saveAsync` 自己生成?

**标准答案**:

> 原始实现有双写 — 调用方和 `saveAsync` 各自调一次 `LocalDateTime.now()`,两个时间点相差几十微秒到几毫秒。这会让 DB 里存的 `createTime`、Stream 里的 body、广播 DTO 里的 `timestamp` 都对不上。
>
> 统一由调用方在锁外预计算一次,全链路用同一个时刻源。锁外预计算还有第二个好处 —— 不占用临界区。

**加分点**:把"双写"和"锁外预计算"两个决策串起来讲。

---

### Q6:为什么锁外预计算 `msgId`,不怕 UUID 跟消息 seq 对不上?

**标准答案**:

> `msgId` 是 UUID,用于业务层面幂等和跨服务追踪,跟 `msgSeqId`(Redis INCR 单调递增)没有绑定关系。只有 `msgSeqId` 需要在锁内生成(因为它依赖 Redis INCR 顺序),`msgId`/`nowMillis`/`createTime` 都是独立的,锁外算好再塞进 MessageDO.builder 即可。
>
> 这是"把临界区做瘦"的一个具体手法 —— 临界区只包含真正需要顺序保证的操作。

---

### Q7:你怎么测"同房间 FIFO"这种并发不变量?

**标准答案**:

> 用"注入式验证"。具体见 `RoomSideEffectMailboxConcurrencyTest#sameRoomSubmitsShouldExecuteInStrictFifoOrder`:
>
> 1. 16 个生产者线程并发调用 `mailbox.submit`
> 2. 用一个 `synchronized (submitLock)` 把"原子序号分配 + 入队"两步捆在一起,保证"submit 调用顺序 = 序号递增顺序"
> 3. 每个 task 内部把自己的序号加到 `Collections.synchronizedList`
> 4. 所有 task 完成后断言 `executedOrder` 严格等于 `[0, 1, ..., 1999]`
>
> 关键点是**并发提交但提交入队是原子的** — 这正好模拟了生产代码里"在 stripe 锁内调用 submit"的场景。如果不加 `submitLock`,测试本身就会失败,但那是测试设计错了,不是被测组件错了。

**加分点**:能说出"测试设计要反映被测组件真实使用模式"。

---

### Q8:Error(比如 OOM)在 worker 线程被抛出,你怎么处理的?

**标准答案**:

> 明确区分 `Exception` 和 `Error`:
>
> - `safeXxx` 用 `catch (Throwable t)` 捕获所有,但只是**记 counter + rethrow Error**
> - `Exception` 被吸收(失败隔离)
> - `Error`(OOM、StackOverflow、LinkageError)必须向上传播 — 这些是"进程已经坏了"的信号,吞掉等于让进程带伤运行
>
> 原始实现里直接 `catch (Exception e)`,对 Error 是透明的 — 但那只是因为 Error 不继承 Exception,不是刻意设计。我在重构后显式区分,语义更清晰。

---

### Q9:stripe 锁超时 3 秒的依据?

**标准答案**:

> 经验值,依据是"病态阈值"。正常 XADD 耗时 ~1ms,正常 stripe 等待 <10ms。如果一个请求在同一个 stripe 上等了 3s,说明前面的请求要么卡在 Redis 调用上要么链路已经坏掉,继续等只会把调用方的线程池也填满。
>
> 3 秒这个值是可配置的(`RoomSerialLock(long defaultTimeoutMs, MeterRegistry)` 构造函数),生产上配到 application.yml。如果压测发现 3s 太激进,可以调大。

---

### Q10:你为什么选 Redis Stream 做 durable handoff,而不是直接写 DB?

**标准答案**:

> 两个原因:
>
> 1. **写 DB 是同步 IO**,MySQL 写延迟在单机 SSD 上大约 1-5ms,高并发下还会因连接池排队放大到几十 ms,直接拖 RT
> 2. **Redis Stream 的 XADD 是纯内存操作**,p99 <1ms,且支持 MAXLEN trim 保证不会无限增长
>
> 流程是:XADD 成功 → 请求线程返回客户端("消息已接收") → 后台 Consumer 批量拉取 Stream 数据异步入库。用户感知的 RT 只包含 XADD,不包含 DB 写入。
>
> 缺点:Redis 崩溃 + 数据未持久化到 AOF 的窗口内会丢消息。这是**单机部署的刻意取舍** — 完美的 durable 需要 Kafka/RocketMQ 多副本,运维复杂度不值得。

---

### Q11:如果房间内有一个慢客户端,会不会拖累同房间其他人的广播?

**标准答案**:

> 不会,这是重构前就解决的独立问题(参见 commit `f9c8d87 重构广播路径 + 引入慢客户端保护`)。广播层对单个 channel 用 WriteBufferHighWaterMark 做背压,写不进去就丢弃那个 channel 的这条消息,不会阻塞其他 channel。
>
> 但这里有个有意思的交互 — 如果慢客户端多到 `broadcastToRoom` 整体变慢,会拖慢 mailbox shard 的 worker,进而让其他同 shard 房间的副作用也变慢。这个风险我在重构时注意到了,缓解方案是增加 shard 数,把房间分散开。目前没压测到这个瓶颈,列入后续工作。

**加分点**:主动说出"没碰过的风险"并给出缓解思路,比假装全知靠谱。

---

### Q12:为什么系统消息走 drop-on-timeout,用户消息走抛 BUSY_ROOM?

**标准答案**:

> 语义不同:
>
> - **用户消息**有等待方(用户在界面上盯着 loading),超时必须给前端一个明确反馈("房间繁忙请重试"),前端 toast + 重试
> - **系统消息**是后台触发(入房通知、房主变更),没有用户直接等待。如果 stripe 锁抖动时向上抛异常,会污染触发系统消息的那个业务(比如"加入房间"本身会失败)
>
> 所以系统消息选择 drop + WARN + counter,保护主业务不被背景噪声拖垮。

---

### Q13:限流放在哪里?为什么?

**标准答案**:

> 放在第一层最早位置 — 禁言检查之后、内容校验和审核之前。理由:
>
> 1. 限流是一次廉价 Redis 调用,放早不占下游资源
> 2. 畸形请求(content/files 都空)也应该消耗一次限流额度,防止攻击者用畸形包绕过限流
> 3. 被限流的请求**不进入** stripe 锁 —— 如果放在锁内,被限流的请求仍然要排队拿锁,没意义
>
> 限流枚举有三个维度:user_global / user_room / room_global,对应"防某个用户刷屏"和"防某个房间整体过载",用同一个 Lua 脚本原子判断。

**陷阱**:面试官可能问"为什么不放最前面、校验之前"。
答:因为 `accountId` 和 `roomId` 还没校验完,放最前面可能对无效 ID 调用 Redis,小概率但是多余。

---

### Q14:你的测试用例为什么不 Mock?

**标准答案**:

> 分两类:
>
> - **组件级并发测试**(`RoomSerialLockTest`, `RoomSideEffectMailboxConcurrencyTest`)不 Mock,因为要测的就是 `ReentrantLock` / `ExecutorService` 这些并发原语的交互行为。Mock 掉就等于测 Mock,没意义
> - **服务级单元测试**(`MessageSideEffectServiceImplTest`)用 Mockito 把下游 `MessageWindowService` / `RoomChannelManager` 等 mock 掉,因为要测的是编排逻辑,不是下游行为
>
> 这里的关键判断是:"被测单元的本质是不是可以脱离协作者独立定义?"`RoomSerialLock` 的本质就是对 `ReentrantLock` 的使用,所以必须用真的 lock。

**加分点**:能说出"测试边界的选择取决于被测单元的本质"。

---

### Q15:这个重构对业务 SLA 有量化影响吗?

**诚实答案(推荐)**:

> 目前只有单元测试的正确性保证,没有生产环境的量化数据。重构收敛的时候我在 P0 清单里列了"线上压测 + Grafana 面板",但还没执行。
>
> 从理论上能说的是:请求线程不再同步执行 4 项副作用,RT 上限从"XADD + 窗口 + 广播 + 未读数"降到"XADD",减少 3 次 Redis RTT + 1 次 channel 写入。单次预计节省 5-20ms,但这是估算,不是实测。

**加分点**:不吹牛。面试官更相信"我承认没测过"的人。

---

## 三、可能的陷阱题

### T1:"你这个 mailbox 其实就是 Disruptor 的山寨版吧?"

**答**:

> 不完全是。Disruptor 的 RingBuffer 是单生产者-单消费者或多生产者-单消费者的 lock-free 结构,吞吐极高,但学习成本和集成复杂度都高。我这里用 `ThreadPoolExecutor + LinkedBlockingQueue` 是因为:
>
> 1. 业务 QPS 目标在 1k/s 级别,远没到需要 Disruptor 的量级
> 2. `ThreadPoolExecutor` 的 `execute/shutdown` 语义已经足够,且社区熟
> 3. Disruptor 的 wait strategy 调不好反而会烧 CPU
>
> 场景不同不能直接类比。如果压测发现 queue 成为瓶颈,换 Disruptor 是下一步。

---

### T2:"你的临界区包含 XADD(~1ms),这不是锁里做 IO 吗?"

**答**:

> 对,这是**已知的刻意权衡**,不是疏忽。原因:
>
> 1. `msgSeqId` 分配(Redis INCR)和 `XADD` 必须在同一个"房间顺序点"上原子化 — 否则两个请求拿到的 seqId 和 Stream 写入顺序可能倒置
> 2. 不锁 XADD 就要用 "先提交到队列 → worker 线程再分配 seqId" 的模式,但这让 seqId 分配权离开了 request thread,前端返回需要等 worker,反而拖 RT
>
> 我的选择是:用 stripe lock 分散热点(64 条,不同房间不互相干扰),单个 stripe 内短暂 hold 1ms 的 XADD 是可接受的。配合 `tryLock(3s)` 超时兜底病态场景。

**加分点**:能说"这是取舍,不是疏忽",并说清对立方案的缺点。

---

### T3:"你怎么知道 64 条 stripe 够?"

答已在 Q1,此处要补充一个**具体的退路方案**:

> 如果生产观察到 stripe 热点,第一步是看 `chat.stripe.lock.wait.duration` 的 p99 和 `chat.stripe.lock.timeout` 的速率。第二步有两个选择:
>
> - **加 stripe 数到 256/1024**:简单,但浪费内存(每条 stripe 一个 ReentrantLock 对象)
> - **改成 `ConcurrentHashMap<roomId, ReentrantLock>`**:按需分配,配合弱引用定期清理 — 更精细但要处理清理时机

---

### T4:"你没测 Layer 3 和 Layer 4 的集成,怎么保证 durable 语义?"

**答**:

> 单元测试层面没覆盖 Layer 3 + Layer 4 的端到端,这是事实。我在重构总结里把"Consumer 侧审计 + Testcontainers 端到端压测"列为未完成的 P0 项。
>
> 单元测试覆盖的是"每一层各自的不变量",集成保证要靠:
>
> 1. Testcontainers + 真 Redis 跑端到端(未做)
> 2. 线上 `chat.sideeffect.fail.*` 持续监控(已做)
> 3. Consumer 侧的幂等和 ack 机制(未改动,复用原有)

---

## 四、面试准备要点总结

1. **先抛结论再讲细节** — "我拆成 4 层,原因是..."
2. **区分已验证和未验证** — 不确定的事主动说"这个我没实测"
3. **每个决策都带着"为什么不是另一种"** — "我用 tryLock 而不是 synchronized 因为..."
4. **把 trade-off 说清楚** — 没有完美方案,面试官要看你知不知道自己付了什么代价
5. **测试是活文档** — 追问并发正确性时直接引用压测用例,比空谈模型更有说服力
6. **指标先行** — "我加了 X 个 counter,线上出问题先看哪个"
7. **诚实 > 吹牛** — "我不知道"比"我装懂"的加分多得多
