package com.flashchat.userservice.dao.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 用户反馈实体。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("t_feedback")
public class FeedbackDO {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 提交人业务账号 ID，可为空。
     */
    private String accountId;

    /**
     * 提交时的昵称快照，便于管理员快速识别。
     */
    private String nicknameSnapshot;

    /**
     * 账号类型：GUEST / REGISTERED。
     */
    private String accountType;

    /**
     * 反馈类型。
     */
    private String feedbackType;

    /**
     * 反馈正文。
     */
    private String content;

    /**
     * 联系方式。
     */
    private String contact;

    /**
     * 截图地址。
     */
    private String screenshotUrl;

    /**
     * 是否愿意被联系，0/1。
     */
    private Integer willingContact;

    /**
     * 来源页面。
     */
    private String sourcePage;

    /**
     * 来源场景。
     */
    private String sourceScene;

    /**
     * 处理状态。
     */
    private String status;

    /**
     * 管理员处理备注。
     */
    private String reply;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @TableLogic
    private Integer delFlag;
}
