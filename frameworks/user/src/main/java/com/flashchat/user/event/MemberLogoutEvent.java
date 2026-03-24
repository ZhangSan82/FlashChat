package com.flashchat.user.event;


import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * 成员登出事件
 * <p>
 * <b>当前阶段</b>：仅定义，不发布、不监听。
 * 登出时直接方法调用 RoomChannelManager 关闭 WS 连接。
 * <p>
 * <b>未来拆 user-service 时</b>：
 * 在 AccountServiceImpl.doLogout() 中发布此事件，
 * chat-service 的 @EventListener 接收后关闭对应 WS 连接。
 * <p>
 * <b>重要</b>：发布此事件必须在 StpUtil.logout() 之前，
 * 否则 listener 中无法再通过 SaToken API 查询 Session 信息。
 * <p>
 * token 字段用途：多设备场景下匹配具体 Channel。
 * 单设备场景下可直接用 accountId 关闭所有连接。
 */
@Getter
public class MemberLogoutEvent extends ApplicationEvent {

    /** 登出的账号 ID（t_account.id） */
    private final Long accountId;

    /** 当前登出的 token（来自 StpUtil.getTokenValue()，在 logout 之前获取） */
    private final String token;

    public MemberLogoutEvent(Object source, Long accountId, String token) {
        super(source);
        this.accountId = accountId;
        this.token = token;
    }
}