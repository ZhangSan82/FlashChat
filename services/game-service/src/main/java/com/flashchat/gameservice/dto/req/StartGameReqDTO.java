package com.flashchat.gameservice.dto.req;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 开始游戏请求
 */
@Data
public class StartGameReqDTO {

    @NotBlank(message = "游戏 ID 不能为空")
    private String gameId;
}