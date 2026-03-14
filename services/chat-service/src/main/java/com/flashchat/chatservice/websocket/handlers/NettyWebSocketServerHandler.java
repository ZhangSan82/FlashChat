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
 * 职责精简为 3 件事：
 *   1. 握手完成 → 分配身份 + 注册在线
 *   2. 心跳保活
 *   3. 发送聊天消息
 *
 * 以下操作全部移到 HTTP 接口（RoomController）：
 *   加入房间   → POST /api/room/join
 *   离开房间   → POST /api/room/leave
 *   禁言/解禁  → POST /api/room/mute、/unmute
 *   踢人      → POST /api/room/kick
 *   关闭房间   → POST /api/room/close
 *
 * 断线行为：
 *   断线 = 标记离线 + 广播 USER_OFFLINE，成员关系保留
 *   重连 = 自动恢复在线 + 广播 USER_ONLINE
 *
 * 客户端 WS 消息格式（只有 2 种）：
 *   心跳：  "ping" 或 {"type":1}
 *   发消息：{"type":2, "data":{"roomId":"room_abc", "content":"大家好"}}
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


    // ：WebSocket 握手完成 / 心跳超时

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
     */
    private void handleConnect(Channel channel) {
        // TODO分配临时身份（后续接入DB后改为从token解析或DB查询）
        long userId = USER_ID_GEN.getAndIncrement();
        String nickname = NICKNAMES[ThreadLocalRandom.current().nextInt(NICKNAMES.length)];
        String avatar = "#" + String.format("%06X",
                ThreadLocalRandom.current().nextInt(0xFFFFFF));

        // 2. 绑定到 Channel 属性（只绑用户级信息，不绑房间级信息）
        ChannelAttrUtil.bindIdentity(channel, userId, 0, nickname, avatar);

        // 3. 注册在线（只记录 userId ↔ Channel 的映射，不加入房间）
        //    【对比旧代码】旧代码这里直接 joinRoom(roomId, channel)
        roomManager.online(userId, channel);

        log.info("[握手完成] userId={}, nickname={}, 等待加入房间...", userId, nickname);

        // 4. 通知客户端身份信息（客户端需要知道分配到的userId）
        //    【新增】旧代码没有这步，因为旧代码加入房间时在 joinRoom 里发了欢迎消息
        roomManager.sendToChannel(channel,
                WsRespDTO.ofGlobal(WsRespDTOTypeEnum.LOGIN_SUCCESS,
                        IdentityInfoRespDTO.builder()
                                .userId(userId)
                                .nickname(nickname)
                                .avatar(avatar)
                                .build()));

    }


    /**
     * 连接断开
     * 断线 ≠ 离开房间
     * offline() 只标记离线 + 广播 USER_OFFLINE，成员关系保留
     */
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

    private void handleDisconnect(Channel channel) {
        if (!channel.isOpen()) return;
        roomManager.offline(channel);
        if (channel.isActive()) {
            channel.close();
        }
    }

    // ================================================================
    //                    消息处理（只有 2 种）
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
                    WsRespDTO.ofGlobal(WsRespDTOTypeEnum.SYSTEM_MSG,
                            "未知的消息类型: " + typeValue
                                    + "，WS只支持心跳(1)和发消息(2)，其他操作请走HTTP接口"));
            return;
        }

        JsonElement data = jsonObj.get("data");

        switch (type) {
            case HEARTBEAT -> ctx.channel().writeAndFlush(new TextWebSocketFrame("pong"));
            case SEND_MSG -> handleSendMsg(ctx.channel(), data);
        }
    }

    // ================================================================
    //                    发送聊天消息
    // ================================================================

    /**
     * 处理发送消息
     * <p>
     * 客户端发: {"type":2, "data":{"roomId":"room_abc", "content":"大家好"}}
     * <p>
     * 这是 WS 通道上唯一的业务操作
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
        if (!roomManager.isInRoom(roomId, userId)) {
            roomManager.sendToChannel(channel,
                    WsRespDTO.of(roomId, WsRespDTOTypeEnum.MSG_REJECTED,
                            "你不在该房间中，请先通过HTTP接口加入房间"));
            return;
        }

        // 2. 检查禁言（每个房间独立）
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

        // 4. 广播给房间所有在线成员
        roomManager.broadcastToRoom(roomId,
                WsRespDTO.of(roomId, WsRespDTOTypeEnum.CHAT_BROADCAST, broadcastMsg));

        log.info("[WS-发消息] room={}, userId={}, content={}", roomId, userId, req.getContent());
    }
}