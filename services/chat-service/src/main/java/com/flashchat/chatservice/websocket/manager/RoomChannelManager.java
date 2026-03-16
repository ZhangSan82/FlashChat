package com.flashchat.chatservice.websocket.manager;


import com.flashchat.chatservice.dto.enums.WsRespDTOTypeEnum;
import com.flashchat.chatservice.dto.req.UserJoinMsgReqDTO;
import com.flashchat.chatservice.dto.req.UserLeaveMsgReqDTO;
import com.flashchat.chatservice.dto.resp.WsRespDTO;
import com.flashchat.chatservice.toolkit.ChannelAttrUtil;
import com.flashchat.chatservice.toolkit.JsonUtil;
import com.flashchat.convention.exception.ClientException;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.util.concurrent.GlobalEventExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
/**
 * 房间与用户连接管理器
 *
 * 【本次改造重点】断线 ≠ 离开房间
 *
 *   内存分两层：
 *     第1层 - 成员关系（roomMembers + userRooms）
 *       断线不影响，只有 HTTP 主动 leaveRoom / kickMember 才移除
 *       后续接入 DB 后以 t_room_member 表为准
 *
 *     第2层 - 在线状态（userChannels + channelUserIndex）
 *       断线立即移除，重连立即恢复
 *       用于判断"能否收到 WS 推送"
 *
 *   数据结构：
 *     userChannels:     userId → Channel             （在线状态，断线删）
 *     channelUserIndex: Channel → userId              （反向索引，断线删）
 *     roomMembers:      roomId → {userId → MemberInfo}（成员关系，断线不删）
 *     userRooms:        userId → Set<roomId>          （成员关系，断线不删）
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
        // ★ 新增：检查是否有残留的房间成员关系（断线重连场景）
        //   如果有，说明用户之前断线但没退出房间，现在恢复在线
        // 检查是否有残留的房间成员关系（断线重连场景）
        Set<String> rooms = userRooms.get(userId);
        if (rooms != null && !rooms.isEmpty()) {
            log.info("[断线重连] userId={}, 恢复 {} 个房间的在线状态", userId, rooms.size());

            // 复制一份再遍历，防止并发修改
            for (String roomId : new ArrayList<>(rooms)) {
                RoomMemberInfo info = getRoomMemberInfo(roomId, userId);
                if (info != null) {
                    Map<String, Object> onlineData = new LinkedHashMap<>();
                    onlineData.put("userId", userId);
                    onlineData.put("nickname", info.getNickname());
                    onlineData.put("onlineCount", getOnlineCountInRoom(roomId));

                    broadcastToRoomExclude(roomId,
                            WsRespDTO.of(roomId, WsRespDTOTypeEnum.USER_ONLINE, onlineData),
                            userId);
                }
            }
        } else {
            log.info("[用户上线] userId={}", userId);
        }
    }

    /**
     * 用户下线（WS 连接断开时调用）
     * 【关键改变】
     *   旧: 断线 → leaveRoomInternal() → 用户不再是房间成员
     *   新: 断线 → 只清除在线状态 → 成员关系保留 → 广播 USER_OFFLINE
     *       用户重连后在 online() 中自动恢复
     */
    public void offline(Channel channel) {
        Long userId = channelUserIndex.remove(channel);
        if (userId == null) return;

        userChannels.remove(userId, channel);

        // 不再调用 leaveRoomInternal！
        //   不删除 userRooms，不删除 roomMembers
        //   用户仍然是房间成员，只是暂时不在线

        // 广播"用户离线"到所有房间
        Set<String> rooms = userRooms.get(userId);  // 注意：get 不是 remove！
        if (rooms != null && !rooms.isEmpty()) {
            for (String roomId : new ArrayList<>(rooms)) {
                RoomMemberInfo info = getRoomMemberInfo(roomId, userId);
                if (info != null) {
                    Map<String, Object> offlineData = new LinkedHashMap<>();
                    offlineData.put("userId", userId);
                    offlineData.put("nickname", info.getNickname());
                    offlineData.put("onlineCount", getOnlineCountInRoom(roomId));

                    broadcastToRoom(roomId,
                            WsRespDTO.of(roomId, WsRespDTOTypeEnum.USER_OFFLINE, offlineData));
                }
            }
        }

        log.info("[用户离线] userId={}, 仍保留 {} 个房间成员关系",
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
            //throw  new ClientException("房间ID不能为空");
        }
        if (userId == null) {
            log.warn("[加入房间] userId 为空, roomId={}", roomId);
            //throw  new ClientException("用户ID不能为空");
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
     * 内部离开逻辑（真正移除成员关系）
     *
     * 只被以下场景调用：
     *   1. leaveRoom() - 用户主动退出（HTTP）
     *   2. kickMember() → leaveRoom() - 被房主踢出（HTTP）
     *   3. closeRoom() - 房间关闭
     *
     * 不再被 offline() 调用！
     */
    private void leaveRoomInternal(String roomId, Long userId) {
        // 用数组在 lambda 中"传出"数据
        final RoomMemberInfo[] removedInfo = {null};
        final int[] remainOnline = {0};
        final boolean[] roomEmpty = {false};

        roomMembers.compute(roomId, (key, members) -> {
            if (members == null) return null;

            removedInfo[0] = members.remove(userId);
            if (removedInfo[0] == null) {
                return members.isEmpty() ? null : members;
            }

            if (members.isEmpty()) {
                roomEmpty[0] = true;
                log.info("[清理房间] room={} 已空，移除", roomId);
                return null; // 返回 null 自动删除 key
            }
            return members;
        });

        // ★ 关键：在 compute 外部执行广播和 getOnlineCountInRoom
        //   避免 compute 内部调用可能操作同一 ConcurrentHashMap 的方法
        if (removedInfo[0] != null) {
            remainOnline[0] = getOnlineCountInRoom(roomId);

            log.info("[离开房间] room={}, userId={}, nickname={},  剩余在线={}",
                    roomId, userId, removedInfo[0].getNickname(), remainOnline[0]);

            if (!roomEmpty[0]) {
                broadcastToRoom(roomId,
                        WsRespDTO.of(roomId, WsRespDTOTypeEnum.USER_LEAVE,
                                UserLeaveMsgReqDTO.builder()
                                        .nickname(removedInfo[0].getNickname())

                                        .onlineCount(remainOnline[0])
                                        .build()));
            }
        }
    }
    public void joinRoomSilent(String roomId, Long userId, String nickname, String avatar, boolean isHost) {
        if (roomId == null || roomId.isBlank() || userId == null) return;

        ConcurrentHashMap<Long, RoomMemberInfo> members =
                roomMembers.computeIfAbsent(roomId, k -> new ConcurrentHashMap<>());

        // 已经在房间中就跳过
        if (members.containsKey(userId)) return;

        RoomMemberInfo memberInfo = RoomMemberInfo.builder()
                .userId(userId)
                .nickname(nickname != null ? nickname : "匿名用户")
                .avatar(avatar != null ? avatar : "")
                .isHost(isHost)
                .isMuted(false)
                .build();

        members.put(userId, memberInfo);
        userRooms.computeIfAbsent(userId, k -> ConcurrentHashMap.newKeySet()).add(roomId);

        log.debug("[静默恢复] room={}, userId={}, nickname={}", roomId, userId, nickname);
    }

    // ===========================================================
    //                    消息推送
    // ===========================================================

    /**
     * 广播给房间所有【在线】成员
     *
     * 离线成员（断线但没退出房间）不会收到推送
     * 他们重连后不会收到断线期间的消息（除非做消息持久化+拉取）
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
     */
    public void sendToUser(Long userId, WsRespDTO<?> resp) {
        Channel ch = userChannels.get(userId);
        if (ch != null && ch.isActive()) {
            ch.writeAndFlush(new TextWebSocketFrame(JsonUtil.toJson(resp)));
        }
    }

    /**
     * 发给指定 Channel（用于未认证时的错误提示等）
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

    public Channel getChannel(Long userId) {
        return userChannels.get(userId);
    }

    /**
     * 获取用户在某房间的成员信息
     */
    public RoomMemberInfo getRoomMemberInfo(String roomId, Long userId) {
        ConcurrentHashMap<Long, RoomMemberInfo> members = roomMembers.get(roomId);
        return members != null ? members.get(userId) : null;
    }

    /**
     * 判断某用户是否在某房间
     */
    public boolean isInRoom(String roomId, Long userId) {
        ConcurrentHashMap<Long, RoomMemberInfo> members = roomMembers.get(roomId);
        return members != null && members.containsKey(userId);
    }

    /**
     * 判断用户是否在线（有活跃的 WS 连接）
     * 【新增方法】用于区分"在房间里但断线了" vs "在房间里且在线"
     */
    public boolean isOnline(Long userId) {
        Channel ch = userChannels.get(userId);
        return ch != null && ch.isActive();
    }


    public int getMemberCount(String roomId) {
        ConcurrentHashMap<Long, RoomMemberInfo> members = roomMembers.get(roomId);
        return members != null ? members.size() : 0;
    }

    /**
     * 获取房间内【在线】成员数
     * 【新增方法】遍历房间成员，只数有活跃 WS 连接的
     * 区别于 getMemberCount()（返回所有成员数，包括离线的）
     */
    public int getOnlineCountInRoom(String roomId) {
        ConcurrentHashMap<Long, RoomMemberInfo> members = roomMembers.get(roomId);
        if (members == null) return 0;

        int count = 0;
        for (Long uid : members.keySet()) {
            Channel ch = userChannels.get(uid);
            if (ch != null && ch.isActive()) {
                count++;
            }
        }
        return count;
    }

    /**
     * 获取房间所有成员ID
     */
    public Set<Long> getRoomMemberIds(String roomId) {
        ConcurrentHashMap<Long, RoomMemberInfo> members = roomMembers.get(roomId);
        return members != null ? Collections.unmodifiableSet(members.keySet()) : Collections.emptySet();
    }

    /**
     * 获取用户加入的所有房间ID
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
     */
    public void kickMember(String roomId, Long targetUserId) {
        if (!isInRoom(roomId, targetUserId)) {
            log.warn("[踢人失败] room={}, userId={} 不在房间中", roomId, targetUserId);
            throw  new ClientException("用户不在房间中");
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

    /** 判断用户在某房间是否为房主 */
    public boolean isHost(String roomId, Long userId) {
        RoomMemberInfo info = getRoomMemberInfo(roomId, userId);
        return info != null && info.isHost();
    }

    /** 判断用户在某房间是否被禁言 */
    public boolean isMuted(String roomId, Long userId) {
        RoomMemberInfo info = getRoomMemberInfo(roomId, userId);
        return info != null && info.isMuted();
    }

    /**
     * 关闭整个房间
     */
    public void closeRoom(String roomId) {
        // 原子移除房间
        final ConcurrentHashMap<Long, RoomMemberInfo>[] removedMembers = new ConcurrentHashMap[1];
        roomMembers.compute(roomId, (key, members) -> {
            removedMembers[0] = members;
            return null; // 返回 null 删除 key
        });

        if (removedMembers[0] == null) return;
        ConcurrentHashMap<Long, RoomMemberInfo> members = removedMembers[0];

        // 通知所有在线成员 + 清理 userRooms
        String json = JsonUtil.toJson(
                WsRespDTO.of(roomId, WsRespDTOTypeEnum.ROOM_CLOSED, "房间已关闭"));

        for (Long uid : members.keySet()) {
            // 原子清理 userRooms
            userRooms.compute(uid, (key, rooms) -> {
                if (rooms == null) return null;
                rooms.remove(roomId);
                return rooms.isEmpty() ? null : rooms;
            });

            // 通知在线成员
            Channel ch = userChannels.get(uid);
            if (ch != null && ch.isActive()) {
                ch.writeAndFlush(new TextWebSocketFrame(json));
            }
        }

        log.info("[关闭房间] room={}, 影响 {} 名成员", roomId, members.size());
    }

}

