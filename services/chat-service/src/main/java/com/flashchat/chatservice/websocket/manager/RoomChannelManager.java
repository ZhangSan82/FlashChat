package com.flashchat.chatservice.websocket.manager;


import com.flashchat.channel.ChannelPushService;
import com.flashchat.channel.ChannelQueryService;
import com.flashchat.channel.event.MemberOfflineEvent;
import com.flashchat.channel.event.MemberOnlineEvent;
import com.flashchat.chatservice.dao.entity.RoomMemberDO;
import com.flashchat.chatservice.dao.enums.RoomMemberStatusEnum;
import com.flashchat.chatservice.dto.enums.WsRespDTOTypeEnum;
import com.flashchat.chatservice.dto.req.UserJoinMsgReqDTO;
import com.flashchat.chatservice.dto.req.UserLeaveMsgReqDTO;
import com.flashchat.chatservice.dto.resp.MemberInfoChangedRespDTO;
import com.flashchat.chatservice.dto.resp.WsRespDTO;
import com.flashchat.chatservice.service.RoomMemberService;
import com.flashchat.chatservice.toolkit.ChannelAttrUtil;
import com.flashchat.chatservice.toolkit.JsonUtil;
import com.flashchat.convention.storage.OssAssetUrlService;
import com.flashchat.convention.exception.ClientException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
/**
 * 房间与用户连接管理器
 */
@Slf4j
@Component
public class RoomChannelManager implements ChannelPushService, ChannelQueryService {

    private final MeterRegistry meterRegistry;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final RoomMemberService roomMemberService;
    private final OssAssetUrlService ossAssetUrlService;
    // ==================== 监控指标 ====================
    private final Counter broadcastCounter;
    private final Counter slowClientSkipCounter;
    private final Timer broadcastTimer;
    private final Timer broadcastSerializeTimer;
    private final Timer broadcastWriteTimer;
    private final Timer broadcastFlushTimer;
    private final Timer broadcastWriteCompleteTimer;
    private final Timer broadcastBatchCompleteTimer;
    private final Counter broadcastWriteFailureCounter;
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


    // ==================== 在线通道分组 ====================

    /**
     * roomId → 该房间当前在线的 Channel 集合
     * 广播时直接遍历此集合，不再查 roomMembers + userChannels
     */
    private final ConcurrentHashMap<String, Set<Channel>> roomOnlineChannels =
            new ConcurrentHashMap<>();

    public RoomChannelManager(MeterRegistry meterRegistry,
                              ApplicationEventPublisher applicationEventPublisher,
                              RoomMemberService roomMemberService,
                              OssAssetUrlService ossAssetUrlService) {
        this.meterRegistry = meterRegistry;
        this.applicationEventPublisher = applicationEventPublisher;
        this.roomMemberService = roomMemberService;
        this.ossAssetUrlService = ossAssetUrlService;

        // 动态 Gauge
        meterRegistry.gaugeMapSize("websocket.online.users", Tags.empty(), userChannels);
        meterRegistry.gaugeMapSize("flashchat.rooms.with.online.members", Tags.empty(), roomOnlineChannels);
        meterRegistry.gaugeMapSize("flashchat.rooms.total", Tags.empty(), roomMembers);
        meterRegistry.gaugeMapSize("flashchat.user.rooms.map.size", Tags.empty(), userRooms);

        // 广播指标
        this.broadcastCounter = meterRegistry.counter("flashchat.broadcast.total");
        this.slowClientSkipCounter = meterRegistry.counter("flashchat.broadcast.skip.slow_client");
        this.broadcastTimer =  meterRegistry.timer("flashchat.broadcast.duration");
        this.broadcastSerializeTimer = meterRegistry.timer("flashchat.broadcast.serialize.duration");
        this.broadcastWriteTimer = meterRegistry.timer("flashchat.broadcast.write.duration");
        this.broadcastFlushTimer = meterRegistry.timer("flashchat.broadcast.flush.duration");
        this.broadcastWriteCompleteTimer = meterRegistry.timer("flashchat.broadcast.write.complete.duration");
        this.broadcastBatchCompleteTimer = meterRegistry.timer("flashchat.broadcast.batch.complete.duration");
        this.broadcastWriteFailureCounter = meterRegistry.counter("flashchat.broadcast.write.failure");
    }

    // ===========================================================
    //                    连接生命周期
    // ===========================================================

    /**
     * 用户上线：绑定 userId 与 Channel
     * 如果用户之前断线但没退出房间，恢复到各房间的在线通道集合
     */
    public void online(Long userId, Channel channel) {
        // 1. 如果该用户已有旧连接（比如刷新页面），关闭旧连接
        Channel oldChannel = userChannels.put(userId, channel);
        if (oldChannel != null && oldChannel != channel) {
            channelUserIndex.remove(oldChannel);
            oldChannel.close();
            log.info("[用户重连] userId={}, 关闭旧连接", userId);
        }
        channelUserIndex.put(channel, userId);
        // 2. 恢复到所有房间的在线通道集合
        Set<String> rooms = userRooms.get(userId);
        if (rooms != null && !rooms.isEmpty()) {
            log.info("[断线重连] userId={}, 恢复 {} 个房间的在线状态", userId, rooms.size());

            // 复制一份再遍历，防止并发修改
            for (String roomId : new ArrayList<>(rooms)) {
                //加入在线通道集合
                roomOnlineChannels
                        .computeIfAbsent(roomId,k->ConcurrentHashMap.newKeySet())
                        .add(channel);
                RoomMemberInfo info = getRoomMemberInfo(roomId, userId);
                if (info != null) {
                    info.setLastActiveTime(System.currentTimeMillis());
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
        try {
            applicationEventPublisher.publishEvent(new MemberOnlineEvent(this, userId));
        } catch (Exception e) {
            log.error("[上线事件发布失败] userId={}", userId, e);
        }
    }

    /**
     * 用户下线（WS 连接断开时调用）
     * <P>
     * 如果未来 offline 路径上加了 DB/Redis 等外部 IO 操作，
     * 由 Handler 层的 handleDisconnect 把 offline() 提交到 wsBusinessExecutor，
     */
    public void offline(Channel channel) {
        // 1. 移除在线状态
        Long userId = channelUserIndex.remove(channel);
        if (userId == null) return;

        userChannels.remove(userId, channel);

        // 2. 从所有房间的在线通道集合移除
        Set<String> rooms = userRooms.get(userId);  // get 不是 remove
        List<String> roomsSnapshot = (rooms != null) ? new ArrayList<>(rooms) : List.of();
        removeChannelFromAllRooms(userId, channel);

        // 3. 广播 USER_OFFLINE 到所有房间
        for (String roomId : roomsSnapshot) {
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
        log.info("[用户离线] userId={}, 仍保留 {} 个房间成员关系",
                userId, roomsSnapshot.size());
        if (userId != null) {
            try {
                applicationEventPublisher.publishEvent(new MemberOfflineEvent(this, userId));
            } catch (Exception e) {
                log.error("[离线事件发布失败] userId={}", userId, e);
            }
        }
    }

    /**
     * 从所有房间的在线通道集合中移除指定 Channel
     * 被 online（关闭旧连接）和 offline（断线）调用
     */
    private void removeChannelFromAllRooms(Long userId, Channel channel) {
        Set<String> rooms = userRooms.get(userId);
        if (rooms == null) return;

        for (String roomId : rooms) {
            Set<Channel> channels = roomOnlineChannels.get(roomId);
            if (channels != null) {
                channels.remove(channel);
            }
        }
    }

    // ===========================================================
    //                    房间操作
    // ===========================================================

    /**
     * 加入房间
     * 1. 原子加入 roomMembers（putIfAbsent）
     * 2. 记录 userRooms
     * 3. 如果用户在线，加入 roomOnlineChannels
     * 4. 广播 USER_JOIN
     */
    public void joinRoom(String roomId, Long userId, String nickname, String avatar, boolean isHost, boolean isMuted) {

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

        // 检查是否已在该房间
        if (members.containsKey(userId)) {
            log.info("[重复加入] room={}, userId={}, 已在房间中，忽略", roomId, userId);
            return;
        }
        // 2. 如果没传昵称/头像，从 Channel 属性取默认值
        Channel channel = userChannels.get(userId);
        if (nickname == null || nickname.isBlank()) {
            nickname = channel != null ? ChannelAttrUtil.getNickname(channel) : null;
        }
        if (avatar == null || avatar.isBlank()) {
            avatar = channel != null ? ChannelAttrUtil.getAvatar(channel) : null;
        }
        // 3. 创建成员信息
        RoomMemberInfo memberInfo = RoomMemberInfo.builder()
                .userId(userId)
                .nickname(nickname != null ? nickname : "匿名用户")
                .avatar(avatar != null ? avatar : "")
                .isHost(isHost)
                .isMuted(isMuted)
                .lastActiveTime(System.currentTimeMillis())
                .build();
        // 4. 原子加入房间
        RoomMemberInfo existing = members.putIfAbsent(userId, memberInfo);
        if (existing != null) {
            log.info("[重复加入] room={}, userId={}, 已在房间中，忽略", roomId, userId);
            return;
        }

        // 5. 记录用户加入了哪些房间（旧代码没有这步，因为一个连接只在一个房间）
        userRooms.computeIfAbsent(userId, k -> ConcurrentHashMap.newKeySet())
                .add(roomId);
        // 6. 如果用户在线，加入该房间的在线通道集合
        if(channel != null && channel.isActive()) {
            roomOnlineChannels.computeIfAbsent(roomId, k -> ConcurrentHashMap.newKeySet())
                    .add(channel);
        }

        // 7. 获取在线人数
        int onlineCount = getOnlineCountInRoom(roomId);
        log.info("[加入房间] room={}, userId={}, nickname={}, 在线={}",
                roomId, userId, memberInfo.getNickname(), onlineCount);

        // 8. 广播给房间其他人："某某加入了"
        broadcastToRoomExclude(roomId,
                WsRespDTO.of(roomId, WsRespDTOTypeEnum.USER_JOIN,
                        UserJoinMsgReqDTO.builder()
                                .nickname(memberInfo.getNickname())
                                .avatar(resolveAccessUrl(memberInfo.getAvatar()))
                                .isHost(memberInfo.isHost())
                                .onlineCount(onlineCount)
                                .build()),
                userId);

        // 9. 给加入者自己发欢迎消息
        sendToUser(userId,
                WsRespDTO.of(roomId, WsRespDTOTypeEnum.SYSTEM_MSG,
                        "欢迎进入房间，当前在线 " + onlineCount + " 人"));
    }

    /**
     * 主动离开房间
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

        // 关键：在 compute 外部执行广播和 getOnlineCountInRoom
        //   避免 compute 内部调用可能操作同一 ConcurrentHashMap 的方法
        if (removedInfo[0] != null) {
            //从 roomOnlineChannels 移除
            Channel ch = userChannels.get(userId);
            if (ch != null) {
                Set<Channel> channels = roomOnlineChannels.get(roomId);
                if (channels != null) {
                    channels.remove(ch);
                }
            }
            if (roomEmpty[0]) {
                roomOnlineChannels.remove(roomId);
            }
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
        }else{
            log.debug("[离开房间-已不存在] room={}, userId={}, 可能已被其他操作移除", roomId, userId);
        }
    }

    /**
     * 静默恢复成员关系（重新登录时恢复 DB 中的活跃成员，不广播）
     */
    public void joinRoomSilent(String roomId, Long userId, String nickname, String avatar, boolean isHost,boolean isMuted) {
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
                .isMuted(isMuted)
                .lastActiveTime(System.currentTimeMillis())
                .build();
        // 原子操作：已存在则跳过
        RoomMemberInfo existing = members.putIfAbsent(userId, memberInfo);
        if (existing != null) return;
        userRooms.computeIfAbsent(userId, k -> ConcurrentHashMap.newKeySet()).add(roomId);
        // 如果用户在线，加入该房间的在线通道集合
        Channel channel = userChannels.get(userId);
        if (channel != null && channel.isActive()) {
            roomOnlineChannels
                    .computeIfAbsent(roomId, k -> ConcurrentHashMap.newKeySet())
                    .add(channel);
        }

        log.debug("[静默恢复] room={}, userId={}, nickname={}", roomId, userId, nickname);
    }

    /**
     * 更新用户在所有房间的昵称/头像色
     * 遍历该用户所在的所有房间，逐个更新 RoomMemberInfo。
     */
    public int updateMemberInfo(Long accountId, String newNickname, String newAvatar) {
        if (accountId == null) return 0;
        if (newNickname == null && newAvatar == null) return 0;

        Set<String> rooms = userRooms.get(accountId);
        int updatedCount = 0;
        if (rooms != null) {
            for (String roomId : rooms) {
                RoomMemberInfo info = getRoomMemberInfo(roomId, accountId);
                if (info != null) {
                    if (newNickname != null) {
                        info.setNickname(newNickname);
                    }
                    if (newAvatar != null) {
                        info.setAvatar(newAvatar);
                    }
                    updatedCount++;
                }
            }
        }
        Channel channel = userChannels.get(accountId);
        if (channel != null && channel.isActive()) {
            if (newNickname != null) {
                ChannelAttrUtil.set(channel, ChannelAttrUtil.NICKNAME, newNickname);
            }
            if (newAvatar != null) {
                ChannelAttrUtil.set(channel, ChannelAttrUtil.AVATAR, newAvatar);
            }
        }
        if (updatedCount > 0) {
            log.info("[更新成员信息] accountId={}, nickname={}, avatar={}, 影响 {} 个房间",
                    accountId, newNickname, newAvatar, updatedCount);
        }

        return updatedCount;
    }

    public void broadcastMemberInfoChanged(Long accountId, String nickname, String avatar) {
        if (accountId == null) {
            return;
        }
        Set<String> rooms = userRooms.get(accountId);
        if (rooms == null || rooms.isEmpty()) {
            return;
        }

        MemberInfoChangedRespDTO payload = MemberInfoChangedRespDTO.builder()
                .accountId(accountId)
                .nickname(nickname)
                .avatar(resolveAccessUrl(avatar))
                .build();

        for (String roomId : new ArrayList<>(rooms)) {
            broadcastToRoom(roomId,
                    WsRespDTO.of(roomId, WsRespDTOTypeEnum.MEMBER_INFO_CHANGED, payload));
        }
    }

    // ===========================================================
    //                    消息推送
    // ===========================================================
    /**
     * 广播给房间所有在线成员
     * 改造要点：
     *   1. 遍历 roomOnlineChannels（只含在线 Channel），不再查 roomMembers + userChannels
     *   2. 两轮遍历：第一轮 write（不 flush），第二轮 flush
     *      → 2 个 EventLoop 环境下，从 N 次 flush 系统调用降到 2 次
     *   3. isWritable 检查：跳过写缓冲区已满的慢客户端
     *      → 慢客户端重连后通过 ACK + /chat/new 补齐消息
     */
    public void broadcastToRoom(String roomId, WsRespDTO<?> resp) {
        Set<Channel> channels = roomOnlineChannels.get(roomId);
        if (channels == null || channels.isEmpty()) return;

        broadcastCounter.increment();
        broadcastTimer.record(() -> doBroadcast(channels, resp, null));
    }

    /**
     * 广播给房间成员，排除某人
     */
    public void broadcastToRoomExclude(String roomId, WsRespDTO<?> resp, Long excludeUserId) {
        Set<Channel> channels = roomOnlineChannels.get(roomId);
        if (channels == null || channels.isEmpty()) return;

        broadcastCounter.increment();
        broadcastTimer.record(() -> {
            Channel excludeCh = (excludeUserId != null) ? userChannels.get(excludeUserId) : null;
            doBroadcast(channels, resp, excludeCh);
        });
    }

    /**
     * 广播核心逻辑（write + flush 两轮遍历）
     */
    private void doBroadcast(Set<Channel> channels, WsRespDTO<?> resp, Channel excludeCh) {
        String json = broadcastSerializeTimer.record(() -> JsonUtil.toJson(resp));
        List<BroadcastWriteSample> writeSamples = new ArrayList<>(channels.size());

        // 第一轮：write（不 flush），跳过不可写的慢客户端
        broadcastWriteTimer.record(() -> {
            for (Channel ch : channels) {
                if (ch == excludeCh) continue;
                if (!ch.isActive()) continue;
                if (!ch.isWritable()) {
                    slowClientSkipCounter.increment();
                    continue;
                }
                long writeIssuedAtNanos = System.nanoTime();
                ChannelFuture writeFuture = ch.write(new TextWebSocketFrame(json));
                writeSamples.add(new BroadcastWriteSample(writeFuture, writeIssuedAtNanos));
            }
        });

        long batchStartAtNanos = System.nanoTime();
        AtomicInteger pendingWrites = new AtomicInteger(writeSamples.size());
        for (BroadcastWriteSample sample : writeSamples) {
            sample.future().addListener(future -> {
                long writeCompleteElapsedNanos = System.nanoTime() - sample.writeIssuedAtNanos();
                broadcastWriteCompleteTimer.record(writeCompleteElapsedNanos, TimeUnit.NANOSECONDS);
                if (!future.isSuccess()) {
                    broadcastWriteFailureCounter.increment();
                }
                if (pendingWrites.decrementAndGet() == 0) {
                    long batchElapsedNanos = System.nanoTime() - batchStartAtNanos;
                    broadcastBatchCompleteTimer.record(batchElapsedNanos, TimeUnit.NANOSECONDS);
                }
            });
        }

        // 第二轮：flush
        // 同一 EventLoop 上的多个 flush，第一个真正触发系统调用，后续无新数据时短路跳过
        broadcastFlushTimer.record(() -> {
            for (Channel ch : channels) {
                if (ch == excludeCh) continue;
                ch.flush();
            }
        });
    }

    private record BroadcastWriteSample(ChannelFuture future, long writeIssuedAtNanos) {
    }

    private String resolveAccessUrl(String value) {
        return ossAssetUrlService.resolveAccessUrl(value);
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
     */
    public int getOnlineCountInRoom(String roomId) {
       Set<Channel> channels = roomOnlineChannels.get(roomId);
       return channels != null ? channels.size() : 0;
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
        // 1. 原子移除房间
        final ConcurrentHashMap<Long, RoomMemberInfo>[] removedMembers = new ConcurrentHashMap[1];
        roomMembers.compute(roomId, (key, members) -> {
            removedMembers[0] = members;
            return null; // 返回 null 删除 key
        });
        // 2. 移除在线通道集合（保留引用用于后续通知）
        Set<Channel> onlineChannels = roomOnlineChannels.remove(roomId);
        if (removedMembers[0] == null) return;
        ConcurrentHashMap<Long, RoomMemberInfo> members = removedMembers[0];
        // 3. 清理每个成员的 userRooms
        for (Long uid : members.keySet()) {
            userRooms.compute(uid, (key, rooms) -> {
                if (rooms == null) return null;
                rooms.remove(roomId);
                return rooms.isEmpty() ? null : rooms;
            });
        }

        // 4. 通知所有在线成员（复用 doBroadcast，自动包含 isWritable 检查和指标埋点）
        if (onlineChannels != null && !onlineChannels.isEmpty()) {
            broadcastCounter.increment();
            broadcastTimer.record(() ->
                    doBroadcast(onlineChannels,
                            WsRespDTO.of(roomId, WsRespDTOTypeEnum.ROOM_CLOSED, "房间已关闭"),
                            null));
        }

        log.info("[关闭房间] room={}, 影响 {} 名成员", roomId, members.size());
    }

    /**
     * 更新成员的最后活跃时间
     * 由 ChatServiceImpl.sendMsg() 在消息发送成功后调用
     */
    public void touchMember(String roomId, Long userId) {
        RoomMemberInfo info = getRoomMemberInfo(roomId, userId);
        if (info != null) {
            info.setLastActiveTime(System.currentTimeMillis());
        }
    }

    /**
     * 获取所有房间的成员快照（供清理任务使用）
     */
    public Map<String, Map<Long, Long>> getRoomMembersSnapshot() {
        Map<String, Map<Long, Long>> snapshot = new HashMap<>();
        for (Map.Entry<String, ConcurrentHashMap<Long, RoomMemberInfo>> entry : roomMembers.entrySet()) {
            Map<Long, Long> memberTimes = new HashMap<>();
            for (Map.Entry<Long, RoomMemberInfo> memberEntry : entry.getValue().entrySet()) {
                memberTimes.put(memberEntry.getKey(), memberEntry.getValue().getLastActiveTime());
            }
            snapshot.put(entry.getKey(), memberTimes);
        }
        return snapshot;
    }

    /**
     * 移除指定房间的指定成员（清理任务调用）
     * 与 leaveRoom 的区别：不广播 USER_LEAVE（成员已长时间不活跃，无需通知）
     */
    public void removeStaleMember(String roomId, Long userId) {
        roomMembers.compute(roomId, (key, members) -> {
            if (members == null) return null;
            members.remove(userId);
            return members.isEmpty() ? null : members;
        });

        Channel ch = userChannels.get(userId);
        if (ch != null) {
            Set<Channel> channels = roomOnlineChannels.get(roomId);
            if (channels != null) {
                channels.remove(ch);
            }
        }

        userRooms.compute(userId, (key, rooms) -> {
            if (rooms == null) return null;
            rooms.remove(roomId);
            return rooms.isEmpty() ? null : rooms;
        });

        log.info("[清理僵尸成员] room={}, userId={}", roomId, userId);
    }

    /**
     * 清理残留的已关闭房间数据
     * 处理 closeRoom 因并发导致未完全清理的情况
     */
    public void cleanupClosedRoom(String roomId) {
        ConcurrentHashMap<Long, RoomMemberInfo> members = roomMembers.remove(roomId);
        roomOnlineChannels.remove(roomId);

        if (members != null) {
            for (Long uid : members.keySet()) {
                userRooms.compute(uid, (key, rooms) -> {
                    if (rooms == null) return null;
                    rooms.remove(roomId);
                    return rooms.isEmpty() ? null : rooms;
                });
            }
            log.info("[清理残留房间] room={}, 清理 {} 名成员", roomId, members.size());
        }
    }

    @Override
    public void sendToUser(Long accountId, int type, String roomId, Object data) {
        if (accountId == null || accountId < 0) {
            // 负数 ID 是 AI 玩家，没有 WS 连接，静默跳过
            return;
        }
        Channel ch = userChannels.get(accountId);
        if (ch != null && ch.isActive()) {
            String json = JsonUtil.toJson(buildWsResp(type, roomId, data));
            ch.writeAndFlush(new TextWebSocketFrame(json));
        }
    }

    @Override
    public void sendToUsers(Collection<Long> accountIds, int type, String roomId, Object data) {
        if (accountIds == null || accountIds.isEmpty()) {
            return;
        }
        String json = JsonUtil.toJson(buildWsResp(type, roomId, data));
        Set<Channel> toFlush = new HashSet<>();
        for (Long accountId : accountIds) {
            if (accountId == null || accountId < 0) {
                continue;
            }
            Channel ch = userChannels.get(accountId);
            if (ch != null && ch.isActive() && ch.isWritable()) {
                ch.write(new TextWebSocketFrame(json));
                toFlush.add(ch);
            } else if (ch != null && ch.isActive() && !ch.isWritable()) {
                slowClientSkipCounter.increment();
            }
        }
        for (Channel ch : toFlush) {
            ch.flush();
        }
    }

    @Override
    public void broadcastToRoom(String roomId, int type, Object data) {
        Set<Channel> channels = roomOnlineChannels.get(roomId);
        if (channels == null || channels.isEmpty()) {
            return;
        }
        String json = JsonUtil.toJson(buildWsResp(type, roomId, data));
        for (Channel ch : channels) {
            if (!ch.isActive()) continue;
            if (!ch.isWritable()) {
                slowClientSkipCounter.increment();
                continue;
            }
            ch.write(new TextWebSocketFrame(json));
        }
        for (Channel ch : channels) {
            ch.flush();
        }
    }

    /**
     * 构建标准 WS 推送结构体
     * <p>
     * 统一格式：{ "type": int, "roomId": string|null, "data": object }
     * 与现有的 WsRespDTO 结构一致。
     */
    private Map<String, Object> buildWsResp(int type, String roomId, Object data) {
        Map<String, Object> resp = new LinkedHashMap<>(3);
        resp.put("type", type);
        resp.put("roomId", roomId);
        resp.put("data", data);
        return resp;
    }


    /**
     * 查询用户是否在线
     */
    @Override
    public boolean isRoomMember(String roomId, Long accountId) {
        // 当前实现：offline 不移除 roomMembers，断线后仍返回 true，与持久化语义一致
        ConcurrentHashMap<Long, RoomMemberInfo> members = roomMembers.get(roomId);
        if (members != null && members.containsKey(accountId)) {
            return true;
        }
        try {
            RoomMemberDO roomMember = roomMemberService.getRoomMemberByRoomIdAndAccountId(roomId, accountId);
            return roomMember != null
                    && roomMember.getStatus() != null
                    && roomMember.getStatus() == RoomMemberStatusEnum.ACTIVE.getCode();
        } catch (Exception ex) {
            log.warn("[房间成员校验-DB兜底失败] roomId={}, accountId={}", roomId, accountId, ex);
            return false;
        }
    }

    @Override
    public int getRoomOnlineCount(String roomId) {
        return getOnlineCountInRoom(roomId);
    }
}

