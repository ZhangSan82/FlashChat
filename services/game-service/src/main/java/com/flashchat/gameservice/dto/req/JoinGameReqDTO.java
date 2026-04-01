package com.flashchat.gameservice.dto.req;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 加入游戏请求
 */
@Data
public class JoinGameReqDTO {

    /** 游戏 ID */
    @NotBlank(message = "游戏 ID 不能为空")
    private String gameId;

    /**
     * 游戏内显示名
     */
    @Size(max = 50, message = "昵称不能超过 50 字符")
    private String nickname;

    /**
     * 头像（URL 或颜色值）
     */
    @Size(max = 200, message = "头像 URL 过长")
    private String avatar;
}