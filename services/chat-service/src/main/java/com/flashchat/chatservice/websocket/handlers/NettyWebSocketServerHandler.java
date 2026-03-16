package com.flashchat.chatservice.websocket.handlers;


import com.flashchat.chatservice.dao.entity.MemberDO;
import com.flashchat.chatservice.dto.enums.WsReqDTOTypeEnum;
import com.flashchat.chatservice.dto.enums.WsRespDTOTypeEnum;
import com.flashchat.chatservice.dto.req.*;
import com.flashchat.chatservice.dto.resp.ChatBroadcastMsgRespDTO;
import com.flashchat.chatservice.dto.resp.IdentityInfoRespDTO;
import com.flashchat.chatservice.dto.resp.WsRespDTO;
import com.flashchat.chatservice.service.MemberService;
import com.flashchat.chatservice.service.RoomService;
import com.flashchat.chatservice.toolkit.ChannelAttrUtil;
import com.flashchat.chatservice.toolkit.JsonUtil;

import com.flashchat.chatservice.websocket.manager.RoomChannelManager;
import com.flashchat.chatservice.websocket.manager.RoomMemberInfo;
import com.flashchat.convention.exception.ClientException;
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



    private final MemberService memberService;
    private final RoomChannelManager roomManager;
    private final RoomService roomService;

    public NettyWebSocketServerHandler(RoomChannelManager roomManager, MemberService memberService,RoomService roomService) {
        this.roomManager = roomManager;
        this.memberService = memberService;
        this.roomService = roomService;
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
        log.info("channel:{}", channel);
        String accountId = ChannelAttrUtil.getAccountId(channel);
        String token = ChannelAttrUtil.getToken(channel);

        if (accountId != null && !accountId.isBlank()) {
            // ===== 匿名成员 =====
            handleAnonymousConnect(channel, accountId);

        } else if (token != null && !token.isBlank()) {
            // ===== 注册用户（未来实现）=====
            handleRegisteredUserConnect(channel, token);

        } else {
            // ===== 都没传 =====
            log.warn("[握手失败] 缺少 accountId 或 token 参数");
            sendErrorAndClose(channel, "连接失败：请传入 accountId 或 token");
        }
    }

    /**
     * 匿名成员连接
     *
     * 流程：
     *   前端传 accountId → 查 DB 获取 MemberDO → 绑定 member.id 作为内部ID
     *
     * 为什么用 accountId 而不是 member.id：
     *   accountId 是面向用户的标识，不暴露自增主键
     *   用户可以跨设备输入 accountId 登录
     */
    private void handleAnonymousConnect(Channel channel, String accountId) {

        // 1.//TODO 查 DB
        MemberDO member = memberService.getByAccountId(accountId);

        if (member == null) {
            log.warn("[握手失败] accountId={} 不存在", accountId);
            sendErrorAndClose(channel, "账号不存在，请先调用 /member/auto-register 注册");
            return;
        }

        if (member.getStatus() != null && member.getStatus() == 0) {
            log.warn("[握手失败] accountId={} 已被封禁", accountId);
            sendErrorAndClose(channel, "账号已被封禁");
            return;
        }

        // 2. 绑定真实身份
        //    Channel 上存的 userId = member.id（数据库主键）
        //    后续 RoomChannelManager、消息入库 都用这个 ID
        Long memberId = member.getId();
        String nickname = member.getNickname();
        String avatar = member.getAvatarColor();

        ChannelAttrUtil.bindIdentity(channel, memberId, 0, nickname, avatar);

        // 3. 注册在线
        roomManager.online(memberId, channel);

        // ★ 4. 从 DB 恢复房间成员关系
        try {
            roomService.restoreRoomMemberships(memberId);
        } catch (Exception e) {
            log.error("[恢复房间失败] memberId={}", memberId, e);
            // 恢复失败不影响连接，用户可以重新加入房间
        }


        log.info("[匿名连接成功] memberId={}, accountId={}, nickname={}",
                memberId, accountId, nickname);

        // 4. 通知客户端
        roomManager.sendToChannel(channel,
                WsRespDTO.ofGlobal(WsRespDTOTypeEnum.LOGIN_SUCCESS,
                        IdentityInfoRespDTO.builder()
                                .userId(memberId)
                                .nickname(nickname)
                                .avatar(avatar)
                                .build()));
    }

    /**
     * 注册用户连接（Phase 5 实现）
     */
    private void handleRegisteredUserConnect(Channel channel, String token) {
        // TODO Phase 5：Sa-Token 验证 token → 查 t_user → 绑定身份（userType=1）
        log.info("[注册用户连接] token={}, 功能暂未开放", token);
        sendErrorAndClose(channel, "注册用户登录暂未开放，敬请期待");
    }

    /**
     * 发送错误消息并关闭连接
     * 不能用 throw ClientException：
     *   Netty 线程中抛出的异常不会被 Spring GlobalExceptionHandler 捕获
     *   必须手动发送错误消息 + 手动关闭连接
     */
    private void sendErrorAndClose(Channel channel, String errorMsg) {
        if (channel.isActive()) {
            String json = JsonUtil.toJson(
                    WsRespDTO.ofGlobal(WsRespDTOTypeEnum.SYSTEM_MSG, errorMsg));
            channel.writeAndFlush(new TextWebSocketFrame(json))
                    .addListener(ChannelFutureListener.CLOSE);
        }
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

        // 未认证不处理
        if (!ChannelAttrUtil.isAuthenticated(ctx.channel())) {
            roomManager.sendToChannel(ctx.channel(),
                    WsRespDTO.ofGlobal(WsRespDTOTypeEnum.SYSTEM_MSG, "请先完成身份认证"));
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
            case SEND_MSG ->// 发消息走 HTTP 接口
                    roomManager.sendToChannel(ctx.channel(),
                            WsRespDTO.ofGlobal(WsRespDTOTypeEnum.SYSTEM_MSG,
                                    "发消息请使用 HTTP 接口 POST /api/FlashChat/v1/chat/msg"));
        }
    }


}