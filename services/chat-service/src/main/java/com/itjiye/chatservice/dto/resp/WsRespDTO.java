package com.itjiye.chatservice.dto.resp;

import com.itjiye.chatservice.dto.enums.WsRespDTOTypeEnum;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class WsRespDTO<T> {
    private Integer type;
    private T data;

    public static <T> WsRespDTO<T> of(WsRespDTOTypeEnum type, T data) {
        return WsRespDTO.<T>builder()
                .type(type.getType())
                .data(data)
                .build();
    }
}