package com.flashchat.chatservice.dto.resp;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RoomPreviewMessageRespDTO {

    private Long indexId;
    private String senderId;
    private String username;
    private String avatar;
    private String content;
    private Long timestamp;
}
