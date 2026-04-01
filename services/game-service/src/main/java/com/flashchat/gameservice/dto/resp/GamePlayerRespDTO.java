package com.flashchat.gameservice.dto.resp;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 游戏玩家信息响应
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GamePlayerRespDTO {

    /** 账号 ID（真人正数，AI 负数） */
    private Long accountId;

    /** 显示名 */
    private String nickname;

    /** 头像 */
    private String avatar;

    /** 玩家类型：HUMAN / AI */
    private String playerType;

    /** AI 厂商 */
    private String aiProvider;

    /** AI 性格 */
    private String aiPersona;

    /** 玩家状态 */
    private String status;

    /** 发言顺序（游戏开始前为 null） */
    private Integer playerOrder;
}
