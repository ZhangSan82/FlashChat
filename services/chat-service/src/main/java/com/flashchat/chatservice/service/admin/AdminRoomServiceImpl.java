package com.flashchat.chatservice.service.admin;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.flashchat.cache.MultistageCacheProxy;
import com.flashchat.channel.GameStateQueryService;
import com.flashchat.channel.event.MemberKickedFromRoomEvent;
import com.flashchat.chatservice.cache.RoomCacheKeys;
import com.flashchat.chatservice.dao.entity.RoomDO;
import com.flashchat.chatservice.dao.entity.RoomMemberDO;
import com.flashchat.chatservice.dao.enums.RoomMemberMuteStatusEnum;
import com.flashchat.chatservice.dao.enums.RoomMemberRoleEnum;
import com.flashchat.chatservice.dao.enums.RoomMemberStatusEnum;
import com.flashchat.chatservice.dao.enums.RoomStatusEnum;
import com.flashchat.chatservice.dao.mapper.RoomMapper;
import com.flashchat.chatservice.dto.req.AdminRoomQueryReqDTO;
import com.flashchat.chatservice.dto.resp.AdminRoomSummaryRespDTO;
import com.flashchat.chatservice.dto.resp.RoomInfoRespDTO;
import com.flashchat.chatservice.dto.resp.RoomMemberRespDTO;
import com.flashchat.chatservice.service.RoomMemberService;
import com.flashchat.chatservice.service.RoomService;
import com.flashchat.chatservice.service.SystemMessageService;
import com.flashchat.chatservice.service.UnreadService;
import com.flashchat.chatservice.websocket.manager.RoomChannelManager;
import com.flashchat.convention.exception.ClientException;
import com.flashchat.userservice.dao.entity.AccountDO;
import com.flashchat.userservice.dao.entity.AdminOperationLogDO;
import com.flashchat.userservice.dao.enums.AdminOperationTargetTypeEnum;
import com.flashchat.userservice.dao.enums.AdminOperationTypeEnum;
import com.flashchat.userservice.dto.req.AdminOperationReasonReqDTO;
import com.flashchat.userservice.dto.resp.AdminPageRespDTO;
import com.flashchat.userservice.service.admin.AdminAuthService;
import com.flashchat.userservice.service.admin.AdminOperationLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 管理端房间服务实现。
 * <p>
 * 这里的目标是提供系统管理员能力，不改变原有房主链路的行为边界。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AdminRoomServiceImpl implements AdminRoomService {

    private static final String ROOM_VIOLATION_NOTICE = "该房间违规";

    private final AdminAuthService adminAuthService;
    private final AdminOperationLogService adminOperationLogService;
    private final RoomService roomService;
    private final SystemMessageService systemMessageService;
    private final RoomMemberService roomMemberService;
    private final RoomMapper roomMapper;
    private final RoomChannelManager roomChannelManager;
    private final UnreadService unreadService;
    private final GameStateQueryService gameStateQueryService;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final MultistageCacheProxy multistageCacheProxy;

    @Override
    public AdminPageRespDTO<AdminRoomSummaryRespDTO> searchRooms(Long operatorId, AdminRoomQueryReqDTO request) {
        adminAuthService.requireActiveAdmin(operatorId);

        long pageNo = request.getPage() == null || request.getPage() < 1 ? 1L : request.getPage();
        long size = request.getSize() == null || request.getSize() < 1 ? 20L : Math.min(request.getSize(), 100L);

        LambdaQueryWrapper<RoomDO> wrapper = new LambdaQueryWrapper<>();
        if (hasText(request.getKeyword())) {
            String keyword = request.getKeyword().trim();
            wrapper.and(q -> q.like(RoomDO::getRoomId, keyword)
                    .or().like(RoomDO::getTitle, keyword));
        }
        if (request.getStatus() != null) {
            wrapper.eq(RoomDO::getStatus, request.getStatus());
        }
        if (request.getIsPublic() != null) {
            wrapper.eq(RoomDO::getIsPublic, request.getIsPublic());
        }
        wrapper.orderByDesc(RoomDO::getUpdateTime)
                .orderByDesc(RoomDO::getCreateTime);

        Page<RoomDO> result = roomMapper.selectPage(new Page<>(pageNo, size), wrapper);
        List<AdminRoomSummaryRespDTO> records = result.getRecords().stream()
                .map(this::toRoomSummaryResp)
                .toList();

        return AdminPageRespDTO.<AdminRoomSummaryRespDTO>builder()
                .page(pageNo)
                .size(size)
                .total(result.getTotal())
                .records(records)
                .build();
    }

    @Override
    public RoomInfoRespDTO getRoomDetail(Long operatorId, String roomId) {
        adminAuthService.requireActiveAdmin(operatorId);
        return roomService.previewRoom(roomId);
    }

    @Override
    public List<RoomMemberRespDTO> getRoomMembers(Long operatorId, String roomId) {
        adminAuthService.requireActiveAdmin(operatorId);
        return roomService.getRoomMembers(roomId);
    }

    @Override
    public void closeRoom(Long operatorId, String roomId, AdminOperationReasonReqDTO request) {
        AccountDO operator = adminAuthService.requireActiveAdmin(operatorId);
        RoomDO room = requireOperableRoom(roomId);
        if (RoomStatusEnum.GRACE.getCode() == room.getStatus()) {
            throw new ClientException("房间已进入宽限期，无需重复关闭");
        }
        // 管理员关闭房间时，不直接终结房间，而是强制进入宽限期并广播违规提示。
        roomService.doForceGrace(roomId);
        systemMessageService.sendToRoom(roomId, ROOM_VIOLATION_NOTICE);
        adminOperationLogService.record(buildLog(
                operator,
                AdminOperationTypeEnum.ROOM_CLOSE,
                AdminOperationTargetTypeEnum.ROOM,
                roomId,
                room.getTitle(),
                request.getReason(),
                null
        ));
    }

    @Override
    public void kickMember(Long operatorId, String roomId, Long targetAccountId, AdminOperationReasonReqDTO request) {
        AccountDO operator = adminAuthService.requireActiveAdmin(operatorId);
        RoomDO room = requireOperableRoom(roomId);
        RoomMemberDO target = requireActiveTargetMember(roomId, targetAccountId);
        if (target.getRole() != null && target.getRole() == RoomMemberRoleEnum.HOST.getCode()) {
            throw new ClientException("不能直接踢出房主，请直接关闭房间");
        }
        if (gameStateQueryService.isInPlayingGame(roomId, targetAccountId)) {
            throw new ClientException("目标成员正在游戏中，请等待游戏结束后再操作");
        }

        target.setStatus(RoomMemberStatusEnum.KICKED.getCode());
        target.setLeaveTime(LocalDateTime.now());
        boolean updated = roomMemberService.updateWithCacheEvict(target);
        if (!updated) {
            log.info("[admin kick room member skipped] roomId={}, target={}", roomId, targetAccountId);
            return;
        }
        roomMapper.decrementMemberCount(roomId);
        evictRoomCache(roomId);
        roomChannelManager.kickMember(roomId, targetAccountId);
        unreadService.removeRoomUnread(targetAccountId, roomId);
        applicationEventPublisher.publishEvent(
                new MemberKickedFromRoomEvent(this, targetAccountId, roomId, operatorId)
        );
        adminOperationLogService.record(buildLog(
                operator,
                AdminOperationTypeEnum.ROOM_MEMBER_KICK,
                AdminOperationTargetTypeEnum.ROOM_MEMBER,
                roomId + ":" + targetAccountId,
                room.getTitle(),
                request.getReason(),
                "{\"roomId\":\"" + roomId + "\",\"targetAccountId\":" + targetAccountId + "}"
        ));
    }

    @Override
    public void muteMember(Long operatorId, String roomId, Long targetAccountId, AdminOperationReasonReqDTO request) {
        AccountDO operator = adminAuthService.requireActiveAdmin(operatorId);
        RoomDO room = requireOperableRoom(roomId);
        RoomMemberDO target = requireActiveTargetMember(roomId, targetAccountId);
        if (target.getRole() != null && target.getRole() == RoomMemberRoleEnum.HOST.getCode()) {
            throw new ClientException("不能直接禁言房主，请直接关闭房间");
        }
        if (target.getIsMuted() != null && target.getIsMuted() == RoomMemberMuteStatusEnum.MUTE.getCode()) {
            return;
        }

        target.setIsMuted(RoomMemberMuteStatusEnum.MUTE.getCode());
        boolean updated = roomMemberService.updateWithCacheEvict(target);
        if (!updated) {
            return;
        }
        evictRoomCache(roomId);
        roomChannelManager.muteMember(roomId, targetAccountId);
        adminOperationLogService.record(buildLog(
                operator,
                AdminOperationTypeEnum.ROOM_MEMBER_MUTE,
                AdminOperationTargetTypeEnum.ROOM_MEMBER,
                roomId + ":" + targetAccountId,
                room.getTitle(),
                request.getReason(),
                "{\"roomId\":\"" + roomId + "\",\"targetAccountId\":" + targetAccountId + "}"
        ));
    }

    @Override
    public void unmuteMember(Long operatorId, String roomId, Long targetAccountId, AdminOperationReasonReqDTO request) {
        AccountDO operator = adminAuthService.requireActiveAdmin(operatorId);
        RoomDO room = requireOperableRoom(roomId);
        RoomMemberDO target = requireActiveTargetMember(roomId, targetAccountId);
        if (target.getRole() != null && target.getRole() == RoomMemberRoleEnum.HOST.getCode()) {
            throw new ClientException("房主不需要管理员单独解除禁言");
        }
        if (target.getIsMuted() == null || target.getIsMuted() == RoomMemberMuteStatusEnum.UNMUTE.getCode()) {
            return;
        }

        target.setIsMuted(RoomMemberMuteStatusEnum.UNMUTE.getCode());
        boolean updated = roomMemberService.updateWithCacheEvict(target);
        if (!updated) {
            return;
        }
        evictRoomCache(roomId);
        roomChannelManager.unmuteMember(roomId, targetAccountId);
        adminOperationLogService.record(buildLog(
                operator,
                AdminOperationTypeEnum.ROOM_MEMBER_UNMUTE,
                AdminOperationTargetTypeEnum.ROOM_MEMBER,
                roomId + ":" + targetAccountId,
                room.getTitle(),
                request.getReason(),
                "{\"roomId\":\"" + roomId + "\",\"targetAccountId\":" + targetAccountId + "}"
        ));
    }

    private RoomDO requireOperableRoom(String roomId) {
        RoomDO room = roomService.getRoomByRoomId(roomId);
        if (room == null) {
            throw new ClientException("房间不存在");
        }
        RoomStatusEnum status = RoomStatusEnum.of(room.getStatus());
        if (status == null || status == RoomStatusEnum.CLOSED) {
            throw new ClientException("房间已关闭，无法执行管理员操作");
        }
        return room;
    }

    private RoomMemberDO requireActiveTargetMember(String roomId, Long targetAccountId) {
        RoomMemberDO target = roomMemberService.getRoomMemberByRoomIdAndAccountId(roomId, targetAccountId);
        if (target == null || target.getStatus() == null
                || target.getStatus() != RoomMemberStatusEnum.ACTIVE.getCode()) {
            throw new ClientException("目标成员当前不在该房间中");
        }
        return target;
    }

    private void evictRoomCache(String roomId) {
        try {
            multistageCacheProxy.delete(RoomCacheKeys.room(roomId));
        } catch (Exception ex) {
            log.warn("[admin room cache evict failed] roomId={}", roomId, ex);
        }
    }

    private AdminRoomSummaryRespDTO toRoomSummaryResp(RoomDO room) {
        Integer status = room.getStatus();
        RoomStatusEnum statusEnum = status != null ? RoomStatusEnum.of(status) : null;
        return AdminRoomSummaryRespDTO.builder()
                .roomId(room.getRoomId())
                .title(room.getTitle())
                .status(status)
                .statusDesc(statusEnum != null ? statusEnum.getDesc() : "未知")
                .isPublic(room.getIsPublic())
                .visibilityDesc(room.getIsPublic() != null && room.getIsPublic() == 1 ? "公开" : "私密")
                .memberCount(room.getCurrentMembers())
                .maxMembers(room.getMaxMembers())
                .onlineCount(roomChannelManager.getOnlineCountInRoom(room.getRoomId()))
                .createTime(room.getCreateTime())
                .expireTime(room.getExpireTime())
                .build();
    }

    private AdminOperationLogDO buildLog(AccountDO operator,
                                         AdminOperationTypeEnum operationType,
                                         AdminOperationTargetTypeEnum targetType,
                                         String targetId,
                                         String targetDisplay,
                                         String reason,
                                         String detailJson) {
        return AdminOperationLogDO.builder()
                .operatorId(operator.getId())
                .operatorAccountId(operator.getAccountId())
                .operationType(operationType.name())
                .targetType(targetType.name())
                .targetId(targetId)
                .targetDisplay(targetDisplay)
                .reason(reason)
                .detailJson(detailJson)
                .build();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
