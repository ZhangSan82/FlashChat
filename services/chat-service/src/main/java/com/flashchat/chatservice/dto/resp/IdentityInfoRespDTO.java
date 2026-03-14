package com.flashchat.chatservice.dto.resp;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class IdentityInfoRespDTO {
    private Long userId;
    private String nickname;
    private String avatar;
}