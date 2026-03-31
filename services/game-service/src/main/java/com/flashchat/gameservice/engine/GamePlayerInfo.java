package com.flashchat.gameservice.engine;

import com.flashchat.gameservice.dao.enums.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 玩家运行时内存对象
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GamePlayerInfo {

    /** t_game_player.id（内部 ID，DB 落库用） */
    private Long playerId;

    /** 账号 ID：真人正数，AI 负数 */
    private Long accountId;

    /** 玩家类型 */
    private PlayerTypeEnum playerType;

    /** 游戏内显示名 */
    private volatile String nickname;

    /** 头像 */
    private String avatar;

    /** 角色（游戏开始后分配） */
    private GameRoleEnum role;

    /** 分配到的词语 */
    private String word;

    /** 发言顺序（从 1 开始，全局固定不变） */
    private int playerOrder;

    /** 玩家状态 */
    private volatile PlayerStatusEnum status;

    /** AI 厂商（仅 AI 玩家有值） */
    private AiProviderEnum aiProvider;

    /** AI 性格（仅 AI 玩家有值） */
    private AiPersonaEnum aiPersona;

    /**
     * 是否为真人玩家
     */
    public boolean isHuman() {
        return playerType == PlayerTypeEnum.HUMAN;
    }

    /**
     * 是否为 AI 玩家
     */
    public boolean isAi() {
        return playerType == PlayerTypeEnum.AI;
    }

    /**
     * 是否仍在游戏中（存活或掉线托管）
     */
    public boolean isInGame() {
        return status != null && status.isInGame();
    }

    /**
     * 是否可以主动发言（仅 ALIVE 状态）
     */
    public boolean canSpeak() {
        return status != null && status.canSpeak();
    }

    /**
     * 是否为卧底
     */
    public boolean isSpy() {
        return role == GameRoleEnum.SPY;
    }

    /**
     * 是否为平民
     */
    public boolean isCivilian() {
        return role == GameRoleEnum.CIVILIAN;
    }
}
