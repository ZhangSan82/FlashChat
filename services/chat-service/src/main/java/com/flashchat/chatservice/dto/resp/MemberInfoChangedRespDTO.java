package com.flashchat.chatservice.dto.resp;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MemberInfoChangedRespDTO {

    private Long accountId;

    private String nickname;

    /**
     * 最终展示头像：可能是图片 URL，也可能是颜色值。
     */
    private String avatar;
}
