package com.flashchat.chatservice.dto.req;


import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 消息确认请求
 * 前端调用时机（参考 MallChat 的隐式 ACK 策略）：
 *   1. 用户打开/切换到某个聊天窗口
 *   2. 用户在聊天窗口中，新消息到达且窗口在前台
 *   3. 用户从后台切回前台
 *   4. 建议前端做防抖（1秒内多次合并为一次）
 * 语义：
 *   "我已经看到了这个房间里 lastMsgId 及之前的所有消息"
 */
@Data
public class MsgAckReqDTO {

    @NotBlank(message = "房间 ID 不能为空")
    private String roomId;



    /**
     * 客户端当前看到的最新消息的自增 ID（dbId 字段）
     *
     * 前端从 history 接口返回的 dbId 字段获取
     * 含义：这个 ID 以及之前的消息我都看到了
     */
    @NotNull(message = "消息 ID 不能为空")
    private Long lastMsgId;
}