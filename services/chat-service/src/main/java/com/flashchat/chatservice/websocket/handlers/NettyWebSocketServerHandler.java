package com.flashchat.chatservice.websocket.handlers;


import cn.dev33.satoken.stp.StpUtil;
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
import com.flashchat.user.toolkit.LoginIdUtil;
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
 * <p>
 * 改造后：统一用 token 认证，StpUtil.getLoginIdByToken() 校验</li>
 * <p>
 * 职责：
 * <ol>
 *   <li>握手完成 → token 认证 + 注册在线</li>
 *   <li>心跳保活</li>
 *   <li>消息路由（发消息走 HTTP 接口）</li>
 * </ol>
 * <p>
 * SaToken 在 Netty 中的 API 限制：
 * <ul>
 *   <li>StpUtil.getLoginIdByToken(token) — 纯 Redis 查询，不依赖 HttpServletRequest</li>
 *   <li>StpUtil.getSessionByLoginId(loginId) — 纯 Redis 查询</li>
 *   <li> StpUtil.isLogin() / getLoginId() / getSession() — 依赖 SaHolder，Netty 中不可用</li>
 * </ul>
 */
@Slf4j
@ChannelHandler.Sharable
public class NettyWebSocketServerHandler extends SimpleChannelInboundHandler<TextWebSocketFrame> {

    private final AccountService accountService;
    private final RoomChannelManager roomManager;
    private final RoomService roomService;
    private final ThreadPoolTaskExecutor wsBusinessExecutor;

    /**
     * 正在处理连接的 token 集合（防止同一 token 并发握手）
     */
    private final Set<String> connectingTokens = ConcurrentHashMap.newKeySet();


    /**
     * 队列剩余容量低于此值时拒绝新连接
     */
    private static final int QUEUE_CAPACITY_THRESHOLD = 10;

    public NettyWebSocketServerHandler(RoomChannelManager roomManager,
                                       AccountService accountService,
                                       RoomService roomService,
                                       ThreadPoolTaskExecutor wsBusinessExecutor) {
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
        String token = ChannelAttrUtil.getToken(channel);
        if (token == null || token.isBlank()) {
            log.warn("[握手失败]缺少token");
            sendErrorAndClose(channel,"连接失败:请传入token参数");
            return;
        }
        handleTokenConnect(channel,token);
    }

    /**
     * token 认证连接 — 异步执行
     * <p>
     * EventLoop 线程做轻量操作（防重复、队列预检查），
     * 阻塞操作（Redis 查询、DB 查询）提交到 wsBusinessExecutor。
     * <p>
     * SaToken API 选择：
     * StpUtil.getLoginIdByToken(token) — 纯 Redis 查询，不依赖 HttpServletRequest
     * 内部流程：查 Redis Key satoken:login:token:{token} → 返回 loginId 或 null
     */
    private void handleTokenConnect(Channel channel, String token) {

        // ====== 以下在 EventLoop 线程执行======

        // 防护 1：防重复连接（同一 token 并发握手）
        if (!connectingTokens.add(token)) {
            log.warn("[重复连接] token={}*** 正在处理中", token.substring(0, Math.min(8, token.length())));
            sendErrorAndClose(channel, "正在连接中，请勿重复操作");
            return;
        }

        // 防护 2：队列容量预检查
        if (wsBusinessExecutor.getThreadPoolExecutor().getQueue().remainingCapacity()
                < QUEUE_CAPACITY_THRESHOLD) {
            log.warn("[线程池繁忙] 拒绝连接，剩余容量不足");
            connectingTokens.remove(token);
            sendErrorAndClose(channel, "服务器繁忙，请稍后重试");
            return;
        }

        // ====== 以下提交到业务线程池（EventLoop 立即返回）======

        wsBusinessExecutor.execute(() -> {
            try {
                // 防护 3：执行前检查连接是否已断开
                if (!channel.isActive()) {
                    log.info("[连接已断开] 放弃处理");
                    return;
                }

                // ===== 1. SaToken 校验 token（Redis 查询）=====
                Object loginId = StpUtil.getLoginIdByToken(token);
                if (loginId == null) {
                    log.warn("[握手失败] token 无效或已过期");
                    sendErrorAndClose(channel, "Token 无效或已过期，请重新登录");
                    return;
                }

                // ===== 2. 解析 loginId =====
                // loginId 格式："member_7" 或 "user_7"
                Long accountDbId = LoginIdUtil.extractId(loginId);
                int userType = LoginIdUtil.extractUserType(loginId);

                // ===== 3. 查询账号信息（走缓存）=====
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

                // 防护 4：DB 查询后再检查连接
                if (!channel.isActive()) {
                    log.info("[连接已断开] 查询后发现连接已关闭, loginId={}", loginId);
                    return;
                }

                // ===== 4. 绑定身份到 Channel =====
                // 存入 ChannelAttrUtil，不碰 UserContext（ThreadLocal）
                // 原因：Netty EventLoop 和业务线程池都是线程复用的，
                // ThreadLocal 的 set-use-clear 窗口中如果穿插其他任务会导致身份错乱
                String nickname = account.getNickname();
                String avatar = account.getAvatarColor();

                ChannelAttrUtil.bindIdentity(channel, accountDbId, userType, nickname, avatar);

                // ===== 5. 注册在线 =====
                roomManager.online(accountDbId, channel);

                // ===== 6. 恢复房间成员关系（阻塞操作）=====
                try {
                    roomService.restoreRoomMemberships(accountDbId);
                } catch (Exception e) {
                    log.error("[恢复房间失败] accountId={}", accountDbId, e);
                    // 恢复失败不影响连接，用户可以重新加入房间
                }

                log.info("[WS 连接成功] accountId={}, accountBizId={}, nickname={}, userType={}",
                        accountDbId, account.getAccountId(), nickname, userType);

                // ===== 7. 通知客户端 =====
                // writeAndFlush 从任何线程调用都是线程安全的（Netty 保证）
                roomManager.sendToChannel(channel,
                        WsRespDTO.ofGlobal(WsRespDTOTypeEnum.LOGIN_SUCCESS,
                                IdentityInfoRespDTO.builder()
                                        .userId(accountDbId)
                                        .nickname(nickname)
                                        .avatar(avatar)
                                        .build()));

            } catch (Exception e) {
                log.error("[WS 连接异常] token={}***", token.substring(0, Math.min(8, token.length())), e);
                sendErrorAndClose(channel, "服务器内部错误，请重试");
            } finally {
                // 防护 5：必须在 finally 中移除，否则该 token 永远无法再连接
                connectingTokens.remove(token);
            }
        });
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