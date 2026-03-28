package com.flashchat.chatservice.listener;

import com.flashchat.chatservice.websocket.manager.RoomChannelManager;
import com.flashchat.user.event.AccountDeletedEvent;
import com.flashchat.user.event.MemberInfoChangedEvent;
import io.netty.channel.Channel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

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

    /**
     * 处理成员信息变更事件（改昵称 / 改头像色）
     * <p>
     * 原逻辑在 AccountServiceImpl.updateProfile() 中直接调用：
     * {@code roomChannelManager.updateMemberInfo(loginId, newNickname, newAvatarColor)}
     * <p>
     * 现在通过事件解耦，逻辑完全一致。
     */
    @EventListener
    public void onMemberInfoChanged(MemberInfoChangedEvent event) {
        Long accountId = event.getAccountId();
        String newNickname = event.getNewNickname();
        String newAvatarColor = event.getNewAvatarColor();

        int updatedCount = roomChannelManager.updateMemberInfo(accountId, newNickname, newAvatarColor);

        log.info("[事件-成员信息变更] accountId={}, nickname={}, avatarColor={}, 影响 {} 个房间",
                accountId,
                newNickname != null ? newNickname : "(未改)",
                newAvatarColor != null ? newAvatarColor : "(未改)",
                updatedCount);
    }

    /**
     * 处理账号注销事件
     * <p>
     * 原逻辑在 AccountServiceImpl.deleteAccount() 中直接调用：
     * <ol>
     *   <li>roomChannelManager.getChannel(loginId) → channel.close()</li>
     *   <li>roomChannelManager.getUserRooms(loginId) → 遍历 leaveRoom</li>
     * </ol>
     * <p>
     * 现在通过事件解耦，逻辑完全一致。
     */
    @EventListener
    public void onAccountDeleted(AccountDeletedEvent event) {
        Long accountId = event.getAccountId();

        // 1. 关闭 WS 连接
        Channel channel = roomChannelManager.getChannel(accountId);
        if (channel != null && channel.isActive()) {
            channel.close();
        }

        // 2. 清理所有房间的成员关系
        Set<String> rooms = roomChannelManager.getUserRooms(accountId);
        for (String roomId : rooms) {
            roomChannelManager.leaveRoom(roomId, accountId);
        }

        log.info("[事件-账号注销] accountId={}, 清理 {} 个房间", accountId, rooms.size());
    }
}