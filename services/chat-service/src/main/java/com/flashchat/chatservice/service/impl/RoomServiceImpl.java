package com.flashchat.chatservice.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.flashchat.cache.MultistageCacheProxy;
import com.flashchat.cache.toolkit.CacheUtil;
import com.flashchat.chatservice.dao.entity.RoomDO;
import com.flashchat.chatservice.dao.entity.RoomMemberDO;
import com.flashchat.chatservice.dao.enums.*;
import com.flashchat.chatservice.dao.mapper.RoomMapper;
import com.flashchat.chatservice.delay.producer.RoomDelayProducer;
import com.flashchat.chatservice.dto.context.HostOperationContext;
import com.flashchat.chatservice.dto.enums.WsRespDTOTypeEnum;
import com.flashchat.chatservice.dto.req.*;
import com.flashchat.chatservice.dto.resp.RoomInfoRespDTO;
import com.flashchat.chatservice.dto.resp.RoomMemberRespDTO;
import com.flashchat.chatservice.dto.resp.WsRespDTO;
import com.flashchat.chatservice.service.*;
import com.flashchat.chatservice.toolkit.HashUtil;
import com.flashchat.chatservice.websocket.manager.RoomChannelManager;
import com.flashchat.chatservice.websocket.manager.RoomMemberInfo;
import com.flashchat.convention.exception.ClientException;
import com.flashchat.convention.exception.ServiceException;
import com.flashchat.user.core.UserContext;
import com.flashchat.userservice.dao.entity.AccountDO;
import com.flashchat.userservice.service.AccountService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBloomFilter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@AllArgsConstructor
public class RoomServiceImpl extends ServiceImpl<RoomMapper, RoomDO> implements RoomService {

    private final RoomChannelManager roomChannelManager;
    @Qualifier("flashChatRoomRegisterCachePenetrationBloomFilter")
    private final RBloomFilter<String> flashChatRoomRegisterCachePenetrationBloomFilter;
    private final RoomMemberService roomMemberService;
    private final UnreadService unreadService;
    private final AccountService accountService;
    private final MultistageCacheProxy multistageCacheProxy;
    private final RoomDelayProducer roomDelayProducer;
    private final MessageWindowService messageWindowService;
    private static final long CACHE_TIMEOUT = 60000L;
    /**单用户最多同时加入的房间数 */
    private static final int MAX_ROOMS_PER_USER = 50;


    /**
     * 创建房间
     */
    @Transactional
    @Override
    public RoomInfoRespDTO createRoom(RoomCreateReqDTO request) {


        Long creatorId = UserContext.getRequiredLoginId();
        AccountDO creator = accountService.getAccountByDbId(creatorId);


        // TODO 未来：检查是否为注册用户（主持人）+ 扣除积分

        RoomDurationEnum durationEnum = RoomDurationEnum.of(request.getDuration());
        if (durationEnum == null) {
            durationEnum = RoomDurationEnum.MIN_30;  // 默认 30 分钟
        }
        LocalDateTime expireTime = LocalDateTime.now().plusMinutes(durationEnum.getMinutes());

        String roomId = getRoomID();

        //t_room
        RoomDO room = RoomDO.builder()
                .roomId(roomId)
                .creatorId(creatorId)//t_account.id
                .title(request.getTitle())
                .maxMembers(request.getMaxMembers() != null ? request.getMaxMembers() : 50)
                .isPublic(request.getIsPublic() != null ? request.getIsPublic() : 0)
                .status(RoomStatusEnum.WAITING.getCode())
                .expireTime(expireTime)
                .expireVersion(1)
                .build();
        try {
            this.save(room);
            flashChatRoomRegisterCachePenetrationBloomFilter.add(
                    CacheUtil.buildKey("flashchat", "room", roomId));
            multistageCacheProxy.put(CacheUtil.buildKey("flashchat","room",roomId),
                    room,
                    CACHE_TIMEOUT);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        log.info("[创建房间] roomId={}, title={}, creator={}", roomId, room.getTitle(), room.getCreatorId());

        try {
            roomDelayProducer.submitRoomExpireEvents(roomId, expireTime, room.getExpireVersion());
        } catch (Exception e) {
            // 投递失败不阻塞创建流程，由兜底定时任务保障
            log.error("[延时任务投递失败] room={}, 不影响创建，将由兜底任务兜底", roomId, e);
        }

        RoomMemberDO hostMember = RoomMemberDO.builder()
                .roomId(roomId)
                .accountId(creatorId)
                .role(RoomMemberRoleEnum.HOST.getCode())
                .isMuted(RoomMemberMuteStatusEnum.UNMUTE.getCode())
                .status(RoomMemberStatusEnum.ACTIVE.getCode())
                .lastAckMsgId(0L)
                .joinTime(LocalDateTime.now())
                .build();

        roomMemberService.saveWithCache(hostMember);

        roomChannelManager.joinRoom(roomId, creator.getId(),
                creator.getNickname(), creator.getAvatarColor(), true);


        return buildRoomInfoResp(room);
    }

    /**
     * 加入房间
     */
    @Override
    @Transactional
    public RoomInfoRespDTO joinRoom(RoomJoinReqDTO request) {
        String roomId = request.getRoomId();

        Long accountId = UserContext.getRequiredLoginId();
        AccountDO account = accountService.getAccountByDbId(accountId);


        // 1. 查房间
        RoomDO room = getRoomByRoomId(roomId);
        if (room == null) {
            throw new ClientException("房间不存在");
        }

        // 2. 检查房间状态
        RoomStatusEnum status = RoomStatusEnum.of(room.getStatus());
        if (status == null || !status.canJoin()) {
            throw new ClientException("房间当前不允许加入（状态：" + (status != null ? status.getDesc() : "未知") + "）");
        }

        // 检查用户已加入的房间数（防止单用户 Hash 膨胀）
        long activeRoomCount = roomMemberService.lambdaQuery()
                .eq(RoomMemberDO::getAccountId, accountId)
                .eq(RoomMemberDO::getStatus, RoomMemberStatusEnum.ACTIVE.getCode())
                .count();
        if (activeRoomCount >= MAX_ROOMS_PER_USER) {
            throw new ClientException("最多同时加入 " + MAX_ROOMS_PER_USER + " 个房间");
        }

        // 3. 检查人数TODO从数据库中查询用户人数
        int activeCount = roomChannelManager.getMemberCount(roomId);
        if (activeCount >= room.getMaxMembers()) {
            throw new ClientException("房间已满（" + activeCount + "/" + room.getMaxMembers() + "）");
        }

        // 4. 检查是否已有记录（处理重复加入、重新加入）
        RoomMemberDO existingMember = roomMemberService.getRoomMemberByRoomIdAndAccountId(roomId, accountId);

        if (existingMember != null) {
            if (existingMember.getStatus() == RoomMemberStatusEnum.ACTIVE.getCode()) {
                // 已在房间中，幂等返回
                log.info("[重复加入] room={}, memberId={}, 已在房间中", roomId, accountId);
                return buildRoomInfoResp(room);
            }
            //之前离开过（LEFT）或被踢过（KICKED），恢复为 ACTIVE
            existingMember.setStatus(RoomMemberStatusEnum.ACTIVE.getCode());
            existingMember.setLeaveTime(null);
            existingMember.setJoinTime(LocalDateTime.now());
            roomMemberService.updateWithCacheEvict(existingMember);
            log.info("[重新加入] room={}, accountId={}, 原状态={}",
                    roomId, accountId, existingMember.getStatus());

        } else {
            // 首次加入 → 插入新行
            RoomMemberDO newMember = RoomMemberDO.builder()
                    .roomId(roomId)
                    .accountId(accountId)
                    .role(RoomMemberRoleEnum.MEMBER.getCode())
                    .isMuted(0)
                    .status(RoomMemberStatusEnum.ACTIVE.getCode())
                    .lastAckMsgId(0L)
                    .joinTime(LocalDateTime.now())
                    .build();
            roomMemberService.saveWithCache(newMember);
            log.info("[首次加入] room={}, accountId={}", roomId, accountId);
        }

        // 5. 如果房间是 WAITING → 改为 ACTIVE
        if (room.getStatus() == RoomStatusEnum.WAITING.getCode()) {
            this.lambdaUpdate().eq(RoomDO::getRoomId, roomId)
                            .set(RoomDO::getStatus, RoomStatusEnum.ACTIVE.getCode())
                                    .update();

            room.setStatus(RoomStatusEnum.ACTIVE.getCode());
            evictRoomCache(roomId);
            log.info("[房间激活] room={}, WAITING → ACTIVE", roomId);
        }

        // 6. 同步到内存（RoomChannelManager）
        //    从 Channel 属性获取昵称/头像（WS 连接时已分配）

        roomChannelManager.joinRoom(roomId, accountId, account.getNickname() ,account.getAvatarColor() , false);

        return buildRoomInfoResp(room);
    }

    @Override
    @Transactional
    public void leaveRoom(RoomLeaveReqDTO request) {
        String roomId = request.getRoomId();
        Long accountId = UserContext.getRequiredLoginId();

        // ===== 1. 校验房间存在 =====
        RoomDO room = getRoomByRoomId(roomId);
        if (room == null) {
            throw new ClientException("房间不存在");
        }

        // ===== 2. 查成员记录 =====
        RoomMemberDO member = roomMemberService.getRoomMemberByRoomIdAndAccountId(roomId, accountId);
        if (member == null || member.getStatus() != RoomMemberStatusEnum.ACTIVE.getCode()) {
            log.info("[离开房间-幂等] room={}, memberId={}, 不在房间中或已离开", roomId, accountId);
            return;
        }

        // ===== 3. 房主不能离开，只能关闭房间 =====
        if (member.getRole() == RoomMemberRoleEnum.HOST.getCode()) {
            throw new ClientException("房主不能离开房间，请使用「关闭房间」功能");
        }

        // ===== 4. 更新 DB =====
        member.setStatus(RoomMemberStatusEnum.LEFT.getCode());
        member.setLeaveTime(LocalDateTime.now());
        roomMemberService.updateWithCacheEvict(member);

        // ===== 5. 同步内存 =====
        roomChannelManager.leaveRoom(roomId,accountId);
        unreadService.removeRoomUnread(accountId, roomId);

        log.info("[离开房间] room={}, accountId={}", roomId, accountId);

    }

    /**
     * 房间成员列表
     */
    @Override
    public List<RoomMemberRespDTO> getRoomMembers(String roomId) {
        // ===== 1. 校验房间存在 =====
        RoomDO room = getRoomByRoomId(roomId);
        if (room == null) {
            throw new ClientException("房间不存在");
        }

        // ===== 2. 查 DB 活跃成员 =====
        List<RoomMemberDO> dbMembers = roomMemberService.lambdaQuery().
                eq(RoomMemberDO::getRoomId, roomId)
                .eq(RoomMemberDO::getStatus,RoomMemberStatusEnum.ACTIVE.getCode())
                .orderByAsc(RoomMemberDO::getJoinTime)
                .list();
        if (dbMembers == null || dbMembers.isEmpty()) {
            return List.of();
        }

        List<RoomMemberRespDTO> result = new ArrayList<>();

        for (RoomMemberDO dbMember : dbMembers) {
            Long id = dbMember.getAccountId();

            // 优先从内存取
            RoomMemberInfo memoryInfo = roomChannelManager.getRoomMemberInfo(roomId, id);

            String nickname;
            String avatar;

            if (memoryInfo != null) {
                nickname = memoryInfo.getNickname();
                avatar = memoryInfo.getAvatar();
            } else {
                AccountDO account = accountService.getAccountByDbId(id);
                nickname = account != null ? account.getNickname() : "匿名用户";
                avatar = account != null ? account.getAvatarColor() : "#999999";
            }

            boolean isMuted = memoryInfo != null ? memoryInfo.isMuted() : dbMember.getIsMuted() == 1;
            boolean isHost = dbMember.getRole() == RoomMemberRoleEnum.HOST.getCode();
            boolean isOnline = roomChannelManager.isOnline(id);

            result.add(RoomMemberRespDTO.builder()
                    .accountId(id)
                    .nickname(nickname)
                    .avatar(avatar)
                    .role(dbMember.getRole())
                    .isHost(isHost)
                    .isMuted(isMuted)
                    .isOnline(isOnline)
                    .build());
        }

        result.sort((a, b) -> {
            if (a.getIsHost() && !b.getIsHost()) return -1;
            if (!a.getIsHost() && b.getIsHost()) return 1;
            return 0;
        });

        return result;
    }

    @Override
    public void restoreRoomMemberships(Long accountId) {
        // 1. 查 DB：该用户所有 ACTIVE 的房间成员记录
        List<RoomMemberDO> activeMembers = roomMemberService.lambdaQuery()
                .eq(RoomMemberDO::getAccountId, accountId)
                .eq(RoomMemberDO::getStatus, RoomMemberStatusEnum.ACTIVE.getCode())
                .list();

        if (activeMembers == null || activeMembers.isEmpty()) {
            log.debug("[恢复房间] memberId={}, 无活跃房间", accountId);
            return;
        }

        // 2. 获取用户信息（走缓存）
        AccountDO account = accountService.getAccountByDbId(accountId);
        String nickname = account != null ? account.getNickname() : "匿名用户";
        String avatar = account != null ? account.getAvatarColor() : "#999999";

        int count = 0;
        for (RoomMemberDO rm : activeMembers) {
            //逐个走缓存查询，替代批量 lambdaQuery
            RoomDO room = getRoomByRoomId(rm.getRoomId());
            if (room == null || room.getStatus() == RoomStatusEnum.CLOSED.getCode()) {
                continue;
            }

            boolean isHost = rm.getRole() == RoomMemberRoleEnum.HOST.getCode();
            boolean isMuted = rm.getIsMuted() != null && rm.getIsMuted() == RoomMemberMuteStatusEnum.MUTE.getCode();
            roomChannelManager.joinRoomSilent(rm.getRoomId(), accountId, nickname, avatar, isHost, isMuted);
            count++;
        }

        log.info("[恢复房间] memberId={}, 恢复了 {} 个房间", accountId, count);
    }

    /**
     *踢人
     */
    @Override
    @Transactional
    public void kickMember(RoomKickReqDTO request) {
        HostOperationContext ctx = validateHostOperation(request.getRoomId(), request.getTargetAccountId());

        // 更新 DB
        ctx.getTargetMember().setStatus(RoomMemberStatusEnum.KICKED.getCode());
        ctx.getTargetMember().setLeaveTime(LocalDateTime.now());
        roomMemberService.updateWithCacheEvict(ctx.getTargetMember());

        // 同步内存
        roomChannelManager.kickMember(request.getRoomId(), ctx.getTargetAccountId());
        unreadService.removeRoomUnread(ctx.getTargetAccountId(), request.getRoomId());

        log.info("[踢人成功] room={}, operator={}, target={}",
                request.getRoomId(), ctx.getOperatorMember(), ctx.getTargetMember());
    }

    @Override
    @Transactional
    public void muteMember(RoomMuteReqDTO request) {
        String roomId = request.getRoomId();

        // 公共校验
        HostOperationContext ctx = validateHostOperation(
                roomId,request.getTargetAccountId());

        // 不能禁言房主（理论上公共校验已排除自己，这里防止多房主场景）
        if (ctx.getTargetMember().getRole() == RoomMemberRoleEnum.HOST.getCode()) {
            throw new ClientException("不能禁言房主");
        }

        // 幂等：已经禁言的不重复处理
        if (ctx.getTargetMember().getIsMuted() == RoomMemberMuteStatusEnum.MUTE.getCode()) {
            log.info("[重复禁言] room={}, target={}, 已处于禁言状态", roomId, ctx.getTargetAccountId());
            return;
        }

        // 更新 DB
        ctx.getTargetMember().setIsMuted(RoomMemberMuteStatusEnum.MUTE.getCode());
        roomMemberService.updateWithCacheEvict(ctx.getTargetMember());

        // 同步内存 + WS 通知
        roomChannelManager.muteMember(roomId, ctx.getTargetAccountId());

        log.info("[禁言成功] room={}, operator={}, target={}",
                roomId, ctx.getOperatorAccountId(), ctx.getTargetAccountId());
    }

    @Override
    @Transactional
    public void unmuteMember(RoomMuteReqDTO request) {
        String roomId = request.getRoomId();

        // 公共校验
        HostOperationContext ctx = validateHostOperation(roomId, request.getTargetAccountId());

        // 幂等：没有被禁言的不重复处理
        if (ctx.getTargetMember().getIsMuted() == RoomMemberMuteStatusEnum.UNMUTE.getCode()) {
            log.info("[重复解禁] room={}, target={}, 未处于禁言状态", roomId, ctx.getTargetAccountId());
            return;
        }

        // 更新 DB
        ctx.getTargetMember().setIsMuted(RoomMemberMuteStatusEnum.UNMUTE.getCode());
        roomMemberService.updateWithCacheEvict(ctx.getTargetMember());

        // 同步内存 + WS 通知
        roomChannelManager.unmuteMember(roomId, ctx.getTargetAccountId());

        log.info("[解禁成功] room={}, operator={}, target={}",
                roomId, ctx.getOperatorAccountId(), ctx.getTargetMember());
    }

    /**
     * 关闭房间
     */
    @Override
    public void closeRoom(RoomCloseReqDTO request) {
        String roomId = request.getRoomId();
        Long operatorId = UserContext.getRequiredLoginId();

        // ===== 权限校验 =====
        RoomMemberDO operatorMember =
                roomMemberService.getRoomMemberByRoomIdAndAccountId(roomId, operatorId);
        if (operatorMember == null || operatorMember.getStatus() != RoomMemberStatusEnum.ACTIVE.getCode()) {
            throw new ClientException("你不在该房间中");
        }
        if (operatorMember.getRole() != RoomMemberRoleEnum.HOST.getCode()) {
            throw new ClientException("只有房主可以执行此操作");
        }

        // ===== 执行关闭 =====
        doCloseRoom(roomId);

        log.info("[手动关闭房间] room={}, operator={}", roomId, operatorId);
    }

    /**
     * 即将到期处理（延时队列 EXPIRING_SOON 事件触发）
     * status → EXPIRING(2)，WS 通知房间即将到期
     * 幂等：只有 ACTIVE 状态才处理，其他状态跳过
     */
    @Override
    public void doRoomExpiringSoon(String roomId) {
        RoomDO room = getRoomByRoomId(roomId);
        if (room == null) {
            log.warn("[即将到期-跳过] room={}, 房间不存在", roomId);
            return;
        }

        RoomStatusEnum status = RoomStatusEnum.of(room.getStatus());
        if (status != RoomStatusEnum.ACTIVE) {
            log.info("[即将到期-跳过] room={}, 当前状态={}，不是 ACTIVE", roomId, status);
            return;
        }

        // 更新状态 → EXPIRING
        this.lambdaUpdate()
                .eq(RoomDO::getRoomId, roomId)
                .eq(RoomDO::getStatus, RoomStatusEnum.ACTIVE.getCode())
                .set(RoomDO::getStatus, RoomStatusEnum.EXPIRING.getCode())
                .update();
        evictRoomCache(roomId);

        // WS 通知房间所有在线成员
        roomChannelManager.broadcastToRoom(roomId,
                WsRespDTO.of(roomId, WsRespDTOTypeEnum.ROOM_EXPIRING, "房间即将到期，5 分钟后将进入宽限期"));

        log.info("[即将到期] room={}, ACTIVE → EXPIRING", roomId);
    }

    /**
     * 到期处理（延时队列 EXPIRED 事件触发）
     * 动作：status → GRACE(3)，设置 grace_end_time，WS 通知进入宽限期
     * 幂等：只有 ACTIVE 或 EXPIRING 状态才处理
     */
    @Override
    public void doRoomExpired(String roomId) {
        RoomDO room = getRoomByRoomId(roomId);
        if (room == null) {
            log.warn("[到期-跳过] room={}, 房间不存在", roomId);
            return;
        }

        RoomStatusEnum status = RoomStatusEnum.of(room.getStatus());
        if (status != RoomStatusEnum.ACTIVE && status != RoomStatusEnum.EXPIRING) {
            log.info("[到期-跳过] room={}, 当前状态={}", roomId, status);
            return;
        }

        LocalDateTime graceEndTime = room.getExpireTime().plusMinutes(5);

        this.lambdaUpdate()
                .eq(RoomDO::getRoomId, roomId)
                .in(RoomDO::getStatus, RoomStatusEnum.ACTIVE.getCode(), RoomStatusEnum.EXPIRING.getCode())
                .set(RoomDO::getStatus, RoomStatusEnum.GRACE.getCode())
                .set(RoomDO::getGraceEndTime, graceEndTime)
                .update();
        evictRoomCache(roomId);

        // WS 通知
        roomChannelManager.broadcastToRoom(roomId,
                WsRespDTO.of(roomId, WsRespDTOTypeEnum.ROOM_GRACE, "房间已到期，进入 5 分钟宽限期"));

        log.info("[到期] room={}, → GRACE, graceEndTime={}", roomId, graceEndTime);
    }

    /**
     * 执行房间关闭（内部方法，无权限校验）
     * 调用方：
     *   1. closeRoom() — 房主手动关闭（校验后调用）
     *   2. 延时队列消费者 — GRACE_END 事件
     *   3. 兜底定时任务 — 扫描到期房间
     * 幂等：已关闭则跳过
     */
    @Override
    public void doCloseRoom(String roomId) {
        RoomDO room = getRoomByRoomId(roomId);
        if (room == null) {
            log.warn("[关闭房间-跳过] room={}, 房间不存在", roomId);
            return;
        }

        if (room.getStatus() == RoomStatusEnum.CLOSED.getCode()) {
            log.info("[关闭房间-跳过] room={}, 已经是 CLOSED 状态", roomId);
            return;
        }
        // 先拿成员ID
        Set<Long> memberIds = roomChannelManager.getRoomMemberIds(roomId);

        LocalDateTime now = LocalDateTime.now();

        // 更新 t_room
        boolean updated =  this.lambdaUpdate()
                .eq(RoomDO::getRoomId, roomId)
                .ne(RoomDO::getStatus, RoomStatusEnum.CLOSED.getCode())
                .set(RoomDO::getStatus, RoomStatusEnum.CLOSED.getCode())
                .set(RoomDO::getClosedTime, now)
                .update();

        if (!updated) {
            log.info("[关闭房间-跳过] room={}, CAS更新失败，已被其他操作关闭", roomId);
            return;
        }

        evictRoomCache(roomId);

        // 删除消息滑动窗口
        messageWindowService.deleteWindow(roomId);

        // 批量更新成员 → LEFT
        roomMemberService.lambdaUpdate()
                .eq(RoomMemberDO::getRoomId, roomId)
                .eq(RoomMemberDO::getStatus, RoomMemberStatusEnum.ACTIVE.getCode())
                .set(RoomMemberDO::getStatus, RoomMemberStatusEnum.LEFT.getCode())
                .set(RoomMemberDO::getLeaveTime, now)
                .update();
        roomMemberService.evictAllMemberCacheInRoom(roomId);


        unreadService.clearRoomForAllMembers(roomId, memberIds);

        // 清理内存 + WS 通知
        roomChannelManager.closeRoom(roomId);

        log.info("[关闭房间] room={}, → CLOSED", roomId);
    }

    @Override
    public List<RoomInfoRespDTO> getMyRooms() {

        Long acctId = UserContext.getRequiredLoginId();

        // 查所有 ACTIVE 的房间成员记录
        List<RoomMemberDO> activeMembers = roomMemberService.lambdaQuery()
                .eq(RoomMemberDO::getAccountId, acctId)
                .eq(RoomMemberDO::getStatus, RoomMemberStatusEnum.ACTIVE.getCode())
                .list();

        if (activeMembers == null || activeMembers.isEmpty()) {
            return List.of();
        }

        Map<String, Integer> unreadMap = unreadService.getAllUnreadCounts(acctId);

        List<RoomInfoRespDTO> result = new ArrayList<>();
        for (RoomMemberDO rm : activeMembers) {
            RoomDO room = getRoomByRoomId(rm.getRoomId());
            if (room == null || room.getStatus() == RoomStatusEnum.CLOSED.getCode()) {
                continue;
            }
            RoomInfoRespDTO dto = buildRoomInfoResp(room);
            dto.setUnreadCount(unreadMap.getOrDefault(room.getRoomId(), 0));
            result.add(dto);
        }

        return result;
    }

    /**
     *用缓存查询房间
     */
    @Override
    public RoomDO getRoomByRoomId(String roomId) {
        return  multistageCacheProxy.safeGet(
                CacheUtil.buildKey("flashchat","room",roomId),
                RoomDO.class,
                ()->this.lambdaQuery()
                        .eq(RoomDO::getRoomId,roomId)
                        .one(),
                60000L,
                flashChatRoomRegisterCachePenetrationBloomFilter
        );
    }

    @Override
    public Set<String> listClosedRoomIds() {
        List<RoomDO> rooms = this.lambdaQuery()
                .eq(RoomDO::getStatus, RoomStatusEnum.CLOSED.getCode())
                .select(RoomDO::getRoomId)
                .list();
        return rooms.stream()
                .map(RoomDO::getRoomId)
                .collect(Collectors.toSet());
    }


    /**
     * 构建房间信息响应
     */
    private RoomInfoRespDTO buildRoomInfoResp(RoomDO room) {
        RoomStatusEnum status = RoomStatusEnum.of(room.getStatus());

        // 查活跃成员数
        int memberCount = roomChannelManager.getMemberCount(room.getRoomId());

        // 在线人数（内存中有 WS 连接的）
        int onlineCount = roomChannelManager.getOnlineCountInRoom(room.getRoomId());

        return RoomInfoRespDTO.builder()
                .roomId(room.getRoomId())
                .title(room.getTitle())
                .status(room.getStatus())
                .statusDesc(status != null ? status.getDesc() : "未知")
                .maxMembers(room.getMaxMembers())
                .isPublic(room.getIsPublic())
                .memberCount(memberCount)
                .onlineCount(onlineCount)
                .expireTime(room.getExpireTime())
                .createTime(room.getCreateTime())
                .build();

}




    /**
     * 房主操作公共校验（踢人/禁言/解禁共用）
     */
    private HostOperationContext validateHostOperation(String roomId,Long targetAccountId) {

        // 1. 验证操作者
        Long operatorId = UserContext.getRequiredLoginId();

        // 2. 不能操作自己
        if (operatorId.equals(targetAccountId)) {
            throw new ClientException("不能对自己执行此操作");
        }

        // 3. 验证房间
        RoomDO room = getRoomByRoomId(roomId);
        if (room == null) {
            throw new ClientException("房间不存在");
        }
        RoomStatusEnum roomStatus = RoomStatusEnum.of(room.getStatus());
        if (roomStatus == null || roomStatus == RoomStatusEnum.CLOSED) {
            throw new ClientException("房间已关闭，无法操作");
        }

        // 4. 验证操作者是房主
        RoomMemberDO operatorMember = roomMemberService.getRoomMemberByRoomIdAndAccountId(roomId, operatorId);
        if (operatorMember == null || operatorMember.getStatus() != RoomMemberStatusEnum.ACTIVE.getCode()) {
            throw new ClientException("你不在该房间中");
        }
        if (operatorMember.getRole() != RoomMemberRoleEnum.HOST.getCode()) {
            throw new ClientException("只有房主可以执行此操作");
        }

        // 5. 验证目标在房间中
        RoomMemberDO targetMember = roomMemberService.getRoomMemberByRoomIdAndAccountId(roomId, targetAccountId);
        if (targetMember == null || targetMember.getStatus() != RoomMemberStatusEnum.ACTIVE.getCode()) {
            throw new ClientException("该用户不在房间中");
        }

        return HostOperationContext.builder()
                .room(room)
                .operatorAccountId(operatorId)
                .targetAccountId(targetAccountId)
                .operatorMember(operatorMember)
                .targetMember(targetMember)
                .build();
    }
    /**
     *  新增：Room 缓存失效
     */
    private void evictRoomCache(String roomId) {
        try {
            multistageCacheProxy.delete(CacheUtil.buildKey("flashchat", "room", roomId));
        } catch (Exception e) {
            log.error("[Room缓存失效异常] roomId={}", roomId, e);
        }
    }



    /**
     *生成唯一房间ID
     */
    private String getRoomID(){
        int customGenerateCount = 0;
        String roomId;
        String SEED_PREFIX = "flashchat:room:";
        while (true) {
            if (customGenerateCount > 10)
            {
                throw new ServiceException("房间ID频繁生成,请稍后再试");
            }
            roomId = HashUtil.hashToBase62(SEED_PREFIX + UUID.randomUUID());
            if (!flashChatRoomRegisterCachePenetrationBloomFilter.contains(
                    CacheUtil.buildKey("flashchat", "room", roomId)))
            {
                break;
            }
            customGenerateCount++;
        }

        return roomId;
    }


}
