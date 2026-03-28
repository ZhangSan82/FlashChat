package com.flashchat.chatservice.service;


import com.baomidou.mybatisplus.extension.service.IService;
import com.flashchat.chatservice.dao.entity.MessageDO;
import com.flashchat.chatservice.dao.mapper.MessageMapper;
import com.flashchat.chatservice.dto.req.*;
import com.flashchat.chatservice.dto.resp.ChatBroadcastMsgRespDTO;
import com.flashchat.chatservice.dto.resp.CursorPageBaseResp;
import jakarta.validation.Valid;

import java.util.Map;

public interface ChatService extends IService<MessageDO> {

    /**
     *发送消息
     */
    ChatBroadcastMsgRespDTO sendMsg(@Valid SendMsgReqDTO request);

    /**
     *查询历史消息
     */
    CursorPageBaseResp<ChatBroadcastMsgRespDTO> getHistoryMessages(String roomId, @Valid CursorPageBaseReq request);

    /**
     * 消息ACK
     */
    void ackMessages(@Valid MsgAckReqDTO request);

    /**
     *断线重连拉取消息
     */
    CursorPageBaseResp<ChatBroadcastMsgRespDTO> getNewMessages(String roomId);

/**
 * 查询所有房间的未读消息数
 */
    Map<String, Integer> getUnreadCounts();

    /**
     * 撤回消息
     */
    void recallMsg(@Valid MsgRecallReqDTO request);

    /**
     * 删除消息
     */
    void deleteMsg(@Valid MsgDeleteReqDTO request);

    /**
     * 消息表情回应（Toggle）
     */
    void toggleReaction(@Valid MsgReactionReqDTO request);
}
