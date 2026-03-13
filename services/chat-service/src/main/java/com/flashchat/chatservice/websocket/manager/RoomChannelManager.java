package com.flashchat.chatservice.websocket.manager;


import com.flashchat.chatservice.dto.enums.WsRespDTOTypeEnum;
import com.flashchat.chatservice.dto.req.UserJoinMsgReqDTO;
import com.flashchat.chatservice.dto.req.UserLeaveMsgReqDTO;
import com.flashchat.chatservice.dto.resp.WsRespDTO;
import com.flashchat.chatservice.toolkit.ChannelAttrUtil;
import com.flashchat.chatservice.toolkit.JsonUtil;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.util.concurrent.GlobalEventExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class RoomChannelManager {

    // 房间ID → 该房间的所有连接
    private final ConcurrentHashMap<String, ChannelGroup> roomGroups
            = new ConcurrentHashMap<>();

    // Channel → 房间ID（反向索引，断线时用）
    private final ConcurrentHashMap<Channel, String> channelRoomIndex
            = new ConcurrentHashMap<>();

    // 房间Id → (memberId → Channel)
    private final ConcurrentHashMap<String, ConcurrentHashMap<Long, Channel>> memberIndex
            = new ConcurrentHashMap<>();
//    如果 leaveRoom 没被调用（比如异常断开未触发清理），
//    channelRoomIndex 中的 Channel 引用会一直存在，
//    导致 Channel 对象无法被 GC 回收。




    /**
     * 加入房间
     */
/*    如果同一个 Channel 多次调用 joinRoom（先加入 room_001，再加入 room_002）
        ，反向索引会被覆盖为 room_002，
    但 room_001 中仍然残留这个 Channel。*/
    public void joinRoom(String roomId, Channel channel) {
        leaveRoom(channel); //先离开旧房间
        ChannelGroup group = roomGroups.computeIfAbsent(roomId,
                k -> new DefaultChannelGroup(GlobalEventExecutor.INSTANCE));

        Long memberId = ChannelAttrUtil.getMemberId(channel);
        if (memberId != null) {
            memberIndex.computeIfAbsent(roomId, k -> new ConcurrentHashMap<>())
                    .put(memberId, channel);
        }
/*
        所有房间共用同一个全局执行器。如果房间数量极大，
        ChannelGroup 的内部操作可能产生竞争。
        生产环境可考虑每个房间使用独立的 EventExecutor。
*/

        group.add(channel);
        channelRoomIndex.put(channel, roomId);

        int onlineCount = group.size();
        String nickname = Optional.ofNullable(ChannelAttrUtil.getNickname(channel))
                .orElse("匿名用户");
        log.info("[加入房间] room={}, nickname={}, 在线={}", roomId, nickname, onlineCount);

        // 给房间里的其他人广播"某某加入了"
        broadcastExclude(roomId, WsRespDTO.of(WsRespDTOTypeEnum.USER_JOIN,
                UserJoinMsgReqDTO.builder()
                        .nickname(nickname)
                        .avatar(ChannelAttrUtil.getAvatar(channel))
                        .isHost(ChannelAttrUtil.isHost(channel))
                        .onlineCount(onlineCount)
                        .build()
        ), channel);

        // 给加入者自己发欢迎消息
        sendToOne(channel, WsRespDTO.of(WsRespDTOTypeEnum.SYSTEM_MSG,
                "欢迎进入房间，当前在线 " + onlineCount + " 人"));
    }

    /**
     * 离开房间
     */
    public void leaveRoom(Channel channel) {
        String roomId = channelRoomIndex.remove(channel);
        if (roomId == null) return;

        //使用 compute 原子清理空房间
        roomGroups.compute(roomId, (key, group) -> {
            if (group == null) return null;
            group.remove(channel);

            String nickname = Optional.ofNullable(ChannelAttrUtil.getNickname(channel))
                    .orElse("未知用户");
            Long memberId = ChannelAttrUtil.getMemberId(channel);

            if (memberId != null) {
                ConcurrentHashMap<Long, Channel> members = memberIndex.get(roomId);
                if (members != null) {
                    members.remove(memberId);
                    // 如果该房间没人了，清理整个子Map
                    if (members.isEmpty()) {
                        memberIndex.remove(roomId);
                    }
                }
            }
            int onlineCount = group.size();
            log.info("[离开房间] room={}, nickname={}, 剩余={}", roomId, nickname, onlineCount);

            if (onlineCount > 0) {
                broadcast(roomId, WsRespDTO.of(WsRespDTOTypeEnum.USER_LEAVE,
                        UserLeaveMsgReqDTO.builder()
                                .nickname(nickname)
                                .reason("离开了房间")
                                .onlineCount(onlineCount)
                                .build()));
            }

            if (group.isEmpty()) {
                log.info("[清理房间] room={} 已空，移除", roomId);
                return null;  // 返回 null 自动删除这个 key
            }
            return group;
        });
    }

    /**
     * 广播给房间所有人
     */
    public void broadcast(String roomId, WsRespDTO<?> resp) {
        ChannelGroup group = roomGroups.get(roomId);
        if (group != null && !group.isEmpty()) {
            group.writeAndFlush(new TextWebSocketFrame(JsonUtil.toJson(resp)));
        }
    }

    /**
     * 广播但排除某人
     */
    public void broadcastExclude(String roomId, WsRespDTO<?> resp, Channel exclude) {
        ChannelGroup group = roomGroups.get(roomId);
        if (group == null) return;

        String json = JsonUtil.toJson(resp);
        for (Channel ch : group) {
            if (ch != exclude && ch.isActive()) {
                ch.writeAndFlush(new TextWebSocketFrame(json));
            }
        }
    }

    /**
     * 发给单个人
     */
    public void sendToOne(Channel channel, WsRespDTO<?> resp) {
        if (channel.isActive()) {
            channel.writeAndFlush(new TextWebSocketFrame(JsonUtil.toJson(resp)));
        }
    }

    /**
     * 按 memberId 查找 Channel
     */
    public Channel findByMemberId(String roomId, Long memberId) {
        ConcurrentHashMap<Long, Channel> members = memberIndex.get(roomId);
        if (members == null) return null;
        return members.get(memberId);
    }

    /**
     * 踢人
     */
    public void kickMember(String roomId, Long targetMemberId) {
        Channel ch = findByMemberId(roomId, targetMemberId);
        if (ch == null) {
            log.warn("[踢人失败] room={}, memberId={} 未找到", roomId, targetMemberId);
            return;
        }

        ch.writeAndFlush(new TextWebSocketFrame(
                JsonUtil.toJson(WsRespDTO.of(WsRespDTOTypeEnum.YOU_KICKED, "你被踢出房间"))
        )).addListener(ChannelFutureListener.CLOSE);

        // 清理索引
        channelRoomIndex.remove(ch);
        ConcurrentHashMap<Long, Channel> members = memberIndex.get(roomId);
        if (members != null) {
            members.remove(targetMemberId);
        }

        log.info("[踢人] room={}, memberId={}", roomId, targetMemberId);
    }

    /**
     * 关闭整个房间
     */
    public void closeRoom(String roomId) {
        ChannelGroup group = roomGroups.remove(roomId);
        memberIndex.remove(roomId);
        if (group == null) return;

        // 改进4：确保消息发完再关闭
        group.writeAndFlush(new TextWebSocketFrame(
                        JsonUtil.toJson(WsRespDTO.of(WsRespDTOTypeEnum.ROOM_CLOSED, "房间被关闭"))))
                .addListener(future -> {
                    for (Channel ch : group) {
                        channelRoomIndex.remove(ch);
                    }
                    group.close();
                    log.info("[关闭房间] room={}", roomId);
                });
    }


    /**
     * 获取房间所有成员ID
     */
    public Set<Long> getRoomMemberIds(String roomId) {
        ConcurrentHashMap<Long, Channel> members = memberIndex.get(roomId);
        return members != null ? members.keySet() : Collections.emptySet();
    }

    /**
     * 判断某用户是否在某房间
     */
    public boolean isInRoom(String roomId, Long memberId) {
        ConcurrentHashMap<Long, Channel> members = memberIndex.get(roomId);
        return members != null && members.containsKey(memberId);
    }

    /**
     * 禁言某人
     */
    public void muteMember(String roomId, Long targetMemberId) {
        Channel ch = findByMemberId(roomId, targetMemberId);
        if (ch != null) {
            ChannelAttrUtil.setMuted(ch, true);
            sendToOne(ch, WsRespDTO.of(WsRespDTOTypeEnum.SYSTEM_MSG, "你已被禁言"));
        }
    }


    /**
     *获取在线人数
     */
    public int getOnlineCount(String roomId) {
        ChannelGroup group = roomGroups.get(roomId);
        return group != null ? group.size() : 0;
    }
}


