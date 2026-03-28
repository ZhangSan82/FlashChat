package com.flashchat.chatservice.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
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
import com.flashchat.chatservice.dto.resp.*;
import com.flashchat.chatservice.service.*;
import com.flashchat.chatservice.toolkit.HashUtil;
import com.flashchat.chatservice.websocket.manager.RoomChannelManager;
import com.flashchat.chatservice.websocket.manager.RoomMemberInfo;
import com.flashchat.convention.exception.ClientException;
import com.flashchat.convention.exception.ServiceException;
import com.flashchat.user.core.UserContext;
import com.flashchat.userservice.dao.entity.AccountDO;
import com.flashchat.userservice.dao.enums.CreditTypeEnum;
import com.flashchat.userservice.service.AccountService;
import com.flashchat.userservice.service.CreditService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBloomFilter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
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
    private final CreditService creditService;
    private final TransactionTemplate transactionTemplate;
    private final RoomMapper roomMapper;
    private static final long CACHE_TIMEOUT = 60000L;
    /**单用户最多同时加入的房间数 */
    private static final int MAX_ROOMS_PER_USER = 50;
    /** 房主注销时的宽限期时长（分钟） */
    private static final int FORCE_GRACE_MINUTES = 5;


    @Value("${flashchat.share.base-url:http://localhost:3002}")
    private String shareBaseUrl;

    /**创建房间*/
    @Override
    public RoomInfoRespDTO createRoom(RoomCreateReqDTO request) {
        Long creatorId = UserContext.getRequiredLoginId();
        AccountDO creator = accountService.getAccountByDbId(creatorId);

        RoomDurationEnum durationEnum = RoomDurationEnum.of(request.getDuration());
        if (durationEnum == null) {
            durationEnum = RoomDurationEnum.MIN_30;  // 默认 30 分钟
        }
        int cost = durationEnum.getCost();

        if (cost > 0 && !creator.registered()) {
            throw new ClientException("创建房间需要先升级为注册用户");
        }

        if (cost > 0 && creator.getCredits() != null && creator.getCredits() < cost) {
            throw new ClientException("积分不足，需要 " + cost + " 积分，当前 " + creator.getCredits());
        }

        LocalDateTime expireTime = LocalDateTime.now().plusMinutes(durationEnum.getMinutes());
        String roomId = getRoomID();

        //t_room
        RoomDO room = RoomDO.builder()
                .roomId(roomId)
                .creatorId(creatorId)//t_account.id
                .title(request.getTitle())
                .maxMembers(request.getMaxMembers() != null ? request.getMaxMembers() : 50)
                .currentMembers(1)
                .isPublic(request.getIsPublic() != null ? request.getIsPublic() : 0)
                .status(RoomStatusEnum.WAITING.getCode())
                .expireTime(expireTime)
                .expireVersion(1)
                .build();

        RoomMemberDO hostMember = RoomMemberDO.builder()
                .roomId(roomId)
                .accountId(creatorId)
                .role(RoomMemberRoleEnum.HOST.getCode())
                .isMuted(RoomMemberMuteStatusEnum.UNMUTE.getCode())
                .status(RoomMemberStatusEnum.ACTIVE.getCode())
                .lastAckMsgId(0L)
                .joinTime(LocalDateTime.now())
                .build();
        final RoomDurationEnum finalDuration = durationEnum;

        transactionTemplate.executeWithoutResult(status -> {

            if (cost > 0) {
                creditService.deductCredits(creatorId, cost,
                        CreditTypeEnum.ROOM_CREATE_COST, roomId,
                        "创建房间-" + finalDuration.getDesc());
            }
            this.save(room);
            roomMemberService.save(hostMember);
        });

        if (cost > 0) {
            accountService.evictCacheByDbId(creatorId);
        }
        try {
            flashChatRoomRegisterCachePenetrationBloomFilter.add(
                    CacheUtil.buildKey("flashchat", "room", roomId));
            multistageCacheProxy.put(CacheUtil.buildKey("flashchat","room",roomId),
                    room,
                    CACHE_TIMEOUT);
            multistageCacheProxy.put(
                    CacheUtil.buildKey("flashchat", "roomMember", roomId, String.valueOf(creatorId)),
                    hostMember,
                    CACHE_TIMEOUT
            );
        } catch (Exception e) {
            log.error("[创建房间-缓存写入失败] roomId={}, 不影响创建", roomId, e);
        }
        log.info("[创建房间] roomId={}, title={}, creator={},cost={}", roomId, room.getTitle(), room.getCreatorId(), cost);

        try {
            roomDelayProducer.submitRoomExpireEvents(roomId, expireTime, room.getExpireVersion());
        } catch (Exception e) {
            // 投递失败不阻塞创建流程，由兜底定时任务保障
            log.error("[延时任务投递失败] room={}, 不影响创建，将由兜底任务兜底", roomId, e);
        }

        roomChannelManager.joinRoom(roomId, creator.getId(),
                creator.getNickname(), creator.getAvatarColor(), true);
        return buildRoomInfoResp(room);
    }

    /** 加入房间 */
    @Override
    public RoomInfoRespDTO joinRoom(RoomJoinReqDTO request) {
        String roomId = request.getRoomId();
        Long accountId = UserContext.getRequiredLoginId();
        AccountDO account = accountService.getAccountByDbId(accountId);

        // 1. 校验
        RoomDO room = getRoomByRoomId(roomId);
        if (room == null) {
            throw new ClientException("房间不存在");
        }
        RoomStatusEnum status = RoomStatusEnum.of(room.getStatus());
        if (status == null || !status.canJoin()) {
            throw new ClientException("房间当前不允许加入(状态：" + (status != null ? status.getDesc() : "未知") + ")");
        }
        // 检查用户已加入的房间数
        long activeRoomCount = roomMemberService.lambdaQuery()
                .eq(RoomMemberDO::getAccountId, accountId)
                .eq(RoomMemberDO::getStatus, RoomMemberStatusEnum.ACTIVE.getCode())
                .count();
        if (activeRoomCount >= MAX_ROOMS_PER_USER) {
            throw new ClientException("最多同时加入 " + MAX_ROOMS_PER_USER + " 个房间");
        }

        final boolean[] joined = {false};
        final boolean[] roomActivated = {false};
        transactionTemplate.executeWithoutResult(txStatus -> {
            // 直接查 DB，不走缓存（事务内需要准确状态，防止缓存陈旧导致误判）
            RoomMemberDO existingMember = roomMemberService.lambdaQuery()
                    .eq(RoomMemberDO::getRoomId, roomId)
                    .eq(RoomMemberDO::getAccountId, accountId)
                    .one();
            if (existingMember != null) {
                if (existingMember.getStatus() == RoomMemberStatusEnum.ACTIVE.getCode()) {
                    return;
                }
                // 重新加入
                // CAS 防并发重新加入：WHERE status != ACTIVE
                boolean reactivated = roomMemberService.lambdaUpdate()
                        .eq(RoomMemberDO::getId, existingMember.getId())
                        .ne(RoomMemberDO::getStatus, RoomMemberStatusEnum.ACTIVE.getCode())
                        .set(RoomMemberDO::getStatus, RoomMemberStatusEnum.ACTIVE.getCode())
                        .set(RoomMemberDO::getLeaveTime, null)
                        .set(RoomMemberDO::getJoinTime, LocalDateTime.now())
                        .update();
                if (!reactivated) {
                    return; // 另一个线程已完成重新加入
                }
            } else {
                // 首次加入：INSERT（唯一索引 uk_room_account 兜底并发）
                RoomMemberDO newMember = RoomMemberDO.builder()
                        .roomId(roomId)
                        .accountId(accountId)
                        .role(RoomMemberRoleEnum.MEMBER.getCode())
                        .isMuted(0)
                        .status(RoomMemberStatusEnum.ACTIVE.getCode())
                        .lastAckMsgId(0L)
                        .joinTime(LocalDateTime.now())
                        .build();
                try {
                    roomMemberService.save(newMember);
                } catch (DuplicateKeyException e) {
                    // 并发首次加入，另一个线程已插入，该线程已做 CAS increment
                    log.info("[首次加入] room={}, accountId={}", roomId, accountId);
                    return;
                }
            }
            // 成员操作成功后，CAS 递增（防超卖最终防线）
            // affected=0 → 房间已满或状态变更 → 抛异常 → 事务回滚（上面的 INSERT/UPDATE 也回滚）
            int affected = roomMapper.incrementMemberCount(roomId);
            if (affected == 0) {
                throw new ClientException("房间已满(" + room.getMaxMembers() + " 人)");
            }
            joined[0] = true;
            if (room.getStatus() == RoomStatusEnum.WAITING.getCode()) {
                boolean activated = this.lambdaUpdate()
                        .eq(RoomDO::getRoomId, roomId)
                        .eq(RoomDO::getStatus, RoomStatusEnum.WAITING.getCode())
                        .set(RoomDO::getStatus, RoomStatusEnum.ACTIVE.getCode())
                        .update();
                if (activated) {
                    roomActivated[0] = true;
                }
            }
        });

        // ===== 3. 幂等分支 =====
        if (!joined[0]) {
            log.info("[加入房间-幂等] room={}, accountId={}, 已在房间中", roomId, accountId);
            return buildRoomInfoResp(getRoomByRoomId(roomId));
        }

        // ===== 4. 事务提交后：缓存 + 内存 =====
        roomMemberService.evictCache(roomId, accountId);
        evictRoomCache(roomId);

        roomChannelManager.joinRoom(roomId, accountId,
                account.getNickname(), account.getAvatarColor(), false);
        log.info("[加入房间] room={}, accountId={}", roomId, accountId);
        return buildRoomInfoResp(getRoomByRoomId(roomId));
    }

    @Override
    public void leaveRoom(RoomLeaveReqDTO request) {
        String roomId = request.getRoomId();
        Long accountId = UserContext.getRequiredLoginId();
        // ===== 校验 =====
        RoomDO room = getRoomByRoomId(roomId);
        if (room == null) {
            throw new ClientException("房间不存在");
        }
        RoomMemberDO member = roomMemberService.getRoomMemberByRoomIdAndAccountId(roomId, accountId);
        if (member == null || member.getStatus() != RoomMemberStatusEnum.ACTIVE.getCode()) {
            log.info("[离开房间-幂等] room={}, memberId={}, 不在房间中或已离开", roomId, accountId);
            return;
        }
        if (member.getRole() == RoomMemberRoleEnum.HOST.getCode()) {
            throw new ClientException("房主不能离开房间，请使用「关闭房间」功能");
        }
        // ===== DB 事务 =====
        final boolean[] left = {false};
        transactionTemplate.executeWithoutResult(txStatus -> {
            boolean updated = roomMemberService.lambdaUpdate()
                    .eq(RoomMemberDO::getId, member.getId())
                    .eq(RoomMemberDO::getStatus, RoomMemberStatusEnum.ACTIVE.getCode())
                    .set(RoomMemberDO::getStatus, RoomMemberStatusEnum.LEFT.getCode())
                    .set(RoomMemberDO::getLeaveTime, LocalDateTime.now())
                    .update();
            if (updated) {
                roomMapper.decrementMemberCount(roomId);
                left[0] = true;
            }
        });
        if (!left[0]) {
            log.info("[离开房间-并发跳过] room={}, accountId={}", roomId, accountId);
            return;
        }
        // ===== 事务提交后：缓存 + 内存 =====
        roomMemberService.evictCache(roomId, accountId);
        evictRoomCache(roomId);
        roomChannelManager.leaveRoom(roomId, accountId);
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
    public void kickMember(RoomKickReqDTO request) {
        HostOperationContext ctx = validateHostOperation(request.getRoomId(), request.getTargetAccountId());
        String roomId = request.getRoomId();
        Long targetAccountId = ctx.getTargetAccountId();
        // ===== DB 事务 =====
        final boolean[] kicked = {false};
        transactionTemplate.executeWithoutResult(txStatus -> {
            boolean updated = roomMemberService.lambdaUpdate()
                    .eq(RoomMemberDO::getId, ctx.getTargetMember().getId())
                    .eq(RoomMemberDO::getStatus, RoomMemberStatusEnum.ACTIVE.getCode())
                    .set(RoomMemberDO::getStatus, RoomMemberStatusEnum.KICKED.getCode())
                    .set(RoomMemberDO::getLeaveTime, LocalDateTime.now())
                    .update();
            if (updated) {
                roomMapper.decrementMemberCount(roomId);
                kicked[0] = true;
            }
        });
        if (!kicked[0]) {
            log.info("[踢人-并发跳过] room={}, target={}", roomId, targetAccountId);
            return;
        }
        // ===== 事务提交后：缓存 + 内存 + WS =====
        roomMemberService.evictCache(roomId, targetAccountId);
        evictRoomCache(roomId);
        roomChannelManager.kickMember(roomId, targetAccountId);
        unreadService.removeRoomUnread(targetAccountId, roomId);
        log.info("[踢人成功] room={}, operator={}, target={}",
                roomId, ctx.getOperatorAccountId(), targetAccountId);
    }

    @Override
    @Transactional
    public void muteMember(RoomMuteReqDTO request) {
        String roomId = request.getRoomId();
        // 公共校验
        HostOperationContext ctx = validateHostOperation(
                roomId,request.getTargetAccountId());
        if (ctx.getTargetMember().getRole() == RoomMemberRoleEnum.HOST.getCode()) {
            throw new ClientException("不能禁言房主");
        }
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
        // ===== DB 事务：t_room + t_room_member 原子更新 =====
        final boolean[] closed = {false};
        transactionTemplate.executeWithoutResult(txStatus -> {
            // 1. 更新 t_room（CAS：只有非 CLOSED 才更新）
            boolean updated = this.lambdaUpdate()
                    .eq(RoomDO::getRoomId, roomId)
                    .ne(RoomDO::getStatus, RoomStatusEnum.CLOSED.getCode())
                    .set(RoomDO::getStatus, RoomStatusEnum.CLOSED.getCode())
                    .set(RoomDO::getClosedTime, now)
                    .set(RoomDO::getCurrentMembers, 0)
                    .update();
            if (!updated) {
                return; // 已被其他操作关闭
            }
            // 2. 批量更新成员 → LEFT
            roomMemberService.lambdaUpdate()
                    .eq(RoomMemberDO::getRoomId, roomId)
                    .eq(RoomMemberDO::getStatus, RoomMemberStatusEnum.ACTIVE.getCode())
                    .set(RoomMemberDO::getStatus, RoomMemberStatusEnum.LEFT.getCode())
                    .set(RoomMemberDO::getLeaveTime, now)
                    .update();
            closed[0] = true;
        });
        if (!closed[0]) {
            log.info("[关闭房间-跳过] room={}, CAS更新失败，已被其他操作关闭", roomId);
            return;
        }
        evictRoomCache(roomId);
        // 删除消息滑动窗口
        messageWindowService.deleteWindow(roomId);
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
     * 查询公开房间列表
     * // TODO 缓存：如果公开房间数量增长，考虑 Redis 缓存 + 30s TTL
     */
    @Override
    public List<RoomInfoRespDTO> listPublicRooms(PublicRoomListReqDTO request) {
        var wrapper = new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<RoomDO>()
                .eq(RoomDO::getIsPublic, 1)
                .in(RoomDO::getStatus,
                        RoomStatusEnum.WAITING.getCode(),
                        RoomStatusEnum.ACTIVE.getCode(),
                        RoomStatusEnum.EXPIRING.getCode());

        switch (request.getSort() != null ? request.getSort() : "hot") {
            case "newest" -> wrapper.orderByDesc(RoomDO::getCreateTime);
            case "expiring" -> wrapper.orderByAsc(RoomDO::getExpireTime);
            default -> wrapper.orderByDesc(RoomDO::getCurrentMembers)
                    .orderByDesc(RoomDO::getCreateTime);
        }

        int page = request.getPage() != null ? request.getPage() : 1;
        int size = request.getSize() != null ? request.getSize() : 20;
        Page<RoomDO> pageParam = new Page<>(page, size, false);
        Page<RoomDO> result = this.page(pageParam, wrapper);
        List<RoomDO> rooms = result.getRecords();
        if (rooms == null || rooms.isEmpty()) {
            return List.of();
        }
        return rooms.stream()
                .map(this::buildRoomInfoResp)
                .toList();
    }

    @Override
    public String getShareUrl(String roomId) {
        RoomDO room = getRoomByRoomId(roomId);
        if (room == null) {
            throw new ClientException("房间不存在");
        }
        // 拼接分享链接：前端路由格式
        // 前端收到此 URL 后解析 roomId，调用 joinRoom 接口
        return shareBaseUrl + "/room/" + roomId;
    }

    @Override
    public List<RoomPricingRespDTO> getRoomPricing() {
        List<RoomPricingRespDTO> pricing = new ArrayList<>();
        for (RoomDurationEnum duration : RoomDurationEnum.values()) {
            pricing.add(RoomPricingRespDTO.builder()
                    .name(duration.name())
                    .minutes(duration.getMinutes())
                    .desc(duration.getDesc())
                    .cost(duration.getCost())
                    .build());
        }
        return pricing;
    }

    /**
     * 房间延期
     */
    @Override
    public RoomInfoRespDTO extendRoom(RoomExtendReqDTO request) {
        String roomId = request.getRoomId();
        Long operatorId = UserContext.getRequiredLoginId();
        // ===== 1. 查询房间 =====
        RoomDO room = getRoomByRoomId(roomId);
        if (room == null) {
            throw new ClientException("房间不存在");
        }
        // ===== 2. 状态校验：ACTIVE / EXPIRING / WAITING 允许延期 =====
        RoomStatusEnum status = RoomStatusEnum.of(room.getStatus());
        if (status != RoomStatusEnum.ACTIVE
                && status != RoomStatusEnum.EXPIRING
                && status != RoomStatusEnum.WAITING) {
            String desc = status != null ? status.getDesc() : "未知";
            throw new ClientException("当前状态（" + desc + "）不允许延期，"
                    + "仅等待中、活跃和即将到期状态可操作");
        }
        // ===== 3. 权限校验：仅房主 =====
        RoomMemberDO operatorMember = roomMemberService
                .getRoomMemberByRoomIdAndAccountId(roomId, operatorId);
        if (operatorMember == null
                || operatorMember.getStatus() != RoomMemberStatusEnum.ACTIVE.getCode()) {
            throw new ClientException("你不在该房间中");
        }
        if (operatorMember.getRole() != RoomMemberRoleEnum.HOST.getCode()) {
            throw new ClientException("只有房主可以延期房间");
        }
        // ===== 4. 解析延期档位 =====
        RoomDurationEnum durationEnum = RoomDurationEnum.of(request.getDuration());
        if (durationEnum == null) {
            durationEnum = RoomDurationEnum.MIN_30;
        }
        int cost = durationEnum.getCost();
        // ===== 5. 积分校验（免费档位跳过） =====
        if (cost > 0) {
            AccountDO creator = accountService.getAccountByDbId(operatorId);
            if (!creator.registered()) {
                throw new ClientException("延期房间需要先升级为注册用户");
            }
            if (creator.getCredits() == null || creator.getCredits() < cost) {
                throw new ClientException("积分不足，需要 " + cost + " 积分，当前 "
                        + (creator.getCredits() != null ? creator.getCredits() : 0));
            }
        }

        // ===== 6. 计算新到期时间 =====
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime base = room.getExpireTime().isBefore(now) ? now : room.getExpireTime();
        LocalDateTime newExpireTime = base.plusMinutes(durationEnum.getMinutes());
        int oldVersion = room.getExpireVersion() != null ? room.getExpireVersion() : 1;
        int newVersion = oldVersion + 1;
        // ===== 7. 事务：积分扣减 + CAS 更新房间 =====
        final RoomDurationEnum finalDuration = durationEnum;
        // EXPIRING 回退到 ACTIVE，WAITING 和 ACTIVE 保持原状态
        final int newStatus = (status == RoomStatusEnum.EXPIRING)
                ? RoomStatusEnum.ACTIVE.getCode()
                : room.getStatus();
        transactionTemplate.executeWithoutResult(txStatus -> {
            // 7a. 扣积分（免费档位跳过）
            if (finalDuration.getCost() > 0) {
                creditService.deductCredits(operatorId, finalDuration.getCost(),
                        CreditTypeEnum.ROOM_EXTEND_COST,
                        roomId + ":" + newVersion,
                        "房间延期-" + finalDuration.getDesc());
            }
            // 7b. CAS 更新房间
            boolean updated = lambdaUpdate()
                    .eq(RoomDO::getRoomId, roomId)
                    .eq(RoomDO::getExpireVersion, oldVersion)
                    .in(RoomDO::getStatus,
                            RoomStatusEnum.WAITING.getCode(),
                            RoomStatusEnum.ACTIVE.getCode(),
                            RoomStatusEnum.EXPIRING.getCode())
                    .set(RoomDO::getExpireTime, newExpireTime)
                    .set(RoomDO::getExpireVersion, newVersion)
                    .set(RoomDO::getStatus, newStatus)
                    .set(RoomDO::getGraceEndTime, null)
                    .update();
            if (!updated) {
                throw new ClientException("延期失败，请重试（可能有人同时操作或房间状态已变更）");
            }
        });
        // ===== 8. 事务提交后：缓存失效 =====
        evictRoomCache(roomId);
        if (cost > 0) {
            accountService.evictCacheByDbId(operatorId);
        }
        // ===== 9. 重新投递延时队列事件 =====
        try {
            roomDelayProducer.submitRoomExpireEvents(roomId, newExpireTime, newVersion);
        } catch (Exception e) {
            // 投递失败不影响延期结果，由兜底定时任务保障
            log.error("[延期-延时任务投递失败] room={}, 将由兜底任务保障", roomId, e);
        }
        // ===== 10. WS 广播通知 =====
        RoomExtendRespDTO extendData = RoomExtendRespDTO.builder()
                .newExpireTime(newExpireTime)
                .durationDesc(durationEnum.getDesc())
                .status(RoomStatusEnum.ACTIVE.getCode())
                .statusDesc(RoomStatusEnum.ACTIVE.getDesc())
                .build();
        roomChannelManager.broadcastToRoom(roomId,
                WsRespDTO.of(roomId, WsRespDTOTypeEnum.ROOM_EXTENDED, extendData));
        log.info("[房间延期成功] room={}, operator={}, duration={}, newExpire={}, version={}→{}",
                roomId, operatorId, durationEnum.getDesc(), newExpireTime, oldVersion, newVersion);
        // ===== 11. 返回最新房间信息 =====
        return buildRoomInfoResp(getRoomByRoomId(roomId));
    }

    @Override
    public void resizeRoom(RoomResizeReqDTO request) {
        String roomId = request.getRoomId();
        int newMaxMembers = request.getNewMaxMembers();
        Long operatorId = UserContext.getRequiredLoginId();
        // ===== 1. 查询房间 =====
        RoomDO room = getRoomByRoomId(roomId);
        if (room == null) {
            throw new ClientException("房间不存在");
        }
        // ===== 2. 状态校验：非 CLOSED =====
        RoomStatusEnum status = RoomStatusEnum.of(room.getStatus());
        if (status == null || status == RoomStatusEnum.CLOSED) {
            throw new ClientException("房间已关闭，无法操作");
        }
        // ===== 3. 权限校验：仅房主 =====
        RoomMemberDO operatorMember = roomMemberService
                .getRoomMemberByRoomIdAndAccountId(roomId, operatorId);
        if (operatorMember == null
                || operatorMember.getStatus() != RoomMemberStatusEnum.ACTIVE.getCode()) {
            throw new ClientException("你不在该房间中");
        }
        if (operatorMember.getRole() != RoomMemberRoleEnum.HOST.getCode()) {
            throw new ClientException("只有房主可以修改房间人数上限");
        }
        // ===== 4. 扩容校验：只允许扩大 =====
        int currentMax = room.getMaxMembers() != null ? room.getMaxMembers() : 50;
        if (newMaxMembers <= currentMax) {
            throw new ClientException("新的人数上限必须大于当前上限（当前：" + currentMax + " 人）");
        }
        // ===== 5. CAS 更新 =====
        // WHERE max_members < newMaxMembers 防止并发请求互相覆盖为更小的值
        boolean updated = this.lambdaUpdate()
                .eq(RoomDO::getRoomId, roomId)
                .lt(RoomDO::getMaxMembers, newMaxMembers)
                .ne(RoomDO::getStatus, RoomStatusEnum.CLOSED.getCode())
                .set(RoomDO::getMaxMembers, newMaxMembers)
                .update();
        if (!updated) {
            throw new ClientException("扩容失败，当前人数上限可能已被其他操作修改");
        }
        // ===== 6. 缓存失效 =====
        evictRoomCache(roomId);
        log.info("[房间扩容成功] room={}, operator={}, maxMembers: {} → {}",
                roomId, operatorId, currentMax, newMaxMembers);
    }


    @Override
    public void doForceGrace(String roomId) {
        RoomDO room = getRoomByRoomId(roomId);
        if (room == null) {
            log.warn("[强制宽限期-跳过] room={}, 房间不存在", roomId);
            return;
        }
        RoomStatusEnum status = RoomStatusEnum.of(room.getStatus());
        // 已关闭或已在宽限期 → 跳过
        if (status == RoomStatusEnum.CLOSED || status == RoomStatusEnum.GRACE) {
            log.info("[强制宽限期-跳过] room={}, 当前状态={}", roomId, status);
            return;
        }
        // 宽限期从现在开始算
        LocalDateTime graceEndTime = LocalDateTime.now().plusMinutes(FORCE_GRACE_MINUTES);
        // CAS 更新：只有非 CLOSED 才更新，防并发
        boolean updated = this.lambdaUpdate()
                .eq(RoomDO::getRoomId, roomId)
                .ne(RoomDO::getStatus, RoomStatusEnum.CLOSED.getCode())
                .ne(RoomDO::getStatus, RoomStatusEnum.GRACE.getCode())
                .set(RoomDO::getStatus, RoomStatusEnum.GRACE.getCode())
                .set(RoomDO::getGraceEndTime, graceEndTime)
                .update();
        if (!updated) {
            log.info("[强制宽限期-CAS 跳过] room={}, 可能已被其他操作处理", roomId);
            return;
        }
        evictRoomCache(roomId);
        // 主动投递 GRACE_END 延时事件（5 分钟后自动关闭）
        try {
            roomDelayProducer.submitGraceEndEvent(
                    roomId, graceEndTime, room.getExpireVersion());
        } catch (Exception e) {
            log.error("[强制宽限期-延时任务投递失败] room={}, 将由兜底任务保障", roomId, e);
        }
        // WS 广播：文案区分于自然到期
        roomChannelManager.broadcastToRoom(roomId,
                WsRespDTO.of(roomId, WsRespDTOTypeEnum.ROOM_GRACE,
                        "房主已注销，房间进入 " + FORCE_GRACE_MINUTES + " 分钟宽限期"));
        log.info("[强制宽限期] room={}, 状态 {} → GRACE, graceEndTime={}",
                roomId, status, graceEndTime);
    }

    /**
     * 构建房间信息响应
     */
    private RoomInfoRespDTO buildRoomInfoResp(RoomDO room) {
        RoomStatusEnum status = RoomStatusEnum.of(room.getStatus());
        // 查活跃成员数
        int memberCount = room.getCurrentMembers() != null ? room.getCurrentMembers() : 0;
        // 在线人数（内存中有 WS 连接的）
        int onlineCount = roomChannelManager.getOnlineCountInRoom(room.getRoomId());
        return RoomInfoRespDTO.builder()
                .roomId(room.getRoomId())
                .title(room.getTitle())
                .status(room.getStatus())
                .statusDesc(status != null ? status.getDesc() : "未知")
                .maxMembers(room.getMaxMembers())
                .isPublic(room.getIsPublic())
                .shareUrl(shareBaseUrl + "/room/" + room.getRoomId())
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
       //==== 校验 =====
        Long operatorId = UserContext.getRequiredLoginId();
        if (operatorId.equals(targetAccountId)) {
            throw new ClientException("不能对自己执行此操作");
        }
        RoomDO room = getRoomByRoomId(roomId);
        if (room == null) {
            throw new ClientException("房间不存在");
        }
        RoomStatusEnum roomStatus = RoomStatusEnum.of(room.getStatus());
        if (roomStatus == null || roomStatus == RoomStatusEnum.CLOSED) {
            throw new ClientException("房间已关闭，无法操作");
        }
        RoomMemberDO operatorMember = roomMemberService.getRoomMemberByRoomIdAndAccountId(roomId, operatorId);
        if (operatorMember == null || operatorMember.getStatus() != RoomMemberStatusEnum.ACTIVE.getCode()) {
            throw new ClientException("你不在该房间中");
        }
        if (operatorMember.getRole() != RoomMemberRoleEnum.HOST.getCode()) {
            throw new ClientException("只有房主可以执行此操作");
        }

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
     *  Room 缓存失效
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
