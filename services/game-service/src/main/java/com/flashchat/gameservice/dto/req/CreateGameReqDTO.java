package com.flashchat.gameservice.dto.req;

import com.flashchat.gameservice.config.GameConfig;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 创建游戏请求
 */
@Data
public class CreateGameReqDTO {

    /** 聊天房间 ID（t_room.room_id） */
    @NotBlank(message = "房间 ID 不能为空")
    private String roomId;

    /**
     * 创建者在游戏内的显示名
     */
    @Size(max = 50, message = "昵称不能超过 50 字符")
    private String nickname;

    /**
     * 创建者头像（URL 或颜色值）
     */
    @Size(max = 200, message = "头像 URL 过长")
    private String avatar;

    /**
     * 游戏配置
     */
    private GameConfig config;
}
