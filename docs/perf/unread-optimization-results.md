# 未读数优化后压测结果与代码变更总结

## 1. 文档目的

这份文档专门记录本轮“未读数优化”之后的代码改动、优化原因、测试结果、收益与代价，作为后续继续优化 `window_add`、继续上探容量、以及复盘架构取舍的固定记录。

本文档只关注：

- 未读数原实现为什么慢
- 本次是怎么改的
- 为什么这样改
- 改完后的优点和缺点
- 改完后在 `20 房间 x 50 人` 模型下的最新压测结果

## 2. 结论先看

本次优化的核心结论是：

- 旧实现中，未读数采用“写时 fan-out”，会在发消息后的副作用阶段，对房间内其他成员逐个执行 Redis 更新。
- 这部分逻辑虽然排在 mailbox 任务的尾部，但因为 `RoomSideEffectMailbox` 每个 shard 是单线程串行执行，所以它会显著拉长每个副作用任务的总时长，最终导致 mailbox 排队、backlog 上升、WS 延迟恶化。
- 本次改动把 unread 从“写时 fan-out”改成“读时 DB 统计”后，`unread` 已经退出主瓶颈。
- 优化后，系统在 `20x50` 模型下已经可以打到更高的吞吐；最新最好样本已经达到 **`712.59 msg/s`**。
- 但是高压样本仍然存在波动，说明当前系统的下一瓶颈并没有消失，而是转移到了 `mailbox queue wait + window_add`。

一句话总结：

> 本次优化有效移除了“未读数 fan-out”这个旧瓶颈，显著改善了 mailbox 排队与 WS 延迟，但系统在更高压力下仍然会出现新的 mailbox/shard 排队问题，`window_add` 已成为当前最慢的活跃副作用步骤。

## 3. 旧实现是怎么做未读数的

### 3.1 旧逻辑位置

旧逻辑主要在两个位置：

- [UnreadServiceImpl.java](/d:/javaproject/FlashChat/services/chat-service/src/main/java/com/flashchat/chatservice/service/impl/UnreadServiceImpl.java)
- [MessageSideEffectServiceImpl.java](/d:/javaproject/FlashChat/services/chat-service/src/main/java/com/flashchat/chatservice/service/impl/MessageSideEffectServiceImpl.java)

旧的副作用顺序是：

1. `window_add`
2. `broadcast`
3. `touch`
4. `incrementUnread`

注意这里很关键：

- `incrementUnread` 虽然排在最后
- 但它和前面几个步骤是在同一个 mailbox 任务里串行执行
- 当前 shard 的任务没有执行完，下一个任务就不能开始

所以旧 unread 逻辑虽然不是“排在最前面”，但它会拉长整个任务时长，间接拖慢后续所有任务。

### 3.2 旧 unread 的具体做法

旧实现是“写时 fan-out”：

- 发出 1 条消息后
- 找出这个房间里除发送者外的其他成员
- 对每个成员执行：
  - `HINCRBY`
  - `EXPIRE`

也就是说，在 `50` 人房间里：

- 每发 1 条消息，大约影响 `49` 个成员
- 需要执行约：
  - `49` 次 `HINCRBY`
  - `49` 次 `EXPIRE`

虽然这些命令走了 Redis pipeline，网络往返次数减少了，但 Redis 侧仍然要真正执行这些命令。

这就形成了很典型的“写放大”问题：

- 消息越多
- 房间人数越多
- unread fan-out 成本越高

### 3.3 为什么旧实现会导致性能变差

根因不是“Redis 本身慢”，而是：

- `UnreadServiceImpl.incrementUnread()` 太重
- 它位于 mailbox 单线程任务里
- 于是 mailbox 每处理一条消息，都要额外承担大量 unread fan-out 成本

最终表现为：

- `chat.mailbox.task.duration` 变长
- `chat.mailbox.queue.wait.duration` 变长
- `mailboxBacklog` 上升
- WS 广播延迟恶化

## 4. 本次怎么优化的

### 4.1 本次优化思路

本次没有继续在 Redis 上做“更快的 fan-out”，而是直接换思路：

- **不再在写路径更新 unread**
- **改成在读路径按需实时计算 unread**

也就是：

- 发消息时：不再 `incrementUnread`
- 查 unread 时：基于 `room_id + last_ack_msg_id + status=0` 从 DB 实时统计

### 4.2 代码层面的实际改动

#### 1. 停用写时 unread fan-out

文件：

- [MessageSideEffectServiceImpl.java](/d:/javaproject/FlashChat/services/chat-service/src/main/java/com/flashchat/chatservice/service/impl/MessageSideEffectServiceImpl.java)

改动：

- 在 `dispatchUserMessageAccepted(...)` 中，不再执行 `safeIncrementUnread(...)`
- 旧调用逻辑以注释方式保留，便于回溯和 A/B 对比

#### 2. `incrementUnread()` 改成 no-op

文件：

- [UnreadServiceImpl.java](/d:/javaproject/FlashChat/services/chat-service/src/main/java/com/flashchat/chatservice/service/impl/UnreadServiceImpl.java)

改动：

- `incrementUnread(roomId, excludeMemberId)` 不再做真实 Redis 更新
- 原来的 pipeline 代码完整保留在注释中，没有删除

#### 3. unread 查询改成读时 DB 统计

文件：

- [UnreadServiceImpl.java](/d:/javaproject/FlashChat/services/chat-service/src/main/java/com/flashchat/chatservice/service/impl/UnreadServiceImpl.java)
- [MessageMapper.java](/d:/javaproject/FlashChat/services/chat-service/src/main/java/com/flashchat/chatservice/dao/mapper/MessageMapper.java)

当前主路径：

- `getAllUnreadCounts()` -> `computeAllFromDB()`
- `getUnreadCount()` -> `computeOneFromDB()`

使用的 SQL 包括：

- `selectBatchUnreadCounts(accountId)`
- `selectUnreadMsgIds(roomId, ackId)`

#### 4. ACK 语义复用既有字段

当前 unread 的读时统计，依赖现有字段：

- `t_room_member.last_ack_msg_id`

对应实体：

- [RoomMemberDO.java](/d:/javaproject/FlashChat/services/chat-service/src/main/java/com/flashchat/chatservice/dao/entity/RoomMemberDO.java)

也就是说，本次优化没有重造一套已读模型，而是直接复用了现有 ACK 语义。

### 4.3 为什么这样改是正确的

这里要特别说明一个经常会被误解的问题：

> “所有消息都在一张表里，会不会导致 unread 算错？”

答案是：**不会**，因为现在不是用“最新消息 ID - 最后 ACK ID”做差，而是按条件实时查询：

- `room_id = 当前房间`
- `id > last_ack_msg_id`
- `status = 0`

所以：

- 别的房间的消息不会被算进来
- recall/delete 后的消息也不会继续算 unread

这比直接用“全局最新 ID 差值”更准确。

## 5. 本次优化的优点

### 5.1 直接移除了旧瓶颈

优化前，mailbox probe 已经明确证明：

- `unread` 是最重的副作用步骤之一
- 它和 `window_add` 一起拖慢了整个 mailbox

优化后：

- `unread = 0`
- unread 已经退出副作用主路径

### 5.2 显著降低了 mailbox 排队

同口径 `20x50x175 mailbox probe` 对比：

#### 优化前

结果目录：

- [20260415-225738-ten-room-multi-room-20x50-175vu](/d:/javaproject/FlashChat/perf/results/20260415-225738-ten-room-multi-room-20x50-175vu)

关键指标：

- 总吞吐：`528.77 msg/s`
- HTTP `p95=69.12ms`
- WS `p95=6608ms`
- `queue wait avg = 1378.656ms`
- `task duration avg = 5.613ms`
- `unread avg = 2.946ms`
- `window_add avg = 2.614ms`

#### 优化后

结果目录：

- [20260416-003417-ten-room-multi-room-20x50-175vu](/d:/javaproject/FlashChat/perf/results/20260416-003417-ten-room-multi-room-20x50-175vu)

关键指标：

- 总吞吐：`559.47 msg/s`
- HTTP `p95=44.99ms`
- WS `p95=57ms`
- `queue wait avg = 2.194ms`
- `task duration avg = 1.394ms`
- `window_add avg = 1.34ms`
- `unread = 0`

这说明：

- queue wait 从秒级下降到毫秒级
- WS 从秒级恢复到百毫秒内
- 总吞吐也同步提升

### 5.3 把系统上限继续往上推

优化后继续上探的结果：

- `185 VU` 好样本：
  - [20260416-123503-ten-room-multi-room-20x50-185vu](/d:/javaproject/FlashChat/perf/results/20260416-123503-ten-room-multi-room-20x50-185vu)
  - `606.56 msg/s`
- `190 VU` 好样本：
  - [20260416-124531-ten-room-multi-room-20x50-190vu](/d:/javaproject/FlashChat/perf/results/20260416-124531-ten-room-multi-room-20x50-190vu)
  - `615.06 msg/s`
- `200 VU probe`：
  - [20260416-132358-ten-room-multi-room-20x50-200vu](/d:/javaproject/FlashChat/perf/results/20260416-132358-ten-room-multi-room-20x50-200vu)
  - `623.48 msg/s`
- `230 VU` 最新最好样本：
  - [20260416-151126-ten-room-multi-room-20x50-230vu](/d:/javaproject/FlashChat/perf/results/20260416-151126-ten-room-multi-room-20x50-230vu)
  - `712.59 msg/s`

这说明 unread 改造不是“小修小补”，而是真的把容量边界推高了。

## 6. 本次优化的缺点与代价

### 6.1 unread 查询压力从 Redis 写路径转移到了 DB 读路径

这是本次改造最大的 trade-off。

旧模型：

- 发消息时重
- unread 查询时轻

新模型：

- 发消息时轻
- unread 查询时更依赖 DB 统计

也就是说：

- 我们减少了发送链路上的写放大
- 但 unread 读接口的 DB 压力会上升

### 6.2 不适合把“所有请求都直接查库”当作通用策略

这次改造可行，是因为：

- 当前瓶颈在发送链路
- 而不在 unread 查询接口

所以：

- “unread 改成读时查库”是合理的
- 但这不代表“所有路径都应该直接查数据库”

### 6.3 高压下的新瓶颈会暴露得更明显

本次优化移除了旧瓶颈后，系统更容易继续打到新的瓶颈。

当前 probe 已经证明：

- `unread` 已经退出主矛盾
- 现在最慢的活跃副作用步骤变成了 `window_add`
- 更高压力下，mailbox 仍然会因为 shard 排队而波动

也就是说：

- 优化没有“让系统从此没有瓶颈”
- 而是把瓶颈从 unread 挪到了下一段

### 6.4 高位样本波动仍然存在

同样是 `230 VU`，已经出现过两类样本：

#### 好样本

- [20260416-151126-ten-room-multi-room-20x50-230vu](/d:/javaproject/FlashChat/perf/results/20260416-151126-ten-room-multi-room-20x50-230vu)
- `712.59 msg/s`
- HTTP `p95=69.46ms`
- WS `p95=77ms`

#### 差样本

- [20260416-135025-ten-room-multi-room-20x50-230vu](/d:/javaproject/FlashChat/perf/results/20260416-135025-ten-room-multi-room-20x50-230vu)
- `560.77 msg/s`
- HTTP `p95=369.67ms`
- WS `p95=317ms`

这说明：

- `230 VU` 仍然属于临界区
- 不能直接拿最好样本当稳定值

## 7. 优化后新的瓶颈是什么

最新 probe 说明：

- unread 已经不再是问题
- 当前最慢的活跃副作用步骤是：
  - `window_add`

对应文件：

- [MessageWindowServiceImpl.java](/d:/javaproject/FlashChat/services/chat-service/src/main/java/com/flashchat/chatservice/service/impl/MessageWindowServiceImpl.java)

当前新的瓶颈形态可以概括为：

1. `window_add` 是副作用里最慢的步骤
2. 更高负载下 mailbox shard 仍会出现排队
3. 房间到 shard 的分布差异会导致高位样本波动

一句话说：

> 未读数优化后，系统的旧瓶颈已经被移除，当前新的主要瓶颈是 `window_add + mailbox shard queueing`。

## 8. 当前可用的容量结论

### 8.1 稳定承载值

目前更保守、可重复、适合作为“稳定值”的区间：

- `200 VU`
- 大约 **`620 msg/s`**

参考结果：

- [20260416-132358-ten-room-multi-room-20x50-200vu](/d:/javaproject/FlashChat/perf/results/20260416-132358-ten-room-multi-room-20x50-200vu)
- `623.48 msg/s`
- HTTP `p95=65.91ms`
- WS `p95=89ms`

### 8.2 当前观测到的高位能力

当前最好样本：

- [20260416-151126-ten-room-multi-room-20x50-230vu](/d:/javaproject/FlashChat/perf/results/20260416-151126-ten-room-multi-room-20x50-230vu)
- **`712.59 msg/s`**

但它应被理解为：

- 上沿能力样本
- 不是长期稳定值

## 9. 后续建议

下一步不应该再回头优化 unread，而应该：

1. 优先优化 `MessageWindowServiceImpl.addToWindow()`
2. 继续观察 `roomId -> shard` 分布导致的高位波动
3. 必要时继续提升 shard 数或改进热点房间分布策略

## 10. 最终总结

本次 unread 优化的核心价值，不是“让 unread 更优雅”，而是：

- 把 unread 从写时 fan-out 改成读时 DB 统计
- 从根上移除了发送副作用阶段的大量写放大

改动后，系统已经从：

- unread 重、mailbox 排队严重、WS 秒级抖动

变成：

- unread 不再是瓶颈
- mailbox 大多数时候可控
- 可以继续把系统推到更高吞吐

当前最准确的总结是：

> 未读数优化是有效的，而且是决定性的；它不是把系统“优化结束了”，而是让系统越过了旧瓶颈，并把新的主瓶颈暴露为 `window_add + mailbox shard queueing`。在此基础上，系统当前已经观察到的高位能力样本达到 `~713 msg/s`。
