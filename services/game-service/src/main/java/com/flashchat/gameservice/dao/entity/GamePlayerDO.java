package com.flashchat.gameservice.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 游戏玩家
 * <p>
 * 对应表：t_game_player
 * <p>
 * 真人玩家的 account_id 为正数（t_account.id），
 * AI 玩家的 account_id 为负数（局内分配：-1, -2, -3...）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_game_player")
public class GamePlayerDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 游戏 ID，逻辑关联 t_game_room.game_id */
    private String gameId;

    /** 账号 ID：真人 = t_account.id（正数），AI = 虚拟 ID（负数） */
    private Long accountId;

    /** 玩家类型：HUMAN / AI */
    private String playerType;

    /** AI 厂商：DEEPSEEK / KIMI / QWEN，真人时 NULL */
    private String aiProvider;

    /** AI 性格：CAUTIOUS / AGGRESSIVE / MASTER，真人时 NULL */
    private String aiPersona;

    /** 游戏内显示名 */
    private String nickname;

    /** 头像（URL 或颜色值） */
    private String avatar;

    /** 角色：CIVILIAN / SPY / BLANK，开始前 NULL */
    private String role;

    /** 分配到的词语，开始前 NULL */
    private String word;

    /** 玩家状态：ALIVE / ELIMINATED / DISCONNECTED */
    private String status;

    /** 发言顺序（从 1 开始），开始前 NULL */
    private Integer playerOrder;

    /** 加入时间 */
    private LocalDateTime joinTime;
}