# FlashChat 架构优化总结

> 日期：2026-04-12  
> 范围：chat-service、game-service  
> 目标：窗口可见边界优化、未读数轻量化、线程池与后台任务收敛

---

## 一、窗口可见边界优化

### 问题

| 问题 | 影响 |
|------|------|
| `lastAckMsgId == 0` 时 `/new` 跳过 Redis 窗口，直接查 DB 全量消息 | 新用户首次进房间触发全表扫描，高消息量房间性能灾难 |
| `/history` 接口无可见边界限制 | 任何成员都可无限翻阅全部历史，不符合匿名社交产品调性 |
| ACK 逻辑（lambdaUpdate + evictCache + clearUnread）散落在 3 处 | 维护困难，容易分叉 |

### 改动

#### 1. 统一 ACK 方法 `advanceAckAndClearUnread`

**文件**：`ChatServiceImpl.java`

抽取私有方法，封装"只增不减原子更新 + 缓存淘汰 + 未读清零"三步操作。原来散落在 `ackMessages()`、`getNewMessages()` 窗口路径、DB 路径的 3 处重复代码全部替换为单行调用。

```java
private boolean advanceAckAndClearUnread(Long memberId, String roomId, Long accountId, Long latestSeenMsgId)
```

#### 2. `getNewMessages` 增加 `lastAckMsgId == 0` 分支

**文件**：`ChatServiceImpl.java`

新增 `getNewMessagesForFirstJoin()` 方法：

- 优先调 `messageWindowService.getLatestFromWindow(roomId, 100)` 从 Redis 取最近 100 条
- 窗口不可用时 DB 兜底最近 100 条（仍不开放更早历史）
- 自动 ACK 推进到最新消息，后续请求走正常增量路径

#### 3. `getHistoryMessages` 前置可见边界限制

**文件**：`ChatServiceImpl.java`

前置检查 `lastAckMsgId == 0` 的成员（首次加入），直接返回空列表 + `isLast=true`，不查窗口也不查 DB。

#### 数据流对比

| 场景 | 改动前 | 改动后 |
|------|--------|--------|
| 新用户 `/new` | 跳过窗口 → DB 全量扫描 | Redis 窗口最近 100 条 → DB 兜底 100 条 |
| 新用户 `/history` | 无限翻阅全部历史 | 直接返回空 + `isLast=true` |
| 老用户 `/new` | 不变 | 不变 |
| 老用户 `/history` | 不变 | 不变 |

---

## 二、未读数轻量化

### 问题

| 问题 | 影响 |
|------|------|
| TTL 72 小时偏长 | 僵尸 key 在 Redis 中存活过久，浪费内存 |
| `incrementUnread` Pipeline 没有续期 TTL | TTL 收紧后活跃用户的 unread key 可能意外过期 |
| `computeAllFromDB` N+1 查询 | 用户加入 N 个房间 → N+1 次 DB 查询，低频但极端情况有风险 |

### 改动

#### 1. TTL 72h → 24h

**文件**：`UnreadServiceImpl.java`

```java
// 改动前
private static final long KEY_EXPIRE_HOURS = 72;

// 改动后
private static final long KEY_EXPIRE_HOURS = 24;
```

#### 2. Pipeline 中追加 EXPIRE 续期

**文件**：`UnreadServiceImpl.java`

在 `incrementUnread` 的 Pipeline 中，每个用户的 `HINCRBY` 后追加一条 `EXPIRE`，确保活跃用户的 unread key 不会因 TTL 收紧而意外过期。同一 RTT 内完成，无额外网络开销。

```java
connection.hashCommands().hIncrBy(key, field, 1);
connection.commands().expire(key, expireSeconds);  // 新增
```

#### 3. DB 兜底 N+1 → 聚合 SQL

**文件**：`MessageMapper.java`、`UnreadServiceImpl.java`

新增 `selectBatchUnreadCounts` 聚合查询，`JOIN t_room_member + t_message`，按 `room_id` 分组 `COUNT`，单条 SQL 替代 N+1 循环。

| 改动前 | 改动后 |
|--------|--------|
| 先查用户所有活跃房间，再循环每个房间 `SELECT COUNT` | 单条 `JOIN + GROUP BY` 聚合查询 |
| 20 个房间 = 1 + 20 = 21 次 DB 查询 | 20 个房间 = 1 次 DB 查询 |

```sql
SELECT rm.room_id, COUNT(m.id) AS cnt
FROM t_room_member rm
INNER JOIN t_message m
  ON m.room_id = rm.room_id
  AND m.id > COALESCE(rm.last_ack_msg_id, 0)
  AND m.status = 0
WHERE rm.account_id = #{accountId}
  AND rm.status = 1
GROUP BY rm.room_id
HAVING cnt > 0
```

---

## 三、线程池与后台任务收敛

### 问题

| 问题 | 影响 |
|------|------|
| AI 执行器使用 `CallerRunsPolicy` | 外部 AI 超时时反压调用方线程，可能卡死游戏回合推进 |
| 5 个 `@Scheduled` Job 跑在 Spring 默认匿名调度器上 | 无命名、无错误处理、无优雅关闭，不可监控 |

### 改动

#### 1. AI 执行器拒绝策略：CallerRunsPolicy → 快速失败降级

**文件**：`GameThreadPoolConfig.java`（game-service）

| 改动前 | 改动后 |
|--------|--------|
| `CallerRunsPolicy` — 队列满时在调用方线程同步执行 AI HTTP 调用 | 自定义 Lambda — 打 WARN 日志后丢弃任务 |

安全性保证：AI 任务在提交前已注册超时定时器（`scheduleDescribeTimeout` / `scheduleVoteTimeout`），被丢弃的 AI 任务会由超时回调兜底推进游戏流程。

#### 2. 统一 MaintenanceScheduler

**文件**：`AsyncConfiguration.java`（chat-service）

新增 `taskScheduler()` Bean（`ThreadPoolTaskScheduler`），覆盖 Spring 默认调度器：

- 单线程、daemon、线程名 `maintenance-`
- 带 `ErrorHandler` 防止异常吞没
- 优雅关闭等待 10 秒

承载的 5 个维护任务（无需修改 Job 代码，自动生效）：

| Job | 频率 |
|-----|------|
| RoomExpireCompensateJob | 60s fixedDelay |
| UnreadCleanupJob | 每天 3:00 AM |
| RoomMemberCountReconcileJob | 5min fixedDelay |
| RoomMemberCleanupJob | 5min fixedDelay |
| SensitiveWordReload | 5min fixedDelay |

#### 优化后的 4 层执行模型

```
IO 层          Netty Boss(1) + Worker(2)         — 只做 IO
主链路业务层    ws-biz-(4~8) + room-side-(8×1)    — 短任务，快速失败
高价值后台层    msg-stream-consumer(1)             — 独立守护线程
               room-delay-consumer(1)             — 独立守护线程
波动型外部IO    game-ai-(4~8)                      — 快速失败，可降级
低优先级维护    maintenance-(1)                    — 顺序执行，低频低扰动
游戏定时器      game-timer-(2)                     — 保持独立
```

---

## 改动文件清单

| 文件 | 模块 | 改动类型 |
|------|------|---------|
| `ChatServiceImpl.java` | chat-service | 新增 `advanceAckAndClearUnread`、`getNewMessagesForFirstJoin`；修改 `getNewMessages`、`getHistoryMessages`、`ackMessages` |
| `UnreadServiceImpl.java` | chat-service | TTL 72h→24h；Pipeline 追加 EXPIRE；`computeAllFromDB` 改为聚合查询 |
| `MessageMapper.java` | chat-service | 新增 `selectBatchUnreadCounts` 聚合查询方法 |
| `AsyncConfiguration.java` | chat-service | 新增 `MaintenanceScheduler` Bean |
| `GameThreadPoolConfig.java` | game-service | AI 执行器拒绝策略改为快速失败降级 |
