package com.flashchat.chatservice.service.impl;

import com.flashchat.chatservice.dao.entity.MessageDO;
import com.flashchat.chatservice.dao.mapper.MessageMapper;
import com.flashchat.chatservice.dto.resp.ChatBroadcastMsgRespDTO;
import com.flashchat.chatservice.service.crypto.MessageContentCodec;
import com.flashchat.chatservice.service.persist.PersistResult;
import com.flashchat.chatservice.toolkit.JsonUtil;
import com.flashchat.convention.errorcode.BaseErrorCode;
import com.flashchat.convention.exception.ServiceException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.RedisStreamCommands;
import org.springframework.data.redis.connection.stream.ByteRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

import static com.flashchat.chatservice.config.MessageStreamConfig.FIELD_PAYLOAD;
import static com.flashchat.chatservice.config.MessageStreamConfig.STREAM_KEY;
import static com.flashchat.chatservice.config.MessageStreamConfig.STREAM_MAX_LEN;

/**
 * 消息持久化服务
 * 两条持久化路径：
 *   saveAsync → XADD 到 Redis Stream → 由 MessageStreamConsumer 攒批 INSERT IGNORE 入库
 *     正常路径，<1ms 完成，不阻塞发消息接口
 *     XADD 失败时自动降级为 saveSync
 *   saveSync → 直接 INSERT 到 MySQL
 *     降级路径，Redis 完全不可用时使用
 * 改造说明：
 *   改造前：saveAsync 使用 @Async("messageExecutor") + 逐条 INSERT
 *          问题：线程池队列中的消息在应用宕机时丢失；逐条 INSERT 吞吐低
 *   改造后：saveAsync 改为 XADD 写 Stream，去掉 @Async
 *          XADD <1ms 完成，无需异步；Stream 持久化保证消息不丢
 *          messageExecutor 线程池已删除（无使用方）
 */
@Service
@Slf4j
public class MessagePersistServiceImpl {

    private final StringRedisTemplate stringRedisTemplate;
    private final MessageMapper messageMapper;
    private final MessageContentCodec messageContentCodec;
    private final MessageWindowServiceImpl messageWindowService;

    private final Timer xaddTimer;
    private final Timer xaddWindowMergedTimer;
    private final Counter xaddFailCounter;
    private final Counter fallbackCounter;

    public MessagePersistServiceImpl(StringRedisTemplate stringRedisTemplate,
                                     MessageMapper messageMapper,
                                     MessageContentCodec messageContentCodec,
                                     MessageWindowServiceImpl messageWindowService,
                                     MeterRegistry meterRegistry) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.messageMapper = messageMapper;
        this.messageContentCodec = messageContentCodec;
        this.messageWindowService = messageWindowService;
        this.xaddTimer = Timer.builder("chat.msg.xadd.duration")
                .description("XADD 持久化握手耗时")
                .register(meterRegistry);
        this.xaddWindowMergedTimer = Timer.builder("chat.msg.xadd_window.merged.duration")
                .description("XADD + window_add 合并 Lua 脚本单次 RTT 耗时")
                .register(meterRegistry);
        this.xaddFailCounter = Counter.builder("chat.msg.xadd.fail")
                .description("XADD 失败次数(触发降级 saveSync)")
                .register(meterRegistry);
        this.fallbackCounter = Counter.builder("chat.msg.persist.fallback")
                .description("saveSync 降级路径调用次数")
                .register(meterRegistry);
    }

    /**
     * 异步持久化 — 写入 Redis Stream
     * XADD flashchat:msg:persist:stream MAXLEN ~ 10000 * payload {json}
     * MAXLEN ~ 10000：每次写入时 Redis 自动做渐进式裁剪，控制 Stream 长度
     * createTime 约定：调用方必须在入队前显式设置 createTime(= 消息发送时间),
     *   保证 Stream/DB/广播三条路径使用同一个时刻源,避免时间漂移。
     * @param messageDO 消息实体（id + createTime 已由调用方设置）
     */
    public PersistResult saveAsync(MessageDO messageDO) {
        // Timer.Sample 覆盖包括降级路径的完整延迟,便于监控 XADD 抖动
        Timer.Sample sample = Timer.start();
        try {
            String payload = JsonUtil.toJson(messageDO);
            byte[] streamKeyBytes = STREAM_KEY.getBytes(StandardCharsets.UTF_8);
            byte[] fieldKeyBytes = FIELD_PAYLOAD.getBytes(StandardCharsets.UTF_8);
            byte[] fieldValueBytes = payload.getBytes(StandardCharsets.UTF_8);

            // 使用 RedisCallback 访问底层 API，支持 MAXLEN 参数
            // Spring Data Redis 的 StreamOperations.add() 不支持 MAXLEN
            RecordId recordId = stringRedisTemplate.execute((RedisCallback<RecordId>) connection -> {
                ByteRecord record = StreamRecords.rawBytes(
                                Collections.singletonMap(fieldKeyBytes, fieldValueBytes))
                        .withStreamKey(streamKeyBytes);

                return connection.streamCommands().xAdd(
                        record,
                        RedisStreamCommands.XAddOptions
                                .maxlen(STREAM_MAX_LEN)
                                .approximateTrimming(true)
                );
            });

            if (recordId == null) {
                throw new IllegalStateException("stream record id is null");
            }

            log.debug("[消息入 Stream] msgId={}, dbId={}, streamId={}",
                    messageDO.getMsgId(), messageDO.getId(), recordId.getValue());
            sample.stop(xaddTimer);
            return PersistResult.acceptedByStream(messageDO.getMsgId(), messageDO.getId());
        } catch (Exception e) {
            sample.stop(xaddTimer);
            xaddFailCounter.increment();
            log.warn("[XADD 失败，降级同步写 DB] msgId={}, dbId={}, error={}",
                    messageDO.getMsgId(), messageDO.getId(), e.getMessage());
            return saveSync(messageDO);
        }
    }

    /**
     * XADD 与 window_add 合并写入：一次 Redis RTT 完成持久化 Stream 写入 +
     * 滑动窗口 ZSET 更新（含按需 trim / expire）。
     * <p>
     * 实现方式：单条 Lua 脚本 {@code lua/xadd_window.lua}，通过
     * {@link StringRedisTemplate#execute} 常规 eval/evalsha 下发。
     * 不走 {@code executePipelined}—— Redisson 的 Spring Data 桥在 pipeline session
     * 内调用 eval 会抛 {@code UnsupportedOperationException}，线上历史教训。
     * <p>
     * 失败路径：脚本执行抛出（连接异常 / Redis 故障）时降级为 saveSync
     * （DB 直写）+ 标记窗口降级。窗口降级后后续读会走 DB 分页，5 分钟后自动恢复。
     * <p>
     * 单实例 Redis：脚本一次触及 stream_key 和 window_key 两个键；若未来上 Cluster，
     * 需要 hash-tag 把两个 key 固定到同一 slot，或拆回独立调用。
     *
     * @param messageDO    消息实体，id + createTime 已由调用方设置
     * @param roomId       房间 ID（与 broadcastMsg 所属房间一致）
     * @param dbId         消息全局递增 ID（即 msgSeqId），用作 ZSET 的 score
     * @param broadcastMsg 广播 DTO，序列化后作为 ZSET member
     */
    public PersistResult saveAsyncWithWindow(MessageDO messageDO,
                                             String roomId,
                                             Long dbId,
                                             ChatBroadcastMsgRespDTO broadcastMsg) {
        String payload = JsonUtil.toJson(messageDO);
        MessageWindowServiceImpl.WindowAddStep windowStep =
                messageWindowService.prepareAddStep(roomId, dbId, broadcastMsg);

        Timer.Sample sample = Timer.start();
        try {
            stringRedisTemplate.execute(
                    MessageWindowServiceImpl.mergedXaddWindowScript(),
                    List.of(STREAM_KEY, windowStep.windowKey()),
                    FIELD_PAYLOAD,
                    payload,
                    Long.toString(STREAM_MAX_LEN),
                    windowStep.scoreStr(),
                    windowStep.memberJson(),
                    windowStep.doTrimStr(),
                    windowStep.doExpireStr(),
                    windowStep.windowSizeStr(),
                    windowStep.ttlSecondsStr());

            sample.stop(xaddWindowMergedTimer);
            messageWindowService.signalAddSuccess(roomId);
            log.debug("[消息入 Stream+Window 合并] msgId={}, dbId={}, room={}",
                    messageDO.getMsgId(), dbId, roomId);
            return PersistResult.acceptedByStream(messageDO.getMsgId(), dbId);
        } catch (Exception e) {
            sample.stop(xaddWindowMergedTimer);
            xaddFailCounter.increment();
            log.warn("[XADD+Window 合并脚本失败，降级 saveSync] msgId={}, dbId={}, room={}, error={}",
                    messageDO.getMsgId(), dbId, roomId, e.getMessage());
            messageWindowService.signalAddFailure(roomId, e);
            return saveSync(messageDO);
        }
    }

    /**
     * 同步持久化 — 直接写 MySQL
     * 降级路径，两种场景触发：
     *   1. MsgIdGenerator.tryNextId() 返回 null（Redis INCR 失败）
     *      → ChatServiceImpl 直接调用 saveSync
     *   2. saveAsync 中 XADD 失败
     *      → saveAsync 内部 fallback 到 saveSync
     * 使用 MyBatis-Plus 的 insert()，createTime 由 MetaObjectHandler 自动填充
     * @param messageDO 消息实体（id 已分配，不会浪费）
     */
    public PersistResult saveSync(MessageDO messageDO) {
        fallbackCounter.increment();
        MessageDO storageReady = messageContentCodec.encodeForStorage(messageDO);
        try {
            messageMapper.insert(storageReady);
            log.info("[同步持久化成功-降级] msgId={}, dbId={}",
                    messageDO.getMsgId(), messageDO.getId());
            return PersistResult.acceptedByDb(messageDO.getMsgId(), messageDO.getId());
        } catch (Exception e) {
            // 最后兜底：同步写也失败，记录完整消息到日志
            // 但这里不再静默吞掉，而是继续向上抛异常阻断副作用
            log.error("[同步写 DB 失败] 消息可能丢失! msgId={}, dbId={}, payload={}",
                    messageDO.getMsgId(), messageDO.getId(),
                    JsonUtil.toJson(storageReady), e);
            throw new ServiceException("消息持久化失败，请稍后重试", e, BaseErrorCode.SERVICE_ERROR);
        }
    }
}
