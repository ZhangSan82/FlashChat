package com.flashchat.user.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * 成员信息变更事件（改昵称 / 改头像）
 * <p>
 * <b>当前阶段</b>：仅定义，不发布、不监听。
 * 改昵称时直接方法调用 RoomChannelManager，调用链清晰可调试。
 * <p>
 * <b>未来拆 user-service 时</b>：
 * 将直接调用替换为 {@code eventPublisher.publishEvent(new MemberInfoChangedEvent(...))}，
 * 在 chat-service 中添加 {@code @EventListener} 方法接收。
 * 事件类已在 frameworks/user 中定义，两个模块都能引用，无需临时造类。
 * <p>
 * 字段约定：null 表示该属性未变更，listener 按需处理。
 * 例如只改昵称：{@code new MemberInfoChangedEvent(this, 123L, "新昵称", null)}
 */
@Getter
public class MemberInfoChangedEvent extends ApplicationEvent {

    /** 变更的账号 ID（t_account.id） */
    private final Long accountId;

    /** 新昵称（null = 昵称未变） */
    private final String newNickname;

    /** 新头像（null = 头像未变；值可以是图片 URL 或颜色值） */
    private final String newAvatar;

    public MemberInfoChangedEvent(Object source, Long accountId,
                                  String newNickname, String newAvatar) {
        super(source);
        this.accountId = accountId;
        this.newNickname = newNickname;
        this.newAvatar = newAvatar;
    }
}
