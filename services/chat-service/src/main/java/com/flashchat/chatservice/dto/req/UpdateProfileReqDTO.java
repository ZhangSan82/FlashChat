package com.flashchat.chatservice.dto.req;


import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 修改个人资料请求
 * <p>
 * 字段为 null 表示不修改该项。
 * 至少传一个非 null 字段，否则无意义（Service 层判断）。
 * <p>
 * 头像上传流程：
 * 前端先调 POST /api/FlashChat/v1/file/upload 上传图片拿到 URL，
 * 再把 URL 传到此接口的 avatarUrl 字段。
 */
@Data
public class UpdateProfileReqDTO {

    /**
     * 新昵称
     * null = 不修改
     */
    @Size(max = 20, message = "昵称长度 1-20 字符")
    private String nickname;

    /**
     * 新头像背景色（#RRGGBB 格式）
     * null = 不修改
     */
    @Size(max = 7, message = "颜色格式错误")
    private String avatarColor;

    /**
     * 头像图片 URL
     * <p>
     * null = 不修改
     * 空字符串 = 清除头像（回退到 avatarColor 纯色方案）
     * 所有用户（匿名+注册）都可上传头像
     */
    @Size(max = 512, message = "头像 URL 过长")
    private String avatarUrl;
}