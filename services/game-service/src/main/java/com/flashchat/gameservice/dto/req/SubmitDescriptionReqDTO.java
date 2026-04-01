package com.flashchat.gameservice.dto.req;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 提交发言请求
 */
@Data
public class SubmitDescriptionReqDTO {

    @NotBlank(message = "游戏 ID 不能为空")
    private String gameId;

    /**
     * 发言内容
     */
    @NotBlank(message = "发言内容不能为空")
    @Size(max = 200, message = "发言内容不能超过 200 字")
    private String content;
}