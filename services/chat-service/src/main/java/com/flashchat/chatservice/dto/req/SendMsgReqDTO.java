package com.flashchat.chatservice.dto.req;

import com.flashchat.chatservice.dto.msg.FileDTO;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 发送消息请求体
 * 对齐 vue-advanced-chat 的 send-message 事件数据格式：
 *   { content: "hello", files: [{...}], replyMessage: {...} }
 * 消息类型由后端根据 files 有无 + MIME type 自动推断，前端不传 msgType
 * 跨字段校验：content 和 files 不能同时为空
 * 前端调用示例：
 *   纯文本：  { "roomId":"xxx", "accountId":"FC-xxx", "content":"你好" }
 *   纯图片：  { "roomId":"xxx", "accountId":"FC-xxx", "files":[{...}] }
 *   文字+图片：{ "roomId":"xxx", "accountId":"FC-xxx", "content":"看这个", "files":[{...}] }
 *   引用回复： { "roomId":"xxx", "accountId":"FC-xxx", "content":"同意", "replyMsgId":100 }
 */
@Data
public class SendMsgReqDTO {

    @NotBlank(message = "房间 ID 不能为空")
    private String roomId;

    @NotBlank(message = "账号 ID 不能为空")
    private String accountId;

    /**
     * 文本内容
     * 纯文本消息：必填
     * 纯媒体消息：可为空（此时 files 必须非空）
     * 文字+媒体：有值（作为附带文字）
     */
    @Size(max = 500, message = "消息内容不能超过 500 字")
    private String content;

    /**
     *附件文件列表
     * 对齐 vue-advanced-chat 的 files 数组格式
     * 纯文本消息时为 null 或空
     * @Valid 触发级联校验：自动校验 List 中每个 FileDTO 的 @NotBlank 注解
     */
    @Valid
    @Size(max = 9, message = "一次最多发送 9 个文件")
    private List<FileDTO> files;

    /**
     *引用回复的消息 ID
     * vue-advanced-chat 长按消息触发回复，组件自动传此字段
     * null 表示非回复消息
     */
    private Long replyMsgId;
}