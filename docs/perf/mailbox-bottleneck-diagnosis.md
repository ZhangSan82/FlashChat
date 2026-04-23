# Mailbox 堆积定位说明

## 1. 这次要解决什么问题

前面的压测已经确认：

- `20 房间 x 50 人 x 175 VU` 在边缘区会出现明显波动
- 旧版 `mailbox shard=4` 时，最先恶化的是 `mailbox backlog` 和 WS 广播延迟
- 调到 `mailbox shard=8` 后，问题被明显推后，但 `175 VU` 仍然不是完全稳定档

下一步不是继续猜，而是要回答两个更具体的问题：

1. `mailbox` 里到底是哪一步最慢
2. 是“排队等执行”导致的，还是“某个步骤本身执行慢”导致的

## 2. 真实执行链路

用户消息在进入 `mailbox` 后，当前真实执行顺序是：

1. `window_add`
2. `broadcast`
3. `touch`
4. `unread`

代码位置：

- 副作用编排：
  - [MessageSideEffectServiceImpl.java](/d:/javaproject/FlashChat/services/chat-service/src/main/java/com/flashchat/chatservice/service/impl/MessageSideEffectServiceImpl.java)
- mailbox 队列与 shard：
  - [RoomSideEffectMailbox.java](/d:/javaproject/FlashChat/services/chat-service/src/main/java/com/flashchat/chatservice/service/dispatch/RoomSideEffectMailbox.java)
- WebSocket 广播：
  - [RoomChannelManager.java](/d:/javaproject/FlashChat/services/chat-service/src/main/java/com/flashchat/chatservice/websocket/manager/RoomChannelManager.java)
- 窗口写入：
  - [MessageWindowServiceImpl.java](/d:/javaproject/FlashChat/services/chat-service/src/main/java/com/flashchat/chatservice/service/impl/MessageWindowServiceImpl.java)
- 未读递增：
  - [UnreadServiceImpl.java](/d:/javaproject/FlashChat/services/chat-service/src/main/java/com/flashchat/chatservice/service/impl/UnreadServiceImpl.java)

其中：

- `touch` 只是内存内更新活跃时间，理论上最不应该成为主瓶颈
- 真正值得重点怀疑的是：
  - `window_add`
  - `broadcast`
  - `unread`

## 3. 这次新增了哪些定位指标

### 3.1 mailbox 队列级

- `chat.mailbox.queue.wait.duration`
  - 任务从 `submit()` 到真正开始执行之间的等待时间
- `chat.mailbox.task.duration`
  - 任务真正进入 worker 后的执行时间

如果：

- `queue.wait` 明显高于 `task.duration`

说明：

- 更像是 shard 排队造成的堆积
- 也就是“队列先堵了”

### 3.2 副作用步骤级

- `chat.side_effect.step.duration{step=window_add}`
- `chat.side_effect.step.duration{step=broadcast}`
- `chat.side_effect.step.duration{step=touch}`
- `chat.side_effect.step.duration{step=unread}`
- 以及 reaction / recall / delete 对应步骤

如果某一步的 `avgMs / maxMs` 明显最高，它就是当前最像的慢步骤。

### 3.3 broadcast 子阶段级

为了防止只看到 `broadcast` 慢、但不知道慢在什么地方，这次又把它拆成了：

- `flashchat.broadcast.serialize.duration`
- `flashchat.broadcast.write.duration`
- `flashchat.broadcast.flush.duration`

对应关系：

- `serialize`
  - `WsRespDTO -> JSON` 序列化
- `write`
  - 遍历房间在线连接并执行 `ch.write(...)`
- `flush`
  - 遍历连接执行 `ch.flush()`

## 4. 怎么跑

### 4.1 推荐的一键方式

```powershell
Set-Location D:\javaproject\FlashChat
powershell -ExecutionPolicy Bypass -File .\scripts\perf\Run-MultiRoom-20x50-175-MailboxProbe.ps1 -Runner local
```

这条会先跑：

- `20 房间 x 50 人 x 175 VU`

然后自动生成：

- `mailbox-step-diagnosis.json`
- `mailbox-step-diagnosis.md`

输出位置：

- 当前最新结果目录下

### 4.2 分开执行

先跑压测：

```powershell
Set-Location D:\javaproject\FlashChat
powershell -ExecutionPolicy Bypass -File .\scripts\perf\Run-MultiRoom-20x50-175.ps1 -Runner local
```

再做定位：

```powershell
Set-Location D:\javaproject\FlashChat
powershell -ExecutionPolicy Bypass -File .\scripts\perf\Inspect-MailboxStepMetrics.ps1
```

## 5. 结果怎么判断

### 5.1 如果 `queue.wait` 高，但 `task.duration` 不高

说明：

- 主要问题是 mailbox shard 排队
- 更像“同一时间进队列的任务太多”
- 不是单个任务内部特别慢

优先方向：

- 看 roomId 到 shard 的分布
- 看是不是多个高活跃房间撞到同一 shard
- 继续评估 `shard=8 -> 12/16` 是否有价值

### 5.2 如果 `window_add` 最慢

说明：

- Redis 窗口写入更像当前堆积入口

优先方向：

- 排查 Redis RTT
- 看窗口 key 是否出现热点
- 关注 pipeline 的真实耗时

### 5.3 如果 `broadcast` 最慢

再继续看：

- `serialize` 最慢
  - 说明 JSON 序列化/对象分配更像问题
- `write` 最慢
  - 说明房间 fan-out 写出更像问题
- `flush` 最慢
  - 说明 event loop / socket flush / 慢客户端更像问题

### 5.4 如果 `unread` 最慢

说明：

- 大房间按成员递增未读数更像瓶颈

优先方向：

- 评估是否要继续保留“每条消息即时全量未读递增”
- 看是否可以改成更轻的批量或懒更新策略

## 6. 当前先验判断

基于前面已经跑出来的结果，当前最像的路径仍然是：

- `mailbox / broadcast` 这条副作用链路

而不是：

- `touch`
- `DB/Hikari`
- `RoomSerialLock`

原因是之前多轮 `20x50` 压测里，最先恶化的一直是：

- `mailbox backlog`
- WS 广播 p95/p99

这次新加的埋点，就是为了把“broadcast 慢”继续拆成：

- `serialize`
- `write`
- `flush`

以及和 `window_add / unread` 做真正对比。

## 7. 怎么办

不要先大改业务逻辑，先按下面顺序做：

1. 用这次新增的诊断脚本，在 `20x50x175` 下跑 1 到 2 轮
2. 明确是：
   - `queue.wait` 主导
   - 还是 `window_add / broadcast / unread` 主导
3. 再针对最慢步骤做最小改动

当前最可能的优化方向优先级：

1. 如果 `queue.wait` 主导
   - 继续优化 shard 分布或 shard 数
2. 如果 `broadcast.write / flush` 主导
   - 优化 WS fan-out 与慢客户端处理
3. 如果 `unread` 主导
   - 优化大房间未读更新策略
4. 如果 `window_add` 主导
   - 优化 Redis 窗口写入与 key 热点

## 8. 本次涉及的脚本

- 运行压测：
  - [Run-MultiRoom-20x50-175.ps1](/d:/javaproject/FlashChat/scripts/perf/Run-MultiRoom-20x50-175.ps1)
- 一键压测 + 定位：
  - [Run-MultiRoom-20x50-175-MailboxProbe.ps1](/d:/javaproject/FlashChat/scripts/perf/Run-MultiRoom-20x50-175-MailboxProbe.ps1)
- 诊断摘要：
  - [Inspect-MailboxStepMetrics.ps1](/d:/javaproject/FlashChat/scripts/perf/Inspect-MailboxStepMetrics.ps1)
- 运行时采样：
  - [Watch-ActuatorRuntime.ps1](/d:/javaproject/FlashChat/scripts/perf/Watch-ActuatorRuntime.ps1)
- 诊断抓包：
  - [Capture-PerfDiagnostics.ps1](/d:/javaproject/FlashChat/scripts/perf/Capture-PerfDiagnostics.ps1)
