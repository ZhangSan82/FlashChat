package com.flashchat.chatservice.dao.entity;


import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 房间
 * <p>
 * 对应表：t_room
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_room")
public class RoomDO {

    /**
     * 主键
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 房间业务 ID（8 位短码，用于 URL/QR 码）
     * 示例：a3f8b2c1
     */
    private String roomId;

    /**
     * 创建者用户 ID → t_user.id
     */
    private Long creatorId;

    /**
     * 房间标题（1-50 字符）
     */
    private String title;

    /**
     * 最大人数（2-100，默认 50）
     */
    private Integer maxMembers;

    /**
     * 是否公开：0-私密（仅扫码/链接） 1-公开（展示在房间列表）
     */
    private Integer isPublic;

    /**
     * 房间状态：0-等待中 1-活跃 2-即将到期 3-宽限期 4-已关闭
     */
    private Integer status;

    /**
     * 二维码 URL
     */
    private String qrUrl;

    /**
     * 预计到期时间
     * NULL 表示永不过期（广场房间）
     */
    private LocalDateTime expireTime;

    /**
     * 宽限期结束时间
     */
    private LocalDateTime graceEndTime;

    /**
     * 实际关闭时间
     */
    private LocalDateTime closedTime;

    /**
     * 创建时间
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    /**
     * 逻辑删除：0-未删除 1-已删除
     */
    @TableLogic
    private Integer delFlag;
}