package com.flashchat.chatservice.dto.msg;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 文件信息 DTO
 * 完全对齐 vue-advanced-chat 的 file 对象结构，前后端零转换
 * 同时用于三个地方（一个 DTO 三处复用）：
 *   1. SendMsgReqDTO.files   → 请求入参（前端 → 后端）
 *   2. t_message.body        → DB 存储（JSON 序列化后的数组）
 *   3. ChatBroadcastMsgRespDTO.files → 响应出参（后端 → 前端 / WS 广播）
 * vue-advanced-chat 的渲染规则（由 type 字段驱动）：
 *   type 以 "image/" 开头                → 渲染图片预览
 *   type 以 "audio/" 开头 + audio=true   → 渲染语音播放器
 *   type 以 "video/" 开头                → 渲染视频播放器
 *   其他 type                            → 渲染文件下载链接
 * 各消息类型的字段使用：
 *   图片：name + size + type + url + preview(缩略图)
 *   语音：name + size + type + url + audio(true) + duration(秒)
 *   视频：name + size + type + url + preview(封面图) + duration(秒)
 *   文件：name + size + type + url
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FileDTO {

    /**
     * 文件名
     * 示例："photo.jpg"、"voice.mp3"、"report.pdf"
     */
    @NotBlank(message = "文件名不能为空")
    private String name;

    /**
     * 文件大小（字节）
     * 前端可用于展示文件大小（如 "2.1 MB"）
     */
    @NotNull
    private Long size;

    /**
     * MIME 类型
     * 示例："image/jpeg"、"audio/mp3"、"video/mp4"、"application/pdf"
     * 决定 vue-advanced-chat 的渲染方式
     * 同时用于后端推断消息类型（MessageTypeEnum.ofMimeType）
     */
    @NotBlank(message = "文件类型不能为空")
    private String type;

    /**
     * 文件访问 URL
     * 由文件上传接口返回，前端拿到后塞入此字段
     */
    @NotBlank(message = "文件URL不能为空")
    private String url;

    /**
     * 预览图 URL
     * 图片消息：缩略图 URL（可与 url 相同，前端自动展示）
     * 视频消息：封面图 URL
     * 语音/文件消息：null
     */
    private String preview;

    /**
     * 是否为语音消息
     * 仅语音消息设为 true，vue-advanced-chat 据此渲染语音播放器
     * 图片/视频/文件消息：null 或 false
     */
    private Boolean audio;

    /**
     * 媒体时长（秒）
     * 语音消息：语音时长（如 14.4 秒）
     * 视频消息：视频时长（如 120.0 秒）
     * 图片/文件消息：null
     */
    private Double duration;

    @Override
    public String toString() {
        return "FileDTO{name='" + name + "', type='" + type + "', size=" + size + "}";
    }

}