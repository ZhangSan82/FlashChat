package com.flashchat.chatservice.service.impl;


import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.flashchat.cache.DistributedCache;
import com.flashchat.chatservice.config.MsgIdGenerator;
import com.flashchat.chatservice.dao.entity.AccountDO;
import com.flashchat.chatservice.dao.entity.MessageDO;
import com.flashchat.chatservice.dao.entity.RoomDO;
import com.flashchat.chatservice.dao.entity.RoomMemberDO;
import com.flashchat.chatservice.dao.enums.RoomStatusEnum;
import com.flashchat.chatservice.dao.mapper.MessageMapper;
import com.flashchat.chatservice.dto.enums.WsRespDTOTypeEnum;

import com.flashchat.chatservice.dto.msg.FileDTO;
import com.flashchat.chatservice.dto.req.CursorPageBaseReq;
import com.flashchat.chatservice.dto.req.MsgAckReqDTO;
import com.flashchat.chatservice.dto.req.SendMsgReqDTO;
import com.flashchat.chatservice.dto.resp.ChatBroadcastMsgRespDTO;
import com.flashchat.chatservice.dto.resp.CursorPageBaseResp;
import com.flashchat.chatservice.dto.resp.ReplyMessageDTO;
import com.flashchat.chatservice.dto.resp.WsRespDTO;
import com.flashchat.chatservice.service.*;
import com.flashchat.chatservice.service.strategy.msg.AbstractMsgHandler;
import com.flashchat.chatservice.service.strategy.msg.MsgHandlerFactory;
import com.flashchat.chatservice.toolkit.CursorUtils;
import com.flashchat.chatservice.websocket.manager.RoomChannelManager;
import com.flashchat.chatservice.websocket.manager.RoomMemberInfo;
import com.flashchat.convention.exception.ClientException;
import com.flashchat.user.core.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBloomFilter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class ChatServiceImpl extends ServiceImpl<MessageMapper,MessageDO> implements ChatService {

    private final RoomChannelManager roomChannelManager;
    private final AccountService accountService;
    private final MessagePersistServiceImpl messagePersistServiceImpl;
    private final RoomService roomService;
    private final RoomMemberService roomMemberService;
    private final UnreadService unreadService;
    private final DistributedCache distributedCache;
    @Qualifier("flashChatRoomRegisterCachePenetrationBloomFilter")
    private final RBloomFilter<String> flashChatRoomRegisterCachePenetrationBloomFilter;
    private final MsgIdGenerator msgIdGenerator;

    @Override
    public ChatBroadcastMsgRespDTO sendMsg(SendMsgReqDTO request) {



        Long accountId = UserContext.getRequiredLoginId();
        String roomId = request.getRoomId();

        // ===== 1. 公共前置校验 =====
        validateRoomCanSendMsg(roomId);

        if (!roomChannelManager.isInRoom(roomId, accountId)) {
            throw new ClientException("你不在该房间中，请先加入房间");
        }

        RoomMemberInfo memberInfo = roomChannelManager.getRoomMemberInfo(roomId, accountId);
        if (memberInfo != null && memberInfo.isMuted()) {
            throw new ClientException("你已被禁言，无法发送消息");
        }

        // ===== 2. 跨字段校验：content 和 files 不能同时为空 =====
        String content = request.getContent();
        List<FileDTO> files = request.getFiles();
        if ((content == null || content.isBlank()) && (files == null || files.isEmpty())) {
            throw new ClientException("消息内容不能为空");
        }

        // ===== 3. Handler 路由 + 校验 + 数据修正 =====
        AbstractMsgHandler handler = MsgHandlerFactory.getHandler(files);
        handler.checkMsg(content, files);
        // enrichFiles 前判空，保护子类不需要每次防御 null
        if (files != null && !files.isEmpty()) {
            handler.enrichFiles(files);
        }

        // ===== 4. Handler 构建消息体 =====
        String bodyJson = handler.buildBodyJson(files);
        String contentSummary = handler.buildContentSummary(content, files);
        Integer msgType = handler.getMsgTypeEnum().getType();

        // ===== 5. 回复消息校验 =====
        MessageDO replyMsg = null;
        if (request.getReplyMsgId() != null) {
            replyMsg = this.getById(request.getReplyMsgId());
            if (replyMsg == null) {
                throw new ClientException("引用的消息不存在");
            }
            if (!replyMsg.getRoomId().equals(roomId)) {
                throw new ClientException("只能引用同一房间的消息");
            }
        }

        // ===== 6. 生成消息 ID =====
        String msgId = UUID.randomUUID().toString().replace("-", "");
        Integer isHost = memberInfo != null && memberInfo.isHost() ? 1 : 0;
        Long msgSeqId = msgIdGenerator.tryNextId();


        // ===== 7. 构建消息实体 =====
        MessageDO messageDO = MessageDO.builder()
                .msgId(msgId)
                .roomId(roomId)
                .content(contentSummary)
                .body(bodyJson)
                .replyMsgId(request.getReplyMsgId())
                .avatarColor(memberInfo != null ? memberInfo.getAvatar() : "")
                .msgType(msgType)
                .nickname(memberInfo != null ? memberInfo.getNickname() : "匿名")
                .senderId(accountId)
                .status(0)
                .isHost(isHost)
                .build();


        // ===== 8. 根据 ID 生成结果选择持久化路径 =====
        if (msgSeqId != null) {
            // ------ 正常路径：Redis 预分配 ID，异步写 DB ------
            messageDO.setId(msgSeqId);
            messagePersistServiceImpl.saveAsync(messageDO);
        } else {
            // ------ 降级路径：本地递增 ID，同步写 DB ------
            // lastKnownId 保证 ID 一定大于所有已通过 Redis 分配的 ID
            Long fallbackId = msgIdGenerator.fallbackNextId();
            messageDO.setId(fallbackId);
            messagePersistServiceImpl.saveSync(messageDO);
            msgSeqId = fallbackId;

            log.warn("[发消息-降级] Redis不可用，使用本地ID={}, room={}, memberId={}",
                    fallbackId, roomId, accountId);
        }
        // ===== 9. 构建回复消息 DTO =====
        ReplyMessageDTO replyMessageDTO = buildReplyMessageDTO(replyMsg);

        // ===== 10. 构建广播消息 DTO =====
        ChatBroadcastMsgRespDTO broadcastMsg = ChatBroadcastMsgRespDTO.builder()
                ._id(msgId)
                .indexId(msgSeqId)
                .content(contentSummary)
                .senderId(accountId.toString())
                .username(memberInfo != null ? memberInfo.getNickname() : "匿名")
                .avatar(memberInfo != null ? memberInfo.getAvatar() : "")
                .timestamp(System.currentTimeMillis())
                .isHost(memberInfo != null && memberInfo.isHost())
                .msgType(msgType)
                .files(files)
                .replyMessage(replyMessageDTO)
                .deleted(false)
                .system(false)
                .build();


        // ===== 11. WebSocket 广播 =====
        roomChannelManager.broadcastToRoom(roomId,
                WsRespDTO.of(roomId, WsRespDTOTypeEnum.CHAT_BROADCAST, broadcastMsg));

        log.info("[发消息] room={}, memberId={}, dbId={}, content={}",
                roomId, accountId, msgSeqId, request.getContent());

        // ===== 12. 更新发送者活跃时间 =====
        roomChannelManager.touchMember(roomId, accountId);

        // ===== 13. 未读计数 +1 =====
        unreadService.incrementUnread(roomId, accountId);

        return broadcastMsg;
    }

    // TODO: 未来实现撤回功能时，需要把 .eq(status, 0) 改为 .in(status, List.of(0, 1))
    //       让已删除/已撤回消息也出现在历史中，前端通过 deleted: true 展示删除态
    //       否则对话流会"跳跃"，引用了已删消息的回复会显示"引用的消息不存在"
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
        List<ChatBroadcastMsgRespDTO> respList = convertToRespDTOList(messages);

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
        Long accountId = UserContext.getRequiredLoginId();
        String roomId = request.getRoomId();

        // 2. 查找房间成员记录
        RoomMemberDO roomMember = roomMemberService.getRoomMemberByRoomIdAndAccountId(roomId, accountId);

        if (roomMember == null) {
            throw new ClientException("你不在该房间中");
        }

        // 3. 只增不减（幂等 + 防并发回退）
        Long currentAckId = roomMember.getLastAckMsgId() != null
                ? roomMember.getLastAckMsgId() : 0L;

        if (currentAckId >= request.getLastMsgId()) {
            // 当前已读位置 >= 请求的位置，说明已经确认过了，直接忽略
            log.debug("[ACK 跳过] room={}, accountId={}, current={}, request={}（未前进）",
                    roomId, accountId, currentAckId, request.getLastMsgId());
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
            roomMemberService.evictCache(roomId, accountId);
            unreadService.clearUnread(accountId, roomId);
            log.info("[ACK 成功] room={}, memberId={}, {} → {}",
                    roomId, accountId, currentAckId, request.getLastMsgId());
        } else {
            log.debug("[ACK 并发跳过] room={}, memberId={}, 可能被其他请求抢先更新",
                    roomId, accountId);
        }
    }

    /**
     * 拉取新消息（断线重连后补齐）
     */
    @Override
    public CursorPageBaseResp<ChatBroadcastMsgRespDTO> getNewMessages(String roomId) {

        // ===== 1. 校验房间存在 =====
        validateRoomExists(roomId);

        // ===== 2. 查找用户的已读位置 =====
        Long acctId = UserContext.getRequiredLoginId();

        RoomMemberDO roomMember = roomMemberService.getRoomMemberByRoomIdAndAccountId(roomId, acctId);

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
        // TODO: 未来实现撤回功能时，同 getHistoryMessages 的 TODO
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
            log.info("[拉新消息] room={}, acctId={}, lastAck={}, 无新消息",
                    roomId, acctId, lastAckMsgId);
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
               roomMemberService.evictCache(roomId, acctId);
               unreadService.clearUnread(acctId, roomId);
               log.info("[自动ACK] room={}, acctId={}, {} → {}",
                       roomId, acctId, lastAckMsgId, latestMsg.getId());
           }
        }

        // ===== 4. DO → DTO（已经是正序，不需要翻转） =====
        // 批量转换
        List<ChatBroadcastMsgRespDTO> respList = convertToRespDTOList(page.getList());


        log.info("[拉新消息] room={}, memberId={}, lastAck={}, 拉到 {} 条, isLast={}",
                roomId, acctId, lastAckMsgId, respList.size(), page.getIsLast());

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
    public Map<String, Integer> getUnreadCounts() {
        return unreadService.getAllUnreadCounts(UserContext.getRequiredLoginId());
    }

    /**
     * 批量转换 MessageDO → ChatBroadcastMsgRespDTO
     * 关键优化：回复消息批量查询，避免 N+1
     *   一页 20 条消息，其中 10 条有 replyMsgId
     *   不批量：10 次额外 DB 查询
     *   批量：1 次 SELECT ... WHERE id IN (...) + Map 组装
     */
    private List<ChatBroadcastMsgRespDTO> convertToRespDTOList(List<MessageDO> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }

        // 1. 收集所有需要查询的回复消息 ID
        Set<Long> replyMsgIds = messages.stream()
                .map(MessageDO::getReplyMsgId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        // 2. 批量查询回复消息 → Map<id, MessageDO>
        //    包含已删除消息（status=1），用于展示"原消息已被删除"
        //    如果过滤掉 status=1，replyMsgMap.get() 返回 null，回复气泡会完全消失
        Map<Long, MessageDO> replyMsgMap;
        if (replyMsgIds.isEmpty()) {
            replyMsgMap = Map.of();
        } else {
            List<MessageDO> replyMsgs = this.lambdaQuery()
                    .in(MessageDO::getId, replyMsgIds)
                    .list();
            replyMsgMap = replyMsgs.stream()
                    .collect(Collectors.toMap(MessageDO::getId, Function.identity()));
        }

        // 3. 逐条转换
        return messages.stream()
                .map(msg -> convertSingleToRespDTO(msg, replyMsgMap))
                .toList();
    }

    /**
     * 单条转换 MessageDO → ChatBroadcastMsgRespDTO
     * 用于历史消息拉取场景（从 DB 的 body JSON 反序列化 files）
     */
    private ChatBroadcastMsgRespDTO convertSingleToRespDTO(
            MessageDO msg, Map<Long, MessageDO> replyMsgMap) {

        // 发送者 ID
        String senderId = getSenderId(msg);

        // 时间戳
        long timestamp = 0L;
        if (msg.getCreateTime() != null) {
            timestamp = msg.getCreateTime()
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli();
        }

        // 解析 files（从 DB 的 body JSON 反序列化）
        List<FileDTO> files = parseFiles(msg.getBody());

        // 构建回复消息
        ReplyMessageDTO replyMessageDTO = null;
        if (msg.getReplyMsgId() != null) {
            MessageDO replyMsg = replyMsgMap.get(msg.getReplyMsgId());
            replyMessageDTO = buildReplyMessageDTO(replyMsg);
        }

        // 判断消息状态
        boolean deleted = msg.getStatus() != null && msg.getStatus() == 1;
        boolean system = msg.getMsgType() != null && msg.getMsgType() == 2;

        return ChatBroadcastMsgRespDTO.builder()
                ._id(msg.getMsgId())
                .indexId(msg.getId())
                .content(deleted ? "" : msg.getContent())
                .senderId(senderId)
                .username(msg.getNickname())
                .avatar(msg.getAvatarColor())
                .timestamp(timestamp)
                .isHost(msg.getIsHost() != null && msg.getIsHost() == 1)
                .msgType(msg.getMsgType())
                .files(deleted ? null : files)
                .replyMessage(replyMessageDTO)
                .deleted(deleted)
                .system(system)
                .build();
    }

    /**
     * 构建回复消息 DTO
     * 对齐 vue-advanced-chat 的 replyMessage 对象格式
     * @param replyMsg 被引用的消息实体（可为 null：消息不存在或被物理删除）
     */
    private ReplyMessageDTO buildReplyMessageDTO(MessageDO replyMsg) {
        if (replyMsg == null) {
            return null;
        }

        // 被引用消息已删除/已撤回
        if (replyMsg.getStatus() != null && replyMsg.getStatus() == 1) {
            return ReplyMessageDTO.builder()
                    .content("原消息已被删除")
                    .senderId(getSenderId(replyMsg))
                    .files(null)
                    .build();
        }

        return ReplyMessageDTO.builder()
                .content(replyMsg.getContent())
                .senderId(getSenderId(replyMsg))
                .files(parseFiles(replyMsg.getBody()))
                .build();
    }

    /**
     * 从 MessageDO 提取发送者 ID
     */
    private String getSenderId(MessageDO msg) {
        if (msg.getSenderId() != null) {
            return msg.getSenderId().toString();
        }
        return "0";
    }

    /**
     * 解析 body JSON → List<FileDTO>
     * body 存的是 vue-advanced-chat 的 files 数组格式
     * 仅用于历史消息拉取场景（从 DB 反序列化）
     * 实时广播场景直接用内存中的 files，不走此方法
     *
     * @param bodyJson body 字段的 JSON 字符串
     * @return FileDTO 列表，或 null（文本消息）
     */
    private List<FileDTO> parseFiles(String bodyJson) {
        if (bodyJson == null || bodyJson.isBlank()) {
            return null;
        }
        try {
            return JSON.parseArray(bodyJson, FileDTO.class);
        } catch (Exception e) {
            log.warn("[解析 files] body JSON 解析失败: {}", bodyJson, e);
            return null;
        }
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


}