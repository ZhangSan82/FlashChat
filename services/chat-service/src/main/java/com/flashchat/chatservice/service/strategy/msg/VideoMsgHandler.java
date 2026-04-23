package com.flashchat.chatservice.service.strategy.msg;

import com.flashchat.chatservice.dao.enums.MessageTypeEnum;
import com.flashchat.chatservice.dto.msg.FileDTO;
import com.flashchat.convention.exception.ClientException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 视频消息处理器。
 */
@Slf4j
@Component
public class VideoMsgHandler extends AbstractMsgHandler {

    @Override
    public MessageTypeEnum getMsgTypeEnum() {
        return MessageTypeEnum.VIDEO;
    }

    @Override
    public void checkMsg(String content, List<FileDTO> files) {
        if (files == null || files.isEmpty()) {
            throw new ClientException("视频消息必须包含文件");
        }
        for (FileDTO file : files) {
            if (file.getType() == null || !file.getType().toLowerCase().startsWith("video/")) {
                throw new ClientException("视频消息的文件类型必须为 video/*");
            }
        }
    }

    @Override
    public String buildContentSummary(String content, List<FileDTO> files) {
        if (content != null && !content.isBlank()) {
            return content;
        }
        return "[视频]";
    }
}
