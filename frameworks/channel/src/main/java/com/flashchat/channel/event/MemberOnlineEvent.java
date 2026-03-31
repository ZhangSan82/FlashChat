package com.flashchat.channel.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * 成员上线事件
 * <p>
 * 发布时机：chat-service 的 RoomChannelManager.online() 中，
 * 完成连接注册和房间恢复后发布。
 * <p>
 * game-service 监听此事件后：
 * <ol>
 *   <li>通过 GameContextManager 的反向索引查找该用户所在的游戏</li>
 *   <li>如果在进行中的游戏里且状态为 DISCONNECTED → 取消重连定时器 → 恢复 ALIVE</li>
 * </ol>
 * <p>
 * <b>建议异步处理</b>（{@code @Async}），避免拖慢 chat-service 的上线流程。
 */
@Getter
public class MemberOnlineEvent extends ApplicationEvent {

    /** 上线用户的 ID */
    private final Long accountId;

    public MemberOnlineEvent(Object source, Long accountId) {
        super(source);
        this.accountId = accountId;
    }
}