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
 * 轮次记录
 * <p>
 * 对应表：t_game_round
 * <p>
 * 每轮投票结束后插入一条记录，记录本轮的淘汰结果。
 * 是游戏进度的关键落库节点——进程重启后可从最后一个完成的轮次恢复。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_game_round")
public class GameRoundDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 游戏 ID */
    private String gameId;

    /** 轮次号（从 1 开始） */
    private Integer roundNumber;

    /**
     * 本轮被淘汰的玩家 ID（t_game_player.id）
     * <p>
     * 平票时为 NULL（无人淘汰）。
     */
    private Long eliminatedPlayerId;

    /** 是否平票：0=否 1=是 */
    private Integer isTie;

    /** 创建时间（即本轮结束时间） */
    private LocalDateTime createTime;
}
