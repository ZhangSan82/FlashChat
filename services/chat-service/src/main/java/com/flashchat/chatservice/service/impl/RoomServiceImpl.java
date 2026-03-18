package com.flashchat.chatservice.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.flashchat.chatservice.dao.entity.MemberDO;
import com.flashchat.chatservice.dao.entity.RoomDO;
import com.flashchat.chatservice.dao.entity.RoomMemberDO;
import com.flashchat.chatservice.dao.enums.RoomMemberRoleEnum;
import com.flashchat.chatservice.dao.enums.RoomMemberStatusEnum;
import com.flashchat.chatservice.dao.enums.RoomStatusEnum;
import com.flashchat.chatservice.dao.mapper.RoomMapper;
import com.flashchat.chatservice.dto.context.HostOperationContext;
import com.flashchat.chatservice.dto.req.*;
import com.flashchat.chatservice.dto.resp.RoomInfoRespDTO;
import com.flashchat.chatservice.dto.resp.RoomMemberRespDTO;
import com.flashchat.chatservice.service.MemberService;
import com.flashchat.chatservice.service.RoomMemberService;
import com.flashchat.chatservice.service.RoomService;
import com.flashchat.chatservice.toolkit.HashUtil;
import com.flashchat.chatservice.websocket.manager.RoomChannelManager;
import com.flashchat.chatservice.websocket.manager.RoomMemberInfo;
import com.flashchat.convention.exception.ClientException;
import com.flashchat.convention.exception.ServiceException;
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
    private final MemberService memberService;

    /**
     * 创建房间
     */
    @Transactional
    @Override
    public RoomInfoRespDTO createRoom(RoomCreateReqDTO request) {


        MemberDO creator = memberService.getByAccountId(request.getAccountId());

        if (creator == null) {
            throw new ClientException("账号不存在");
        }
        if (creator.getStatus() != null && creator.getStatus() == 0) {
            throw new ClientException("账号已被封禁，无法创建房间");
        }

        // TODO 未来：检查是否为注册用户（主持人）+ 扣除积分

        double hours = request.getDurationHours() != null ? request.getDurationHours() : 2.0;
        LocalDateTime expireTime = LocalDateTime.now().plusMinutes((long) (hours * 60));

        String roomId = getRoomID();

        //t_room
        RoomDO room = RoomDO.builder()
                .roomId(roomId)
                .creatorId(creator.getId())
                .title(request.getTitle())
                .maxMembers(request.getMaxMembers() != null ? request.getMaxMembers() : 50)
                .isPublic(request.getIsPublic() != null ? request.getIsPublic() : 0)
                .status(RoomStatusEnum.WAITING.getCode())
                .expireTime(expireTime)
                .build();
        this.save(room);
        flashChatRoomRegisterCachePenetrationBloomFilter.add(roomId);
        log.info("[创建房间] roomId={}, title={}, creator={}", roomId, room.getTitle(), room.getCreatorId());

        RoomMemberDO hostMember = RoomMemberDO.builder()
                .roomId(roomId)
                .userId(null)//TODO注册用户ID暂时没有
                .memberId(creator.getId())
                .role(RoomMemberRoleEnum.HOST.getCode())
                .isMuted(0)
                .status(RoomMemberStatusEnum.ACTIVE.getCode())
                .lastAckMsgId(0L)
                .joinTime(LocalDateTime.now())
                .build();

        roomMemberService.save(hostMember);

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

        MemberDO memberDO = memberService.getByAccountId(request.getAccountId());

        if (memberDO == null) {
            throw new ClientException("账号不存在");
        }
        if (memberDO.getStatus() != null && memberDO.getStatus() == 0) {
            throw new ClientException("账号已被封禁，无法加入房间");
        }

        Long memberId = memberDO.getId();
        // 1. 查房间
        RoomDO room = this.lambdaQuery().eq(RoomDO::getRoomId, roomId).one();
        if (room == null) {
            throw new ClientException("房间不存在");
        }

        // 2. 检查房间状态
        RoomStatusEnum status = RoomStatusEnum.of(room.getStatus());
        if (status == null || !status.canJoin()) {
            throw new ClientException("房间当前不允许加入（状态：" + (status != null ? status.getDesc() : "未知") + "）");
        }

        // 3. 检查人数TODO从数据库中查询用户人数
        int activeCount = roomChannelManager.getMemberCount(roomId);
        if (activeCount >= room.getMaxMembers()) {
            throw new ClientException("房间已满（" + activeCount + "/" + room.getMaxMembers() + "）");
        }

        // 4. 检查是否已有记录（处理重复加入、重新加入）
        RoomMemberDO existingMember = roomMemberService.lambdaQuery().eq(RoomMemberDO::getRoomId, roomId)
                .eq(RoomMemberDO::getMemberId,memberId).one();
        if (existingMember != null) {
            if (existingMember.getStatus() == RoomMemberStatusEnum.ACTIVE.getCode()) {
                // 已在房间中，幂等返回
                log.info("[重复加入] room={}, memberId={}, 已在房间中", roomId, memberId);
                return buildRoomInfoResp(room);
            }
            //之前离开过（LEFT）或被踢过（KICKED），恢复为 ACTIVE
            existingMember.setStatus(RoomMemberStatusEnum.ACTIVE.getCode());
            existingMember.setLeaveTime(null);
            existingMember.setJoinTime(LocalDateTime.now());
            roomMemberService.updateById(existingMember);
            log.info("[重新加入] room={}, memberId={}, 原状态={}",
                    roomId, memberId, existingMember.getStatus());

        } else {
            // 首次加入 → 插入新行
            RoomMemberDO newMember = RoomMemberDO.builder()
                    .roomId(roomId)
                    .memberId(memberId)
                    .userId(null) // 匿名成员，user_id 为 null
                    .role(RoomMemberRoleEnum.MEMBER.getCode())
                    .isMuted(0)
                    .status(RoomMemberStatusEnum.ACTIVE.getCode())
                    .lastAckMsgId(0L)
                    .joinTime(LocalDateTime.now())
                    .build();
            roomMemberService.save(newMember);
            log.info("[首次加入] room={}, memberId={}", roomId, memberId);
        }

        // 5. 如果房间是 WAITING → 改为 ACTIVE
        if (room.getStatus() == RoomStatusEnum.WAITING.getCode()) {
            this.lambdaUpdate().eq(RoomDO::getRoomId, roomId)
                            .set(RoomDO::getStatus, RoomStatusEnum.ACTIVE.getCode())
                                    .update();

            room.setStatus(RoomStatusEnum.ACTIVE.getCode());
            log.info("[房间激活] room={}, WAITING → ACTIVE", roomId);
        }

        // 6. 同步到内存（RoomChannelManager）
        //    从 Channel 属性获取昵称/头像（WS 连接时已分配）

        roomChannelManager.joinRoom(roomId, memberId, memberDO.getNickname() ,memberDO.getAvatarColor() , false);

        return buildRoomInfoResp(room);
    }

    @Override
    @Transactional
    public void leaveRoom(RoomLeaveReqDTO request) {
        String roomId = request.getRoomId();
        MemberDO memberDO = memberService.getByAccountId(request.getAccountId());

        if (memberDO == null) {
            throw new ClientException("账号不存在");
        }

        Long memberId = memberDO.getId();

        // ===== 1. 校验房间存在 =====
        RoomDO room = this.lambdaQuery().eq(RoomDO::getRoomId, roomId).one();
        if (room == null) {
            throw new ClientException("房间不存在");
        }

        // ===== 2. 查成员记录 =====
        RoomMemberDO member = roomMemberService.lambdaQuery().eq(RoomMemberDO::getMemberId, memberId)
                .eq(RoomMemberDO::getRoomId,roomId).one();
        if (member == null || member.getStatus() != RoomMemberStatusEnum.ACTIVE.getCode()) {
            log.info("[离开房间-幂等] room={}, memberId={}, 不在房间中或已离开", roomId, memberId);
            return;
        }

        // ===== 3. 房主不能离开，只能关闭房间 =====
        if (member.getRole() == RoomMemberRoleEnum.HOST.getCode()) {
            throw new ClientException("房主不能离开房间，请使用「关闭房间」功能");
        }

        // ===== 4. 更新 DB =====
        member.setStatus(RoomMemberStatusEnum.LEFT.getCode());
        member.setLeaveTime(LocalDateTime.now());
        roomMemberService.updateById(member);

        // ===== 5. 同步内存 =====
        roomChannelManager.leaveRoom(roomId, memberId);

        log.info("[离开房间] room={}, memberId={}", roomId, memberId);

    }

    /**
     * 房间成员列表
     */
    @Override
    public List<RoomMemberRespDTO> getRoomMembers(String roomId) {
        // ===== 1. 校验房间存在 =====
        RoomDO room = this.lambdaQuery().eq(RoomDO::getRoomId, roomId).one();
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

        List<Long> memberIds = new ArrayList<>();
        for (RoomMemberDO member : dbMembers) {
            Long memberId = member.getMemberId() != null ? member.getMemberId() : member.getUserId();
            RoomMemberInfo memoryInfo = roomChannelManager.getRoomMemberInfo(roomId, memberId);
            if (memoryInfo != null) {
                memberIds.add(memberId);
            }
        }

        Map<Long,MemberDO> memberMap = new HashMap<>();
        if (!memberIds.isEmpty()) {
            List<MemberDO> memberDOS = memberService.listByIds(memberIds);
            for (MemberDO member : memberDOS) {
                memberMap.put(member.getId(),member);
            }
        }

        // ===== 3. 组装响应（DB + 内存合并）=====
        List<RoomMemberRespDTO> result = new ArrayList<>();

        for (RoomMemberDO dbMember : dbMembers) {
            Long id = dbMember.getMemberId() != null ? dbMember.getMemberId() : dbMember.getUserId();

            // ★ 优先从内存取（RoomChannelManager）
            RoomMemberInfo memoryInfo = roomChannelManager.getRoomMemberInfo(roomId, id);

            // ★ 内存没有就查 DB（通过缓存，不会真的每次查 DB）
            String nickname;
            String avatar;

            if (memoryInfo != null) {
                nickname = memoryInfo.getNickname();
                avatar = memoryInfo.getAvatar();
            } else {
                // 用 memberService 带缓存的查询
                MemberDO memberDO = memberMap.get(id);
                nickname = memberDO != null ? memberDO.getNickname() : "匿名用户";
                avatar = memberDO != null ? memberDO.getAvatarColor() : "#999999";
            }

            // 禁言：内存优先 → DB fallback
            boolean isMuted = memoryInfo != null ? memoryInfo.isMuted() : dbMember.getIsMuted() == 1;

            // 角色：DB 为准
            boolean isHost = dbMember.getRole() == RoomMemberRoleEnum.HOST.getCode();

            // 在线状态：只有内存知道
            boolean isOnline = roomChannelManager.isOnline(id);

            result.add(RoomMemberRespDTO.builder()
                    .memberId(id)
                    .nickname(nickname)
                    .avatar(avatar)
                    .role(dbMember.getRole())
                    .isHost(isHost)
                    .isMuted(isMuted)
                    .isOnline(isOnline)
                    .build());
        }

        // ===== 4. 排序：房主排第一 =====
        result.sort((a, b) -> {
            if (a.getIsHost() && !b.getIsHost()) return -1;
            if (!a.getIsHost() && b.getIsHost()) return 1;
            return 0;
        });

        return result;
    }

    @Override
    public void restoreRoomMemberships(Long memberId) {
        // 1. 查 DB：该用户所有 ACTIVE 的房间成员记录
        List<RoomMemberDO> activeMembers = roomMemberService.lambdaQuery()
                .eq(RoomMemberDO::getMemberId, memberId)
                .eq(RoomMemberDO::getStatus, RoomMemberStatusEnum.ACTIVE.getCode())
                .list();

        if (activeMembers == null || activeMembers.isEmpty()) {
            log.debug("[恢复房间] memberId={}, 无活跃房间", memberId);
            return;
        }

        // 2. 获取用户信息（走缓存）
        MemberDO member = memberService.getById(memberId);
        String nickname = member != null ? member.getNickname() : "匿名用户";
        String avatar = member != null ? member.getAvatarColor() : "#999999";


        List<String> roomIds = activeMembers.stream()
                .map(RoomMemberDO::getRoomId)
                .toList();

        Map<String,RoomDO> roomMap = this.lambdaQuery()
                .in(RoomDO::getRoomId,roomIds)
                .list()
                .stream()
                .collect(Collectors.toMap(RoomDO::getRoomId,room -> room));

        // 3. 逐个恢复到内存
        int count = 0;
        for (RoomMemberDO rm : activeMembers) {
            // 检查房间是否还存在且未关闭
            RoomDO room = roomMap.get(rm.getRoomId());
            if (room == null || room.getStatus() == RoomStatusEnum.CLOSED.getCode()) {
                continue;  // 房间已关闭，跳过
            }

            boolean isHost = rm.getRole() == RoomMemberRoleEnum.HOST.getCode();

            // 静默加入（不广播）
            roomChannelManager.joinRoomSilent(rm.getRoomId(), memberId, nickname, avatar, isHost);
            count++;
        }

        log.info("[恢复房间] memberId={}, 恢复了 {} 个房间", memberId, count);
    }

    /**
     *踢人
     */
    @Override
    @Transactional
    public void kickMember(RoomKickReqDTO request) {
        HostOperationContext ctx = validateHostOperation(
                request.getRoomId(), request.getAccountId(), request.getTargetMemberId());

        // 更新 DB
        ctx.getTargetMember().setStatus(RoomMemberStatusEnum.KICKED.getCode());
        ctx.getTargetMember().setLeaveTime(LocalDateTime.now());
        roomMemberService.updateById(ctx.getTargetMember());

        // 同步内存
        roomChannelManager.kickMember(request.getRoomId(), ctx.getTargetId());

        log.info("[踢人成功] room={}, operator={}, target={}",
                request.getRoomId(), ctx.getOperatorId(), ctx.getTargetId());
    }

    @Override
    public void muteMember(RoomMuteReqDTO request) {
        String roomId = request.getRoomId();

        // 公共校验
        HostOperationContext ctx = validateHostOperation(
                roomId, request.getAccountId(), request.getTargetMemberId());

        // 不能禁言房主（理论上公共校验已排除自己，这里防止多房主场景）
        if (ctx.getTargetMember().getRole() == RoomMemberRoleEnum.HOST.getCode()) {
            throw new ClientException("不能禁言房主");
        }

        // 幂等：已经禁言的不重复处理
        if (ctx.getTargetMember().getIsMuted() == 1) {
            log.info("[重复禁言] room={}, target={}, 已处于禁言状态", roomId, ctx.getTargetId());
            return;
        }

        // 更新 DB
        ctx.getTargetMember().setIsMuted(1);
        roomMemberService.updateById(ctx.getTargetMember());

        // 同步内存 + WS 通知
        roomChannelManager.muteMember(roomId, ctx.getTargetId());

        log.info("[禁言成功] room={}, operator={}, target={}",
                roomId, ctx.getOperatorId(), ctx.getTargetId());
    }

    @Override
    public void unmuteMember(RoomMuteReqDTO request) {
        String roomId = request.getRoomId();

        // 公共校验
        HostOperationContext ctx = validateHostOperation(
                roomId, request.getAccountId(), request.getTargetMemberId());

        // 幂等：没有被禁言的不重复处理
        if (ctx.getTargetMember().getIsMuted() == 0) {
            log.info("[重复解禁] room={}, target={}, 未处于禁言状态", roomId, ctx.getTargetId());
            return;
        }

        // 更新 DB
        ctx.getTargetMember().setIsMuted(0);
        roomMemberService.updateById(ctx.getTargetMember());

        // 同步内存 + WS 通知
        roomChannelManager.unmuteMember(roomId, ctx.getTargetId());

        log.info("[解禁成功] room={}, operator={}, target={}",
                roomId, ctx.getOperatorId(), ctx.getTargetId());
    }

    /**
     * 关闭房间
     */
    @Override
    public void closeRoom(RoomCloseReqDTO request) {
        String roomId = request.getRoomId();

        MemberDO operator = memberService.getByAccountId(request.getAccountId());

        // ===== 1. 校验操作者是房主 =====
        RoomMemberDO operatorMember = roomMemberService.lambdaQuery()
                .eq(RoomMemberDO::getRoomId, roomId)
                .eq(RoomMemberDO::getMemberId, operator.getId())
                .eq(RoomMemberDO::getStatus, RoomMemberStatusEnum.ACTIVE.getCode())
                .one();
        if (operatorMember == null) {
            throw new ClientException("你不在该房间中");
        }
        if (operatorMember.getRole() != RoomMemberRoleEnum.HOST.getCode()) {
            throw new ClientException("只有房主可以执行此操作");
        }


        // ===== 2. 更新 t_room =====
        LocalDateTime now = LocalDateTime.now();
        this.lambdaUpdate()
                .eq(RoomDO::getRoomId, roomId)
                .set(RoomDO::getStatus, RoomStatusEnum.CLOSED.getCode())
                .set(RoomDO::getClosedTime, now)
                .update();

        // ===== 3. 批量更新所有活跃成员 → LEFT =====
        roomMemberService.lambdaUpdate()
                .eq(RoomMemberDO::getRoomId, roomId)
                .eq(RoomMemberDO::getStatus, RoomMemberStatusEnum.ACTIVE.getCode())
                .set(RoomMemberDO::getStatus, RoomMemberStatusEnum.LEFT.getCode())
                .set(RoomMemberDO::getLeaveTime, now)
                .update();

        // ===== 4. 同步内存：通知所有在线成员 + 清理房间数据 =====
        roomChannelManager.closeRoom(roomId);

        log.info("[关闭房间] room={}, operator={}", roomId, request.getAccountId());
    }

    @Override
    public List<RoomInfoRespDTO> getMyRooms(String accountId) {
        MemberDO member = memberService.getByAccountId(accountId);
        Long memberId = member.getId();

        // 查所有 ACTIVE 的房间成员记录
        List<RoomMemberDO> activeMembers = roomMemberService.lambdaQuery()
                .eq(RoomMemberDO::getMemberId, memberId)
                .eq(RoomMemberDO::getStatus, RoomMemberStatusEnum.ACTIVE.getCode())
                .list();

        if (activeMembers == null || activeMembers.isEmpty()) {
            return List.of();
        }

        List<String> roomIds = activeMembers.stream()
                .map(RoomMemberDO::getRoomId)
                .toList();

        List<RoomDO> rooms = this.lambdaQuery()
                .in(RoomDO::getRoomId, roomIds)
                .ne(RoomDO::getStatus,RoomStatusEnum.CLOSED.getCode())
                .list();

        return rooms.stream()
                .map(this::buildRoomInfoResp)
                .toList();

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
    private HostOperationContext validateHostOperation(
            String roomId, String operatorAccountId, Long targetMemberId) {

        // 1. 验证操作者
        MemberDO operator = memberService.getByAccountId(operatorAccountId);
        Long operatorId = operator.getId();

        // 2. 不能操作自己
        if (operatorId.equals(targetMemberId)) {
            throw new ClientException("不能对自己执行此操作");
        }

        // 3. 验证房间
        RoomDO room = this.lambdaQuery().eq(RoomDO::getRoomId, roomId).one();
        if (room == null) {
            throw new ClientException("房间不存在");
        }
        RoomStatusEnum roomStatus = RoomStatusEnum.of(room.getStatus());
        if (roomStatus == null || roomStatus == RoomStatusEnum.CLOSED) {
            throw new ClientException("房间已关闭，无法操作");
        }

        // 4. 验证操作者是房主
        RoomMemberDO operatorMember = roomMemberService.lambdaQuery()
                .eq(RoomMemberDO::getRoomId, roomId)
                .eq(RoomMemberDO::getMemberId, operatorId)
                .eq(RoomMemberDO::getStatus, RoomMemberStatusEnum.ACTIVE.getCode())
                .one();
        if (operatorMember == null) {
            throw new ClientException("你不在该房间中");
        }
        if (operatorMember.getRole() != RoomMemberRoleEnum.HOST.getCode()) {
            throw new ClientException("只有房主可以执行此操作");
        }

        // 5. 验证目标在房间中
        RoomMemberDO targetMember = roomMemberService.lambdaQuery()
                .eq(RoomMemberDO::getRoomId, roomId)
                .eq(RoomMemberDO::getMemberId, targetMemberId)
                .eq(RoomMemberDO::getStatus, RoomMemberStatusEnum.ACTIVE.getCode())
                .one();
        if (targetMember == null) {
            throw new ClientException("该用户不在房间中");
        }

        return HostOperationContext.builder()
                .room(room)
                .operatorId(operatorId)
                .targetId(targetMemberId)
                .operatorMember(operatorMember)
                .targetMember(targetMember)
                .build();
    }


    /**
     *生成唯一房间ID
     */
    private String getRoomID(){
        int customGenerateCount = 0;
        String roomId;
        String SEED_PREFIX = "flashchat:room:" + UUID.randomUUID().toString();
        while (true) {
            if (customGenerateCount > 10)
            {
                throw new ServiceException("房间ID频繁生成,请稍后再试");
            }
            roomId = HashUtil.hashToBase62(SEED_PREFIX);
            if (!flashChatRoomRegisterCachePenetrationBloomFilter.contains(roomId))
            {
                break;
            }
            customGenerateCount++;
        }

        return roomId;
    }


}
