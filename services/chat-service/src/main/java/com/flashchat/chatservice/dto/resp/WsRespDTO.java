package com.flashchat.chatservice.dto.resp;


import com.flashchat.chatservice.dto.enums.WsRespDTOTypeEnum;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * WebSocket 统一推送结构
 *
 * 每条推送消息都带 roomId，前端根据 roomId 分发到对应的聊天窗口
 * roomId 为 null 时表示全局消息（如系统通知），不属于某个具体房间
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class WsRespDTO<T> {

    /** 消息类型 */
    private Integer type;

    /** 房间ID，前端据此分发到对应聊天窗口，null表示全局消息 */
    private String roomId;

    /** 具体数据 */
    private T data;

    /**
     * 构建房间级消息（带 roomId）
     */
    public static <T> WsRespDTO<T> of(String roomId, WsRespDTOTypeEnum type, T data) {
        return WsRespDTO.<T>builder()
                .type(type.getType())
                .roomId(roomId)
                .data(data)
                .build();
    }

    /**
     * 构建全局消息（不带 roomId）
     */
    public static <T> WsRespDTO<T> ofGlobal(WsRespDTOTypeEnum type, T data) {
        return WsRespDTO.<T>builder()
                .type(type.getType())
                .roomId(null)
                .data(data)
                .build();
    }
}