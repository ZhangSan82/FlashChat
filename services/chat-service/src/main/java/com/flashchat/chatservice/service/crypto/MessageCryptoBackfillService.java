package com.flashchat.chatservice.service.crypto;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.flashchat.chatservice.config.MessageCryptoProperties;
import com.flashchat.chatservice.dao.entity.MessageDO;
import com.flashchat.chatservice.dao.mapper.MessageMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 把历史明文消息迁移到密文字段。
 *
 * <p>回填按批次执行，这样可以控制节奏，避免上线过程中持续压数据库。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MessageCryptoBackfillService {

    private final MessageMapper messageMapper;
    private final MessageContentCodec messageContentCodec;
    private final MessageCryptoProperties properties;

    /**
     * 按配置执行一轮回填。
     */
    public int backfillConfiguredRounds() {
        MessageCryptoProperties.BackfillProperties backfill = properties.getBackfill();
        return runBackfillRounds(
                backfill.getMaxBatchesPerRun(),
                backfill.getBatchSize(),
                backfill.getBatchSleepMs()
        );
    }

    /**
     * 连续执行多个批次，直到达到上限或没有剩余待回填数据。
     */
    public int runBackfillRounds(int maxBatches, int batchSize, long batchSleepMs) {
        ensureWriteEnabled();
        int effectiveMaxBatches = Math.max(1, maxBatches);
        int effectiveBatchSize = Math.max(1, batchSize);
        int totalUpdated = 0;
        for (int i = 0; i < effectiveMaxBatches; i++) {
            int updated = backfillNextBatch(effectiveBatchSize);
            if (updated <= 0) {
                break;
            }
            totalUpdated += updated;
            if (updated < effectiveBatchSize) {
                break;
            }
            sleepQuietly(batchSleepMs);
        }
        return totalUpdated;
    }

    /**
     * 回填一个批次的历史明文消息。
     */
    public int backfillNextBatch(int batchSize) {
        ensureWriteEnabled();
        int effectiveBatchSize = Math.max(1, batchSize);
        List<MessageDO> legacyRows = loadLegacyBatch(effectiveBatchSize);
        if (legacyRows.isEmpty()) {
            return 0;
        }
        List<MessageDO> encryptedRows = legacyRows.stream()
                .map(messageContentCodec::encodeForStorage)
                .toList();
        int updated = messageMapper.updateCryptoBatch(encryptedRows);
        log.info("[message crypto backfill] selected={}, updated={}, batchSize={}",
                legacyRows.size(), updated, effectiveBatchSize);
        return updated;
    }

    /**
     * 只加载仍然依赖旧明文字段的消息行。
     */
    private List<MessageDO> loadLegacyBatch(int batchSize) {
        return messageMapper.selectList(new QueryWrapper<MessageDO>()
                .isNotNull("content")
                .and(wrapper -> wrapper
                        .isNull("content_cipher")
                        .or().eq("content_cipher", "")
                        .or().isNull("content_iv")
                        .or().eq("content_iv", "")
                        .or().isNull("key_version"))
                .select("id", "msg_id", "room_id", "sender_id", "content", "msg_type")
                .orderByAsc("id")
                .last("LIMIT " + batchSize));
    }

    private void ensureWriteEnabled() {
        if (!messageContentCodec.isWriteEnabled()) {
            throw new IllegalStateException("message crypto write must be enabled before backfill");
        }
    }

    private void sleepQuietly(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
