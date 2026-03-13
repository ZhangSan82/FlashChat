package com.itjiye.chatservice.dto.req;

import lombok.Data;

@Data
public class WsReqDTO {
    private Integer type;
    private Object data;
}