package com.flashchat.chatservice.service;

import com.flashchat.chatservice.dto.resp.ChatBroadcastMsgRespDTO;

import java.util.List;
import java.util.Map;

/**
 * 消息发送成功后的副作用分发服务
 * 设计目标：
 *   1. 主链路只负责校验 + durable handoff
 *   2. 写窗口 / 广播 / 未读 / 活跃时间等副作用统一下沉
 *   3. 同房间副作用保持串行，避免并发打乱顺序
 */
public interface MessageSideEffectService {

    /**
     * 用户消息发送成功后的副作用
     */
    void dispatchUserMessageAccepted(String roomId,
                                     Long senderId,
                                     Long msgSeqId,
                                     ChatBroadcastMsgRespDTO broadcastMsg);

    /**
     * 系统消息发送成功后的副作用
     */
    void dispatchSystemMessageAccepted(String roomId,
                                       Long msgSeqId,
                                       ChatBroadcastMsgRespDTO broadcastMsg);

    /**
     * 消息 reaction 更新后的副作用
     */
    void dispatchReactionUpdated(String roomId,
                                 Long msgSeqId,
                                 String msgId,
                                 Map<String, List<String>> reactions,
                                 ChatBroadcastMsgRespDTO windowMsg);

    /**
     * 消息撤回后的副作用
     */
    void dispatchMessageRecalled(String roomId,
                                 Long msgSeqId,
                                 String msgId,
                                 String senderId,
                                 ChatBroadcastMsgRespDTO recalledMsg);

    /**
     * 消息删除后的副作用
     */
    void dispatchMessageDeleted(String roomId,
                                Long msgSeqId,
                                String msgId,
                                String senderId);
}
