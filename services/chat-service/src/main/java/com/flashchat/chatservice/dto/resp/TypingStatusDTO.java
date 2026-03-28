package com.flashchat.chatservice.dto.resp;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 打字状态 DTO（上行请求 + 下行广播共用）
 * </pre>
 * <p>
 * vue-advanced-chat 通过 typingUsers 属性展示"xxx 正在输入..."。
 * 前端收到广播后维护一个 typingUsers 列表，传给组件即可。
 * <p>
 * 纯内存转发，不落库、不走 Redis。用户断线后自动停止（offline 广播兜底）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TypingStatusDTO {

    /** 房间 ID */
    private String roomId;

    /**
     * 用户 ID
     */
    private Long userId;

    /**
     * 用户昵称
     */
    private String nickname;

    /**
     * true=正在输入  false=停止输入
     * <p>
     * 前端发送时机建议：
     * <ul>
     *   <li>用户开始输入 → 发 typing=true（防抖 1 秒，避免频繁发送）</li>
     *   <li>用户停止输入 2 秒 → 发 typing=false</li>
     *   <li>用户发送消息 → 发 typing=false</li>
     * </ul>
     */
    private Boolean typing;
}