package com.flashchat.chatservice.dto.req;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * Create room request.
 */
@Data
public class RoomCreateReqDTO {

    @NotBlank(message = "房间标题不能为空")
    private String title;

    /** max members, default 50 */
    private Integer maxMembers;

    /** 0-private, 1-public, default 0 */
    private Integer isPublic;

    /** room avatar URL, empty string clears avatar */
    @Size(max = 512, message = "房间头像 URL 过长")
    private String avatarUrl;

    /** room duration enum name, default MIN_30 */
    private String duration;
}
