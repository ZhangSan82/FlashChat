package com.flashchat.chatservice.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.flashchat.chatservice.dao.entity.RoomDO;
import com.flashchat.chatservice.dao.entity.RoomMemberDO;
import com.flashchat.chatservice.dao.enums.RoomMemberRoleEnum;
import com.flashchat.chatservice.dao.enums.RoomMemberStatusEnum;
import com.flashchat.chatservice.dao.enums.RoomStatusEnum;
import com.flashchat.chatservice.dao.mapper.RoomMapper;
import com.flashchat.chatservice.dto.req.RoomCreateReqDTO;
import com.flashchat.chatservice.dto.req.RoomJoinReqDTO;
import com.flashchat.chatservice.dto.req.RoomLeaveReqDTO;
import com.flashchat.chatservice.dto.resp.RoomInfoRespDTO;
import com.flashchat.chatservice.dto.resp.RoomMemberRespDTO;
import com.flashchat.chatservice.service.RoomMemberService;
import com.flashchat.chatservice.service.RoomService;
import com.flashchat.chatservice.toolkit.ChannelAttrUtil;
import com.flashchat.chatservice.toolkit.HashUtil;
import com.flashchat.chatservice.websocket.manager.RoomChannelManager;
import com.flashchat.chatservice.websocket.manager.RoomMemberInfo;
import com.flashchat.convention.exception.ClientException;
import com.flashchat.convention.exception.ServiceException;
import io.netty.channel.Channel;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBloomFilter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@AllArgsConstructor
public class RoomServiceImpl extends ServiceImpl<RoomMapper, RoomDO> implements RoomService {

    private final RoomChannelManager roomChannelManager;
    private final RBloomFilter<String> flashChatRoomRegisterCachePenetrationBloomFilter;
    private final RoomMemberService  roomMemberService;

    /**
     * 创建房间
     */
    @Transactional
    @Override
    public RoomInfoRespDTO createRoom(RoomCreateReqDTO request) {


        //TODO之后要检查用户的身份以及扣除相应的积分

        double hours = request.getDurationHours() != null ? request.getDurationHours() : 2.0;
        LocalDateTime expireTime = LocalDateTime.now().plusMinutes((long) (hours * 60));

        String roomId = getRoomID();

        //t_room
        RoomDO room = RoomDO.builder()
                .roomId(roomId)
                .creatorId(request.getCreatorUserId())
                .title(request.getTitle())
                .maxMembers(request.getMaxMembers() != null ? request.getMaxMembers() : 50)
                .isPublic(request.getIsPublic() != null ? request.getIsPublic() : 0)
                .status(RoomStatusEnum.WAITING.getCode())
                .expireTime(expireTime)
                .build();
        this.save(room);
        log.info("[创建房间] roomId={}, title={}, creator={}", roomId, room.getTitle(), room.getCreatorId());

        RoomMemberDO hosrMember = RoomMemberDO.builder()
                .roomId(roomId)
                .userId(request.getCreatorUserId())
                .memberId(null)
                .role(RoomMemberRoleEnum.HOST.getCode())
                .isMuted(0)
                .status(RoomStatusEnum.ACTIVE.getCode())
                .lastAckMsgId(0L)
                .joinTime(LocalDateTime.now())
                .build();

        roomMemberService.save(hosrMember);


        return buildRoomInfoResp(room);
    }

    /**
     * 加入房间
     */
    @Override
    @Transactional
    public RoomInfoRespDTO joinRoom(RoomJoinReqDTO request) {
        String roomId = request.getRoomId();
        Long memberId = request.getMemberId();

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

        // 3. 检查人数
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
//            roomMapper.update(null, new LambdaUpdateWrapper<RoomDO>()
//                    .eq(RoomDO::getRoomId, roomId)
//                    .set(RoomDO::getStatus, RoomStatusEnum.ACTIVE.getCode()));

            room.setStatus(RoomStatusEnum.ACTIVE.getCode());
            log.info("[房间激活] room={}, WAITING → ACTIVE", roomId);
        }

        // 6. 同步到内存（RoomChannelManager）
        //    从 Channel 属性获取昵称/头像（WS 连接时已分配）
        String nickname = getNicknameFromChannel(memberId);
        String avatar = getAvatarFromChannel(memberId);

        roomChannelManager.joinRoom(roomId, memberId, nickname, avatar, false);

        return buildRoomInfoResp(room);
    }

    @Override
    @Transactional
    public void leaveRoom(RoomLeaveReqDTO request) {
        String roomId = request.getRoomId();
        Long memberId = request.getMemberId();

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

        // ===== 3. 组装响应（DB + 内存合并）=====
        List<RoomMemberRespDTO> result = new ArrayList<>();

        for (RoomMemberDO dbMember : dbMembers) {
            // 确定成员 ID（匿名成员用 memberId，注册用户用 userId）
            Long id = dbMember.getMemberId() != null ? dbMember.getMemberId() : dbMember.getUserId();

            // 从内存获取实时数据
            RoomMemberInfo memoryInfo = roomChannelManager.getRoomMemberInfo(roomId, id);

            // 昵称：内存优先 → DB/默认值 fallback
            String nickname;
            if (memoryInfo != null && memoryInfo.getNickname() != null) {
                nickname = memoryInfo.getNickname();
            } else {
                nickname = getNicknameFromChannel(id);
            }

            // 头像：内存优先 → DB/默认值 fallback
            String avatar;
            if (memoryInfo != null && memoryInfo.getAvatar() != null) {
                avatar = memoryInfo.getAvatar();
            } else {
                avatar = getAvatarFromChannel(id);
            }

            // 禁言状态：内存优先（实时）→ DB fallback
            boolean isMuted;
            if (memoryInfo != null) {
                isMuted = memoryInfo.isMuted();
            } else {
                isMuted = dbMember.getIsMuted() == 1;
            }

            // 角色：以 DB 为准
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

        // ===== 4. 排序：房主排第一，其余按加入时间正序（DB 查询已按时间排）=====
        result.sort((a, b) -> {
            // 房主永远排第一
            if (a.getIsHost() && !b.getIsHost()) return -1;
            if (!a.getIsHost() && b.getIsHost()) return 1;
            return 0;  // 同角色保持 DB 查询顺序（join_time 正序）
        });

        return result;
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

    /**
     * 获取成员昵称
     */
    private String getNicknameFromChannel(Long memberId) {
        Channel ch = roomChannelManager.getChannel(memberId);
        if (ch != null) {
            String nickname = ChannelAttrUtil.getNickname(ch);
            if (nickname != null && !nickname.isBlank()) {
                return nickname;
            }
        }
        return "匿名用户";
    }

    /**
     * 获取成员头像
     */
    private String getAvatarFromChannel(Long memberId) {
        Channel ch = roomChannelManager.getChannel(memberId);
        if (ch != null) {
            String avatar = ChannelAttrUtil.getAvatar(ch);
            if (avatar != null && !avatar.isBlank()) {
                return avatar;
            }
        }
        return "#999999";
    }


}
