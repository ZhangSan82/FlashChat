package com.flashchat.chatservice.dto.resp;

import com.flashchat.chatservice.dto.msg.FileDTO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 聊天消息广播/历史消息 DTO
 * 对齐 vue-advanced-chat 的 message 对象格式
 * vue-advanced-chat 的渲染规则：
 *   files == null     → 纯文本气泡（渲染 content）
 *   files != null     → 媒体气泡（由 files[].type 决定渲染方式）
 *   replyMessage != null → 显示引用回复气泡
 *   deleted == true   → 显示"此消息已被删除"
 *   system == true    → 系统消息样式（居中、无头像）
 * 统一用于两个场景（格式完全一致，前端无需区分）：
 *   1. WebSocket 实时广播（ChatServiceImpl.sendMsg 构建）
 *   2. HTTP 历史消息拉取（ChatServiceImpl.convertToRespDTOList 构建）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatBroadcastMsgRespDTO {

    /** 消息业务 ID（UUID 去横线） */
    private String _id;

    /** 消息自增 ID（排序 + 游标分页 + ACK 偏移量） */
    private Long indexId;

    /** 文本内容 / 摘要 */
    private String content;

    /** 发送者 ID */
    private String senderId;

    /** 发送者昵称 */
    private String username;

    /** 发送者头像（颜色值如 #FF6B6B，或头像 URL） */
    private String avatar;

    /** 发送时间戳（毫秒），前端自行格式化为本地时间 */
    private Long timestamp;

    /** 是否房主 */
    private Boolean isHost;

    // ==================== ★ 新增字段 ====================

    /** 消息类型编号（1文本 4图片 5语音 6视频 7文件） */
    private Integer msgType;

    /**
     * 附件文件列表
     * 对齐 vue-advanced-chat 的 files 数组
     * 文本消息 = null（前端看到 null 渲染纯文本气泡）
     * 媒体消息 = FileDTO 数组（前端根据 type 渲染图片/语音/视频/文件）
     */
    private List<FileDTO> files;

    /**
     * 引用回复消息
     * 对齐 vue-advanced-chat 的 replyMessage 对象
     * null = 非回复消息
     */
    private ReplyMessageDTO replyMessage;

    /**
     * 是否已删除
     * true 时 vue-advanced-chat 显示"此消息已被删除"
     */
    private Boolean deleted;

    /**
     * 是否系统消息
     * true 时 vue-advanced-chat 渲染为系统消息样式（居中、无头像）
     */
    private Boolean system;
}