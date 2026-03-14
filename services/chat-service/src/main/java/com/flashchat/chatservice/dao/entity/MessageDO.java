package com.flashchat.chatservice.dao.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 聊天消息
 * 对应表：t_message
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_message")
public class MessageDO {

    /**
     * 主键（自增，兼做离线消息偏移量）
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 消息业务 ID（UUID 去横线，32 位）
     */
    private String msgId;

    /**
     * 房间业务 ID → t_room.room_id
     */
    private String roomId;

    /**
     * 发送者注册用户 ID → t_user.id
     * 与 sender_member_id 互斥
     */
    private Long senderUserId;

    /**
     * 发送者匿名成员 ID → t_member.id
     * 与 sender_user_id 互斥
     */
    private Long senderMemberId;

    /**
     * 发送时昵称（冗余快照）
     */
    private String nickname;

    /**
     * 发送时头像色（冗余快照）
     */
    private String avatarColor;

    /**
     * 消息内容（最长 500 字）
     */
    private String content;

    /**
     * 消息类型：1-文本 2-系统消息 3-游戏消息
     */
    private Integer msgType;

    /**
     * 状态：0-正常 1-已撤回 2-AI审核拦截
     */
    private Integer status;

    /**
     * 发送者是否为房主：0-否 1-是
     */
    private Integer isHost;

    /**
     * 发送时间
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
