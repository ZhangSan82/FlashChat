package com.flashchat.channel.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * 成员离线事件
 * <p>
 * 发布时机：chat-service 的 RoomChannelManager.offline() 中，
 * 完成连接清理和 USER_OFFLINE 广播后发布。
 * <p>
 * 与 {@code MemberLogoutEvent} 的区别：
 * <ul>
 *   <li>MemberLogoutEvent = 用户主动登出，SaToken 会话失效</li>
 *   <li>MemberOfflineEvent = WS 连接断开（可能是网络抖动），SaToken 会话仍有效</li>
 * </ul>
 * <p>
 * game-service 监听此事件后：
 * <ol>
 *   <li>通过 GameContextManager 的反向索引查找该用户所在的游戏</li>
 *   <li>如果在进行中的游戏里 → 标记 DISCONNECTED + 启动 30 秒重连定时器</li>
 * </ol>
 * <p>
 * <b>建议异步处理</b>（{@code @Async}），避免拖慢 chat-service 的离线清理流程。
 */
@Getter
public class MemberOfflineEvent extends ApplicationEvent {

    /** 离线用户的 ID */
    private final Long accountId;

    public MemberOfflineEvent(Object source, Long accountId) {
        super(source);
        this.accountId = accountId;
    }
}