package com.flashchat.chatservice.dto.req;


import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 禁言/解禁请求（两个接口共用）
 */
@Data
public class RoomMuteReqDTO {

    @NotBlank(message = "房间 ID 不能为空")
    private String roomId;

    /** 操作者（房主）的 accountId */
    @NotBlank(message = "操作者账号 ID 不能为空")
    private String accountId;

    /** 被操作人的 memberId */
    @NotNull(message = "目标成员 ID 不能为空")
    private Long targetAccountId;
}