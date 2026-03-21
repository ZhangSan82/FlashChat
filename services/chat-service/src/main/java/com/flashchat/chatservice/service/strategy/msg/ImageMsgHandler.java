package com.flashchat.chatservice.service.strategy.msg;

import com.flashchat.chatservice.dao.enums.MessageTypeEnum;
import com.flashchat.chatservice.dto.msg.FileDTO;
import com.flashchat.convention.exception.ClientException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 图片消息处理器
 * vue-advanced-chat 渲染条件：files[].type 以 "image/" 开头
 * 关键字段：url（原图）、preview（缩略图，可与 url 相同）
 */
@Slf4j
@Component
public class ImageMsgHandler extends AbstractMsgHandler {

    @Override
    public MessageTypeEnum getMsgTypeEnum() {
        return MessageTypeEnum.IMAGE;
    }

    /**
     * 图片消息校验（纯校验，不修改数据）
     */
    @Override
    public void checkMsg(String content, List<FileDTO> files) {
        if (files == null || files.isEmpty()) {
            throw new ClientException("图片消息必须包含文件");
        }
        for (FileDTO file : files) {
            if (file.getType() == null || !file.getType().toLowerCase().startsWith("image/")) {
                throw new ClientException("图片消息的文件类型必须为 image/*");
            }
        }
        // TODO: 图片域名白名单校验、文件大小上限
    }

    @Override
    public String buildContentSummary(String content, List<FileDTO> files) {
        if (content != null && !content.isBlank()) {
            return content;
        }
        return "[图片]";
    }
}
