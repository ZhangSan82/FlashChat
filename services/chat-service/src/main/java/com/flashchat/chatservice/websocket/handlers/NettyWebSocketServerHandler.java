package com.flashchat.chatservice.websocket.handlers;


import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.flashchat.chatservice.dao.entity.AccountDO;
import com.flashchat.chatservice.dao.enums.AccountStatusEnum;
import com.flashchat.chatservice.dto.enums.WsReqDTOTypeEnum;
import com.flashchat.chatservice.dto.enums.WsRespDTOTypeEnum;
import com.flashchat.chatservice.dto.resp.IdentityInfoRespDTO;
import com.flashchat.chatservice.dto.resp.WsRespDTO;
import com.flashchat.chatservice.service.AccountService;
import com.flashchat.chatservice.service.RoomService;
import com.flashchat.chatservice.toolkit.ChannelAttrUtil;
import com.flashchat.chatservice.toolkit.JsonUtil;

import com.flashchat.chatservice.websocket.manager.RoomChannelManager;
import io.netty.channel.*;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.timeout.IdleStateEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;


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



    //private final MemberService memberService;
    private final AccountService accountService;
    private final RoomChannelManager roomManager;
    private final RoomService roomService;
    private final ThreadPoolTaskExecutor wsBusinessExecutor;

    /**
     * 正在处理连接的 accountId 集合
     * 防止同一用户快速刷新页面导致并发处理同一个 accountId
     */
    private final Set<String> connectingAccounts = ConcurrentHashMap.newKeySet();

    /**
     * 队列剩余容量低于此值时拒绝新连接
     */
    private static final int QUEUE_CAPACITY_THRESHOLD = 10;

    public NettyWebSocketServerHandler(RoomChannelManager roomManager, AccountService accountService, RoomService roomService, ThreadPoolTaskExecutor wsBusinessExecutor) {
        this.roomManager = roomManager;
        this.accountService = accountService;
        this.roomService = roomService;
        this.wsBusinessExecutor = wsBusinessExecutor;
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
            handleAnonymousConnectAsync(channel, accountId);

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
     * 匿名成员连接 — 异步执行
     */
    private void handleAnonymousConnectAsync(Channel channel, String accountId) {

        // ============================
        // 以下 3 个防护在 EventLoop 线程执行（纳秒级，不阻塞）
        // ============================

        // 防护1：防重复连接
        if (!connectingAccounts.add(accountId)) {
            log.warn("[重复连接] accountId={} 正在处理中，拒绝本次", accountId);
            sendErrorAndClose(channel, "正在连接中，请勿重复操作");
            return;
        }

        // 防护2：队列容量预检查
        if (wsBusinessExecutor.getThreadPoolExecutor().getQueue().remainingCapacity() < QUEUE_CAPACITY_THRESHOLD) {
            log.warn("[线程池繁忙] 拒绝连接 accountId={}, 剩余容量不足", accountId);
            connectingAccounts.remove(accountId);
            sendErrorAndClose(channel, "服务器繁忙，请稍后重试");
            return;
        }

        // ============================
        // 提交到业务线程池（EventLoop 立即返回）
        // ============================
        wsBusinessExecutor.execute(() -> {
            try {
                // ============================
                // 以下全部在 ws-biz 线程中执行
                // 即使阻塞也不影响 Netty EventLoop
                // ============================

                // 防护3：执行前检查连接（用户可能在排队期间已断开）
                if (!channel.isActive()) {
                    log.info("[连接已断开] accountId={}, 放弃处理", accountId);
                    return;
                }

                // ===== 1. 查 DB（阻塞操作，现在在业务线程池中执行）=====
                AccountDO account;
                try {
                    account = accountService.getByAccountId(accountId);
                } catch (Exception e) {
                    log.warn("[握手失败] accountId={}, 查询异常: {}", accountId, e.getMessage());
                    sendErrorAndClose(channel, "账号不存在，请先注册");
                    return;
                }

                if (account == null) {
                    log.warn("[握手失败] accountId={} 不存在", accountId);
                    sendErrorAndClose(channel, "账号不存在，请先调用 /member/auto-register 注册");
                    return;
                }

                if (account.getStatus() != null && account.getStatus() == AccountStatusEnum.BANNED.getCode()) {
                    log.warn("[握手失败] accountId={} 已被封禁", accountId);
                    sendErrorAndClose(channel, "账号已被封禁");
                    return;
                }

                // 防护4：DB 查询后再检查连接（查询可能耗时较长）
                if (!channel.isActive()) {
                    log.info("[连接已断开] accountId={}, DB查询后发现连接已关闭", accountId);
                    return;
                }

                // ===== 2. 绑定身份（内存操作，安全）=====
                Long acctId = account.getId();
                String nickname = account.getNickname();
                String avatar = account.getAvatarColor();

                ChannelAttrUtil.bindIdentity(channel, acctId, 0, nickname, avatar);

                // ===== 3. 注册在线（内存操作，安全）=====
                roomManager.online(acctId, channel);

                // ===== 4. 从 DB 恢复房间成员关系（阻塞操作，在业务线程池中执行）=====
                try {
                    roomService.restoreRoomMemberships(acctId);
                } catch (Exception e) {
                    log.error("[恢复房间失败] acctId={}", acctId, e);
                    // 恢复失败不影响连接，用户可以重新加入房间
                }

                log.info("[匿名连接成功] acctId={}, accountId={}, nickname={}",
                        acctId, accountId, nickname);

                // ===== 5. 通知客户端（writeAndFlush 从任何线程调用都是线程安全的）=====
                roomManager.sendToChannel(channel,
                        WsRespDTO.ofGlobal(WsRespDTOTypeEnum.LOGIN_SUCCESS,
                                IdentityInfoRespDTO.builder()
                                        .userId(acctId)
                                        .nickname(nickname)
                                        .avatar(avatar)
                                        .build()));

            } catch (Exception e) {
                log.error("[匿名连接异常] accountId={}", accountId, e);
                sendErrorAndClose(channel, "服务器内部错误，请重试");
            } finally {
                // 防护5：必须在 finally 中移除，否则该 accountId 永远无法再连接
                connectingAccounts.remove(accountId);
            }
        });
    }

    /**
     * 注册用户连接（Phase 5 实现）* 未来实现时也需要提交到 wsBusinessExecutor
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
        JSONObject jsonObj;
        try {
            jsonObj = JSON.parseObject(text);
        } catch (Exception e) {
            log.warn("[消息格式错误] 不是合法JSON: {}",
                    text.substring(0, Math.min(text.length(), 200)));
            roomManager.sendToChannel(ctx.channel(),
                    WsRespDTO.ofGlobal(WsRespDTOTypeEnum.SYSTEM_MSG, "消息格式错误：不是合法JSON"));
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
                                    + "，WS只支持心跳(1)和发消息(2)，其他操作请走HTTP接口"));
            return;
        }


        switch (type) {
            case HEARTBEAT -> ctx.channel().writeAndFlush(new TextWebSocketFrame("pong"));
            case SEND_MSG ->// 发消息走 HTTP 接口
                    roomManager.sendToChannel(ctx.channel(),
                            WsRespDTO.ofGlobal(WsRespDTOTypeEnum.SYSTEM_MSG,
                                    "发消息请使用 HTTP 接口 POST /api/FlashChat/v1/chat/msg"));
        }
    }


}