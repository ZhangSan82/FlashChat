# FlashChat 消息加密存储改造说明

## 1. 文档目的

这份文档记录本轮消息加密存储改造的目标、实现方式、代码落点、上线启用方法，以及后续如何做历史数据回填和密钥轮换。

文档面向两类读者：

- 开发同学：快速理解这次代码到底改了什么、为什么这么做
- 运维 / 联调同学：按步骤启用、验证、回填、轮换

## 2. 这次要解决什么问题

原有实现里，消息正文 `t_message.content` 以明文方式存入 MySQL。这样虽然开发简单，但有几个明显问题：

- 数据库直接暴露聊天正文，数据泄露风险高
- 历史库、备份库、测试库都可能带出明文消息
- 后续如果想做密钥轮换或细粒度安全治理，没有基础字段承接

本轮改造的目标不是一步做到端到端加密，而是先完成服务端存储加密：

- Redis 中转链路仍然保持明文，保证现有实时性和消息主链路稳定
- MySQL 中只把正文密文化存储
- 保证老数据可读、新数据可写、上线过程可灰度

## 3. 方案结论

本轮采用的是：

- Redis 明文中转
- MySQL 正文密文落库
- 读链路兼容老明文 + 新密文
- 当前版本写入，历史版本可读

不在本轮范围内的内容：

- 不做端到端加密
- 不加密 Redis Stream / Redis Window
- 不加密 `body` 内的文件元数据、文件名、URL
- 不做旧密文自动重加密

## 4. 表结构怎么改的

这次为 `t_message` 增加了 3 个字段，并把旧 `content` 改成了兼容列：

```sql
ALTER TABLE t_message
    MODIFY COLUMN content TEXT NULL COMMENT '消息内容（兼容旧数据/未加密数据）',
    ADD COLUMN content_cipher TEXT NULL COMMENT '加密后的消息正文(Base64)' AFTER content,
    ADD COLUMN content_iv VARCHAR(64) NULL COMMENT '消息正文随机IV(Base64)' AFTER content_cipher,
    ADD COLUMN key_version INT NULL COMMENT '消息正文加密密钥版本' AFTER content_iv;
```

字段含义：

- `content`
  兼容老明文数据，回填完成后新消息不再写这里
- `content_cipher`
  加密后的正文密文，Base64 编码
- `content_iv`
  AES-GCM 使用的随机 IV，Base64 编码
- `key_version`
  这条消息使用的密钥版本号

配套脚本文件：

- `frameworks/database/chat-message-crypto.sql`
- `docker/perf/init/04-chat-message-crypto.sql`

## 5. 核心架构是怎么做的

### 5.1 写链路

发送消息后，消息对象先在内存里保持明文，只有在真正准备写 MySQL 时才加密。

这样做的目的是：

- 不影响 WebSocket 广播
- 不影响 Redis Stream 中转
- 不影响现有 ACK / 实时窗口逻辑
- 把加密影响范围收敛到存储边界

写链路入口有两处：

1. 同步落库路径  
   `MessagePersistServiceImpl.saveSync()`

2. Stream 消费批量落库路径  
   `MessageStreamConsumer#doFlush()`  
   `MessageStreamConsumer#processPendingMessages()`

在这两个入口里，都会先经过 `MessageContentCodec.encodeForStorage(...)`：

- 如果未开启加密：沿用旧逻辑，继续写 `content`
- 如果开启加密：把 `content` 加密后写入 `content_cipher/content_iv/key_version`，同时把 `content` 置空

### 5.2 读链路

所有依赖数据库正文的读取点都改成了先走统一解码：

- 历史消息读取
- 回复消息摘要
- 房间预览的 DB 回退

统一通过 `MessageContentCodec.decodeContent(...)` 处理：

- 如果是老数据：直接返回 `content`
- 如果是新数据：读取 `content_cipher/content_iv/key_version` 解密后返回

这样可以做到：

- 老数据无需立刻迁移也能继续读
- 新数据加密后对上层业务透明

### 5.3 加密算法

本轮使用：

- `AES/GCM/NoPadding`

原因：

- 同时具备机密性和完整性校验
- 适合短文本正文
- Java 原生支持，工程接入成本低

每条消息都会生成独立随机 IV。  
另外还把以下字段拼成 AAD 参与校验：

- `msgId`
- `roomId`
- `senderId`
- `msgType`

这样即使有人试图篡改密文关联的元数据，也会导致解密失败。

## 6. 关键代码落点

### 6.1 配置层

- `services/chat-service/src/main/java/com/flashchat/chatservice/config/MessageCryptoProperties.java`

负责承接：

- 是否开启加密写入
- 当前写入密钥版本
- 当前密钥
- 旧密钥 keyring
- 回填任务配置

### 6.2 加解密服务

- `services/chat-service/src/main/java/com/flashchat/chatservice/service/crypto/MessageCryptoService.java`

职责：

- AES-GCM 加密
- AES-GCM 解密
- 当前版本写入
- 历史版本读取

多版本支持方式：

- 当前写密钥走 `flashchat.message.crypto.key + key-version`
- 历史读密钥走 `flashchat.message.crypto.keyring`

### 6.3 编解码门面

- `services/chat-service/src/main/java/com/flashchat/chatservice/service/crypto/MessageContentCodec.java`

职责：

- 入库前编码
- 出库前解码
- 屏蔽上层业务对密文细节的感知

### 6.4 回填服务

- `services/chat-service/src/main/java/com/flashchat/chatservice/service/crypto/MessageCryptoBackfillService.java`
- `services/chat-service/src/main/java/com/flashchat/chatservice/config/MessageCryptoBackfillJob.java`
- `services/chat-service/src/main/java/com/flashchat/chatservice/config/MessageCryptoBackfillRunner.java`

职责：

- 分批扫描老明文数据
- 编码成密文后批量更新
- 支持启动时回填一轮
- 支持定时增量回填
- 使用分布式锁避免多实例重复执行

### 6.5 Mapper 与实体

- `services/chat-service/src/main/java/com/flashchat/chatservice/dao/entity/MessageDO.java`
- `services/chat-service/src/main/java/com/flashchat/chatservice/dao/mapper/MessageMapper.java`

实体新增字段：

- `contentCipher`
- `contentIv`
- `keyVersion`

Mapper 新增能力：

- 批量插入密文字段
- 批量回填更新密文字段

## 7. 这次改动是怎么一步步做到的

### 第一步：先把加密边界收口

先确认系统里消息真正写入 MySQL 的位置只有两类：

- 同步写库
- Redis Stream 消费写库

于是把加密只放在这两个位置之前，避免把逻辑散落到 Controller、消息处理器、DTO 转换器里。

### 第二步：统一编码 / 解码入口

新增 `MessageContentCodec`，把“何时加密、何时解密、如何兼容老数据”收成一层。

这样做的好处是：

- 业务层不关心密文细节
- 以后如果算法、字段、AAD 规则变化，改动范围可控
- 测试更容易补齐

### 第三步：做双读兼容

读链路不直接假设“数据库里全是密文”，而是：

- 优先按密文解
- 解不了再按旧字段读

这一步是灰度上线能成立的关键。

### 第四步：补多版本密钥

只做单密钥方案，上线后很快会遇到密钥轮换问题。  
所以本轮直接补了：

- 当前版本写入
- 历史版本可读

这样以后切换 `key_version=2` 时：

- 新消息用 v2
- 老消息仍可用 v1 解密

### 第五步：补历史回填能力

如果只支持新消息密文化，库里会长期同时存在：

- 老明文
- 新密文

所以又补了回填服务，把老明文逐批迁移成密文，最终让库里主体数据完成收敛。

## 8. 配置项说明

当前配置已经接入 `application.yaml`：

```yaml
flashchat:
  message:
    crypto:
      enabled: ${FLASHCHAT_MESSAGE_CRYPTO_ENABLED:false}
      algorithm: AES/GCM/NoPadding
      key: ${FLASHCHAT_MESSAGE_CRYPTO_KEY:}
      keyring: ${FLASHCHAT_MESSAGE_CRYPTO_KEYRING:}
      key-version: ${FLASHCHAT_MESSAGE_CRYPTO_KEY_VERSION:1}
      iv-length-bytes: 12
      tag-length-bits: 128
      backfill:
        enabled: ${FLASHCHAT_MESSAGE_CRYPTO_BACKFILL_ENABLED:false}
        run-on-startup: ${FLASHCHAT_MESSAGE_CRYPTO_BACKFILL_RUN_ON_STARTUP:false}
        batch-size: ${FLASHCHAT_MESSAGE_CRYPTO_BACKFILL_BATCH_SIZE:200}
        max-batches-per-run: ${FLASHCHAT_MESSAGE_CRYPTO_BACKFILL_MAX_BATCHES_PER_RUN:20}
        batch-sleep-ms: ${FLASHCHAT_MESSAGE_CRYPTO_BACKFILL_BATCH_SLEEP_MS:100}
        fixed-delay-ms: ${FLASHCHAT_MESSAGE_CRYPTO_BACKFILL_FIXED_DELAY_MS:30000}
        initial-delay-ms: ${FLASHCHAT_MESSAGE_CRYPTO_BACKFILL_INITIAL_DELAY_MS:30000}
        lock-wait-seconds: ${FLASHCHAT_MESSAGE_CRYPTO_BACKFILL_LOCK_WAIT_SECONDS:0}
        lock-lease-seconds: ${FLASHCHAT_MESSAGE_CRYPTO_BACKFILL_LOCK_LEASE_SECONDS:600}
```

## 9. 日常使用方法

### 9.1 生成一把密钥

在 PowerShell 里生成 32 字节 AES key：

```powershell
$bytes = New-Object byte[] 32
[System.Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
$v1 = [Convert]::ToBase64String($bytes)
$v1
```

### 9.2 开启加密写入

在启动 `chat-service` 的同一个 PowerShell 会话里执行：

```powershell
$env:FLASHCHAT_MESSAGE_CRYPTO_ENABLED = "true"
$env:FLASHCHAT_MESSAGE_CRYPTO_KEY_VERSION = "1"
$env:FLASHCHAT_MESSAGE_CRYPTO_KEY = $v1
$env:FLASHCHAT_MESSAGE_CRYPTO_KEYRING = ""
```

然后重启 `chat-service`。

### 9.3 验证新消息是否写成密文

发送一条新消息后执行：

```sql
SELECT id, msg_id, content, content_cipher, content_iv, key_version
FROM t_message
ORDER BY id DESC
LIMIT 10;
```

预期：

- 新消息：`content` 为 `NULL`
- 新消息：`content_cipher/content_iv/key_version` 有值
- 老消息：仍可能保留在 `content`

### 9.4 验证功能是否正常

至少做下面几项联调：

1. 发送普通文本消息
2. 拉历史消息
3. 发送引用回复
4. 刷新房间列表，看房间预览

预期是页面展示不受影响。

## 10. 历史数据回填怎么用

### 10.1 启动时先跑一轮

如果希望服务启动时顺手回填一轮：

```powershell
$env:FLASHCHAT_MESSAGE_CRYPTO_BACKFILL_RUN_ON_STARTUP = "true"
$env:FLASHCHAT_MESSAGE_CRYPTO_BACKFILL_ENABLED = "true"
$env:FLASHCHAT_MESSAGE_CRYPTO_BACKFILL_BATCH_SIZE = "200"
$env:FLASHCHAT_MESSAGE_CRYPTO_BACKFILL_MAX_BATCHES_PER_RUN = "20"
$env:FLASHCHAT_MESSAGE_CRYPTO_BACKFILL_FIXED_DELAY_MS = "30000"
```

然后重启服务。

### 10.2 定时持续回填

上面的 `BACKFILL_ENABLED=true` 打开后，服务会按配置周期持续扫描并回填老数据。

默认策略：

- 每批 200 条
- 每轮最多 20 批
- 每 30 秒执行一次

### 10.3 查看回填是否完成

```sql
SELECT
    COUNT(*) AS total_rows,
    SUM(CASE WHEN content IS NOT NULL THEN 1 ELSE 0 END) AS legacy_plain_rows,
    SUM(CASE WHEN content_cipher IS NOT NULL THEN 1 ELSE 0 END) AS encrypted_rows
FROM t_message;
```

如果 `legacy_plain_rows` 持续降到 0，说明历史正文已经基本回填完成。

## 11. 密钥轮换怎么用

### 11.1 什么时候轮换

建议在这些场景做密钥轮换：

- 安全周期要求
- 密钥接触面扩大
- 运维交接后想做一次主密钥刷新

### 11.2 轮换步骤

先生成新密钥：

```powershell
$bytes = New-Object byte[] 32
[System.Security.Cryptography.RandomNumberGenerator]::Fill($bytes)
$v2 = [Convert]::ToBase64String($bytes)
$v2
```

然后切到 v2 写入，并保留 v1 读能力：

```powershell
$env:FLASHCHAT_MESSAGE_CRYPTO_ENABLED = "true"
$env:FLASHCHAT_MESSAGE_CRYPTO_KEY_VERSION = "2"
$env:FLASHCHAT_MESSAGE_CRYPTO_KEY = $v2
$env:FLASHCHAT_MESSAGE_CRYPTO_KEYRING = "1:$v1"
```

重启 `chat-service` 后：

- 新消息会用 `key_version=2`
- 库里旧的 `key_version=1` 仍然可以解密

### 11.3 轮换后的注意事项

不要在还有 `key_version=1` 数据时删掉 v1 key。  
只有在下面两个条件都满足后，才适合考虑移除旧 key：

1. 库里已经没有老明文
2. 库里已经没有需要用 v1 解密的密文

当前系统还没有做“旧密文自动重加密为新版本”，所以旧版本 key 不能过早删除。

## 12. 验证与测试

本轮已经补了针对性的测试：

- `MessageContentCodecTest`
- `MessageCryptoServiceTest`
- `MessageCryptoBackfillServiceTest`
- `MessagePersistServiceImplTest`
- `MessageStreamConsumerTest`

执行命令：

```powershell
mvn -q -pl services/chat-service -am "-Dsurefire.failIfNoSpecifiedTests=false" test
```

结果：通过。

说明：

- 测试日志里出现的 Redis / DB error 是已有单测故意模拟的失败场景
- 不是本轮改造引入的新异常

## 13. 本轮已知边界

当前已经完成的是“消息正文存储加密”，不是“消息系统全面加密”。以下内容仍然是明文：

- Redis Stream 中转 payload
- Redis Window 缓存消息
- `body` 字段里的媒体元数据
- 文件名、URL 等附件信息

这不是遗漏，而是本轮明确保留的边界。  
这样做的原因是先优先收口数据库泄露面，避免一次性改动过大影响实时消息主链路。

## 14. 涉及文件清单

### 新增或重点改动

- `services/chat-service/src/main/java/com/flashchat/chatservice/config/MessageCryptoProperties.java`
- `services/chat-service/src/main/java/com/flashchat/chatservice/service/crypto/MessageCryptoService.java`
- `services/chat-service/src/main/java/com/flashchat/chatservice/service/crypto/MessageContentCodec.java`
- `services/chat-service/src/main/java/com/flashchat/chatservice/service/crypto/MessageCryptoBackfillService.java`
- `services/chat-service/src/main/java/com/flashchat/chatservice/config/MessageCryptoBackfillJob.java`
- `services/chat-service/src/main/java/com/flashchat/chatservice/config/MessageCryptoBackfillRunner.java`
- `services/chat-service/src/main/java/com/flashchat/chatservice/dao/entity/MessageDO.java`
- `services/chat-service/src/main/java/com/flashchat/chatservice/dao/mapper/MessageMapper.java`
- `services/chat-service/src/main/java/com/flashchat/chatservice/service/impl/MessagePersistServiceImpl.java`
- `services/chat-service/src/main/java/com/flashchat/chatservice/stream/MessageStreamConsumer.java`
- `services/chat-service/src/main/java/com/flashchat/chatservice/service/impl/ChatServiceImpl.java`
- `services/chat-service/src/main/java/com/flashchat/chatservice/service/impl/RoomServiceImpl.java`
- `services/chat-service/src/main/resources/application.yaml`
- `frameworks/database/chat-message-crypto.sql`
- `docker/perf/init/04-chat-message-crypto.sql`

### 测试

- `services/chat-service/src/test/java/com/flashchat/chatservice/service/crypto/MessageContentCodecTest.java`
- `services/chat-service/src/test/java/com/flashchat/chatservice/service/crypto/MessageCryptoServiceTest.java`
- `services/chat-service/src/test/java/com/flashchat/chatservice/service/crypto/MessageCryptoBackfillServiceTest.java`
- `services/chat-service/src/test/java/com/flashchat/chatservice/service/impl/MessagePersistServiceImplTest.java`
- `services/chat-service/src/test/java/com/flashchat/chatservice/stream/MessageStreamConsumerTest.java`

## 15. 一句话总结

这次改造已经把 FlashChat 的消息正文存储，从“数据库明文存储”升级成了“Redis 明文中转、MySQL 密文落库、读链路兼容、支持回填、支持密钥轮换”的可上线方案。
