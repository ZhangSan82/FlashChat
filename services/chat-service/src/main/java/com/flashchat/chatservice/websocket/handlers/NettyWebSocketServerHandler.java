package com.flashchat.chatservice.websocket.handlers;


import com.flashchat.chatservice.dto.enums.WsReqDTOTypeEnum;
import com.flashchat.chatservice.dto.enums.WsRespDTOTypeEnum;
import com.flashchat.chatservice.dto.req.*;
import com.flashchat.chatservice.dto.resp.IdentityInfoRespDTO;
import com.flashchat.chatservice.dto.resp.WsRespDTO;
import com.flashchat.chatservice.toolkit.ChannelAttrUtil;
import com.flashchat.chatservice.toolkit.JsonUtil;

import com.flashchat.chatservice.websocket.manager.RoomChannelManager;
import com.flashchat.chatservice.websocket.manager.RoomMemberInfo;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.netty.channel.*;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.internal.ThreadLocalRandom;
import lombok.extern.slf4j.Slf4j;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
/**
 * WebSocket 业务处理器
 *
 * 【改造说明】
 *
 * 旧流程：
 *   连接建立 → 从URL取roomId → 分配身份 → 立即加入房间
 *   收到消息 → 只处理管理操作（禁言/踢人等），聊天消息走HTTP
 *   连接断开 → 离开唯一的那个房间
 *
 * 新流程：
 *   连接建立 → 分配身份 → 只注册在线（不加入任何房间）
 *   收到消息 → 处理所有操作：加入房间、离开房间、发消息、管理操作
 *   连接断开 → 自动离开所有房间
 *
 * 客户端消息格式：
 *   {"type": 1, "data": {"roomId":"r1", "nickname":"Alice", "avatar":"#FF5733", "isHost":false}}  → 加入房间
 *   {"type": 2, "data": {"roomId":"r1"}}                                                         → 离开房间
 *   {"type": 3, "data": {"roomId":"r1", "content":"hello"}}                                      → 发消息
 *   {"type": 4}                                                                                  → 心跳
 *   {"type": 5, "data": {"roomId":"r1", "targetUserId": 123}}                                    → 禁言
 *   {"type": 6, "data": {"roomId":"r1", "targetUserId": 123}}                                    → 解除禁言
 *   {"type": 7, "data": {"roomId":"r1", "targetUserId": 123}}                                    → 踢人
 *   {"type": 8, "data": {"roomId":"r1"}}                                                         → 关闭房间
 */
@Slf4j
@ChannelHandler.Sharable
public class NettyWebSocketServerHandler extends SimpleChannelInboundHandler<TextWebSocketFrame> {

    // ===== 临时的ID和昵称生成（后续接入数据库后删除）=====
    private static final AtomicLong USER_ID_GEN = new AtomicLong(1);
    private static final String[] NICKNAMES = {
            "神秘猫咪", "月光兔子", "星空企鹅", "云端熊猫", "深海水母",
            "极光狐狸", "雷电松鼠", "彩虹鹦鹉", "暗夜猫头鹰", "晨曦麻雀",
            "迷雾獾", "闪电猎豹", "蓝色海豚", "翡翠蜥蜴", "紫色蝴蝶"
    };

    private final RoomChannelManager roomManager;

    public NettyWebSocketServerHandler(RoomChannelManager roomManager) {
        this.roomManager = roomManager;
    }

    // ================================================================
    // 事件1：WebSocket 握手完成 / 心跳超时
    //
    // 【改造核心】握手完成后只注册在线，不加入任何房间
    // ================================================================

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof WebSocketServerProtocolHandler.HandshakeComplete) {
            handleConnect(ctx.channel());
        } else if (evt instanceof IdleStateEvent) {
            log.info("[心跳超时] userId={}", ChannelAttrUtil.getUserId(ctx.channel()));
            handleDisconnect(ctx.channel());
        } else {
            super.userEventTriggered(ctx, evt);
        }
    }

    /**
     * 连接建立：分配身份 + 注册在线
     *
     * 【对比旧代码】
     *   旧: 握手完成 → 从URL取roomId → 校验roomId → 分配身份 → joinRoom(roomId, channel)
     *       如果没有roomId直接关闭连接
     *   新: 握手完成 → 分配身份 → online(userId, channel)
     *       不需要roomId，不加入任何房间
     *       客户端后续通过发 JOIN_ROOM 消息来加入房间
     */
    private void handleConnect(Channel channel) {
        // 1. 分配临时身份（后续接入DB后改为从token解析或DB查询）
        long userId = USER_ID_GEN.getAndIncrement();
        String nickname = NICKNAMES[ThreadLocalRandom.current().nextInt(NICKNAMES.length)];
        String avatar = "#" + String.format("%06X",
                ThreadLocalRandom.current().nextInt(0xFFFFFF));

        // 2. 绑定到 Channel 属性（只绑用户级信息，不绑房间级信息）
        //    【对比旧代码】旧代码 bindIdentity 还绑了 roomId 和 isHost
        //                 新代码不绑这些，因为一个用户可以在多个房间有不同角色
        ChannelAttrUtil.bindIdentity(channel, userId, 0, nickname, avatar);

        // 3. 注册在线（只记录 userId ↔ Channel 的映射，不加入房间）
        //    【对比旧代码】旧代码这里直接 joinRoom(roomId, channel)
        roomManager.online(userId, channel);

        log.info("[握手完成] userId={}, nickname={}, 等待加入房间...", userId, nickname);

        // 4. 通知客户端身份信息（客户端需要知道分配到的userId）
        //    【新增】旧代码没有这步，因为旧代码加入房间时在 joinRoom 里发了欢迎消息
        roomManager.sendToChannel(channel,
                WsRespDTO.ofGlobal(WsRespDTOTypeEnum.IDENTITY_ASSIGNED,
                        IdentityInfoRespDTO.builder()
                                .userId(userId)
                                .nickname(nickname)
                                .avatar(avatar)
                                .build()));

    }

    // ================================================================
    // 事件2：收到 WebSocket 文本消息
    //
    // 【改造核心】新增 JOIN_ROOM / LEAVE_ROOM / SEND_MSG 处理
    //            管理操作改为从消息data里取roomId（不再从Channel属性取）
    // ================================================================

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, TextWebSocketFrame frame) throws Exception {
        String text = frame.text();

        // 简单心跳（兼容直接发 "ping" 字符串的客户端）
        if ("ping".equalsIgnoreCase(text.trim())) {
            ctx.channel().writeAndFlush(new TextWebSocketFrame("pong"));
            return;
        }

        log.debug("[收到WS消息] {}", text);

        // 解析消息
        //    【不变】外层结构还是 {"type": x, "data": {...}}
        JsonObject jsonObj;
        try {
            jsonObj = JsonParser.parseString(text).getAsJsonObject();
        } catch (Exception e) {
            log.warn("[消息格式错误] 不是合法JSON: {}",
                    text.substring(0, Math.min(text.length(), 200)));
            roomManager.sendToChannel(ctx.channel(),
                    WsRespDTO.ofGlobal(WsRespDTOTypeEnum.SYSTEM_MSG, "消息格式错误：不是合法JSON"));
            return;
        }

        JsonElement typeElement = jsonObj.get("type");
        if (typeElement == null) {
            log.warn("[消息格式错误] 缺少 type: {}", text);
            return;
        }

        int typeValue = typeElement.getAsInt();
        WsReqDTOTypeEnum type = WsReqDTOTypeEnum.of(typeValue);
        if (type == null) {
            log.warn("[未知消息类型] type={}", typeValue);
            roomManager.sendToChannel(ctx.channel(),
                    WsRespDTO.ofGlobal(WsRespDTOTypeEnum.SYSTEM_MSG, "未知的消息类型: " + typeValue));
            return;
        }

        JsonElement data = jsonObj.get("data");

        /*
         * 【对比旧代码】
         *   旧 switch 里只有: HEARTBEAT / MUTE / UNMUTE / KICK / CLOSE_ROOM
         *   新 switch 增加了: JOIN_ROOM / LEAVE_ROOM / SEND_MSG
         *   因为旧代码里加入房间=建连接，发消息=走HTTP
         *   新代码里这些全部走 WS 消息
         */
        switch (type) {
            case JOIN_ROOM -> handleJoinRoom(ctx.channel(), data);
            case LEAVE_ROOM -> handleLeaveRoom(ctx.channel(), data);
            case SEND_MSG -> handleSendMsg(ctx.channel(), data);
            case HEARTBEAT -> roomManager.sendToChannel(ctx.channel(),
                    WsRespDTO.ofGlobal(WsRespDTOTypeEnum.HEARTBEAT_REPLY, "pong"));
            case MUTE -> handleMute(ctx.channel(), data, true);
            case UNMUTE -> handleMute(ctx.channel(), data, false);
            case KICK -> handleKick(ctx.channel(), data);
            case CLOSE_ROOM -> handleCloseRoom(ctx.channel(), data);
        }
    }

    // ================================================================
    // 事件3：连接断开
    //
    // 【对比旧代码】
    //   旧: leaveRoom(channel) → 离开唯一的那个房间
    //   新: offline(channel) → 内部自动离开所有房间
    // ================================================================

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        log.info("[连接断开] userId={}", ChannelAttrUtil.getUserId(ctx.channel()));
        handleDisconnect(ctx.channel());
        super.channelInactive(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("[异常] userId={}", ChannelAttrUtil.getUserId(ctx.channel()), cause);
        handleDisconnect(ctx.channel());
        ctx.fireExceptionCaught(cause);
    }

    /**
     * 统一断线处理
     *
     * 【对比旧代码】
     *   旧: getRoomManager().leaveRoom(channel) → 只离开一个房间
     *   新: roomManager.offline(channel) → 自动离开所有房间
     */
    private void handleDisconnect(Channel channel) {
        if (!channel.isOpen())  return;
        roomManager.offline(channel);
        if (channel.isActive()) {
            channel.close();
        }
    }

    // ================================================================
    //                  【新增】加入房间
    // ================================================================

    /**
     * 处理加入房间
     * 【新增方法】旧代码没有，因为旧代码建连接就自动加入
     * 客户端发: {"type":1, "data":{"roomId":"room_abc", "nickname":"Alice", "avatar":"#FF5733", "isHost":false}}
     */
    private void handleJoinRoom(Channel channel, JsonElement data) {
        // 1. 校验参数
        if (data == null) {
            roomManager.sendToChannel(channel,
                    WsRespDTO.ofGlobal(WsRespDTOTypeEnum.SYSTEM_MSG, "加入房间失败：缺少参数"));
            return;
        }

        WsJoinRoomReqDTO req = JsonUtil.fromJson(data.toString(), WsJoinRoomReqDTO.class);
        if (req == null || req.getRoomId() == null || req.getRoomId().isBlank()) {
            roomManager.sendToChannel(channel,
                    WsRespDTO.ofGlobal(WsRespDTOTypeEnum.SYSTEM_MSG, "加入房间失败：roomId不能为空"));
            return;
        }

        // 2. 获取用户ID
        Long userId = ChannelAttrUtil.getUserId(channel);
        if (userId == null) {
            log.warn("[加入房间] 未认证的连接");
            return;
        }

        // 3. 昵称/头像：优先用请求里的，否则用Channel上绑定的默认值
        String nickname = (req.getNickname() != null && !req.getNickname().isBlank())
                ? req.getNickname()
                : ChannelAttrUtil.getNickname(channel);
        String avatar = (req.getAvatar() != null && !req.getAvatar().isBlank())
                ? req.getAvatar()
                : ChannelAttrUtil.getAvatar(channel);

        // ★ 修改11：isHost 应该由服务端验证，而不是客户端传入
        //          临时方案：仍然信任客户端（标注 TODO）
        //          正式方案：查数据库 roomService.isRoomCreator(roomId, userId)
        boolean isHost = Boolean.TRUE.equals(req.getIsHost());
        // TODO: 接入数据库后改为: boolean isHost = roomService.isRoomCreator(req.getRoomId(), userId);

        // 4. 加入房间
        roomManager.joinRoom(
                req.getRoomId(),
                userId,
                nickname,
                avatar,
                isHost
        );
    }

    // ================================================================
    //                  【新增】离开房间
    // ================================================================

    /**
     * 处理离开房间
     *
     * 【新增方法】旧代码没有，因为旧代码关连接就自动离开
     *
     * 客户端发: {"type":2, "data":{"roomId":"room_abc"}}
     */
    private void handleLeaveRoom(Channel channel, JsonElement data) {
        if (data == null) {
            roomManager.sendToChannel(channel,
                    WsRespDTO.ofGlobal(WsRespDTOTypeEnum.SYSTEM_MSG, "离开房间失败：缺少参数"));
            return;
        }

        WsLeaveRoomReqDTO req = JsonUtil.fromJson(data.toString(), WsLeaveRoomReqDTO.class);
        if (req == null || req.getRoomId() == null) {
            roomManager.sendToChannel(channel,
                    WsRespDTO.ofGlobal(WsRespDTOTypeEnum.SYSTEM_MSG, "离开房间失败：roomId不能为空"));
            return;
        }

        Long userId = ChannelAttrUtil.getUserId(channel);
        if (userId == null) return;

        // 检查是否真的在这个房间
        if (!roomManager.isInRoom(req.getRoomId(), userId)) {
            roomManager.sendToChannel(channel,
                    WsRespDTO.of(req.getRoomId(), WsRespDTOTypeEnum.SYSTEM_MSG, "你不在该房间中"));
            return;
        }

        roomManager.leaveRoom(req.getRoomId(), userId);
    }

    // ================================================================
    //                  【新增】通过WS发送聊天消息
    // ================================================================

    /**
     * 处理发送消息
     *
     * 【新增方法】旧代码聊天消息走HTTP接口
     *            新代码也支持通过WS发送（HTTP接口保留，两种方式并存）
     *
     * 客户端发: {"type":3, "data":{"roomId":"room_abc", "content":"大家好"}}
     */
    private void handleSendMsg(Channel channel, JsonElement data) {
        if (data == null) {
            roomManager.sendToChannel(channel,
                    WsRespDTO.ofGlobal(WsRespDTOTypeEnum.MSG_REJECTED, "发送失败：缺少参数"));
            return;
        }

        WsSendMsgReqDTO req = JsonUtil.fromJson(data.toString(), WsSendMsgReqDTO.class);
        if (req == null || req.getRoomId() == null || req.getContent() == null) {
            roomManager.sendToChannel(channel,
                    WsRespDTO.ofGlobal(WsRespDTOTypeEnum.MSG_REJECTED, "消息格式错误"));
            return;
        }

        Long userId = ChannelAttrUtil.getUserId(channel);
        if (userId == null) return;

        String roomId = req.getRoomId();

        // 1. 检查是否在房间中
        //    【对比旧代码】旧代码不需要检查，因为连接就绑定了房间
        if (!roomManager.isInRoom(roomId, userId)) {
            roomManager.sendToChannel(channel,
                    WsRespDTO.of(roomId, WsRespDTOTypeEnum.MSG_REJECTED, "你不在该房间中，请先加入"));
            return;
        }

        // 2. 检查禁言
        //    【对比旧代码】旧代码：ChannelAttrUtil.isMuted(channel) → 全局一个禁言状态
        //                 新代码：从 RoomMemberInfo 取 → 每个房间独立的禁言状态
        RoomMemberInfo memberInfo = roomManager.getRoomMemberInfo(roomId, userId);
        if (memberInfo != null && memberInfo.isMuted()) {
            roomManager.sendToChannel(channel,
                    WsRespDTO.of(roomId, WsRespDTOTypeEnum.MSG_REJECTED, "你在该房间已被禁言"));
            return;
        }

        // 3. 构建广播消息体
        String msgId = UUID.randomUUID().toString().replace("-", "");
        ChatBroadcastMsgReqDTO broadcastMsg = ChatBroadcastMsgReqDTO.builder()
                ._id(msgId)
                .content(req.getContent())
                .senderId(userId.toString())
                .username(memberInfo != null ? memberInfo.getNickname() : "匿名")
                .avatar(memberInfo != null ? memberInfo.getAvatar() : "")
                .timestamp(System.currentTimeMillis())
                .isHost(memberInfo != null && memberInfo.isHost())
                .build();

        // 4. 广播给房间所有人
        roomManager.broadcastToRoom(roomId,
                WsRespDTO.of(roomId, WsRespDTOTypeEnum.CHAT_BROADCAST, broadcastMsg));

        log.info("[WS-发消息] room={}, userId={}, content={}", roomId, userId, req.getContent());
    }

    // ================================================================
    //                  禁言/解禁（改造）
    // ================================================================

    /**
     * 处理禁言/解禁
     *
     * 客户端发: {"type":5, "data":{"roomId":"room_abc", "targetUserId": 123}}
     *
     * 【对比旧代码】
     *   旧: 从 Channel 属性取 isHost 判断权限 → ChannelAttrUtil.isHost(channel)
     *       从 Channel 属性取 roomId → ChannelAttrUtil.getRoomId(channel)
     *       设置禁言 → ChannelAttrUtil.setMuted(targetCh, true)（全局生效）
     *   新: 从消息data取 roomId → 从 RoomMemberInfo 判断 isHost
     *       设置禁言 → roomManager.muteMember(roomId, targetUserId)（只在该房间生效）
     */
    private void handleMute(Channel channel, JsonElement data, boolean mute) {
        Long userId = ChannelAttrUtil.getUserId(channel);
        if (userId == null) return;

        // 1. 解析参数（roomId + targetUserId）
        if (data == null) {
            roomManager.sendToChannel(channel,
                    WsRespDTO.ofGlobal(WsRespDTOTypeEnum.SYSTEM_MSG, "参数错误"));
            return;
        }

        String roomId = parseRoomId(data);
        Long targetUserId = parseTargetUserId(data);

        if (roomId == null || targetUserId == null) {
            roomManager.sendToChannel(channel,
                    WsRespDTO.ofGlobal(WsRespDTOTypeEnum.SYSTEM_MSG, "参数错误：缺少roomId或targetUserId"));
            return;
        }

        // 2. 权限检查：是否为该房间的房主
        //    【对比旧代码】旧代码：ChannelAttrUtil.isHost(channel) → Channel属性，全局一个
        //                 新代码：getRoomMemberInfo → 每个房间独立判断
        RoomMemberInfo operatorInfo = roomManager.getRoomMemberInfo(roomId, userId);
        if (operatorInfo == null || !operatorInfo.isHost()) {
            roomManager.sendToChannel(channel,
                    WsRespDTO.of(roomId, WsRespDTOTypeEnum.SYSTEM_MSG, "只有房主才能操作"));
            return;
        }

        // 3. 不能操作自己
        if (targetUserId.equals(userId)) {
            roomManager.sendToChannel(channel,
                    WsRespDTO.of(roomId, WsRespDTOTypeEnum.SYSTEM_MSG, "不能操作自己"));
            return;
        }

        // 4. 检查目标用户是否在房间中
        RoomMemberInfo targetInfo = roomManager.getRoomMemberInfo(roomId, targetUserId);
        if (targetInfo == null) {
            roomManager.sendToChannel(channel,
                    WsRespDTO.of(roomId, WsRespDTOTypeEnum.SYSTEM_MSG, "该用户不在房间中"));
            return;
        }

        // 5. 执行禁言/解禁
        //    【对比旧代码】旧代码：ChannelAttrUtil.setMuted(targetCh, mute)
        //                 新代码：roomManager.muteMember / unmuteMember
        if (mute) {
            roomManager.muteMember(roomId, targetUserId);
        } else {
            roomManager.unmuteMember(roomId, targetUserId);
        }

        // 6. 通知操作者
        roomManager.sendToUser(userId,
                WsRespDTO.of(roomId, WsRespDTOTypeEnum.SYSTEM_MSG,
                        (mute ? "已禁言 " : "已解禁 ") + targetInfo.getNickname()));

        log.info("[{}] room={}, operator={}, target={}",
                mute ? "禁言" : "解禁", roomId, userId, targetUserId);
    }

    // ================================================================
    //                  踢人（改造）
    // ================================================================

    /**
     * 处理踢人
     *
     * 客户端发: {"type":7, "data":{"roomId":"room_abc", "targetUserId": 123}}
     *
     * 【对比旧代码】
     *   旧: 踢人 → findByMemberId 找Channel → 发通知 → kickMember → Channel关闭
     *   新: 踢人 → roomManager.kickMember → 只离开该房间 → 连接不关闭（可能还在其他房间）
     */
    private void handleKick(Channel channel, JsonElement data) {
        Long userId = ChannelAttrUtil.getUserId(channel);
        if (userId == null) return;

        if (data == null) {
            roomManager.sendToChannel(channel,
                    WsRespDTO.ofGlobal(WsRespDTOTypeEnum.SYSTEM_MSG, "参数错误"));
            return;
        }

        String roomId = parseRoomId(data);
        Long targetUserId = parseTargetUserId(data);

        if (roomId == null || targetUserId == null) {
            roomManager.sendToChannel(channel,
                    WsRespDTO.ofGlobal(WsRespDTOTypeEnum.SYSTEM_MSG, "参数错误：缺少roomId或targetUserId"));
            return;
        }

        // 权限检查
        RoomMemberInfo operatorInfo = roomManager.getRoomMemberInfo(roomId, userId);
        if (operatorInfo == null || !operatorInfo.isHost()) {
            roomManager.sendToChannel(channel,
                    WsRespDTO.of(roomId, WsRespDTOTypeEnum.SYSTEM_MSG, "只有房主才能踢人"));
            return;
        }

        // 不能踢自己
        if (targetUserId.equals(userId)) {
            roomManager.sendToChannel(channel,
                    WsRespDTO.of(roomId, WsRespDTOTypeEnum.SYSTEM_MSG, "不能踢自己"));
            return;
        }

        // 检查目标是否在房间
        if (!roomManager.isInRoom(roomId, targetUserId)) {
            roomManager.sendToChannel(channel,
                    WsRespDTO.of(roomId, WsRespDTOTypeEnum.SYSTEM_MSG, "该用户不在房间中"));
            return;
        }

        // 执行踢人
        //    【对比旧代码】
        //    旧: 先手动广播 USER_LEAVE → 再 kickMember（内部只关闭Channel）
        //    新: kickMember 内部会完成所有逻辑（通知被踢者 + 离开房间 + 通知其他人）
        roomManager.kickMember(roomId, targetUserId);

        roomManager.sendToUser(userId,
                WsRespDTO.of(roomId, WsRespDTOTypeEnum.SYSTEM_MSG, "已将该用户移出房间"));

        log.info("[踢人] room={}, operator={}, target={}", roomId, userId, targetUserId);
    }

    // ================================================================
    //                  关闭房间（改造）
    // ================================================================

    /**
     * 处理关闭房间
     *
     * 客户端发: {"type":8, "data":{"roomId":"room_abc"}}
     *
     * 【对比旧代码】
     *   旧: 从 Channel 属性取 roomId → ChannelAttrUtil.getRoomId(channel)
     *       关闭房间 → group.close() → 所有连接断开
     *   新: 从消息 data 取 roomId（因为用户可能同时是多个房间的房主）
     *       关闭房间 → 只清除房间数据 → 连接不断开
     */
    private void handleCloseRoom(Channel channel, JsonElement data) {
        Long userId = ChannelAttrUtil.getUserId(channel);
        if (userId == null) return;

        // 【对比旧代码】旧代码从 Channel 属性取 roomId
        //              新代码从消息 data 里取（用户可能是多个房间的房主）
        String roomId = parseRoomId(data);
        if (roomId == null) {
            roomManager.sendToChannel(channel,
                    WsRespDTO.ofGlobal(WsRespDTOTypeEnum.SYSTEM_MSG, "参数错误：缺少roomId"));
            return;
        }

        // 权限检查
        RoomMemberInfo operatorInfo = roomManager.getRoomMemberInfo(roomId, userId);
        if (operatorInfo == null || !operatorInfo.isHost()) {
            roomManager.sendToChannel(channel,
                    WsRespDTO.of(roomId, WsRespDTOTypeEnum.SYSTEM_MSG, "只有房主才能关闭房间"));
            return;
        }

        log.info("[关闭房间] room={}, operator={}", roomId, userId);
        roomManager.closeRoom(roomId);
    }

    // ================================================================
    //                  工具方法
    // ================================================================

    /**
     * 从 data 中解析 roomId
     *
     * 【对比旧代码】旧代码不需要这个方法，因为 roomId 存在 Channel 属性上
     *              新代码每条管理消息都要带 roomId
     */
    private String parseRoomId(JsonElement data) {
        try {
            if (data == null) return null;
            JsonObject obj = data.getAsJsonObject();
            JsonElement roomIdEl = obj.get("roomId");
            return roomIdEl != null ? roomIdEl.getAsString() : null;
        } catch (Exception e) {
            log.warn("[解析roomId失败]", e);
            return null;
        }
    }

    /**
     * 从 data 中解析 targetUserId
     *
     * 【对比旧代码】旧方法叫 parseTargetMemberId，从 WsReqDTO.data 解析
     *              新方法改名 parseTargetUserId，从 JsonElement 解析
     *              字段名也从 targetMemberId 改为 targetUserId（统一命名）
     */
    private Long parseTargetUserId(JsonElement data) {
        try {
            if (data == null) return null;
            JsonObject obj = data.getAsJsonObject();
            JsonElement targetEl = obj.get("targetUserId");
            return targetEl != null ? targetEl.getAsLong() : null;
        } catch (Exception e) {
            log.warn("[解析targetUserId失败]", e);
            return null;
        }
    }
}