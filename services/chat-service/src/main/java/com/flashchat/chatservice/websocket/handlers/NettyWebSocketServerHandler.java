package com.flashchat.chatservice.websocket.handlers;

import cn.dev33.satoken.stp.StpUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.flashchat.chatservice.config.WebSocketProperties;
import com.flashchat.chatservice.dto.enums.WsReqDTOTypeEnum;
import com.flashchat.chatservice.dto.enums.WsRespDTOTypeEnum;
import com.flashchat.chatservice.dto.resp.IdentityInfoRespDTO;
import com.flashchat.chatservice.dto.resp.TypingStatusDTO;
import com.flashchat.chatservice.dto.resp.WsRespDTO;
import com.flashchat.chatservice.service.RoomService;
import com.flashchat.chatservice.toolkit.ChannelAttrUtil;
import com.flashchat.chatservice.toolkit.JsonUtil;
import com.flashchat.chatservice.websocket.manager.RoomChannelManager;
import com.flashchat.user.toolkit.LoginIdUtil;
import com.flashchat.userservice.dao.entity.AccountDO;
import com.flashchat.userservice.dao.enums.AccountStatusEnum;
import com.flashchat.userservice.service.AccountService;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.timeout.IdleStateEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket 业务处理器。
 * 这里只处理三类事情：
 * 1. 握手完成后的 token 认证与上线
 * 2. 心跳保活
 * 3. 少量 WS 控制消息（心跳、打字中）
 *
 * 发消息主路径仍走 HTTP 接口，避免在 Netty EventLoop 上做重活。
 */
@Slf4j
@ChannelHandler.Sharable
public class NettyWebSocketServerHandler extends SimpleChannelInboundHandler<TextWebSocketFrame> {

    private final AccountService accountService;
    private final RoomChannelManager roomManager;
    private final RoomService roomService;
    private final ThreadPoolTaskExecutor wsBusinessExecutor;
    private final WebSocketProperties webSocketProperties;

    /**
     * 正在处理握手的 token 集合，用于防止同一 token 并发重复登录。
     */
    private final Set<String> connectingTokens = ConcurrentHashMap.newKeySet();

    public NettyWebSocketServerHandler(RoomChannelManager roomManager,
                                       AccountService accountService,
                                       RoomService roomService,
                                       ThreadPoolTaskExecutor wsBusinessExecutor,
                                       WebSocketProperties webSocketProperties) {
        this.roomManager = roomManager;
        this.accountService = accountService;
        this.roomService = roomService;
        this.wsBusinessExecutor = wsBusinessExecutor;
        this.webSocketProperties = webSocketProperties;
    }

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

    private void handleConnect(Channel channel) {
        String token = ChannelAttrUtil.getToken(channel);
        if (token == null || token.isBlank()) {
            log.warn("[握手失败] 缺少 token");
            sendErrorAndClose(channel, "连接失败：请传入 token 参数");
            return;
        }
        handleTokenConnect(channel, token);
    }

    /**
     * token 认证连接。
     * EventLoop 只做轻量防护，真正的 Redis/DB 查询切到独立线程池。
     */
    private void handleTokenConnect(Channel channel, String token) {
        if (!connectingTokens.add(token)) {
            log.warn("[重复连接] token={}*** 正在处理中",
                    token.substring(0, Math.min(8, token.length())));
            sendErrorAndClose(channel, "正在连接中，请勿重复操作");
            return;
        }

        if (wsBusinessExecutor.getThreadPoolExecutor().getQueue().remainingCapacity()
                < webSocketProperties.getQueueCapacityThreshold()) {
            log.warn("[线程池繁忙] 拒绝连接，剩余容量不足");
            connectingTokens.remove(token);
            sendErrorAndClose(channel, "服务器繁忙，请稍后重试");
            return;
        }

        wsBusinessExecutor.execute(() -> {
            try {
                if (!channel.isActive()) {
                    log.info("[连接已断开] 放弃处理");
                    return;
                }

                Object loginId = StpUtil.getLoginIdByToken(token);
                if (loginId == null) {
                    log.warn("[握手失败] token 无效或已过期");
                    sendErrorAndClose(channel, "Token 无效或已过期，请重新登录");
                    return;
                }

                Long accountDbId = LoginIdUtil.extractId(loginId);
                int userType = LoginIdUtil.extractUserType(loginId);

                AccountDO account;
                try {
                    account = accountService.getAccountByDbId(accountDbId);
                } catch (Exception e) {
                    log.warn("[握手失败] 查询账号异常, loginId={}", loginId, e);
                    sendErrorAndClose(channel, "账号信息获取失败，请重试");
                    return;
                }

                if (account == null) {
                    log.warn("[握手失败] 账号不存在, loginId={}", loginId);
                    sendErrorAndClose(channel, "账号不存在");
                    return;
                }

                if (account.getStatus() != null
                        && account.getStatus() == AccountStatusEnum.BANNED.getCode()) {
                    log.warn("[握手失败] 账号已封禁, loginId={}", loginId);
                    sendErrorAndClose(channel, "账号已被封禁");
                    return;
                }

                if (!channel.isActive()) {
                    log.info("[连接已断开] 查询后发现连接已关闭, loginId={}", loginId);
                    return;
                }

                String nickname = account.getNickname();
                String avatar = account.getAvatarUrl();
                if (avatar == null || avatar.isBlank()) {
                    avatar = account.getAvatarColor();
                }

                ChannelAttrUtil.bindIdentity(channel, accountDbId, userType, nickname, avatar);

                // online() 会优先基于 RoomChannelManager 内存态恢复在线房间映射。
                roomManager.online(accountDbId, channel);

                // 低风险优化：
                // 普通断线重连时，userRooms 仍然保留在 JVM 内存里，online() 已经完成恢复。
                // 这时再无条件扫 DB 恢复所有房间成员关系，会把重连风暴放大成 DB/恢复风暴。
                Set<String> reusedRooms = roomManager.getUserRooms(accountDbId);
                if (reusedRooms.isEmpty()) {
                    try {
                        roomService.restoreRoomMemberships(accountDbId);
                    } catch (Exception e) {
                        log.error("[恢复房间失败] accountId={}", accountDbId, e);
                        // 恢复失败不影响连接建立，用户仍可后续重新加入房间。
                    }
                } else {
                    log.debug("[跳过 DB 恢复] accountId={}, reusedRooms={}",
                            accountDbId, reusedRooms.size());
                }

                log.info("[WS 连接成功] accountId={}, accountBizId={}, nickname={}, userType={}",
                        accountDbId, account.getAccountId(), nickname, userType);

                roomManager.sendToChannel(channel,
                        WsRespDTO.ofGlobal(WsRespDTOTypeEnum.LOGIN_SUCCESS,
                                IdentityInfoRespDTO.builder()
                                        .userId(accountDbId)
                                        .nickname(nickname)
                                        .avatar(avatar)
                                        .build()));
            } catch (Exception e) {
                log.error("[WS 连接异常] token={}***",
                        token.substring(0, Math.min(8, token.length())), e);
                sendErrorAndClose(channel, "服务器内部错误，请重试");
            } finally {
                connectingTokens.remove(token);
            }
        });
    }

    /**
     * 发送错误消息并关闭连接。
     * Netty 线程中的异常不会被 Spring MVC 的全局异常处理接住，
     * 因此这里统一主动回写错误并关闭。
     */
    private void sendErrorAndClose(Channel channel, String errorMsg) {
        if (channel.isActive()) {
            String json = JsonUtil.toJson(
                    WsRespDTO.ofGlobal(WsRespDTOTypeEnum.SYSTEM_MSG, errorMsg));
            channel.writeAndFlush(new TextWebSocketFrame(json))
                    .addListener(ChannelFutureListener.CLOSE);
        }
    }

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
        roomManager.offline(channel);
        if (channel.isActive()) {
            channel.close();
        }
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, TextWebSocketFrame frame) {
        String text = frame.text();

        if ("ping".equalsIgnoreCase(text.trim())) {
            ctx.channel().writeAndFlush(new TextWebSocketFrame("pong"));
            return;
        }

        if (!ChannelAttrUtil.isAuthenticated(ctx.channel())) {
            roomManager.sendToChannel(ctx.channel(),
                    WsRespDTO.ofGlobal(WsRespDTOTypeEnum.SYSTEM_MSG, "请先完成身份认证"));
            return;
        }

        log.debug("[收到 WS 消息] {}", text);

        JSONObject jsonObj;
        try {
            jsonObj = JSON.parseObject(text);
        } catch (Exception e) {
            log.warn("[消息格式错误] 非法 JSON: {}",
                    text.substring(0, Math.min(text.length(), 200)));
            roomManager.sendToChannel(ctx.channel(),
                    WsRespDTO.ofGlobal(WsRespDTOTypeEnum.SYSTEM_MSG, "消息格式错误：不是合法 JSON"));
            return;
        }

        Integer typeValue = jsonObj.getInteger("type");
        if (typeValue == null) {
            log.warn("[消息格式错误] 缺少 type: {}", text);
            return;
        }

        WsReqDTOTypeEnum type = WsReqDTOTypeEnum.of(typeValue);
        if (type == null) {
            log.warn("[未知消息类型] type={}", typeValue);
            roomManager.sendToChannel(ctx.channel(),
                    WsRespDTO.ofGlobal(WsRespDTOTypeEnum.SYSTEM_MSG,
                            "未知的消息类型: " + typeValue
                                    + "，WS 只支持心跳(1)和发消息(2)，其余操作请走 HTTP 接口"));
            return;
        }

        switch (type) {
            case HEARTBEAT -> ctx.channel().writeAndFlush(new TextWebSocketFrame("pong"));
            case SEND_MSG ->
                    roomManager.sendToChannel(ctx.channel(),
                            WsRespDTO.ofGlobal(WsRespDTOTypeEnum.SYSTEM_MSG,
                                    "发消息请使用 HTTP 接口 POST /api/FlashChat/v1/chat/msg"));
            case TYPING -> handleTyping(ctx.channel(), jsonObj);
        }
    }

    private void handleTyping(Channel channel, JSONObject jsonObj) {
        Long userId = ChannelAttrUtil.getUserId(channel);
        if (userId == null) {
            return;
        }

        JSONObject data = jsonObj.getJSONObject("data");
        if (data == null) {
            return;
        }

        String roomId = data.getString("roomId");
        Boolean typing = data.getBoolean("typing");
        if (roomId == null || roomId.isBlank() || typing == null) {
            return;
        }

        if (!roomManager.isInRoom(roomId, userId)) {
            return;
        }

        TypingStatusDTO broadcastData = TypingStatusDTO.builder()
                .roomId(roomId)
                .userId(userId)
                .nickname(ChannelAttrUtil.getNickname(channel))
                .typing(typing)
                .build();

        roomManager.broadcastToRoomExclude(roomId,
                WsRespDTO.of(roomId, WsRespDTOTypeEnum.TYPING_STATUS, broadcastData),
                userId);
    }
}
