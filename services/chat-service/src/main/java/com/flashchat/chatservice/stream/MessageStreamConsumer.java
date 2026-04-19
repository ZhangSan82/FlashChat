package com.flashchat.chatservice.stream;

import com.flashchat.chatservice.dao.entity.MessageDO;
import com.flashchat.chatservice.dao.mapper.MessageMapper;
import com.flashchat.chatservice.service.crypto.MessageContentCodec;
import com.flashchat.chatservice.toolkit.JsonUtil;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.flashchat.chatservice.config.MessageStreamConfig.*;

/**
 * Redis Stream 消息消费者 — 批量持久化到 MySQL
 * 独立守护线程，持续从 Redis Stream 拉取消息，攒批后 INSERT IGNORE 写入 DB。
 * 两阶段消费：
 *   阶段一（启动时）：处理 Pending 消息（上次宕机未 ACK 的），不 BLOCK，处理完立即进入阶段二
 *   阶段二（持续）：消费新消息，BLOCK 200ms 等待，攒批 flush
 * 攒批策略（两个条件任一触发 flush）：
 *   数量触发：buffer 积满 BATCH_SIZE (50) 条
 *   时间触发：BLOCK 超时 (200ms) 后 flush 残留
 *   不需要额外定时器：BLOCK 超时天然充当时间窗口
 * 可靠性保障：
 *   1. INSERT IGNORE — 幂等，重复消费不会产生重复数据
 *   2. flush 失败 → 不清 buffer、不 ACK → 下次循环重试 → 自动背压
 *   3. 未 ACK 的消息留在 Pending List → 重启后阶段一自动恢复
 *   4. 消息体被 MAXLEN 裁剪 → 判空跳过 ACK → 不阻塞后续消息
 *   5. 失败时日志记录完整消息体 → 穷人版死信，可从日志恢复
 * 线程安全：
 *   单线程消费（一个守护线程），无并发竞争
 *   与 ChatServiceImpl（生产者）通过 Redis Stream 解耦，无直接交互
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MessageStreamConsumer {

    private final StringRedisTemplate stringRedisTemplate;
    private final MessageMapper messageMapper;
    private final MessageContentCodec messageContentCodec;

    /** 控制消费循环退出 */
    private volatile boolean running = true;
    private Thread consumerThread;

    /** 优雅停机等待消费线程退出的超时时间（毫秒） */
    private static final long SHUTDOWN_TIMEOUT_MS = 5000;

    // ================================================================
    //                      生命周期管理
    // ================================================================

    @PostConstruct
    public void start() {
        consumerThread = new Thread(this::consumeLoop, "msg-stream-consumer");
        consumerThread.setDaemon(true);
        consumerThread.start();
        log.info("[Stream Consumer] 启动成功");
    }

    /**
     * 优雅停机
     * 必须 join 等待消费线程退出，否则 Spring 继续销毁 StringRedisTemplate / DataSource，
     * 消费线程的停机 flush 会因为连接已关闭而失败。
     * 时序保证：
     *   stop() → running=false, interrupt() → join(5s) 等待
     *   消费线程 → 醒来 → flush 残留 → ACK → 退出
     *   join 返回 → stop() 返回 → Spring 销毁 Bean（此时消费线程已退出）
     */
    @PreDestroy
    public void stop() {
        running = false;
        if (consumerThread != null) {
            consumerThread.interrupt();
            try {
                consumerThread.join(SHUTDOWN_TIMEOUT_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("[Stream Consumer] 等待消费线程退出被中断");
            }
        }
        log.info("[Stream Consumer] 已停止");
    }

    // ================================================================
    //                      消费主入口
    // ================================================================

    /**
     * 消费主循环
     * 执行顺序：
     *   1. 确保 Consumer Group 存在（首次部署或 Redis 重启后）
     *   2. 阶段一：处理 Pending 消息（上次未 ACK 的）
     *   3. 阶段二：正常消费新消息（持续循环直到停机）
     */
    private void consumeLoop() {
        ensureConsumerGroupExists();
        processPendingMessages();
        consumeNewMessages();
    }

    // ================================================================
    //     阶段一：Pending 消息恢复（启动时执行，处理完即退出）
    // ================================================================

    /**
     * 处理上次未 ACK 的消息
     * 场景：Consumer 上次执行了 INSERT IGNORE 但 ACK 前宕机
     * 恢复：重新读取 → INSERT IGNORE（幂等安全）→ ACK
     * ReadOffset.from("0")：读取当前 consumer 的所有 Pending 消息
     *   与 ReadOffset.lastConsumed() (">") 不同，">" 只读未投递的新消息
     * 不使用 BLOCK：Pending 消息已经在 Redis 中，不需要等待
     *   有 Pending → 立即返回 → 处理 → 继续拉取
     *   无 Pending → 立即返回空 → 退出阶段一
     */
    private void processPendingMessages() {
        log.info("[Stream Consumer] 开始处理 Pending 消息...");

        while (running) {
            try {
                List<MapRecord<String, Object, Object>> records = stringRedisTemplate
                        .opsForStream().read(
                                Consumer.from(GROUP_NAME, CONSUMER_NAME),
                                StreamReadOptions.empty().count(BATCH_SIZE),
                                StreamOffset.create(STREAM_KEY, ReadOffset.from("0"))
                        );

                if (records == null || records.isEmpty()) {
                    log.info("[Stream Consumer] Pending 消息处理完毕");
                    return;
                }

                List<MessageDO> batch = new ArrayList<>();
                List<RecordId> recordIds = new ArrayList<>();

                for (MapRecord<String, Object, Object> record : records) {
                    MessageDO msg = deserialize(record);
                    if (msg != null) {
                        batch.add(msg);
                        recordIds.add(record.getId());
                    } else {
                        ackSingle(record.getId());
                        log.warn("[Stream Consumer] Pending 消息体为空, ACK 跳过, streamId={}",
                                record.getId());
                    }
                }

                if (!batch.isEmpty()) {
                    int affected = messageMapper.insertBatchIgnore(encodeBatchForStorage(batch));
                    ackBatch(recordIds);
                    log.info("[Stream Consumer] Pending 恢复: {} 条, 实际插入 {} 条",
                            batch.size(), affected);
                }

            } catch (Exception e) {
                log.error("[Stream Consumer] Pending 处理异常, {}ms 后重试",
                        FLUSH_RETRY_DELAY_MS, e);
                sleepQuietly(FLUSH_RETRY_DELAY_MS);
            }
        }
    }

    // ================================================================
    //     阶段二：正常消费新消息（持续循环，直到 running=false）
    // ================================================================

    /**
     * 正常消费新消息
     * XREADGROUP ... COUNT 50 BLOCK 200 STREAMS key >
     * BLOCK 200ms 的三种返回情况：
     *   1. Stream 中有 ≥1 条新消息 → 立即返回（最多 50 条）
     *   2. 200ms 内无新消息 → 超时返回 null/empty
     *   3. 线程被中断（停机）→ 抛异
     * 攒批逻辑：
     *   收到消息 → 加入 buffer
     *   buffer ≥ 50 → flush（高峰期：高吞吐）
     *   超时返回空 + buffer 有残留 → flush（低峰期：低延迟）
     * 背压机制：
     *   flush 失败 → buffer 不清空 → 下次循环先重试 flush → 不读新消息
     *   防止 DB 宕机时消息无限堆积在内存 buffer 中
     */
    private void consumeNewMessages() {
        List<MessageDO> buffer = new ArrayList<>(BATCH_SIZE);
        List<RecordId> recordIdBuffer = new ArrayList<>(BATCH_SIZE);

        while (running) {
            try {
                // ===== 背压：上次 flush 失败，先重试 =====
                if (!buffer.isEmpty()) {
                    if (!doFlush(buffer, recordIdBuffer)) {
                        sleepQuietly(FLUSH_RETRY_DELAY_MS);
                        continue;
                    }
                }

                // ===== 从 Stream 读取新消息 =====
                List<MapRecord<String, Object, Object>> records = stringRedisTemplate
                        .opsForStream().read(
                                Consumer.from(GROUP_NAME, CONSUMER_NAME),
                                StreamReadOptions.empty()
                                        .count(BATCH_SIZE)
                                        .block(Duration.ofMillis(BLOCK_TIMEOUT_MS)),
                                StreamOffset.create(STREAM_KEY, ReadOffset.lastConsumed())
                        );

                if (records != null && !records.isEmpty()) {
                    for (MapRecord<String, Object, Object> record : records) {
                        MessageDO msg = deserialize(record);
                        if (msg != null) {
                            buffer.add(msg);
                            recordIdBuffer.add(record.getId());
                        } else {
                            ackSingle(record.getId());
                            log.warn("[Stream Consumer] 消息体为空, ACK 跳过, streamId={}",
                                    record.getId());
                        }
                    }

                    if (buffer.size() >= BATCH_SIZE) {
                        doFlush(buffer, recordIdBuffer);
                    }

                } else {
                    // BLOCK 超时，无新消息 → flush 残留
                    if (!buffer.isEmpty()) {
                        doFlush(buffer, recordIdBuffer);
                    }
                }

            } catch (Exception e) {
                if (!running) {
                    log.info("[Stream Consumer] 收到停机信号，退出消费循环");
                    break;
                }
                log.error("[Stream Consumer] 消费异常, {}ms 后重试", FLUSH_RETRY_DELAY_MS, e);
                ensureConsumerGroupExists();
                sleepQuietly(FLUSH_RETRY_DELAY_MS);
            }
        }

        // ===== 优雅停机：flush 残留 =====
        if (!buffer.isEmpty()) {
            log.info("[Stream Consumer] 停机前 flush {} 条残留消息", buffer.size());
            doFlush(buffer, recordIdBuffer);
        }

        log.info("[Stream Consumer] 消费线程退出");
    }

    // ================================================================
    //              flush：批量写 DB + ACK
    // ================================================================

    /**
     * 批量写入 DB 并确认消费
     * @return true=成功（buffer 已清空），false=失败（buffer 保留，等待重试）
     */
    private boolean doFlush(List<MessageDO> buffer, List<RecordId> recordIds) {
        if (buffer.isEmpty()) {
            return true;
        }

        try {
            int affected = messageMapper.insertBatchIgnore(encodeBatchForStorage(buffer));

            try {
                ackBatch(recordIds);
            } catch (Exception ackEx) {
                log.warn("[Stream flush] ACK 失败, 消息可能重复消费（幂等安全）: {}",
                        ackEx.getMessage());
            }

            if (affected < buffer.size()) {
                log.info("[Stream flush] {} 条 → DB, 实际插入 {} 条, 重复跳过 {} 条",
                        buffer.size(), affected, buffer.size() - affected);
            } else {
                log.debug("[Stream flush] {} 条 → DB", buffer.size());
            }

            buffer.clear();
            recordIds.clear();
            return true;

        } catch (Exception e) {
            log.error("[Stream flush] 批量写 DB 失败, {} 条消息等待重试. msgIds=[{}]",
                    buffer.size(),
                    buffer.stream()
                            .map(m -> m.getMsgId() + ":" + m.getId())
                            .collect(Collectors.joining(",")),
                    e);
            return false;
        }
    }

    // ================================================================
    //                        辅助方法
    // ================================================================

    /**
     * 反序列化 Stream 消息 → MessageDO
     * @return MessageDO 对象；消息体为空或反序列化失败时返回 null
     */
    List<MessageDO> encodeBatchForStorage(List<MessageDO> batch) {
        return batch.stream()
                .map(messageContentCodec::encodeForStorage)
                .toList();
    }

    private MessageDO deserialize(MapRecord<String, Object, Object> record) {
        Map<Object, Object> body = record.getValue();
        if (body == null || body.isEmpty()) {
            return null;
        }

        Object payloadObj = body.get(FIELD_PAYLOAD);
        if (payloadObj == null) {
            return null;
        }

        String payload = payloadObj.toString();
        if (payload.isBlank()) {
            return null;
        }

        try {
            return JsonUtil.fromJson(payload, MessageDO.class);
        } catch (Exception e) {
            log.error("[Stream Consumer] 反序列化失败, streamId={}, payload={}",
                    record.getId(), payload, e);
            return null;
        }
    }

    /**
     * 单条 ACK（用于消息体为空等异常情况）
     */
    private void ackSingle(RecordId recordId) {
        try {
            stringRedisTemplate.opsForStream()
                    .acknowledge(STREAM_KEY, GROUP_NAME, recordId);
        } catch (Exception e) {
            log.warn("[Stream Consumer] 单条 ACK 失败, streamId={}: {}",
                    recordId, e.getMessage());
        }
    }

    /**
     * 批量 ACK
     */
    private void ackBatch(List<RecordId> recordIds) {
        if (recordIds.isEmpty()) {
            return;
        }
        stringRedisTemplate.opsForStream()
                .acknowledge(STREAM_KEY, GROUP_NAME,
                        recordIds.toArray(new RecordId[0]));
    }

    /**
     * 确保 Consumer Group 存在（幂等）
     * 使用 RedisCallback 直接调用底层 API，显式传 MKSTREAM = true
     * 当 Stream key 不存在时（首次部署，还没有消息写入过）自动创建空 Stream
     * 对比 StreamOperations.createGroup()：
     *   StreamOperations.createGroup() 不带 MKSTREAM，Stream 不存在时报错
     *   底层 xGroupCreate(..., true) 带 MKSTREAM，Stream 不存在时自动创建
     * BUSYGROUP 异常表示 Group 已存在，是正常情况（应用重启时 Group 还在 Redis 中）
     * 如果 Redis 不可用，循环重试直到成功或 running=false
     */
    private void ensureConsumerGroupExists() {
        while (running) {
            try {
                stringRedisTemplate.execute((RedisCallback<Void>) connection -> {
                    byte[] streamKeyBytes = STREAM_KEY.getBytes(StandardCharsets.UTF_8);
                    connection.streamCommands().xGroupCreate(
                            streamKeyBytes, GROUP_NAME, ReadOffset.from("0"), true);
                    return null;
                });
                log.info("[Stream Consumer] Consumer Group 创建成功");
                return;
            } catch (Exception e) {
                String msg = e.getCause() != null
                        ? e.getCause().getMessage() : e.getMessage();
                if (msg != null && msg.contains("BUSYGROUP")) {
                    log.debug("[Stream Consumer] Consumer Group 已存在");
                    return;
                }
                log.warn("[Stream Consumer] Consumer Group 创建失败, {}ms 后重试: {}",
                        FLUSH_RETRY_DELAY_MS, msg);
                sleepQuietly(FLUSH_RETRY_DELAY_MS);
            }
        }
    }

    /**
     * 安全 sleep，不抛异常
     */
    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
