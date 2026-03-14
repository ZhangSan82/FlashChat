package com.flashchat.chatservice.dto.req;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 创建房间请求（MVP 阶段仅注册用户可用，通过 creatorUserId 标识）
 */
@Data
public class RoomCreateReqDTO {

    @NotBlank(message = "房间标题不能为空")
    private String title;

    /** 最大人数，默认 50 */
    private Integer maxMembers;

    /** 0-私密 1-公开，默认 0 */
    private Integer isPublic;

    /** 时长（小时），默认 2.0，步进 0.5 */
    private Double durationHours;

    /** 创建者 t_user.id（MVP 阶段手动传入，后续从 Token 获取） */
    @NotBlank(message = "账号ID 不能为空")
    private String accountId;
}