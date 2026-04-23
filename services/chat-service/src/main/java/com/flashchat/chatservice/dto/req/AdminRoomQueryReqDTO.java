package com.flashchat.chatservice.dto.req;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

@Data
public class AdminRoomQueryReqDTO {

    private String keyword;

    private Integer status;

    private Integer isPublic;

    @Min(1)
    private Long page = 1L;

    @Min(1)
    @Max(100)
    private Long size = 20L;
}
