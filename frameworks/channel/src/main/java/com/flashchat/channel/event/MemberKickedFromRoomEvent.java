package com.flashchat.channel.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * 成员被踢出房间事件
 * <p>
 * game-service 监听此事件后：
 * <ol>
 *   <li>通过 GameContextManager 查找该用户是否在该房间的进行中游戏里</li>
 *   <li>如果是 → 标记为 ELIMINATED → 重新判定胜负</li>
 * </ol>
 * <p>
 * <b>同步处理</b>：踢人需要立即完成游戏内淘汰判定，
 * 确保踢人接口返回时游戏状态已更新。
 */
@Getter
public class MemberKickedFromRoomEvent extends ApplicationEvent {

    /** 被踢用户的 ID */
    private final Long accountId;

    /** 被踢出的聊天房间 ID */
    private final String roomId;

    /** 操作者 ID */
    private final Long operatorId;

    public MemberKickedFromRoomEvent(Object source, Long accountId,
                                     String roomId, Long operatorId) {
        super(source);
        this.accountId = accountId;
        this.roomId = roomId;
        this.operatorId = operatorId;
    }
}