package com.flashchat.chatservice.service.impl;


import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.flashchat.cache.DistributedCache;
import com.flashchat.cache.toolkit.CacheUtil;
import com.flashchat.chatservice.dao.entity.MemberDO;
import com.flashchat.chatservice.dao.entity.MessageDO;
import com.flashchat.chatservice.dao.entity.RoomDO;
import com.flashchat.chatservice.dao.entity.RoomMemberDO;
import com.flashchat.chatservice.dao.enums.RoomStatusEnum;
import com.flashchat.chatservice.dao.mapper.MessageMapper;
import com.flashchat.chatservice.dto.enums.WsRespDTOTypeEnum;

import com.flashchat.chatservice.dto.req.CursorPageBaseReq;
import com.flashchat.chatservice.dto.req.MsgAckReqDTO;
import com.flashchat.chatservice.dto.req.SendMsgReqDTO;
import com.flashchat.chatservice.dto.resp.ChatBroadcastMsgRespDTO;
import com.flashchat.chatservice.dto.resp.CursorPageBaseResp;
import com.flashchat.chatservice.dto.resp.WsRespDTO;
import com.flashchat.chatservice.service.*;
import com.flashchat.chatservice.toolkit.CursorUtils;
import com.flashchat.chatservice.websocket.manager.RoomChannelManager;
import com.flashchat.chatservice.websocket.manager.RoomMemberInfo;
import com.flashchat.convention.exception.ClientException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBloomFilter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.util.*;

@Service
@Slf4j
@RequiredArgsConstructor
public class ChatServiceImpl extends ServiceImpl<MessageMapper,MessageDO> implements ChatService {

    private final RoomChannelManager roomChannelManager;
    private final MemberService  memberService;
    private final MessagePersistServiceImpl messagePersistServiceImpl;
    private final RoomService roomService;
    private final RoomMemberService roomMemberService;
    private final UnreadService unreadService;
    private final DistributedCache distributedCache;
    @Qualifier("flashChatRoomRegisterCachePenetrationBloomFilter")
    private final RBloomFilter<String> flashChatRoomRegisterCachePenetrationBloomFilter;

    @Override
    public ChatBroadcastMsgRespDTO sendMsg(SendMsgReqDTO request) {


        MemberDO memberDO = memberService.getByAccountId(request.getAccountId());
        Long memberId = memberDO.getId();

        String roomId = request.getRoomId();

        validateRoomCanSendMsg(roomId);


        // ===== 1. 检查用户是否在房间中=====
        if (!roomChannelManager.isInRoom(roomId, memberId)) {
            throw new ClientException("你不在该房间中，请先加入房间");
        }

        // ===== 2. 获取成员信息 + 检查禁言 =====
        RoomMemberInfo memberInfo = roomChannelManager.getRoomMemberInfo(roomId, memberId);
        if (memberInfo != null && memberInfo.isMuted()) {
            throw new ClientException("你已被禁言，无法发送消息");
        }

        // ===== 3. TODO: 敏感词过滤（下一步添加）=====


        // ===== 4. 构建广播消息 =====
        String msgId = UUID.randomUUID().toString().replace("-", "");

        ChatBroadcastMsgRespDTO broadcastMsg = ChatBroadcastMsgRespDTO.builder()
                ._id(msgId)
                .content(request.getContent())
                .senderId(memberId.toString())
            //    .dbId()
                .username(memberInfo != null ? memberInfo.getNickname() : "匿名")
                .avatar(memberInfo != null ? memberInfo.getAvatar() : "")
                .timestamp(System.currentTimeMillis())
                .isHost(memberInfo != null && memberInfo.isHost())
                .build();

        // ===== 5. 通过 WebSocket 广播给房间所有人 =====
        roomChannelManager.broadcastToRoom(roomId,
                WsRespDTO.of(roomId, WsRespDTOTypeEnum.CHAT_BROADCAST, broadcastMsg));

        log.info("[发消息] room={}, memberId={}, content={}",
                roomId, memberId, request.getContent());


        // ===== 6. TODO: 保存到数据库  ,异步批量添加=====
        // messageBatchWriter.addMessage(message);
        Integer isHost = memberInfo != null && memberInfo.isHost() ? 1 : 0;
        MessageDO messageDO = MessageDO.builder()
                .msgId(msgId)
                .roomId(roomId)
                .content(request.getContent())
                .avatarColor(memberInfo != null ? memberInfo.getAvatar() : "")
                .msgType(1)//TODO
                .nickname(memberInfo != null ? memberInfo.getNickname() : "匿名")
                .senderMemberId(memberId)
                .status(0)//TODO
                .isHost(isHost)
                .build();
        messagePersistServiceImpl.saveAsync(messageDO);

        unreadService.incrementUnread(roomId, memberId);


        return broadcastMsg;
    }

    @Override
    public CursorPageBaseResp<ChatBroadcastMsgRespDTO> getHistoryMessages(
            String roomId, CursorPageBaseReq request) {

        // ===== 1. 校验房间存在 =====
        validateRoomExists(roomId);

        // ===== 2. 游标分页查询 =====
        //  等价 SQL：
        //    SELECT * FROM t_message
        //    WHERE room_id = #{roomId} AND status = 0 [AND id < #{cursor}]
        //    ORDER BY id DESC
        //    LIMIT #{pageSize + 1}
        //
        //  命中索引：idx_room_id (room_id, id)
        CursorPageBaseResp<MessageDO> page = CursorUtils.getCursorPage(
                this,
                request,
                wrapper -> wrapper
                        .eq(MessageDO::getRoomId, roomId)
                        .eq(MessageDO::getStatus, 0),
                MessageDO::getId
        );

        if (page.getList().isEmpty()) {
            return CursorPageBaseResp.<ChatBroadcastMsgRespDTO>builder()
                    .list(List.of())
                    .cursor(null)
                    .isLast(true)
                    .build();
        }

        // ===== 3. 翻转为时间正序 =====
        //  查询结果：[msg-100, msg-99, msg-98, ..., msg-81]  ← DESC
        //  翻转后：  [msg-81, msg-82, ..., msg-99, msg-100]  ← ASC
        //  前端直接从上到下渲染即可
        List<MessageDO> messages = new ArrayList<>(page.getList());
        Collections.reverse(messages);

        // ===== 4. DO → DTO =====
        List<ChatBroadcastMsgRespDTO> respList = messages.stream()
                .map(this::convertToRespDTO)
                .toList();

        log.info("[历史消息] room={}, cursor={}, pageSize={}, returned={}, isLast={}",
                roomId, request.getCursor(), request.getPageSize(),
                respList.size(), page.getIsLast());

        return CursorPageBaseResp.<ChatBroadcastMsgRespDTO>builder()
                .list(respList)
                .cursor(page.getCursor())
                .isLast(page.getIsLast())
                .build();
    }

    /**
     * 消息确认
     * 关键保护：只增不减
     *   防止网络延迟导致旧的 ACK 请求覆盖新的值
     *   用 SQL 条件实现原子性：WHERE last_ack_msg_id < #{newValue}
     */
    @Override
    public void ackMessages(MsgAckReqDTO request) {
        // 1. 查找用户
        MemberDO member = memberService.getByAccountId(request.getAccountId());
        Long memberId = member.getId();
        String roomId = request.getRoomId();

        // 2. 查找房间成员记录
        RoomMemberDO roomMember = roomMemberService.getRoomMemberByRoomIdAndMemberId(roomId, memberId);

        if (roomMember == null) {
            throw new ClientException("你不在该房间中");
        }

        // 3. 只增不减（幂等 + 防并发回退）
        Long currentAckId = roomMember.getLastAckMsgId() != null
                ? roomMember.getLastAckMsgId() : 0L;

        if (currentAckId >= request.getLastMsgId()) {
            // 当前已读位置 >= 请求的位置，说明已经确认过了，直接忽略
            log.debug("[ACK 跳过] room={}, memberId={}, current={}, request={}（未前进）",
                    roomId, memberId, currentAckId, request.getLastMsgId());
            return;
        }

        // 4. 原子更新：加上 lt 条件防止并发覆盖
        //    等价 SQL:
        //    UPDATE t_room_member
        //    SET last_ack_msg_id = #{lastMsgId}
        //    WHERE id = #{id} AND last_ack_msg_id < #{lastMsgId}
        boolean updated = roomMemberService.lambdaUpdate()
                .eq(RoomMemberDO::getId, roomMember.getId())
                .lt(RoomMemberDO::getLastAckMsgId, request.getLastMsgId())
                .set(RoomMemberDO::getLastAckMsgId, request.getLastMsgId())
                .update();

        if (updated) {
            roomMemberService.evictCache(roomId, memberId);
            unreadService.clearUnread(memberId, roomId);
            log.info("[ACK 成功] room={}, memberId={}, {} → {}",
                    roomId, memberId, currentAckId, request.getLastMsgId());
        } else {
            log.debug("[ACK 并发跳过] room={}, memberId={}, 可能被其他请求抢先更新",
                    roomId, memberId);
        }
    }

    /**
     * 拉取新消息（断线重连后补齐）
     */
    @Override
    public CursorPageBaseResp<ChatBroadcastMsgRespDTO> getNewMessages(
            String roomId, String accountId) {

        // ===== 1. 校验房间存在 =====
        validateRoomExists(roomId);

        // ===== 2. 查找用户的已读位置 =====
        MemberDO member = memberService.getByAccountId(accountId);
        Long memberId = member.getId();

        RoomMemberDO roomMember = roomMemberService.getRoomMemberByRoomIdAndMemberId(roomId, memberId);

        if (roomMember == null) {
            throw new ClientException("你不在该房间中");
        }

        // last_ack_msg_id：用户最后确认看到的消息 ID
        // 0 表示从未 ACK 过 → 拉取所有消息
        Long lastAckMsgId = roomMember.getLastAckMsgId() != null
                ? roomMember.getLastAckMsgId() : 0L;

        String afterCursor = lastAckMsgId > 0 ? lastAckMsgId.toString() : null;

        // ===== 3. 反向游标查询 =====
        //  SQL: SELECT * FROM t_message
        //       WHERE room_id = ? AND status = 0 AND id > last_ack_msg_id
        //       ORDER BY id ASC
        //       LIMIT 101
        CursorPageBaseResp<MessageDO> page = CursorUtils.getAfterCursor(
                this,
                afterCursor,
                100,  // 断线重连最多拉 100 条，防止一次返回太多
                wrapper -> wrapper
                        .eq(MessageDO::getRoomId, roomId)
                        .eq(MessageDO::getStatus, 0),
                MessageDO::getId
        );

        if (page.getList().isEmpty()) {
            log.info("[拉新消息] room={}, memberId={}, lastAck={}, 无新消息",
                    roomId, memberId, lastAckMsgId);
            return CursorPageBaseResp.<ChatBroadcastMsgRespDTO>builder()
                    .list(List.of())
                    .cursor(null)
                    .isLast(true)
                    .build();
        }

        // 拉到的最后一条消息就是最新的，直接更新已读位置
        MessageDO latestMsg = page.getList().get(page.getList().size() - 1);
        if (latestMsg.getId() > lastAckMsgId) {
           boolean updated = roomMemberService.lambdaUpdate()
                    .eq(RoomMemberDO::getId, roomMember.getId())
                    .lt(RoomMemberDO::getLastAckMsgId, latestMsg.getId())
                    .set(RoomMemberDO::getLastAckMsgId, latestMsg.getId())
                    .update();
           if (updated) {
               roomMemberService.evictCache(roomId, memberId);
               unreadService.clearUnread(memberId, roomId);
               log.info("[自动ACK] room={}, memberId={}, {} → {}",
                       roomId, memberId, lastAckMsgId, latestMsg.getId());
           }
        }

        // ===== 4. DO → DTO（已经是正序，不需要翻转） =====
        List<ChatBroadcastMsgRespDTO> respList = page.getList().stream()
                .map(this::convertToRespDTO)
                .toList();

        log.info("[拉新消息] room={}, memberId={}, lastAck={}, 拉到 {} 条, isLast={}",
                roomId, memberId, lastAckMsgId, respList.size(), page.getIsLast());

        return CursorPageBaseResp.<ChatBroadcastMsgRespDTO>builder()
                .list(respList)
                .cursor(page.getCursor())
                .isLast(page.getIsLast())
                .build();
    }

    /**
     * 查询所有房间的未读消息数
     */
    @Override
    public Map<String, Integer> getUnreadCounts(String accountId) {
        MemberDO member = memberService.getByAccountId(accountId);
        return unreadService.getAllUnreadCounts(member.getId());
    }


    private void validateRoomExists(String roomId) {
      RoomDO roomDO = roomService.getRoomByRoomId(roomId);
       if(roomDO == null) {
           throw new ClientException("房间不存在");
       }
    }

    /**
     * 改造：校验房间存在且允许发消息
     */
    private void validateRoomCanSendMsg(String roomId) {
        RoomDO roomDO = roomService.getRoomByRoomId(roomId);
        if (roomDO == null) {
            throw new ClientException("房间不存在");
        }
        RoomStatusEnum status = RoomStatusEnum.of(roomDO.getStatus());
        if (status == null || !status.canSendMsg()) {
            throw new ClientException("房间当前不允许发送消息（状态：" +
                    (status != null ? status.getDesc() : "未知") + "）");
        }
    }

    /**
     * MessageDO → ChatBroadcastMsgRespDTO
     * 保持与实时广播消息（sendMsg 中构建的）完全一致的字段格式
     * 前端拿到后无需区分"这是历史消息还是实时消息"，统一渲染逻辑
     */
    private ChatBroadcastMsgRespDTO convertToRespDTO(MessageDO msg) {
        // 发送者 ID：优先匿名成员，其次注册用户
        String senderId;
        if (msg.getSenderMemberId() != null) {
            senderId = msg.getSenderMemberId().toString();
        } else if (msg.getSenderUserId() != null) {
            senderId = msg.getSenderUserId().toString();
        } else {
            senderId = "0";
        }

        // LocalDateTime → 毫秒时间戳（与实时消息的 System.currentTimeMillis() 一致）
        long timestamp = 0L;
        if (msg.getCreateTime() != null) {
            timestamp = msg.getCreateTime()
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli();
        }

        return ChatBroadcastMsgRespDTO.builder()
                ._id(msg.getMsgId())
                .dbId(msg.getId())
                .content(msg.getContent())
                .senderId(senderId)
                .username(msg.getNickname())
                .avatar(msg.getAvatarColor())
                .timestamp(timestamp)
                .isHost(msg.getIsHost() != null && msg.getIsHost() == 1)
                .build();
    }


}