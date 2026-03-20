package com.flashchat.chatservice.service.impl;

import com.flashchat.chatservice.dao.entity.MessageDO;
import com.flashchat.chatservice.dao.mapper.MessageMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class MessagePersistServiceImpl {

    private final MessageMapper messageMapper;


    //TODO未来实现批量保存
    @Async("messageExecutor")
    public void saveAsync(MessageDO messageDO) {
        try {
            messageMapper.insert(messageDO);
        } catch (Exception e) {
            log.error("[消息持久化失败] msgId={}, roomId={}",
                    messageDO.getMsgId(), messageDO.getRoomId(), e);
        }
    }

    /**
     * 降级路径：同步写 DB
     * Redis 不可用时使用，消息已有 lastKnownId 分配的 ID（messageDO.id 已设值）
     * 显式指定 ID 写入，不依赖 MySQL 自增
     * 为什么不用 MySQL 自增：
     *   Redis 预分配的 ID 可能超前于攒批实际写入
     *   MySQL 自增指针落后 → 分配重复 ID → 主键冲突
     */
    public void saveSync(MessageDO messageDO) {
        try {
            messageMapper.insert(messageDO);
            log.info("[同步持久化成功-降级] msgId={}, dbId={}", messageDO.getMsgId(), messageDO.getId());
        } catch (Exception e) {
            log.error("[同步持久化失败-降级] msgId={}, dbId={}, roomId={}",
                    messageDO.getMsgId(), messageDO.getId(), messageDO.getRoomId(), e);
        }
    }
}
