# FlashChat 多级缓存架构优化总结

> **日期**: 2026-04-12  
> **范围**: Room / Account / RoomMember 三个缓存域  
> **目标**: 建立统一、可维护的缓存访问协议，按数据特征合理使用高级缓存能力

---

## 1. 优化背景

### 改动前的问题

| 问题 | 影响 |
|------|------|
| 缓存 key 散落在业务代码中，手搓 `CacheUtil.buildKey(...)` | key 格式不一致、Caffeine 前缀路由出错风险 |
| 写路径使用手动 `bloom.add + put`，与 `safePut` 语义割裂 | 漏 add Bloom / 漏写本地缓存 |
| Room 使用 BloomFilter 但无法处理已关闭房间 | Bloom 不可删除，已关闭房间仍穿透到 DB |
| 公开房间列表无缓存 | 每次请求直接查 DB，高频页面承压 |
| Account 双 key（byAccountId / byDbId）散落在业务代码 | 忘记失效其中一个 key 导致脏数据 |

---

## 2. 架构概览

### 缓存层次模型

```
┌─────────────┐
│   Client     │
└──────┬───────┘
       ▼
┌──────────────────────────────────────────┐
│        MultistageCacheProxy              │
│  ┌───────────┐    ┌────────────────────┐ │
│  │ Caffeine  │ ←→ │ Redis (Distributed)│ │
│  │ (L1 本地) │    │ (L2 分布式)        │ │
│  └───────────┘    └────────────────────┘ │
│         ↓ miss          ↓ miss           │
│  ┌────────────────────────────────────┐  │
│  │      DB (MySQL)  CacheLoader      │  │
│  └────────────────────────────────────┘  │
└──────────────────────────────────────────┘
```

### 本地缓存路由规则

| Key 前缀 | Caffeine Cache 域 |
|----------|-------------------|
| `flashchat_room_` | roomCache |
| `flashchat_roomMember_` | roomMemberCache |
| `flashchat_account_` | accountCache |

---

## 3. 新增文件

### 3.1 Room 域

#### `RoomCacheKeys.java`

> `services/chat-service/.../cache/RoomCacheKeys.java`

统一 Room 缓存 key 生成，确保前缀路由正确。

```java
public final class RoomCacheKeys {
    // flashchat_room_{roomId}
    public static String room(String roomId);

    // flashchat_roomClosed_{roomId}  — closed marker 短路 key
    public static String closedMarker(String roomId);

    // flashchat_roomPublic_page_{p}_size_{s}_sort_{sort}
    public static String publicList(int page, int size, String sort);

    // flashchat_roomPublic_*  — 批量失效用
    public static String publicListPrefix();
}
```

#### `RoomCacheGetFilter.java`

> `services/chat-service/.../cache/RoomCacheGetFilter.java`

实现 `CacheGetFilter<String>` 接口。检查 Redis 中是否存在 `closedMarker(roomId)`，存在则直接返回 null，不再回源 DB。

**解决的问题**: BloomFilter 无法删除 — 房间关闭后 Bloom 仍返回 "可能存在"，导致无效穿透。

```
读取路径:  safeGet → Bloom 判定"存在" → Filter 检查 closedMarker → 存在则 null 短路
```

#### `RoomCacheIfAbsentHandler.java`

> `services/chat-service/.../cache/RoomCacheIfAbsentHandler.java`

实现 `CacheGetIfAbsent<String>` 接口。当 Cache + DB 均未命中时执行轻量 debug 日志，为后续监控/告警预留扩展点。

### 3.2 RoomMember 域

#### `RoomMemberCacheKeys.java`

> `services/chat-service/.../cache/RoomMemberCacheKeys.java`

```java
public final class RoomMemberCacheKeys {
    // flashchat_roomMember_{roomId}_{accountId}
    public static String member(String roomId, Long accountId);
}
```

### 3.3 Account 域

#### `AccountCacheKeys.java`

> `services/user-service/.../cache/AccountCacheKeys.java`

```java
public final class AccountCacheKeys {
    // flashchat_account_{accountId}   — 按业务 accountId 查
    public static String byAccountId(String accountId);

    // flashchat_account_id_{dbId}     — 按数据库主键查
    public static String byDbId(Long id);
}
```

---

## 4. 核心改动 — Before / After 对比

### 4.1 Room 读取路径（`getRoomByRoomId`）

**Before** — 5 参数 `safeGet`，无 Filter / IfAbsent：
```java
return multistageCacheProxy.safeGet(
    CacheUtil.buildKey("flashchat", "room", roomId),
    RoomDO.class,
    () -> this.lambdaQuery().eq(RoomDO::getRoomId, roomId).one(),
    CACHE_TIMEOUT,
    flashChatRoomRegisterCachePenetrationBloomFilter
);
```

**After** — 8 参数 `safeGet`，带 Filter + IfAbsent：
```java
return multistageCacheProxy.safeGet(
    RoomCacheKeys.room(roomId),
    RoomDO.class,
    () -> this.lambdaQuery().eq(RoomDO::getRoomId, roomId).one(),
    CACHE_TIMEOUT,
    flashChatRoomRegisterCachePenetrationBloomFilter,
    roomCacheGetFilter,        // ← 已关闭房间短路
    roomCacheIfAbsentHandler   // ← 全未命中后置动作
);
```

### 4.2 Room 写入路径（`createRoom`）

**Before** — 手动 `bloom.add + put`：
```java
flashChatRoomRegisterCachePenetrationBloomFilter.add(key);
multistageCacheProxy.put(key, room, CACHE_TIMEOUT);
```

**After** — 统一 `safePut`（原子 Bloom + Redis + 本地）：
```java
multistageCacheProxy.safePut(
    RoomCacheKeys.room(roomId),
    room,
    CACHE_TIMEOUT,
    flashChatRoomRegisterCachePenetrationBloomFilter
);
```

### 4.3 Room 关闭路径（`doCloseRoom`）

**Before** — 仅 evict：
```java
evictRoomCache(roomId);
```

**After** — 统一关闭动作：
```java
private void onRoomClosed(String roomId) {
    evictRoomCache(roomId);         // 1. 清 Redis + Caffeine
    markRoomClosed(roomId);         // 2. 写 30min closed marker
    evictPublicRoomListCache();     // 3. 清公开列表缓存
}
```

### 4.4 公开房间列表（`listPublicRooms`）— 全新

**Before** — 无缓存，每次直接查 DB。

**After** — 30s 短 TTL Redis 结果缓存：
```java
String cacheKey = RoomCacheKeys.publicList(page, size, sort);
String cached = stringRedisTemplate.opsForValue().get(cacheKey);
if (cached != null) {
    return JSON.parseObject(cached, new TypeReference<List<RoomInfoRespDTO>>() {});
}
// miss → 查 DB → 写回 Redis（30s TTL）
```

> **为何不走 MultistageCacheProxy？**  
> `safeGet(key, List.class, ...)` 存在 Java 泛型擦除问题，FastJSON2 会反序列化为 `JSONObject` 而非 `RoomInfoRespDTO`。改用 `StringRedisTemplate` + `TypeReference` 解决。

### 4.5 Account 写入路径（`postRegisterCache`）

**Before** — 手动双 key `bloom.add + put`：
```java
accountBloomFilter.add(CacheUtil.buildKey("flashchat","account",accountId));
multistageCacheProxy.put(key1, account, CACHE_TIMEOUT);
accountBloomFilter.add(CacheUtil.buildKey("flashchat","account","id",String.valueOf(id)));
multistageCacheProxy.put(key2, account, CACHE_TIMEOUT);
```

**After** — 统一 `safePut` × 2：
```java
multistageCacheProxy.safePut(
    AccountCacheKeys.byAccountId(account.getAccountId()),
    account, CACHE_TIMEOUT, accountBloomFilter
);
multistageCacheProxy.safePut(
    AccountCacheKeys.byDbId(account.getId()),
    account, CACHE_TIMEOUT, accountBloomFilter
);
```

### 4.6 Account 失效路径（`evictAccountCache`）

**Before** — 手搓 key：
```java
multistageCacheProxy.delete(CacheUtil.buildKey("flashchat","account",account.getAccountId()));
multistageCacheProxy.delete(CacheUtil.buildKey("flashchat","account","id",String.valueOf(account.getId())));
```

**After** — CacheKeys 统一生成：
```java
multistageCacheProxy.delete(AccountCacheKeys.byAccountId(account.getAccountId()));
multistageCacheProxy.delete(AccountCacheKeys.byDbId(account.getId()));
```

### 4.7 RoomMember key 生成

**Before** — 业务层手搓：
```java
return CacheUtil.buildKey("flashchat", "roomMember", roomId, String.valueOf(accountId));
```

**After** — 委托 CacheKeys：
```java
return RoomMemberCacheKeys.member(roomId, accountId);
```

---

## 5. 各域缓存能力矩阵

| 能力 | Room | Account | RoomMember |
|------|:----:|:-------:|:----------:|
| 统一 CacheKeys | ✅ | ✅ | ✅ |
| BloomFilter 防穿透 | ✅ | ✅ | — |
| CacheGetFilter（闭合短路） | ✅ | — | — |
| CacheGetIfAbsent（未命中后置） | ✅ | — | — |
| safePut（原子 Bloom+写入） | ✅ | ✅ | — |
| 双 key 联动失效 | — | ✅ | — |
| 列表结果缓存（短 TTL） | ✅ | — | — |
| 批量成员缓存清理 | — | — | ✅ |
| 本地缓存自动路由 | ✅ | ✅ | ✅ |

### 能力选型理由

| 域 | 数据特征 | 选型决策 |
|----|----------|----------|
| **Room** | 生命周期有限（创建→关闭），关闭后需短路而非穿透 | Bloom + Filter + IfAbsent 全链路 |
| **Account** | 双查询维度（accountId / dbId），更新频繁（昵称/密码/积分） | 双 key safePut + 双 key 联动 evict |
| **RoomMember** | 高频点查（roomId + accountId），无全局唯一 ID 防穿透需求 | 统一 key 生成 + saveWithCache / updateWithCacheEvict |

---

## 6. 设计决策与权衡

### Q1: 为何 RoomMember 不用 BloomFilter？

RoomMember 的查询总是在用户已经进入房间的上下文中发生，命中率高。引入 Bloom 增加的复杂度（初始化、容量规划、关闭清理）大于收益。

### Q2: 公开房间列表为何用 StringRedisTemplate 而非 MultistageCacheProxy？

1. Java 泛型擦除：`safeGet(key, List.class, loader)` 丢失 `List<RoomInfoRespDTO>` 的泛型信息
2. `flashchat_roomPublic_*` 前缀不匹配 LocalCacheManager 路由规则，本地缓存不生效
3. 列表数据变动频繁（30s TTL），不需要 Bloom / Filter 等高级能力

### Q3: closedMarker TTL 为何设 30 分钟？

- 房间关闭后的读取高峰通常在前几分钟（用户退出、前端轮询）
- 30 分钟后 Bloom 的误判率仍可接受（极偶尔穿透到 DB 查询空行）
- TTL 过长浪费 Redis 内存，过短失去短路效果

### Q4: safePut 相比手动 bloom.add + put 的优势？

`safePut` 内部按固定顺序执行 `bloom.add → Redis SET → 本地 put`，保证三者的原子语义一致性。手动操作容易遗漏步骤或执行顺序不一致。

---

## 7. 文件变更清单

### 新增文件（5 个）

| 文件 | 域 | 用途 |
|------|----|------|
| `chat-service/.../cache/RoomCacheKeys.java` | Room | 统一 key 生成 |
| `chat-service/.../cache/RoomCacheGetFilter.java` | Room | 已关闭房间 Filter 短路 |
| `chat-service/.../cache/RoomCacheIfAbsentHandler.java` | Room | 全未命中后置动作 |
| `chat-service/.../cache/RoomMemberCacheKeys.java` | RoomMember | 统一 key 生成 |
| `user-service/.../cache/AccountCacheKeys.java` | Account | 统一 key 生成（双 key） |

### 修改文件（3 个）

| 文件 | 改动要点 |
|------|----------|
| `chat-service/.../impl/RoomServiceImpl.java` | 8 参数 safeGet + safePut + listPublicRooms 缓存 + onRoomClosed 统一动作 |
| `chat-service/.../impl/RoomMemberServiceImpl.java` | buildCacheKey 委托 RoomMemberCacheKeys |
| `user-service/.../impl/AccountServiceImpl.java` | AccountCacheKeys 统一 key + safePut 双 key |

---

## 8. 验证

- 全量编译 `mvn compile -q` 通过，无编译错误
- 本地缓存路由前缀与 CacheKeys 生成结果一致性已确认
- Bloom 查询 / safePut 调用链路与 MultistageCacheProxy 接口签名匹配
