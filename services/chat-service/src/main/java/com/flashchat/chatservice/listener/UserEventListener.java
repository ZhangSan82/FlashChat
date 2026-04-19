package com.flashchat.chatservice.listener;

import com.flashchat.chatservice.dao.entity.RoomMemberDO;
import com.flashchat.chatservice.dao.enums.RoomMemberRoleEnum;
import com.flashchat.chatservice.dao.enums.RoomMemberStatusEnum;
import com.flashchat.chatservice.service.RoomMemberService;
import com.flashchat.chatservice.service.RoomService;
import com.flashchat.chatservice.websocket.manager.RoomChannelManager;
import com.flashchat.user.event.AccountBannedEvent;
import com.flashchat.user.event.AccountDeletedEvent;
import com.flashchat.user.event.MemberInfoChangedEvent;
import com.flashchat.user.event.MemberLogoutEvent;
import io.netty.channel.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * 用户事件监听器
 * <p>
 * 监听 user-service 通过 Spring Event 发布的用户变更事件，
 * 在 chat-service 内部调用 RoomChannelManager 完成 WS 层面的操作。
 * <p>
 * 事件类定义在 frameworks/user 模块（两个 service 共享），
 * 解耦 user-service 与 chat-service 的 Maven 依赖。
 * <p>
 * 当前阶段：单 JVM 部署（aggregation-service 聚合），
 * Spring Event 是同步 in-process 调用，等同于直接方法调用，零开销。
 * <p>
 * 未来微服务拆分时：替换为 MQ 消息（RocketMQ/Kafka），
 * 只需改发布方和监听方的通道，事件类不变。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserEventListener {

    private final RoomChannelManager roomChannelManager;
    private final RoomService roomService;
    private final RoomMemberService roomMemberService;

    /**
     * 处理成员信息变更事件（改昵称 / 改头像）
     * <p>
     * 原逻辑在 AccountServiceImpl.updateProfile() 中直接调用：
     * {@code roomChannelManager.updateMemberInfo(loginId, newNickname, newAvatar)}
     * <p>
     * 现在通过事件解耦，逻辑完全一致。
     */
    @EventListener
    public void onMemberInfoChanged(MemberInfoChangedEvent event) {
        Long accountId = event.getAccountId();
        String newNickname = event.getNewNickname();
        String newAvatar = event.getNewAvatar();

        int updatedCount = roomChannelManager.updateMemberInfo(accountId, newNickname, newAvatar);
        roomChannelManager.broadcastMemberInfoChanged(accountId, newNickname, newAvatar);

        log.info("[事件-成员信息变更] accountId={}, nickname={}, avatar={}, 影响 {} 个房间",
                accountId,
                newNickname != null ? newNickname : "(未改)",
                newAvatar != null ? "(已更新)" : "(未改)",
                updatedCount);
    }

    /**
     * 处理账号注销事件
     */
    @EventListener
    public void onAccountDeleted(AccountDeletedEvent event) {
        Long accountId = event.getAccountId();

        // 1. 房主身份的所有活跃房间 → 强制进入宽限期
        int graceCount = handleHostRoomsOnDelete(accountId);
        // 2. 关闭 WS 连接
        Channel channel = roomChannelManager.getChannel(accountId);
        if (channel != null && channel.isActive()) {
            channel.close();
        }

        // 3. 清理所有房间的内存成员关系
        Set<String> rooms = roomChannelManager.getUserRooms(accountId);
        for (String roomId : rooms) {
            roomChannelManager.leaveRoom(roomId, accountId);
        }

        log.info("[事件-账号注销] accountId={}, 清理 {} 个房间, {} 个房主房间进入宽限期",
                accountId, rooms.size(), graceCount);
    }

    /**
     * 处理成员登出事件
     */
    @EventListener
    public void onMemberLogout(MemberLogoutEvent event) {
        Long accountId = event.getAccountId();

        // 关闭 WS 连接
        Channel channel = roomChannelManager.getChannel(accountId);
        if (channel != null && channel.isActive()) {
            channel.close();
            log.info("[事件-成员登出] accountId={}, WS 连接已关闭", accountId);
        } else {
            log.debug("[事件-成员登出] accountId={}, 无活跃 WS 连接", accountId);
        }
    }

    /**
     * 处理注销用户作为房主的所有活跃房间
     * @return 进入宽限期的房间数量
     */
    @EventListener
    public void onAccountBanned(AccountBannedEvent event) {
        Long accountId = event.getAccountId();

        int graceCount = handleHostRoomsOnDelete(accountId);
        Channel channel = roomChannelManager.getChannel(accountId);
        if (channel != null && channel.isActive()) {
            channel.close();
        }

        Set<String> rooms = roomChannelManager.getUserRooms(accountId);
        for (String roomId : rooms) {
            roomChannelManager.leaveRoom(roomId, accountId);
        }

        log.info("[account banned cleanup] accountId={}, clearedRooms={}, hostRoomsIntoGrace={}",
                accountId, rooms.size(), graceCount);
    }

    private int handleHostRoomsOnDelete(Long accountId) {
        List<RoomMemberDO> hostMembers = roomMemberService.lambdaQuery()
                .eq(RoomMemberDO::getAccountId, accountId)
                .eq(RoomMemberDO::getRole, RoomMemberRoleEnum.HOST.getCode())
                .eq(RoomMemberDO::getStatus, RoomMemberStatusEnum.ACTIVE.getCode())
                .select(RoomMemberDO::getRoomId)
                .list();

        if (hostMembers == null || hostMembers.isEmpty()) {
            return 0;
        }

        int count = 0;
        for (RoomMemberDO member : hostMembers) {
            try {
                roomService.doForceGrace(member.getRoomId());
                count++;
                log.info("[房主注销-房间进入宽限期] accountId={}, roomId={}",
                        accountId, member.getRoomId());
            } catch (Exception e) {
                log.error("[房主注销-房间宽限期失败] accountId={}, roomId={}",
                        accountId, member.getRoomId(), e);
            }
        }
        return count;
    }
}
