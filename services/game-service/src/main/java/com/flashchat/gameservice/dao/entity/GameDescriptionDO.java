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
 * 发言记录
 * <p>
 * 对应表：t_game_description
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_game_description")
public class GameDescriptionDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 游戏 ID */
    private String gameId;

    /** 轮次号 */
    private Integer roundNumber;

    /** 发言者 ID（t_game_player.id） */
    private Long playerId;

    /** 发言内容，跳过时为 NULL */
    private String content;

    /** 是否被跳过（超时/掉线）：0=否 1=是 */
    private Integer isSkipped;

    /** 提交时间 */
    private LocalDateTime submitTime;
}
