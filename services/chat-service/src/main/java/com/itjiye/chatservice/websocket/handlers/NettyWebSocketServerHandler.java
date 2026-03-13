package com.itjiye.chatservice.websocket.handlers;

import cn.hutool.extra.spring.SpringUtil;
import com.itjiye.chatservice.dto.enums.WsRespDTOTypeEnum;
import com.itjiye.chatservice.dto.req.UserLeaveMsgReqDTO;
import com.itjiye.chatservice.dto.req.WsReqDTO;
import com.itjiye.chatservice.dto.resp.WsRespDTO;
import com.itjiye.chatservice.toolkit.ChannelAttrUtil;
import com.itjiye.chatservice.toolkit.JsonUtil;
import com.itjiye.chatservice.websocket.manager.RoomChannelManager;
import io.netty.channel.*;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.internal.ThreadLocalRandom;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@ChannelHandler.Sharable
public class NettyWebSocketServerHandler extends SimpleChannelInboundHandler<TextWebSocketFrame> {
    // ===== 临时的ID和昵称生成（后续接入数据库后删除）=====
    private static final AtomicLong MEMBER_ID_GEN = new AtomicLong(1);
    private static final String[] NICKNAMES = {
            "神秘猫咪", "月光兔子", "星空企鹅", "云端熊猫", "深海水母",
            "极光狐狸", "雷电松鼠", "彩虹鹦鹉", "暗夜猫头鹰", "晨曦麻雀",
            "迷雾獾", "闪电猎豹", "蓝色海豚", "翡翠蜥蜴", "紫色蝴蝶"
    };

    private static final int TYPE_HEARTBEAT = 1;
    private static final int TYPE_MUTE = 3;
    private static final int TYPE_UNMUTE = 4;
    private static final int TYPE_KICK = 5;
    private static final int TYPE_CLOSE_ROOM = 6;

    // 构造器注入
    private final RoomChannelManager roomManager;

    public NettyWebSocketServerHandler(RoomChannelManager roomManager) {
        this.roomManager = roomManager;
    }

    private RoomChannelManager getRoomManager() {
        return roomManager;
    }

    // ================================================================
    // 事件1：WebSocket 握手完成 / 心跳超时
    // ================================================================
    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {

        if (evt instanceof WebSocketServerProtocolHandler.HandshakeComplete) {
            // ===== 握手完成 =====
            Channel channel = ctx.channel();
            String roomId = ChannelAttrUtil.getRoomId(channel);

            // 校验 roomId
            if (roomId == null || roomId.isEmpty()) {
                log.warn("连接缺少 roomId，关闭: {}", channel.id());
                channel.writeAndFlush(new TextWebSocketFrame(
                                JsonUtil.toJson(WsRespDTO.of(WsRespDTOTypeEnum.SYSTEM_MSG, "缺少roomId参数"))))
                        .addListener(ChannelFutureListener.CLOSE);
                return;
            }

            // 分配临时身份（后续接入DB后改为从DB查询）
            long memberId = MEMBER_ID_GEN.getAndIncrement();
            String nickname = NICKNAMES[ThreadLocalRandom.current().nextInt(NICKNAMES.length)];
            String avatar = "https://api.dicebear.com/7.x/adventurer/svg?seed=" + memberId;
            boolean isHost = ChannelAttrUtil.isHost(channel);

            ChannelAttrUtil.bindIdentity(channel, memberId, nickname, avatar, roomId, isHost);

            log.info("[握手完成] memberId={}, nickname={}, roomId={}, isHost={},channel={}",
                    memberId, nickname, roomId, isHost, channel.id());

            // 加入房间
            getRoomManager().joinRoom(roomId, channel);
        }
        else if (evt instanceof IdleStateEvent) {
            // ===== 心跳超时 =====
            log.info("[心跳超时] channel={}", ctx.channel().id());
            handleDisconnect(ctx.channel());
        }
        else {
            //未处理的事件传递给下一个 Handler
            super.userEventTriggered(ctx, evt);
        }
    }

    // ================================================================
    // 事件2：收到 WebSocket 文本消息
    //
    // 架构选择：聊天消息走 HTTP，这里只处理管理操作
    // ================================================================
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, TextWebSocketFrame frame) throws Exception {
        String text = frame.text();
        log.info("[收到WS消息] {}", text);

        // 解析消息
        WsReqDTO wsReqDTO = JsonUtil.fromJson(text, WsReqDTO.class);
        if (wsReqDTO == null || wsReqDTO.getType() == null) {
            log.warn("无效消息: {}", text.substring(0, Math.min(text.length(), 200)));
            getRoomManager().sendToOne(ctx.channel(),
                    WsRespDTO.of(WsRespDTOTypeEnum.SYSTEM_MSG, "消息格式无效"));
        }

        // 按类型分发
        switch (wsReqDTO.getType()) {
            case TYPE_HEARTBEAT:
                break;
            case TYPE_MUTE:
                handleMute(ctx.channel(), wsReqDTO, true);
                break;
            case TYPE_UNMUTE:
                handleMute(ctx.channel(), wsReqDTO, false);
                break;
            case TYPE_KICK:
                handleKick(ctx.channel(), wsReqDTO);
                break;
            case TYPE_CLOSE_ROOM:
                handleCloseRoom(ctx.channel());
                break;
            default:
                log.warn("未知消息类型: {}", wsReqDTO.getType());
                getRoomManager().sendToOne(ctx.channel(),
                        WsRespDTO.of(WsRespDTOTypeEnum.SYSTEM_MSG, "未知的消息类型"));
        }
    }

    // ================================================================
    // 事件3：连接断开
    // ================================================================
    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        log.info("[连接断开] channel={}", ctx.channel().id());
        handleDisconnect(ctx.channel());
        super.channelInactive(ctx);
    }

    // ================================================================
    // 事件4：异常
    // ================================================================
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("[异常] channel={}", ctx.channel().id(), cause);
        handleDisconnect(ctx.channel());
    }

    // ================================================================
    // 私有方法
    // ================================================================

    private void handleDisconnect(Channel channel) {
        if (!channel.isActive() && !channel.isOpen()) {
            return;
        }
        getRoomManager().leaveRoom(channel);
        channel.close();
    }

    /**
     * 处理禁言/解禁
     */
    private void handleMute(Channel channel, WsReqDTO req, boolean mute) {
        if (!ChannelAttrUtil.isHost(channel)) {
            getRoomManager().sendToOne(channel,
                    WsRespDTO.of(WsRespDTOTypeEnum.SYSTEM_MSG, "只有房主才能操作"));
            return;
        }

        Long targetMemberId = parseTargetMemberId(req);
        if (targetMemberId == null)
        {
            getRoomManager().sendToOne(channel,
                    WsRespDTO.of(WsRespDTOTypeEnum.SYSTEM_MSG, "参数错误：缺少targetMemberId"));
            return;
        }

        //：禁言也加自我检查
        if (targetMemberId.equals(ChannelAttrUtil.getMemberId(channel))) {
            getRoomManager().sendToOne(channel,
                    WsRespDTO.of(WsRespDTOTypeEnum.SYSTEM_MSG, "不能操作自己"));
            return;
        }

        String roomId = ChannelAttrUtil.getRoomId(channel);
        Channel targetCh = getRoomManager().findByMemberId(roomId, targetMemberId);
        if (targetCh == null) {
            getRoomManager().sendToOne(channel,
                    WsRespDTO.of(WsRespDTOTypeEnum.SYSTEM_MSG, "该用户不在房间中"));
            return;
        }

        ChannelAttrUtil.setMuted(targetCh, mute);
        getRoomManager().sendToOne(targetCh,
                WsRespDTO.of(mute ? WsRespDTOTypeEnum.YOU_MUTED : WsRespDTOTypeEnum.YOU_UNMUTED, null));

        String targetNickname = ChannelAttrUtil.getNickname(targetCh);
        getRoomManager().sendToOne(channel,
                WsRespDTO.of(WsRespDTOTypeEnum.SYSTEM_MSG,
                        (mute ? "已禁言 " : "已解禁 ") + targetNickname));


        log.info("[{}] room={}, target={}", mute ? "禁言" : "解禁", roomId, targetMemberId);
    }

    /**
     * 处理踢人
     */
    private void handleKick(Channel channel, WsReqDTO req) {
        if (!ChannelAttrUtil.isHost(channel)) {
            getRoomManager().sendToOne(channel,
                    WsRespDTO.of(WsRespDTOTypeEnum.SYSTEM_MSG, "只有房主才能踢人"));
            return;
        }

        Long targetMemberId = parseTargetMemberId(req);
        if (targetMemberId == null) return;

        if (targetMemberId.equals(ChannelAttrUtil.getMemberId(channel))) {
            getRoomManager().sendToOne(channel,
                    WsRespDTO.of(WsRespDTOTypeEnum.SYSTEM_MSG, "不能踢自己"));
            return;
        }

        String roomId = ChannelAttrUtil.getRoomId(channel);
        Channel targetCh = getRoomManager().findByMemberId(roomId, targetMemberId);

        if (targetCh == null) {
            //目标不存在时给反馈
            getRoomManager().sendToOne(channel,
                    WsRespDTO.of(WsRespDTOTypeEnum.SYSTEM_MSG, "该用户不在房间中"));
            return;
        }

        String targetNickname = ChannelAttrUtil.getNickname(targetCh);


        int onlineCount = getRoomManager().getOnlineCount(roomId) - 1;
        getRoomManager().broadcast(roomId, WsRespDTO.of(WsRespDTOTypeEnum.USER_LEAVE,
                UserLeaveMsgReqDTO.builder()
                        .nickname(targetNickname)
                        .reason("被房主移出房间")
                        .onlineCount(onlineCount)
                        .build()));

        getRoomManager().kickMember(roomId, targetMemberId);
    }

    /**
     * 处理关闭房间
     */
    private void handleCloseRoom(Channel channel) {
        if (!ChannelAttrUtil.isHost(channel)) {
            getRoomManager().sendToOne(channel,
                    WsRespDTO.of(WsRespDTOTypeEnum.SYSTEM_MSG, "只有房主才能关闭房间"));
            return;
        }

        String roomId = ChannelAttrUtil.getRoomId(channel);
        log.info("[关闭房间] room={}", roomId);
        getRoomManager().closeRoom(roomId);
    }

    /**
     * 从 WsReq.data 中解析 targetMemberId
     */
    private Long parseTargetMemberId(WsReqDTO req) {
        try {
            if (req.getData() == null) return null;
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> data = (java.util.Map<String, Object>) req.getData();
            Object id = data.get("targetMemberId");
            if (id instanceof Number) {
                return ((Number) id).longValue();
            }
            return Long.parseLong(id.toString());
        } catch (Exception e) {
            log.warn("解析 targetMemberId 失败", e);
            return null;
        }
    }
}

