package com.flashchat.chatservice.service.strategy.msg;

import com.flashchat.chatservice.dao.enums.MessageTypeEnum;
import com.flashchat.chatservice.dto.context.FileSecurityConstants;
import com.flashchat.chatservice.dto.msg.FileDTO;
import com.flashchat.convention.exception.ClientException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 普通文件消息处理器。
 * 当前系统仅允许压缩包走普通文件消息；
 * 图片和视频分别由 Image / Video Handler 处理。
 */
@Slf4j
@Component
public class FileMsgHandler extends AbstractMsgHandler {

    @Override
    public MessageTypeEnum getMsgTypeEnum() {
        return MessageTypeEnum.FILE;
    }

    @Override
    public void checkMsg(String content, List<FileDTO> files) {
        if (files == null || files.isEmpty()) {
            throw new ClientException("文件消息必须包含文件");
        }
        for (FileDTO file : files) {
            String fileName = file.getName();
            if (fileName == null || fileName.isBlank()) {
                throw new ClientException("文件名不能为空");
            }
            if (!FileSecurityConstants.isAllowedFileType(fileName, file.getType())) {
                throw new ClientException("当前仅支持图片、视频和压缩包");
            }
        }
    }

    @Override
    public String buildContentSummary(String content, List<FileDTO> files) {
        if (content != null && !content.isBlank()) {
            return content;
        }
        if (files != null && !files.isEmpty()) {
            String fileName = files.get(0).getName();
            if (fileName != null && !fileName.isBlank()) {
                return "[文件] " + fileName;
            }
        }
        return "[文件]";
    }
}
