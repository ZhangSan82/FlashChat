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
 * 游戏会话
 * <p>
 * 对应表：t_game_room
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_game_room")
public class GameRoomDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 游戏业务 ID */
    private String gameId;

    /** 所属聊天房间 ID，逻辑关联 t_room.room_id */
    private String roomId;

    /** 游戏类型，默认 WHO_IS_SPY */
    private String gameType;

    /** 游戏状态 */
    private String gameStatus;

    /** 当前轮次号，初始 0，每轮结束后 +1 */
    private Integer currentRound;

    /** 本局使用的词对 ID，关联 t_word_pair.id */
    private Long wordPairId;

    /** 平民词（冗余存储，避免联表查询） */
    private String civilianWord;

    /** 卧底词（冗余存储） */
    private String spyWord;

    /**
     * 游戏配置 JSON
     */
    private String config;

    /** 胜利方：CIVILIAN / SPY，异常结束时为 NULL */
    private String winnerSide;

    /** 结束原因：NORMAL / ROOM_EXPIRED / ALL_DISCONNECTED / CANCELLED */
    private String endReason;

    /** 创建者 t_account.id */
    private Long creatorId;

    /** 创建时间 */
    private LocalDateTime createTime;

    /** 结束时间 */
    private LocalDateTime endTime;
}
