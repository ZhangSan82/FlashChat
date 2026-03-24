package com.flashchat.chatservice.dao.entity;


import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 房间成员关系
 * 对应表：t_room_member
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_room_member")
public class RoomMemberDO {

    /**
     * 主键
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 房间业务 ID → t_room.room_id
     */
    private String roomId;

    /**
     * 账号 ID → t_account.id（统一外键）
     */
    private Long accountId;


    /**
     * 角色：0-普通成员 1-房主
     */
    private Integer role;

    /**
     * 是否被禁言：0-否 1-是
     * 每个房间独立，同一用户在不同房间禁言状态不同
     */
    private Integer isMuted;

    /**
     * 状态：1-正常 2-主动离开 3-被踢出
     */
    private Integer status;

    /**
     * 最后确认的消息 ID（离线消息偏移量）
     * 0 表示从未读过任何消息
     */
    private Long lastAckMsgId;

    /**
     * 加入时间
     */
    private LocalDateTime joinTime;

    /**
     * 离开时间（status != 1 时有值）
     */
    private LocalDateTime leaveTime;
}