package com.flashchat.gameservice.dto.req;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 提交投票请求
 */
@Data
public class SubmitVoteReqDTO {

    @NotBlank(message = "游戏 ID 不能为空")
    private String gameId;

    /**
     * 被投目标的 accountId
     */
    @NotNull(message = "投票目标不能为空")
    private Long targetAccountId;
}