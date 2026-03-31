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
 * 投票记录
 * <p>
 * 对应表：t_game_vote
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_game_vote")
public class GameVoteDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 游戏 ID */
    private String gameId;

    /** 轮次号 */
    private Integer roundNumber;

    /** 投票者 ID（t_game_player.id） */
    private Long voterPlayerId;

    /** 被投目标 ID（t_game_player.id） */
    private Long targetPlayerId;

    /** 是否自动投票（掉线托管）：0=否 1=是 */
    private Integer isAuto;

    /** 投票时间 */
    private LocalDateTime voteTime;
}