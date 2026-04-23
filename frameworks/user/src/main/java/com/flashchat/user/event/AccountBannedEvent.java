package com.flashchat.user.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * 账号被系统管理员封禁后的事件。
 * <p>
 * 封禁和注销的后续清理动作基本一致：
 * 需要断开当前 WebSocket，并清理该账号在房间中的在线态与成员关系。
 * 区别在于封禁不会删除账号数据，后续仍可以通过解封恢复使用。
 */
@Getter
public class AccountBannedEvent extends ApplicationEvent {

    /**
     * 被封禁的账号主键，对应 t_account.id。
     */
    private final Long accountId;

    public AccountBannedEvent(Object source, Long accountId) {
        super(source);
        this.accountId = accountId;
    }
}
