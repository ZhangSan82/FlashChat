package com.flashchat.user.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * 账号注销事件
 * <p>
 * 发布时机：AccountServiceImpl.deleteAccount() 中，
 *           完成 DB 封禁 + 缓存失效后、SaToken logout 前发布。
 * <p>
 * chat-service 的 @EventListener 接收后执行：
 * <ol>
 *   <li>关闭该用户的 WebSocket 连接</li>
 *   <li>清理该用户在所有房间的成员关系</li>
 * </ol>
 * <p>
 * 与 {@link MemberLogoutEvent} 的区别：
 * <ul>
 *   <li>MemberLogoutEvent = 普通登出，仅需通知断开 WS</li>
 *   <li>AccountDeletedEvent = 账号注销，需彻底清理所有房间成员关系</li>
 * </ul>
 */
@Getter
public class AccountDeletedEvent extends ApplicationEvent {

    /** 注销的账号 ID（t_account.id） */
    private final Long accountId;

    public AccountDeletedEvent(Object source, Long accountId) {
        super(source);
        this.accountId = accountId;
    }
}