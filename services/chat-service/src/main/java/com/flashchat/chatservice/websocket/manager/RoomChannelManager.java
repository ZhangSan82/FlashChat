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

import java.util.ArrayList;
import java.util.Collections;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
/**
 * 房间与用户连接管理器
 *
 * 【改造前】一个 Channel 只能在一个房间：
 *   roomGroups:       roomId → ChannelGroup
 *   channelRoomIndex: Channel → roomId（1对1）
 *   memberIndex:      roomId → (memberId → Channel)
 *
 * 【改造后】一个 Channel（用户）可以同时在多个房间：
 *   userChannels:     userId → Channel             （一个用户一条连接）
 *   channelUserIndex: Channel → userId              （反向索引，断线时用）
 *   roomMembers:      roomId → {userId → MemberInfo}（每个房间的成员和状态）
 *   userRooms:        userId → Set<roomId>          （用户在哪些房间，断线时批量清理）
 */
@Slf4j
@Component
public class RoomChannelManager {

    // ==================== 用户连接映射 ====================

    /**
     * userId → Channel
     * 一个用户只有一条 WebSocket 连接
     */
    private final ConcurrentHashMap<Long, Channel> userChannels = new ConcurrentHashMap<>();

    /**
     * Channel → userId
     * 反向索引，连接断开时快速找到是哪个用户
     */
    private final ConcurrentHashMap<Channel, Long> channelUserIndex = new ConcurrentHashMap<>();

    // ==================== 房间成员映射 ====================

    /**
     * roomId → { userId → RoomMemberInfo }
     * 每个房间有哪些成员，以及每个成员在该房间的状态（昵称、是否房主、是否禁言）
     */
    private final ConcurrentHashMap<String, ConcurrentHashMap<Long, RoomMemberInfo>> roomMembers =
            new ConcurrentHashMap<>();

    /**
     * userId → Set<roomId>
     * 用户加入了哪些房间
     *
     */
    private final ConcurrentHashMap<Long, Set<String>> userRooms = new ConcurrentHashMap<>();

    // ===========================================================
    //                    连接生命周期
    // ===========================================================

    /**
     * 用户上线：绑定 userId 与 Channel
     *
     * 【对比旧代码】旧代码没有 online 方法
     *              旧代码的连接建立直接就 joinRoom 了
     *              新代码先 online 注册连接，再通过 WS 消息加入房间
     *
     * @param userId  用户ID
     * @param channel WebSocket 连接
     */
    public void online(Long userId, Channel channel) {
        // 如果该用户已有旧连接（比如刷新页面），关闭旧连接
        Channel oldChannel = userChannels.put(userId, channel);
        if (oldChannel != null && oldChannel != channel) {
            channelUserIndex.remove(oldChannel);
            oldChannel.close();
            log.info("[用户重连] userId={}, 关闭旧连接", userId);
        }
        channelUserIndex.put(channel, userId);
        log.info("[用户上线] userId={}", userId);
    }

    /**
     * 用户下线：离开所有房间，清理所有索引
     *
     * 【对比旧代码】旧代码的 leaveRoom(Channel) 只离开一个房间
     *              新代码要批量离开所有房间
     *
     * @param channel 断开的连接
     */
    public void offline(Channel channel) {
        Long userId = channelUserIndex.remove(channel);
        if (userId == null) return;

        userChannels.remove(userId, channel);

        // 批量离开所有房间
        Set<String> rooms = userRooms.remove(userId);
        if (rooms != null) {
            for (String roomId : new ArrayList<>(rooms)) {
                leaveRoomInternal(roomId, userId);
            }
        }

        log.info("[用户下线] userId={}, 已离开 {} 个房间",
                userId, rooms != null ? rooms.size() : 0);
    }

    // ===========================================================
    //                    房间操作
    // ===========================================================

    /**
     * 加入房间
     *
     * 【对比旧代码】
     *   旧: joinRoom(roomId, channel) → 第一行就 leaveRoom(channel) 离开旧房间
     *       → 一个 Channel 只能在一个房间
     *   新: joinRoom(roomId, userId, ...) → 不离开旧房间，直接加入新房间
     *       → 同一个用户可以在多个房间
     *
     * @param roomId   房间ID
     * @param userId   用户ID
     * @param nickname 在该房间使用的昵称
     * @param avatar   在该房间使用的头像
     * @param isHost   是否为房主
     */
    public void joinRoom(String roomId, Long userId, String nickname, String avatar, boolean isHost) {

        if (roomId == null || roomId.isBlank()) {
            log.warn("[加入房间] roomId 为空, userId={}", userId);
            return;
        }
        if (userId == null) {
            log.warn("[加入房间] userId 为空, roomId={}", roomId);
            return;
        }


        // 1. 获取或创建房间成员表
        ConcurrentHashMap<Long, RoomMemberInfo> members =
                roomMembers.computeIfAbsent(roomId, k -> new ConcurrentHashMap<>());

        // 2. 检查是否已在该房间（旧代码没有这个检查，因为先 leaveRoom 了）
        if (members.containsKey(userId)) {
            log.info("[重复加入] room={}, userId={}, 已在房间中，忽略", roomId, userId);
            return;
        }

        // 3. 如果没传昵称/头像，从 Channel 属性取默认值
        Channel channel = userChannels.get(userId);
        if (nickname == null || nickname.isBlank()) {
            nickname = channel != null ? ChannelAttrUtil.getNickname(channel) : null;
        }
        if (avatar == null || avatar.isBlank()) {
            avatar = channel != null ? ChannelAttrUtil.getAvatar(channel) : null;
        }

        // 4. 创建成员信息
        //    【对比旧代码】旧代码把这些信息存在 Channel 属性上
        //                 新代码存在独立的 RoomMemberInfo 对象中
        RoomMemberInfo memberInfo = RoomMemberInfo.builder()
                .userId(userId)
                .nickname(nickname != null ? nickname : "匿名用户")
                .avatar(avatar != null ? avatar : "")
                .isHost(isHost)
                .isMuted(false)
                .build();

        // 5. 加入房间
        members.put(userId, memberInfo);

        // 6. 记录用户加入了哪些房间（旧代码没有这步，因为一个连接只在一个房间）
        userRooms.computeIfAbsent(userId, k -> ConcurrentHashMap.newKeySet())
                .add(roomId);

        int onlineCount = members.size();
        log.info("[加入房间] room={}, userId={}, nickname={}, 在线={}",
                roomId, userId, memberInfo.getNickname(), onlineCount);

        // 7. 广播给房间其他人："某某加入了"
        //    【对比旧代码】旧代码用 broadcastExclude(roomId, resp, channel)
        //                 新代码用 broadcastToRoomExclude(roomId, resp, userId)
        broadcastToRoomExclude(roomId,
                WsRespDTO.of(roomId, WsRespDTOTypeEnum.USER_JOIN,
                        UserJoinMsgReqDTO.builder()
                                .nickname(memberInfo.getNickname())
                                .avatar(memberInfo.getAvatar())
                                .isHost(memberInfo.isHost())
                                .onlineCount(onlineCount)
                                .build()),
                userId);

        // 8. 给加入者自己发欢迎消息
        sendToUser(userId,
                WsRespDTO.of(roomId, WsRespDTOTypeEnum.SYSTEM_MSG,
                        "欢迎进入房间，当前在线 " + onlineCount + " 人"));
    }

    /**
     * 主动离开房间（客户端发 LEAVE_ROOM 指令时调用）
     *
     * 【对比旧代码】
     *   旧: leaveRoom(Channel) → 从 channelRoomIndex 找到唯一的 roomId → 离开
     *   新: leaveRoom(roomId, userId) → 指定离开哪个房间（因为用户可能在多个房间）
     *
     * @param roomId 要离开的房间ID
     * @param userId 用户ID
     */
    public void leaveRoom(String roomId, Long userId) {
        if (roomId == null || roomId.isBlank()) return ;
        leaveRoomInternal(roomId, userId);

        // 从用户的房间列表中移除
        userRooms.compute(userId, (key, rooms) -> {
            if (rooms == null) return null;
            rooms.remove(roomId);
            return rooms.isEmpty() ? null : rooms;  // 空了就删除
        });
    }

    /**
     * 内部离开逻辑
     * 不操作 userRooms，供 offline() 批量调用时使用
     *
     * 【对比旧代码】旧代码的离开逻辑在 leaveRoom 中用 compute 原子操作
     *              新代码拆成独立方法，因为 offline 需要批量调用
     */
    private void leaveRoomInternal(String roomId, Long userId) {
        //  compute 原子操作，解决空房间清理竞态
        roomMembers.compute(roomId, (key, members) -> {
            if (members == null) return null;

            RoomMemberInfo memberInfo = members.remove(userId);
            if (memberInfo == null) return members.isEmpty() ? null : members;

            int onlineCount = members.size();
            log.info("[离开房间] room={}, userId={}, nickname={}, 剩余={}",
                    roomId, userId, memberInfo.getNickname(), onlineCount);

            // 通知剩余成员
            if (onlineCount > 0) {
                // ★ 注意：这里在 compute 内部广播
                //   compute 会锁住这个 key，广播耗时可能导致其他操作等待
                //   如果性能敏感，可以把广播放到 compute 外面
                broadcastToRoom(roomId,
                        WsRespDTO.of(roomId, WsRespDTOTypeEnum.USER_LEAVE,
                                UserLeaveMsgReqDTO.builder()
                                        .nickname(memberInfo.getNickname())
                                        .reason("离开了房间")
                                        .onlineCount(onlineCount)
                                        .build()));
            }

            if (members.isEmpty()) {
                log.info("[清理房间] room={} 已空，移除", roomId);
                return null;  // 返回 null 自动删除这个 key
            }
            return members;
        });
    }

    // ===========================================================
    //                    消息推送
    // ===========================================================

    /**
     * 广播给房间所有在线成员
     *
     * 【对比旧代码】
     *   旧: ChannelGroup.writeAndFlush() 一行搞定
     *   新: 遍历房间成员 → 用 userId 找到 Channel → 逐个推送
     *       因为不再用 ChannelGroup（一个 Channel 可能在多个房间的 group 里会重复）
     */
    public void broadcastToRoom(String roomId, WsRespDTO<?> resp) {
        ConcurrentHashMap<Long, RoomMemberInfo> members = roomMembers.get(roomId);
        if (members == null || members.isEmpty()) return;

        String json = JsonUtil.toJson(resp);

        for (Long uid : members.keySet()) {
            Channel ch = userChannels.get(uid);
            if (ch != null && ch.isActive()) {
                ch.writeAndFlush(new TextWebSocketFrame(json));
            }
        }
    }

    /**
     * 广播给房间成员，排除某人
     * 【对比旧代码】
     *   旧: broadcastExclude(roomId, resp, Channel exclude) → 按 Channel 排除
     *   新: broadcastToRoomExclude(roomId, resp, Long excludeUserId) → 按 userId 排除
     *       因为现在按用户维度管理，不再按 Channel 维度
     */
    public void broadcastToRoomExclude(String roomId, WsRespDTO<?> resp, Long excludeUserId) {
        ConcurrentHashMap<Long, RoomMemberInfo> members = roomMembers.get(roomId);
        if (members == null || members.isEmpty()) return;

        String json = JsonUtil.toJson(resp);

        for (Long uid : members.keySet()) {
            if (uid.equals(excludeUserId)) continue;
            Channel ch = userChannels.get(uid);
            if (ch != null && ch.isActive()) {
                ch.writeAndFlush(new TextWebSocketFrame(json));
            }
        }
    }

    /**
     * 发给指定用户（通过 userId 找到唯一的 Channel）
     *
     * 【对比旧代码】
     *   旧: sendToOne(Channel, resp) → 需要先找到 Channel
     *   新: sendToUser(userId, resp) → 内部自动找 Channel
     */
    public void sendToUser(Long userId, WsRespDTO<?> resp) {
        Channel ch = userChannels.get(userId);
        if (ch != null && ch.isActive()) {
            ch.writeAndFlush(new TextWebSocketFrame(JsonUtil.toJson(resp)));
        }
    }

    /**
     * 发给指定 Channel（用于未认证时的错误提示等）
     *
     * 【保留旧方法】有些场景用户还没认证，没有 userId，只能直接操作 Channel
     */
    public void sendToChannel(Channel channel, WsRespDTO<?> resp) {
        if (channel != null && channel.isActive()) {
            channel.writeAndFlush(new TextWebSocketFrame(JsonUtil.toJson(resp)));
        }
    }

    // ===========================================================
    //                    查询方法
    // ===========================================================

    /**
     * 根据 Channel 获取 userId
     *
     * 【新增方法】旧代码不需要，因为旧代码用 Channel 属性存 memberId
     *            新代码用独立映射
     */
    public Long getUserId(Channel channel) {
        return channelUserIndex.get(channel);
    }

    /**
     * 获取用户在某房间的成员信息
     *
     * 【新增方法】旧代码用 ChannelAttrUtil 读 Channel 属性
     *            新代码用 RoomMemberInfo，因为同一用户在不同房间状态不同
     */
    public RoomMemberInfo getRoomMemberInfo(String roomId, Long userId) {
        ConcurrentHashMap<Long, RoomMemberInfo> members = roomMembers.get(roomId);
        return members != null ? members.get(userId) : null;
    }

    /**
     * 判断某用户是否在某房间
     * （逻辑不变，只是数据源从 memberIndex 改为 roomMembers）
     */
    public boolean isInRoom(String roomId, Long userId) {
        ConcurrentHashMap<Long, RoomMemberInfo> members = roomMembers.get(roomId);
        return members != null && members.containsKey(userId);
    }

    /**
     * 获取房间在线人数
     * （逻辑不变，只是数据源改了）
     */
    public int getOnlineCount(String roomId) {
        ConcurrentHashMap<Long, RoomMemberInfo> members = roomMembers.get(roomId);
        return members != null ? members.size() : 0;
    }

    /**
     * 获取房间所有成员ID
     * （逻辑不变，只是数据源改了）
     */
    public Set<Long> getRoomMemberIds(String roomId) {
        ConcurrentHashMap<Long, RoomMemberInfo> members = roomMembers.get(roomId);
        return members != null ? Collections.unmodifiableSet(members.keySet()) : Collections.emptySet();
    }

    /**
     * 获取用户加入的所有房间ID
     *
     * 【新增方法】旧代码不需要，因为一个用户只在一个房间
     */
    public Set<String> getUserRooms(Long userId) {
        Set<String> rooms = userRooms.get(userId);
        return rooms != null ? Collections.unmodifiableSet(rooms) : Collections.emptySet();
    }

    // ===========================================================
    //                    管理操作
    // ===========================================================

    /**
     * 踢人
     * 【对比旧代码】
     *   旧: 踢人后关闭 Channel（ChannelFutureListener.CLOSE）
     *   新: 踢人只离开该房间，不关闭 Channel（用户可能还在其他房间）
     */
    public void kickMember(String roomId, Long targetUserId) {
        if (!isInRoom(roomId, targetUserId)) {
            log.warn("[踢人失败] room={}, userId={} 不在房间中", roomId, targetUserId);
            return;
        }

        // 先通知被踢者
        sendToUser(targetUserId,
                WsRespDTO.of(roomId, WsRespDTOTypeEnum.YOU_KICKED, "你被踢出房间"));

        // 再执行离开（不关闭连接！用户可能还在其他房间）
        leaveRoom(roomId, targetUserId);

        log.info("[踢人] room={}, userId={}", roomId, targetUserId);
    }

    /**
     * 禁言某人
     * 【对比旧代码】
     *   旧: ChannelAttrUtil.setMuted(ch, true) → 存在 Channel 属性上，所有房间共享
     *   新: memberInfo.setMuted(true) → 只在该房间禁言，其他房间不受影响
     */
    public void muteMember(String roomId, Long targetUserId) {
        RoomMemberInfo info = getRoomMemberInfo(roomId, targetUserId);
        if (info != null) {
            info.setMuted(true);
            sendToUser(targetUserId,
                    WsRespDTO.of(roomId, WsRespDTOTypeEnum.YOU_MUTED, "你已被禁言"));
        }
    }

    /**
     * 解除禁言
     * 【新增方法】旧代码没有解除禁言功能
     */
    public void unmuteMember(String roomId, Long targetUserId) {
        RoomMemberInfo info = getRoomMemberInfo(roomId, targetUserId);
        if (info != null) {
            info.setMuted(false);
            sendToUser(targetUserId,
                    WsRespDTO.of(roomId, WsRespDTOTypeEnum.YOU_UNMUTED, "你已解除禁言"));
        }
    }

    /**
     * 关闭整个房间
     * 【对比旧代码】
     *   旧: group.close() → 关闭所有 Channel → 用户断线
     *   新: 只通知成员并移除房间数据 → 不关闭 Channel → 用户可能还在其他房间
     */
    public void closeRoom(String roomId) {
        ConcurrentHashMap<Long, RoomMemberInfo> members = roomMembers.remove(roomId);
        if (members == null) return;

        // 通知所有成员
        String json = JsonUtil.toJson(
                WsRespDTO.of(roomId, WsRespDTOTypeEnum.ROOM_CLOSED, "房间已关闭"));

        for (Long uid : members.keySet()) {
            // 从用户的房间列表移除
            Set<String> rooms = userRooms.get(uid);
            if (rooms != null) {
                rooms.remove(roomId);
                if (rooms.isEmpty()) {
                    userRooms.remove(uid);
                }
            }
            // 通知用户（不关闭连接！）
            Channel ch = userChannels.get(uid);
            if (ch != null && ch.isActive()) {
                ch.writeAndFlush(new TextWebSocketFrame(json));
            }
        }

        log.info("[关闭房间] room={}, 影响 {} 名成员", roomId, members.size());
    }

}

