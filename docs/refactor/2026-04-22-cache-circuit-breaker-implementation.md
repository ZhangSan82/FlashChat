# FlashChat Redis 熔断与缓存一致性改造说明

> **日期**: 2026-04-22  
> **范围**: `frameworks/cache` 框架层，及 `chat-service` / `aggregation-service` 相关配置  
> **目标**: 在现有多级缓存体系上增加 Redis 熔断能力，并解决 Redis 故障期的缓存一致性问题

---

## 1. 背景

### 1.1 当前缓存架构

FlashChat 当前的缓存链路不是“只有 Redis”，而是一个典型的多级缓存模型：

```text
业务代码
   ↓
MultistageCacheProxy
   ↓
本地缓存 Caffeine（L1）
   ↓ miss
Redis / StringRedisTemplateProxy（L2）
   ↓ miss
DB / CacheLoader
```

同时，框架层已经具备：

- BloomFilter 防穿透
- Redisson 分布式锁防击穿
- TTL 随机化防雪崩
- Caffeine 本地缓存提升热点读性能

也就是说，现有体系已经解决了“有没有缓存”和“缓存基本可不可用”的问题，但还没有解决一个更难的问题：

**Redis 处于“半死不活”状态时，系统如何优雅降级，而不是被 Redis 超时拖垮。**

### 1.2 旧实现的核心短板

旧版 `MultistageCacheProxy` 的行为更接近“被动降级”：

- 每次请求依然先尝试 Redis
- Redis 真失败后，再 catch 异常走降级
- Redis 写入失败时，只是记录日志，不会做后续补偿

这会带来两个架构级问题。

#### 问题1：Redis 半故障时，请求会被超时拖慢

典型场景：

- Redis 没彻底挂，但网络抖动、连接池耗尽、主从切换中
- 每次 Redis 调用都要等 1~3 秒甚至更久才报错
- 高并发下，大量业务线程都卡在 Redis 超时上

最终结果不是“缓存 miss 了一下”，而是：

- 接口 RT 激增
- Tomcat/业务线程池被占满
- 数据库回源压力同步上升
- 整个应用出现级联雪崩

#### 问题2：Redis 故障期写失败后，恢复后可能读回旧值

这是更隐蔽、也更危险的问题。

例如：

1. 本地缓存里有 key=A 的值 `v1`
2. 业务把 A 更新成 `v2`
3. Redis 写入失败，只把本地更新成 `v2`
4. Redis 里仍残留旧值 `v1`
5. 本地缓存 TTL 过期后，再次读取
6. 如果直接去读 Redis，会把旧值 `v1` 又读回来

这会导致：

- 本地缓存重新被脏数据污染
- 删除失败的 key 在恢复后“诈尸”
- 用户刚更新的数据又回滚成旧版本

这也是为什么本次改造里，**我没有直接照搬“熔断期间只写本地”的实现**，而是补了“待修复操作”机制。

---

## 2. 本次改了什么

本次改造的核心文件如下：

- `frameworks/cache/src/main/java/com/flashchat/cache/RedisCircuitBreaker.java`
- `frameworks/cache/src/main/java/com/flashchat/cache/MultistageCache.java`
- `frameworks/cache/src/main/java/com/flashchat/cache/MultistageCacheProxy.java`
- `frameworks/cache/src/main/java/com/flashchat/cache/config/CacheAutoConfiguration.java`
- `frameworks/cache/src/main/java/com/flashchat/cache/config/RedisDistributedProperties.java`
- `frameworks/cache/src/test/java/com/flashchat/cache/RedisCircuitBreakerTest.java`
- `frameworks/cache/src/test/java/com/flashchat/cache/MultistageCacheProxyTest.java`
- `frameworks/cache/pom.xml`

配置侧新增了：

- `services/chat-service/src/main/resources/application.yaml`
- `services/chat-service/src/main/resources/application-test.yaml`
- `services/aggregation-service/src/main/resources/application.yaml`

注意：

- 本次文档只讨论与缓存熔断直接相关的变更
- 上述 YAML 文件里如果存在其他业务配置调整，不属于本次缓存熔断设计本身
- 本次真正新增的配置是 `flashchat.cache.circuit-breaker.*`

---

## 3. 为什么要增加 Redis 熔断

### 3.1 熔断不是为了“少报错”，而是为了“别把系统拖死”

很多人第一次看熔断，会以为它的目的只是：

- Redis 挂了以后少打日志
- Redis 出错时快点返回

这只是表象。

真正的目的，是在分布式系统里把“下游慢故障”隔离掉。

Redis 真正可怕的不是“完全挂掉”，而是以下这类状态：

- 连接可建但操作超时
- 主从切换中响应不稳定
- 连接池耗尽，等待时间很长
- 网络抖动导致大量偶发超时

这时如果每个请求都还坚持访问 Redis，业务线程就会被下游拖住。

熔断的核心价值在于：

- 一旦确认 Redis 已经进入异常窗口，后续请求立刻跳过 Redis
- 不再为一个大概率失败的下游继续付出超时成本
- 用“快速失败 + 本地降级 + 回源”保护整个应用的主链路

### 3.2 没有熔断时，超时是线性叠加的

假设：

- 单次 Redis 操作超时 3 秒
- 每秒 200 个请求命中缓存链路
- Redis 半故障持续 30 秒

如果不熔断，那么在这 30 秒里，大量请求都会被 3 秒超时拖住。

这带来的问题不是单点延迟，而是系统性资源耗尽：

- 线程池占满
- 请求堆积
- 数据库回源暴涨
- 上游重试进一步放大问题

而有了熔断之后：

- 前几次失败负责“探测”
- 达到阈值后进入 `OPEN`
- 后续请求毫秒级跳过 Redis
- 冷却期后再用单请求试探 Redis 是否恢复

这才是一个高并发系统对下游缓存组件应有的保护方式。

---

## 4. 本次是怎么写的

## 4.1 新增 `RedisCircuitBreaker`

文件：

- `frameworks/cache/src/main/java/com/flashchat/cache/RedisCircuitBreaker.java`

这是一个轻量级、框架内自实现的熔断器，使用了经典的三态模型：

- `CLOSED`
- `OPEN`
- `HALF_OPEN`

### 状态语义

#### `CLOSED`

正常状态，所有请求都允许访问 Redis。

如果连续失败次数达到阈值：

```text
CLOSED -> OPEN
```

#### `OPEN`

熔断状态，所有请求都直接跳过 Redis。

只有在冷却时间 `openDurationMs` 到期后，才会尝试进入试探状态：

```text
OPEN -> HALF_OPEN
```

#### `HALF_OPEN`

试探状态，只允许**一个请求**真正访问 Redis。

原因很简单：

- 如果 Redis 还没恢复，不应该一下子放大量请求过去
- 单探针足够判断 Redis 是恢复还是继续故障

成功则：

```text
HALF_OPEN -> CLOSED
```

失败则：

```text
HALF_OPEN -> OPEN
```

### 并发控制方式

实现上用了几个原子类：

- `AtomicReference<State>` 管理状态
- `AtomicInteger` 记录连续失败次数
- `AtomicLong` 记录进入 `OPEN` 的时间
- `AtomicBoolean probing` 保证 `HALF_OPEN` 时只有一个请求拿到试探资格

这么设计的好处是：

- 无锁
- 足够轻量
- 对缓存框架这种高频路径很友好

### 为什么不用 Resilience4j

这里没有引入 Resilience4j 之类的完整熔断框架，主要是出于三点考虑：

1. 当前诉求非常聚焦，只需要 Redis 访问熔断，不需要完整的限流/舱壁/滑窗统计套件
2. `frameworks/cache` 是基础组件，依赖越轻越好
3. 这里的读写降级逻辑和“待修复操作”是和缓存语义强耦合的，自己实现更容易精确控制

换句话说，这里不是做通用中间件平台，而是在做**符合本项目缓存语义的定制熔断**。

---

## 4.2 扩展 `MultistageCache` 接口

文件：

- `frameworks/cache/src/main/java/com/flashchat/cache/MultistageCache.java`

增加了一个接口方法：

```java
String getCircuitBreakerState();
```

这样做的目的不是为了业务代码直接依赖熔断器，而是为了后续可观测性：

- Actuator 暴露
- 运维健康检查
- 自定义监控面板
- 故障定位时快速判断缓存链路是否处于 `OPEN`

这是一个典型的“控制面”能力，而不是“数据面”能力。

---

## 4.3 重构 `MultistageCacheProxy`

文件：

- `frameworks/cache/src/main/java/com/flashchat/cache/MultistageCacheProxy.java`

这是本次改造的核心。

如果说 `RedisCircuitBreaker` 解决的是“是否访问 Redis”，那么 `MultistageCacheProxy` 解决的是：

- 什么时候访问 Redis
- Redis 不可用时怎么降级
- 写失败后怎么保证后续一致性

### 4.3.1 读路径改造

新版本的带 `CacheLoader` 读路径，大致变成：

```text
1. 查本地缓存
2. 查待修复操作
3. 查熔断器
4. 访问 Redis
5. Redis 异常时直接回源
6. 回填本地缓存
```

#### 为什么先查本地缓存

这是热点链路，L1 命中永远是最便宜的。

#### 为什么在熔断器之前先查“待修复操作”

因为故障期里真正危险的不是“Redis 不可用”，而是“Redis 里可能有旧值”。

只要 key 在本地被标记为“待修复”，后续读就不能直接再信任 Redis。

所以优先级应该是：

```text
本地一致性语义 > Redis 当前可用性
```

这也是本次设计最关键的一点。

### 4.3.2 写路径改造

普通 `put/safePut` 不再只是：

```text
尝试 Redis -> 失败打日志 -> 写本地
```

而是：

```text
1. 熔断关闭时写 Redis
2. 熔断打开时跳过 Redis
3. Redis 写失败时记录“待修复 UPSERT”
4. 无论如何先把本地写成功
```

这样做的语义是：

- 当前节点的读一致性优先
- Redis 作为分布式共享层，在恢复后再补写

### 4.3.3 删除路径改造

`delete` 比 `put` 更敏感。

因为删除失败意味着：

- Redis 里旧值还在
- 本地已经失效
- 下一次读如果直接信 Redis，会把“该删除的数据”重新读回来

所以这里用了更强的保护：

```text
1. 删除 Redis
2. 无论成功失败，finally 一定清本地
3. 如果 Redis 删除失败，记录“待修复 INVALIDATE”
4. 后续读取时看到 INVALIDATE 标记，禁止直接相信 Redis
```

这一点是本次方案相对下载版代码的关键增强。

---

## 5. 为什么要增加“待修复操作”机制

### 5.1 如果只做熔断，不做待修复，会出什么问题

如果实现逻辑只是：

- Redis 不可用时只写本地
- Redis 恢复后再让读路径自然回 Redis

那么系统会出现“恢复后脏读”。

#### 场景A：更新失败导致旧值回流

```text
旧值在 Redis = v1
业务更新为 v2
Redis 写失败
本地 = v2，Redis 仍是 v1
本地 TTL 到期
再次读取 Redis
又拿到 v1
```

#### 场景B：删除失败导致脏数据诈尸

```text
Redis 中还残留 key=A
删除 A 时 Redis 失败
本地已清掉
下一次读 A
如果直接去 Redis，会把旧值重新读回来
```

所以，**熔断只解决“快不快”，待修复机制才解决“对不对”。**

### 5.2 本次的做法

在 `MultistageCacheProxy` 内部增加了一个本地 Caffeine 结构：

```java
Cache<String, PendingRedisOperation> pendingRedisRepairs
```

默认策略：

- 最大 50,000 条
- 写后过期 5 分钟

每条记录表示：

- 这个 key 在 Redis 层还有一个待补偿动作

动作分两种：

- `UPSERT`
- `INVALIDATE`

### 5.3 `UPSERT` 语义

表示：

- 本地已经拿到了新值
- Redis 写入失败了
- Redis 恢复后要把这个值重新写回去

读路径遇到 `UPSERT` 时：

1. 优先尝试回放补偿动作
2. 无论回放是否成功，本次先把本地值返回出去
3. 避免去 Redis 读出旧值覆盖本地

### 5.4 `INVALIDATE` 语义

表示：

- 这个 key 的 Redis 删除失败了
- Redis 里可能残留旧值

读路径遇到 `INVALIDATE` 时：

1. 不直接访问 Redis
2. 直接走 `CacheLoader` 回源
3. 如果 DB 有新值，优先使用新值重建缓存
4. 如果 DB 为空，再尝试回放删除动作

这保证了：

- 删除失败不会导致旧值重新回到本地
- 恢复路径始终以最新数据源为准

---

## 6. 关键设计决策与原因

## 6.1 为什么本地缓存仍然要继续工作

熔断 Redis 后，不能把整个缓存体系一起关掉。

原因：

- 当前节点本地缓存本来就是为了吸收热点读
- Redis 故障时，本地缓存是最重要的第一层保护
- 很多热点 key 即使 Redis 不可用，仍然可以靠本地缓存顶住一部分流量

所以本次设计不是“Redis 挂了就所有缓存失效”，而是：

```text
Redis 熔断 = 跳过 L2，不等于关闭 L1
```

## 6.2 为什么 `delete` 要放在 `finally` 清本地

因为删除操作的最小正确语义不是“Redis 删除成功”，而是：

**当前节点不能再继续读到旧值。**

所以哪怕 Redis 删除失败，本地也必须立即失效。

这保证：

- 当前节点不会继续提供旧缓存
- 后续读请求会走“待修复路径”而不是继续命中脏本地

## 6.3 为什么 `HALF_OPEN` 只放一个探针

如果 `HALF_OPEN` 一次放很多请求过去，会有两个问题：

1. 如果 Redis 还没恢复，会瞬间被再次打爆
2. 熔断状态切换会抖动，日志和指标噪音变大

所以单探针是更稳妥的设计：

- 成功一次就说明 Redis 大概率恢复
- 失败一次就继续熔断

## 6.4 为什么 `MeterRegistry` 设计成可选

当前项目不同服务的监控接入程度不一致：

- `chat-service` 有 actuator / prometheus
- 其他服务未必总是提供 `MeterRegistry`

如果框架层强依赖 `MeterRegistry`，会带来自动装配脆弱性。

所以这次在 `CacheAutoConfiguration` 里把它做成了 `@Nullable`：

- 有监控时注册指标
- 没监控时框架正常工作

这是一个典型的基础框架“可观测性增强但不强绑定”的设计。

## 6.5 为什么 `hasKey` / `countExistingKeys` 降级得更保守

这两个接口在 Redis 不可用时没有稳定的数据源回退路径。

因此本次选择了保守策略：

- `hasKey` 熔断或异常时返回 `false`
- `countExistingKeys` 熔断或异常时返回 `0`

原因是：

- 宁可保守误判
- 也不要为了这类辅助性查询把请求卡死在 Redis 上

---

## 7. 配置设计

## 7.1 新增配置项

本次新增配置如下：

```yaml
flashchat:
  cache:
    circuit-breaker:
      enabled: true
      failure-threshold: 5
      open-duration-ms: 30000
```

### 参数含义

#### `enabled`

是否启用 Redis 熔断器。

- `true`: 使用新逻辑
- `false`: 退化为原先“每次都尝试 Redis”的行为

#### `failure-threshold`

连续失败多少次后触发熔断。

当前默认值：`5`

设计考虑：

- 小于 5 时容易把偶发网络抖动当成 Redis 故障
- 太大又会让熔断反应变慢

#### `open-duration-ms`

熔断保持多久后再试探 Redis。

当前默认值：`30000`

设计考虑：

- 给 Redis 主从切换、连接池恢复、网络波动留出恢复时间
- 避免 `OPEN` 和 `HALF_OPEN` 快速抖动

## 7.2 为什么配置要加校验

在 `RedisDistributedProperties` 中增加了：

- `@Validated`
- `@Min(1)` for `failureThreshold`
- `@Min(1000)` for `openDurationMs`

原因很直接：

- 熔断阈值不能写成 0 或负数
- 冷却时间过小会导致系统持续抖动

基础框架配置如果没有边界校验，线上风险非常高。

---

## 8. 自动装配层怎么改的

文件：

- `frameworks/cache/src/main/java/com/flashchat/cache/config/CacheAutoConfiguration.java`

这次自动装配层做了三件事。

### 8.1 注册 `RedisCircuitBreaker`

新增 Bean：

```java
public RedisCircuitBreaker redisCircuitBreaker(...)
```

逻辑：

- 开启时按配置创建真正的熔断器
- 关闭时返回一个“永远放行”的空操作熔断器

这样 `MultistageCacheProxy` 内部不需要到处写：

```java
if (enabled) ...
```

整体实现会更干净。

### 8.2 `MeterRegistry` 变成可选注入

这解决了不同服务监控依赖不完全一致时的兼容问题。

### 8.3 `MultistageCacheProxy` 构造参数增加熔断器

由自动装配层统一注入，而不是业务层手动管理。

这符合框架职责边界：

- 业务层只依赖缓存能力
- 熔断属于缓存框架内部行为，不应该外泄给业务层组装

---

## 9. 测试是怎么补的

## 9.1 补测试依赖

文件：

- `frameworks/cache/pom.xml`

增加：

- `spring-boot-starter-test`

原因很简单：

- `frameworks/cache` 之前没有完整单测依赖
- 这次改动是框架级逻辑，必须由模块级单元测试守住

## 9.2 `RedisCircuitBreakerTest`

文件：

- `frameworks/cache/src/test/java/com/flashchat/cache/RedisCircuitBreakerTest.java`

覆盖点：

- 连续失败达到阈值后进入 `OPEN`
- 冷却期后只放行一个探针
- 探针成功后恢复到 `CLOSED`

这确保熔断状态机没有写错。

## 9.3 `MultistageCacheProxyTest`

文件：

- `frameworks/cache/src/test/java/com/flashchat/cache/MultistageCacheProxyTest.java`

覆盖两个最关键场景。

### 场景1：删除失败后，不能把旧 Redis 值重新读回来

测试名：

- `shouldBypassStaleRedisAfterDeleteFailureAndReloadFromLoader`

验证点：

- Redis 删除失败后，key 会被标记为待修复 `INVALIDATE`
- 后续 `safeGet` 不会直接调用 Redis
- 而是直接走 `CacheLoader`
- 新值会重新回填，避免旧值回流

### 场景2：写入失败后，恢复时要能补写 Redis

测试名：

- `shouldReplayPendingPutAfterDistributedWriteFailure`

验证点：

- Redis 写入失败后，会记录待修复 `UPSERT`
- 后续访问该 key 时会优先尝试回放补偿动作
- 回放成功后清理待修复标记

这两组测试实际上锁住了本次设计最重要的两个正确性保证。

---

## 10. 验证结果

本次实际执行的验证命令如下：

```powershell
mvn -q -pl frameworks/cache test
mvn -q -pl services/chat-service,services/user-service,services/aggregation-service -am -DskipTests compile
```

验证结论：

- `frameworks/cache` 单测通过
- 依赖该缓存框架的三个服务编译通过

这说明：

- 框架层改造本身可用
- 自动装配签名变化没有破坏当前服务编译

---

## 11. 这次设计的收益

本次改造带来的收益，不只是“多了个熔断器”。

### 收益1：Redis 半故障时，系统可以快速跳过 Redis

这直接降低了：

- 请求超时
- 线程阻塞
- 级联雪崩风险

### 收益2：写失败后不再简单“记日志了事”

而是有明确的补偿语义：

- `UPSERT`
- `INVALIDATE`

### 收益3：恢复后不会轻易把旧值重新读回本地

这是数据正确性的关键保障。

### 收益4：缓存框架更具可观测性

现在可以观察：

- 熔断触发次数
- 熔断恢复次数
- 探针失败次数
- 各类降级计数

这对线上运维非常重要。

### 收益5：配置更加可控

现在熔断能力可以：

- 按环境开关
- 按环境调阈值
- 做配置校验防止误配

---

## 12. 当前边界与已知取舍

这次方案已经比“只写本地 + 熔断跳过 Redis”的版本安全很多，但仍然有一些边界需要明确。

## 12.1 待修复操作目前是进程内内存结构

也就是说：

- 它对当前单实例/单节点非常有效
- 但如果未来做多实例部署，待修复状态不会自动跨节点同步

当前项目阶段这是可接受的，因为现状偏单机场景。

如果后续演进到多实例，需要考虑：

- Redis Stream / MQ 驱动的补偿队列
- 本地待修复状态广播
- 更系统化的缓存失效总线

## 12.2 `INVALIDATE -> DB 回源 -> 回填 Redis` 路径使用的是默认 TTL

当前实现里，如果删除失败后，后续读从 DB 重新拿到了最新值，会用：

```java
distributedCache.put(key, result)
```

也就是走框架默认 TTL，而不是还原调用方原始自定义 TTL。

这是一个当前可接受但需要知晓的取舍：

- 优先保证正确性
- TTL 精细还原暂未做

如果后续需要更精细，可以把“原始 timeout / timeUnit”一并存进 `PendingRedisOperation`。

## 12.3 不是所有 Redis 访问都自动接入了熔断

当前接入熔断的是走 `MultistageCacheProxy` 的缓存访问。

如果业务代码直接用：

- `StringRedisTemplate`
- `RedissonClient`

那么这些调用不自动享受本次熔断保护。

例如：

- 房间公开列表缓存
- `closed marker`
- 某些限流、流式结构等直连 Redis 场景

这些如果后续需要统一治理，可以继续抽象一层 Redis 访问代理。

## 12.4 当前熔断失败统计还是偏“广义”

当前实现中，只要 Redis 访问抛异常，就会计入失败。

这意味着：

- 连接失败会熔断
- 超时会熔断
- 反序列化异常也可能熔断

更精细的做法应该是只对“基础设施异常”计入熔断，例如：

- Redis 连接异常
- 超时异常
- Redis 系统不可用异常

而把业务数据异常单独统计。

这属于下一步可继续增强的方向。

---

## 13. 总结

这次改造的本质，不是“往缓存里塞了一个 Redis 熔断器”，而是把缓存框架从：

```text
本地缓存 + Redis + 失败后简单降级
```

升级成了：

```text
本地缓存 + Redis + 熔断 + 故障期写补偿 + 恢复后一致性保护
```

从架构角度看，这次真正解决了两个问题：

1. **性能与稳定性问题**  
   Redis 半故障时，不再让每个请求都被超时拖死。

2. **数据正确性问题**  
   Redis 写失败/删失败后，不再允许旧值在恢复阶段回流到本地缓存。

如果只做第一点，不做第二点，这个方案只能算“快”，不能算“稳”。  
如果两点都做到，缓存框架才算真正具备生产可用的故障韧性。

这也是本次设计的核心出发点。

