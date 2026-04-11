package com.flashchat.chatservice.service.impl;


import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.flashchat.chatservice.audit.MessageAuditChain;
import com.flashchat.chatservice.audit.MessageAuditContext;
import com.flashchat.chatservice.config.MsgIdGenerator;
import com.flashchat.chatservice.dao.entity.MessageDO;
import com.flashchat.chatservice.dao.entity.RoomDO;
import com.flashchat.chatservice.dao.entity.RoomMemberDO;
import com.flashchat.chatservice.dao.enums.RoomMemberRoleEnum;
import com.flashchat.chatservice.dao.enums.RoomMemberStatusEnum;
import com.flashchat.chatservice.dao.enums.RoomStatusEnum;
import com.flashchat.chatservice.dao.mapper.MessageMapper;
import com.flashchat.chatservice.dto.enums.WsRespDTOTypeEnum;
import com.flashchat.chatservice.dto.msg.FileDTO;
import com.flashchat.chatservice.dto.req.*;
import com.flashchat.chatservice.dto.resp.*;
import com.flashchat.chatservice.ratelimit.MessageRateLimiter;
import com.flashchat.chatservice.service.*;
import com.flashchat.chatservice.service.dispatch.RoomSerialLock;
import com.flashchat.chatservice.service.persist.PersistResult;
import com.flashchat.chatservice.service.strategy.msg.AbstractMsgHandler;
import com.flashchat.chatservice.service.strategy.msg.MsgHandlerFactory;
import com.flashchat.chatservice.toolkit.CursorUtils;
import com.flashchat.chatservice.websocket.manager.RoomChannelManager;
import com.flashchat.chatservice.websocket.manager.RoomMemberInfo;
import com.flashchat.convention.exception.ClientException;
import com.flashchat.user.core.UserContext;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import static java.time.ZoneId.systemDefault;

@Service
@Slf4j
@RequiredArgsConstructor
public class ChatServiceImpl extends ServiceImpl<MessageMapper,MessageDO> implements ChatService {

    private final RoomChannelManager roomChannelManager;
    private final MessagePersistServiceImpl messagePersistServiceImpl;
    private final RoomService roomService;
    private final RoomMemberService roomMemberService;
    private final UnreadService unreadService;
    private final MsgIdGenerator msgIdGenerator;
    private final MessageWindowService messageWindowService;
    private final MessageSideEffectService messageSideEffectService;
    private final MessageAuditChain  messageAuditChain;
    private final RoomSerialLock roomSerialLock;
    private final MeterRegistry meterRegistry;

    private Counter replyNotFoundCounter;

    @PostConstruct
    void initMetrics() {
        this.replyNotFoundCounter = Counter.builder("chat.reply.not_found")
                .tag("reason", "db_miss_within_stream_window")
                .description("回复校验时 replyMsgId 在 DB 中查不到的次数 — 监控已知的 XADD→Consumer 入库时间窗口 race")
                .register(meterRegistry);
    }
    /** 撤回时间窗口：2 分钟（秒级精度） */
    private static final long RECALL_TIME_LIMIT_SECONDS = 2 * 60;
    /** 消息状态：正常 */
    private static final int MSG_STATUS_NORMAL = 0;
    /** 消息状态：已删除（房主操作，对话流消失） */
    private static final int MSG_STATUS_DELETED = 1;
    /** 消息状态：已撤回（发送者操作，显示占位） */
    private static final int MSG_STATUS_RECALLED = 2;

    /** 单条消息最多支持的不同 emoji 种类 */
    private static final int MAX_EMOJI_TYPES_PER_MSG = 20;
    /** 单用户对同一条消息最多可点的 emoji 种类数 */
    private static final int MAX_EMOJI_PER_USER_PER_MSG = 5;
    /** CAS 重试次数上限（乐观锁冲突时） */
    private static final int REACTION_CAS_MAX_RETRY = 3;

    /**
     * 历史查询包含的消息状态
     */
    private static final List<Integer> VISIBLE_MSG_STATUS =
            List.of(MSG_STATUS_NORMAL, MSG_STATUS_RECALLED);
    //TODO未来实现完全异步
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
        // ===== 4.5 消息审核 =====
        MessageAuditContext auditCtx = MessageAuditContext.of(
                contentSummary,files,roomId,accountId,msgType);
        messageAuditChain.execute(auditCtx);
        if (auditCtx.isRejected()) {
            // 通过 WS 通知发送者（MSG_REJECTED 已有前端监听，会 Toast 提示）
            roomChannelManager.sendToUser(accountId,
                    WsRespDTO.of(roomId, WsRespDTOTypeEnum.MSG_REJECTED,
                            auditCtx.getRejectReason()));
            throw new ClientException(auditCtx.getRejectReason());
        }
        // REPLACE：使用审核后的内容替换原始摘要
        if (auditCtx.isReplaced()) {
            contentSummary = auditCtx.getEffectiveContent();
        }
        // ===== 5. 回复消息校验 =====
        // 已知 race:消息刚经 saveAsync(XADD) 被 durable accept 但 Stream Consumer 还未攒批
        // 入库时,这里查 DB 会 miss,抛 "引用的消息不存在"。评估后刻意接受此 race:
        //   - 时间窗口 <= 200ms(BLOCK 超时 + 批处理延迟),且要求用户在此期间恰好回复该条
        //   - 失败语义是 clean ClientException,前端重试即可,不产生脏数据
        //   - chat.reply.not_found 计数器兜底监控,若 QPS 显著升高再考虑加窗口 fallback
        MessageDO replyMsg = null;
        if (request.getReplyMsgId() != null) {
            replyMsg = this.getById(request.getReplyMsgId());
            if (replyMsg == null) {
                replyNotFoundCounter.increment();
                throw new ClientException("引用的消息不存在");
            }
            if (!replyMsg.getRoomId().equals(roomId)) {
                throw new ClientException("只能引用同一房间的消息");
            }
        }
        // ===== 6-11 房间级串行临界区 =====
        // 目的:保证同一 roomId 的 "msgSeqId 分配 → durable handoff → mailbox submit"
        // 三步在全局线程池中串行执行,避免 msgSeqId 顺序与 mailbox 提交顺序倒置,
        // 从而保证房间内广播/窗口顺序正确。
        // 临界区内包含一次 XADD(约 1ms),同房间吞吐 ~1000 msg/s,单机场景足够。
        // 锁外预计算(不依赖 msgSeqId):降低临界区内的无关工作量,避免 stripe 内竞争放大。
        // createTime/timestamp 统一用一个时刻源,保证 DB/Stream/广播之间的时间一致性。
        String msgId = UUID.randomUUID().toString().replace("-", "");
        Integer isHost = memberInfo != null && memberInfo.isHost() ? 1 : 0;
        long nowMillis = System.currentTimeMillis();
        LocalDateTime createTime = LocalDateTime.now();
        ChatBroadcastMsgRespDTO broadcastMsg;
        Long finalMsgSeqId;
        PersistResult persistResult;
        try (RoomSerialLock.Handle ignored = acquireRoomLockOrBusy(roomId)) {
            // ===== 6. 生成消息 ID =====
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
                    .createTime(createTime)
                    .build();
            // ===== 8. 根据 ID 生成结果选择持久化路径 =====
            if (msgSeqId != null) {
                // ------ 正常路径:Redis 预分配 ID,异步写 DB ------
                messageDO.setId(msgSeqId);
                persistResult = messagePersistServiceImpl.saveAsync(messageDO);
            } else {
                // ------ 降级路径:本地递增 ID,同步写 DB ------
                // lastKnownId 保证 ID 一定大于所有已通过 Redis 分配的 ID
                Long fallbackId = msgIdGenerator.fallbackNextId();
                messageDO.setId(fallbackId);
                persistResult = messagePersistServiceImpl.saveSync(messageDO);
                msgSeqId = fallbackId;
                log.warn("[发消息-降级] Redis不可用,使用本地ID={}, room={}, memberId={}",
                        fallbackId, roomId, accountId);
            }
            finalMsgSeqId = msgSeqId;
            // ===== 9. 构建回复消息 DTO =====
            ReplyMessageDTO replyMessageDTO = buildReplyMessageDTO(replyMsg);
            // ===== 10. 构建广播消息 DTO =====
            broadcastMsg = ChatBroadcastMsgRespDTO.builder()
                    ._id(msgId)
                    .indexId(msgSeqId)
                    .content(contentSummary)
                    .senderId(accountId.toString())
                    .username(memberInfo != null ? memberInfo.getNickname() : "匿名")
                    .avatar(memberInfo != null ? memberInfo.getAvatar() : "")
                    .timestamp(nowMillis)
                    .isHost(memberInfo != null && memberInfo.isHost())
                    .msgType(msgType)
                    .files(files)
                    .replyMessage(replyMessageDTO)
                    .deleted(false)
                    .system(false)
                    .build();
            // ===== 11. 提交 side effect mailbox(仍在临界区内,保证同房间 submit 顺序) =====
            messageSideEffectService.dispatchUserMessageAccepted(roomId, accountId, msgSeqId, broadcastMsg);
        }

        log.info("[发消息] room={}, memberId={}, dbId={}, acceptedBy={}",
                roomId, accountId, finalMsgSeqId, persistResult.acceptedBy());
        // 原始内容含 PII,只在 DEBUG 级别输出
        log.debug("[发消息-content] room={}, dbId={}, content={}",
                roomId, finalMsgSeqId, request.getContent());

        return broadcastMsg;
    }

    //实现撤回功能时，需要把 .eq(status, 0) 改为 .in(status, List.of(0, 1))
    @Override
    public CursorPageBaseResp<ChatBroadcastMsgRespDTO> getHistoryMessages(
            String roomId, CursorPageBaseReq request) {
        // ===== 1. 校验房间存在 =====
        validateRoomExists(roomId);
        // ===== 2. 解析 cursor =====
        Long cursorValue = null;
        if (request.getCursor() != null && !request.getCursor().isBlank()) {
            try {
                cursorValue = Long.parseLong(request.getCursor());
            } catch (NumberFormatException e) {
                log.warn("[历史消息] cursor 格式异常: {}, 当作首次加载", request.getCursor());
            }
        }
        // ===== 3. 尝试走窗口 =====
        WindowQueryResult windowResult =
                messageWindowService.getHistoryFromWindow(roomId, cursorValue, request.getPageSize());
        if (windowResult != null) {
            List<ChatBroadcastMsgRespDTO> messages = windowResult.getMessages();
            boolean isLast;
            String nextCursor = null;
            if (messages.size() < request.getPageSize()) {
                // 不足一页，判断是否到达窗口底部
                isLast = determineIsLastFromWindow(roomId, messages, windowResult.getWindowMinScore());
                // 窗口已用尽但 DB 还有更老消息时，提供 cursor 让前端继续翻页
                // 下一页请求带 cursor=windowMinScore -> getHistoryFromWindow 中 <= 判断成立
                // -> 返回 null -> 自动降级到 DB cursor 查询
                if (!isLast && windowResult.getWindowMinScore() != null) {
                    nextCursor = windowResult.getWindowMinScore().toString();
                }
            } else {
                // 满页
                isLast = false;
                // 倒序列表中最后一个元素 score 最小，作为下一页 cursor
                nextCursor = messages.get(messages.size() - 1).getIndexId().toString();
            }
            // 翻转为正序（窗口查询返回倒序，前端需要正序渲染）
            Collections.reverse(messages);

            log.info("[历史消息-窗口命中] room={}, cursor={}, returned={}, isLast={}",
                    roomId, request.getCursor(), messages.size(), isLast);

            return CursorPageBaseResp.<ChatBroadcastMsgRespDTO>builder()
                    .list(messages)
                    .cursor(nextCursor)
                    .isLast(isLast)
                    .build();
        }
        // ===== 4. 降级走 DB =====
        log.debug("[历史消息-降级DB] room={}, cursor={}", roomId, request.getCursor());

        // ===== 游标分页查询 =====
        // 改造：status IN (0, 2)，包含正常消息和撤回消息，排除已删除消息
        CursorPageBaseResp<MessageDO> page = CursorUtils.getCursorPage(
                this,
                request,
                wrapper -> wrapper
                        .eq(MessageDO::getRoomId, roomId)
                        .in(MessageDO::getStatus, VISIBLE_MSG_STATUS),
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
        // ===== 3. 尝试走窗口 =====
        // TODO [后续优化] lastAckMsgId == 0 时也可尝试走窗口：
        //   ZCARD < WINDOW_SIZE -> 窗口覆盖全量 -> 直接返回
        //   ZCARD >= WINDOW_SIZE -> 窗口可能不够 -> 降级 DB
        if (lastAckMsgId > 0) {
            List<ChatBroadcastMsgRespDTO> windowResult =
                    messageWindowService.getNewFromWindow(roomId, lastAckMsgId);

            if (windowResult != null) {
                // 窗口命中：自动 ACK
                if (!windowResult.isEmpty()) {
                    ChatBroadcastMsgRespDTO latest = windowResult.get(windowResult.size() - 1);
                    if (latest.getIndexId() != null && latest.getIndexId() > lastAckMsgId) {
                        boolean updated = roomMemberService.lambdaUpdate()
                                .eq(RoomMemberDO::getId, roomMember.getId())
                                .lt(RoomMemberDO::getLastAckMsgId, latest.getIndexId())
                                .set(RoomMemberDO::getLastAckMsgId, latest.getIndexId())
                                .update();
                        if (updated) {
                            roomMemberService.evictCache(roomId, acctId);
                            unreadService.clearUnread(acctId, roomId);
                            log.info("[自动ACK-窗口] room={}, acctId={}, {} -> {}",
                                    roomId, acctId, lastAckMsgId, latest.getIndexId());
                        }
                    }
                }
                log.info("[拉新消息-窗口命中] room={}, acctId={}, lastAck={}, 拉到 {} 条",
                        roomId, acctId, lastAckMsgId, windowResult.size());
                return CursorPageBaseResp.<ChatBroadcastMsgRespDTO>builder()
                        .list(windowResult)
                        .cursor(null)
                        .isLast(true)
                        .build();
            }
        }
        // ===== 4. 降级走 DB =====
        String afterCursor = lastAckMsgId > 0 ? lastAckMsgId.toString() : null;
        // =====  反向游标查询 =====
        // 改造：status IN (0, 2)
        CursorPageBaseResp<MessageDO> page = CursorUtils.getAfterCursor(
                this,
                afterCursor,
                100,
                wrapper -> wrapper
                        .eq(MessageDO::getRoomId, roomId)
                        .in(MessageDO::getStatus, VISIBLE_MSG_STATUS),
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

        // ===== 5. DO → DTO（已经是正序，不需要翻转） =====
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

    @Override
    public void recallMsg(MsgRecallReqDTO request) {
        Long operatorId = UserContext.getRequiredLoginId();
        doRecallOrDelete(request.getRoomId(), request.getMsgId(), operatorId, true);
    }

    @Override
    public void deleteMsg(MsgDeleteReqDTO request) {
        Long operatorId = UserContext.getRequiredLoginId();
        doRecallOrDelete(request.getRoomId(), request.getMsgId(), operatorId, false);
    }

    @Override
    public void toggleReaction(MsgReactionReqDTO request) {
        Long accountId = UserContext.getRequiredLoginId();
        String roomId = request.getRoomId();
        Long msgId = request.getMsgId();
        String emoji = request.getEmoji().trim();
        // ===== 1. 校验：用户在房间中 =====
        if (!roomChannelManager.isInRoom(roomId, accountId)) {
            throw new ClientException("你不在该房间中，请先加入房间");
        }
        // ===== 2. 校验：房间状态 =====
        RoomDO room = roomService.getRoomByRoomId(roomId);
        if (room == null) {
            throw new ClientException("房间不存在");
        }
        RoomStatusEnum roomStatus = RoomStatusEnum.of(room.getStatus());
        if (roomStatus == null || roomStatus == RoomStatusEnum.CLOSED) {
            throw new ClientException("房间已关闭，无法操作");
        }
        // ===== 3. 校验：消息存在且属于该房间 =====
        MessageDO msg = this.getById(msgId);
        if (msg == null) {
            throw new ClientException("消息不存在");
        }
        if (!msg.getRoomId().equals(roomId)) {
            throw new ClientException("消息不属于该房间");
        }
        // ===== 4. 校验：消息状态正常 =====
        if (msg.getStatus() != null && msg.getStatus() != MSG_STATUS_NORMAL) {
            throw new ClientException("该消息已被撤回或删除，无法操作");
        }
        // ===== 5. CAS 乐观锁更新 reactions =====
        String uidStr = accountId.toString();
        Map<String, List<String>> newReactionsMap = null;
        for (int retry = 0; retry < REACTION_CAS_MAX_RETRY; retry++) {
            // 每次重试重新读取最新值
            if (retry > 0) {
                msg = this.getById(msgId);
                if (msg == null) {
                    throw new ClientException("消息不存在");
                }
            }
            String oldReactions = msg.getReactions();
            if (oldReactions == null || oldReactions.isBlank()) {
                oldReactions = "{}";
            }
            // 复用 parseReactions，转为可变 Map
            Map<String, List<String>> parsed = parseReactions(oldReactions);
            Map<String, List<String>> map = (parsed != null)
                    ? new LinkedHashMap<>(parsed) : new LinkedHashMap<>();
            // 确保每个 emoji 的 user list 也是可变的
            map.replaceAll((k, v) -> new ArrayList<>(v));
            // ===== 6. Toggle 逻辑 =====
            List<String> users = map.get(emoji);
            if (users != null && users.contains(uidStr)) {
                // 已点过 → 取消
                users.remove(uidStr);
                if (users.isEmpty()) {
                    map.remove(emoji);
                }
            } else {
                // 没点过 → 添加
                // 限制 1：单条消息的 emoji 种类数
                if (users == null && map.size() >= MAX_EMOJI_TYPES_PER_MSG) {
                    throw new ClientException(
                            "该消息的表情种类已达上限(" + MAX_EMOJI_TYPES_PER_MSG + " 种)");
                }
                // 限制 2：单用户对同一条消息的 emoji 种类数
                long userEmojiCount = map.values().stream()
                        .filter(list -> list.contains(uidStr))
                        .count();
                if (userEmojiCount >= MAX_EMOJI_PER_USER_PER_MSG) {
                    throw new ClientException(
                            "每人每条消息最多添加 " + MAX_EMOJI_PER_USER_PER_MSG + " 种表情");
                }
                if (users == null) {
                    users = new ArrayList<>();
                    map.put(emoji, users);
                }
                users.add(uidStr);
            }
            // ===== 7. CAS 更新 DB =====
            String newJson = JSON.toJSONString(map);
            boolean updated = this.lambdaUpdate()
                    .eq(MessageDO::getId, msgId)
                    .eq(MessageDO::getReactions, oldReactions)
                    .set(MessageDO::getReactions, newJson)
                    .update();
            if (updated) {
                newReactionsMap = map;
                break;
            }
            log.debug("[reaction CAS 重试] msgId={}, retry={}", msgId, retry + 1);
        }
        if (newReactionsMap == null) {
            throw new ClientException("操作冲突，请重试");
        }
        // ===== 8. 更新滑动窗口 =====
        Map<String, List<String>> broadcastReactions = newReactionsMap.isEmpty()
                ? null : newReactionsMap;
        ChatBroadcastMsgRespDTO reactionWindowMsg = updateWindowReactions(msg, newReactionsMap);
        // ===== 9. WS 广播 =====
        messageSideEffectService.dispatchReactionUpdated(
                roomId,
                msg.getId(),
                msg.getMsgId(),
                broadcastReactions,
                reactionWindowMsg
        );
        log.info("[表情回应] room={}, msgId={}, emoji={}, operator={}, reactions={}",
                roomId, msgId, emoji, accountId, broadcastReactions);
    }

    /**
     * 更新滑动窗口中消息的 reactions
     */
    private ChatBroadcastMsgRespDTO updateWindowReactions(MessageDO msg, Map<String, List<String>> newReactions) {
        try {
            // 更新内存对象的 reactions，让 convertSingleToRespDTO 直接用新值
            msg.setReactions(newReactions.isEmpty() ? "{}" : JSON.toJSONString(newReactions));
            return convertSingleToRespDTO(msg, Map.of());
        } catch (Exception e) {
            log.error("[reaction 窗口更新失败] msgId={}", msg.getId(), e);
        }
        return null;
    }

    /**
     * 撤回 / 删除的共享核心逻辑
     */
    private void doRecallOrDelete(String roomId, Long msgId, Long operatorId, boolean isRecall) {
        String opName = isRecall ? "撤回" : "删除";

        // ===== 1. 查询消息 =====
        MessageDO msg = this.getById(msgId);
        if (msg == null) {
            throw new ClientException("消息不存在或尚未处理完成，请稍后再试");
        }
        // ===== 2. 消息归属校验 =====
        if (!msg.getRoomId().equals(roomId)) {
            throw new ClientException("消息不属于该房间");
        }
        // ===== 3. 消息状态校验（幂等） =====
        if (msg.getStatus() != null && msg.getStatus() != 0) {
            log.info("[{}幂等] room={}, msgId={}, 当前 status={}",
                    opName, roomId, msgId, msg.getStatus());
            return;
        }
        // ===== 4. 房间状态校验 =====
        RoomDO room = roomService.getRoomByRoomId(roomId);
        if (room == null) {
            throw new ClientException("房间不存在");
        }
        RoomStatusEnum roomStatus = RoomStatusEnum.of(room.getStatus());
        if (roomStatus == null || roomStatus == RoomStatusEnum.CLOSED) {
            throw new ClientException("房间已关闭，无法操作");
        }
        // ===== 5. 权限 & 时间校验（差异化）=====
        if (isRecall) {
            // 撤回：只能撤回自己的消息
            if (!operatorId.equals(msg.getSenderId())) {
                throw new ClientException("只能撤回自己发送的消息");
            }
            // 撤回：秒级时间限制
            if (msg.getCreateTime() != null) {
                long secondsSinceSend = java.time.Duration.between(
                        msg.getCreateTime(), LocalDateTime.now()).toSeconds();
                if (secondsSinceSend > RECALL_TIME_LIMIT_SECONDS) {
                    throw new ClientException("消息发送超过 2 分钟，无法撤回");
                }
            }
        } else {
            // 删除：只有房主可以操作
            RoomMemberDO operatorMember = roomMemberService
                    .getRoomMemberByRoomIdAndAccountId(roomId, operatorId);
            if (operatorMember == null
                    || operatorMember.getStatus() != RoomMemberStatusEnum.ACTIVE.getCode()) {
                throw new ClientException("你不在该房间中");
            }
            if (operatorMember.getRole() != RoomMemberRoleEnum.HOST.getCode()) {
                throw new ClientException("只有房主可以删除消息");
            }
        }

        // ===== 6. CAS 更新 DB =====
        int newStatus = isRecall ? 2 : 1;
        boolean updated = this.lambdaUpdate()
                .eq(MessageDO::getId, msgId)
                .eq(MessageDO::getStatus, 0)
                .set(MessageDO::getStatus, newStatus)
                .update();

        if (!updated) {
            log.info("[{}CAS 跳过] room={}, msgId={}", opName, roomId, msgId);
            return;
        }

        // ===== 7. 更新滑动窗口（差异化）=====
        if (isRecall) {
            // 撤回：替换为 deleted 占位 JSON（保留在窗口中）
            ChatBroadcastMsgRespDTO recalledMsg = buildRecalledMsgForWindow(msg);
            messageSideEffectService.dispatchMessageRecalled(
                    roomId,
                    msg.getId(),
                    msg.getMsgId(),
                    getSenderId(msg),
                    recalledMsg
            );
        } else {
            // 删除：直接从窗口移除（不占位）
            messageSideEffectService.dispatchMessageDeleted(
                    roomId,
                    msg.getId(),
                    msg.getMsgId(),
                    getSenderId(msg)
            );
        }
        log.info("[{}成功] room={}, msgId={}, operator={}", opName, roomId, msgId, operatorId);
    }

    /**
     * 构建撤回态消息 DTO（用于滑动窗口中的占位替换）
     */
    private ChatBroadcastMsgRespDTO buildRecalledMsgForWindow(MessageDO msg) {
        return ChatBroadcastMsgRespDTO.builder()
                ._id(msg.getMsgId())
                .indexId(msg.getId())
                .content("此消息已撤回")
                .senderId(getSenderId(msg))
                .username(msg.getNickname())
                .avatar(msg.getAvatarColor())
                .timestamp(extractTimestamp(msg))
                .isHost(msg.getIsHost() != null && msg.getIsHost() == 1)
                .msgType(msg.getMsgType())
                .files(null)
                .replyMessage(null)
                .reactions(null)
                .deleted(true)
                .system(false)
                .build();
    }

    /**
     * 判断窗口查询不足一页时是否为最后一页
     * <p>
     * 到达窗口底部时需要额外查 DB 判断是否还有更老的消息。
     * 查询条件和历史查询保持一致：status IN (0, 2)
     */
    private boolean determineIsLastFromWindow(String roomId,
                                              List<ChatBroadcastMsgRespDTO> messages,
                                              Long windowMinScore) {
        if (windowMinScore == null) {
            return true;
        }
        // 改造：status IN (0, 2)，和 getHistoryMessages 的 DB 路径保持一致
        boolean dbHasMore = !this.lambdaQuery()
                .eq(MessageDO::getRoomId, roomId)
                .in(MessageDO::getStatus, VISIBLE_MSG_STATUS)
                .lt(MessageDO::getId, windowMinScore)
                .select(MessageDO::getId)
                .last("LIMIT 1")
                .list()
                .isEmpty();
        return !dbHasMore;
    }

    /**
     * 批量转换 MessageDO → ChatBroadcastMsgRespDTO
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
     */
    private ChatBroadcastMsgRespDTO convertSingleToRespDTO(
            MessageDO msg, Map<Long, MessageDO> replyMsgMap) {

        // ===== 1. 状态判断 =====
        int status = msg.getStatus() != null ? msg.getStatus() : MSG_STATUS_NORMAL;
        boolean isDeleted = (status == MSG_STATUS_DELETED);
        boolean isRecalled = (status == MSG_STATUS_RECALLED);
        boolean showDeleted = isDeleted || isRecalled;

        // ===== 2. content 处理 =====
        String displayContent;
        if (isDeleted) {
            displayContent = "";                // 防御性处理，正常查询不会命中 status=1
        } else if (isRecalled) {
            displayContent = "此消息已撤回";      // 前端展示撤回占位文案
        } else {
            displayContent = msg.getContent();
        }

        // ===== 3. files 处理 =====
        List<FileDTO> files = showDeleted ? null : parseFiles(msg.getBody());

        // ===== 4. reactions 处理 =====
        // 撤回/删除的消息不展示 reactions
        Map<String, List<String>> reactions = showDeleted
                ? null
                : parseReactions(msg.getReactions());

        // ===== 5. 回复消息处理 =====
        ReplyMessageDTO replyMessageDTO = null;
        if (msg.getReplyMsgId() != null) {
            MessageDO replyMsg = replyMsgMap.get(msg.getReplyMsgId());
            replyMessageDTO = buildReplyMessageDTO(replyMsg);
        }

        // ===== 6. 构建 DTO =====
        return ChatBroadcastMsgRespDTO.builder()
                ._id(msg.getMsgId())
                .indexId(msg.getId())
                .content(displayContent)
                .senderId(getSenderId(msg))
                .username(msg.getNickname())
                .avatar(msg.getAvatarColor())
                .timestamp(extractTimestamp(msg))
                .isHost(msg.getIsHost() != null && msg.getIsHost() == 1)
                .msgType(msg.getMsgType())
                .files(files)
                .replyMessage(showDeleted ? null : replyMessageDTO)
                .reactions(reactions)
                .deleted(showDeleted)
                .system(msg.getMsgType() != null && msg.getMsgType() == 2)
                .build();
    }

    /**
     * 构建回复消息 DTO
     */
    private ReplyMessageDTO buildReplyMessageDTO(MessageDO replyMsg) {
        if (replyMsg == null) {
            return null;
        }
        int status = replyMsg.getStatus() != null ? replyMsg.getStatus() : MSG_STATUS_NORMAL;
        // 已删除
        if (status == MSG_STATUS_DELETED) {
            return ReplyMessageDTO.builder()
                    .content("原消息已被删除")
                    .senderId(getSenderId(replyMsg))
                    .files(null)
                    .build();
        }
        // 已撤回
        if (status == MSG_STATUS_RECALLED) {
            return ReplyMessageDTO.builder()
                    .content("原消息已被撤回")
                    .senderId(getSenderId(replyMsg))
                    .files(null)
                    .build();
        }
        // 正常
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

    /**
     * 获取房间 stripe 锁,把 dispatch 层的 RuntimeException 映射为客户端可理解的 ClientException。
     * 超时/中断都意味着"当前房间临时繁忙",让客户端自行重试,不做服务端内部重试以免放大抖动。
     */
    private RoomSerialLock.Handle acquireRoomLockOrBusy(String roomId) {
        try {
            return roomSerialLock.acquire(roomId);
        } catch (RoomSerialLock.StripeLockTimeoutException timeout) {
            log.warn("[发消息-房间繁忙] room={}, cause={}", roomId, timeout.getMessage());
            throw new ClientException("房间当前繁忙，请稍后再试");
        } catch (RoomSerialLock.StripeLockInterruptedException interrupted) {
            log.warn("[发消息-线程中断] room={}, cause={}", roomId, interrupted.getMessage());
            throw new ClientException("系统繁忙，请稍后再试");
        }
    }

    /**
     * 解析 reactions JSON → Map
     */
    private Map<String, List<String>> parseReactions(String reactionsJson) {
        if (reactionsJson == null || reactionsJson.isBlank() || "{}".equals(reactionsJson)) {
            return null;
        }
        try {
            Map<String, List<String>> map = JSON.parseObject(reactionsJson,
                    new com.alibaba.fastjson2.TypeReference<Map<String, List<String>>>() {});
            // 空 Map 返回 null（前端不需要渲染空 reactions 对象）
            return (map == null || map.isEmpty()) ? null : map;
        } catch (Exception e) {
            log.warn("[解析 reactions] JSON 解析失败: {}", reactionsJson, e);
            return null;
        }
    }

    /**
     * 从 MessageDO 提取毫秒时间戳
     */
    private long extractTimestamp(MessageDO msg) {
        if (msg.getCreateTime() == null) {
            return 0L;
        }
        return msg.getCreateTime()
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli();
    }
}
