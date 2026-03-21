package com.flashchat.chatservice.service.strategy.msg;

import com.flashchat.chatservice.dao.enums.MessageTypeEnum;
import com.flashchat.chatservice.dto.context.FileSecurityConstants;
import com.flashchat.chatservice.dto.msg.FileDTO;
import com.flashchat.convention.exception.ClientException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

import static com.flashchat.chatservice.dto.context.FileSecurityConstants.BLOCKED_EXTENSIONS;

/**
 * 文件消息处理器
 *
 * vue-advanced-chat 渲染条件：files[].type 不是 image/audio/video 开头
 * 渲染效果：文件名 + 大小 + 下载按钮
 * 关键字段：url（下载地址）、name（文件名）、size（文件大小）
 *
 * 安全策略：黑名单（拦截危险后缀，放行其他）
 *   引用 FileSecurityConstants 公共常量，与 FileServiceImpl 共用同一份黑名单
 *   双重防御：上传接口拦一次（FileServiceImpl），发消息时再拦一次（此处）
 */
@Slf4j
@Component
public class FileMsgHandler extends AbstractMsgHandler {

    @Override
    public MessageTypeEnum getMsgTypeEnum() {
        return MessageTypeEnum.FILE;
    }

    /**
     * 文件消息校验（纯校验，不修改数据）
     * 使用 FileSecurityConstants 公共黑名单，与 FileServiceImpl 保持一致
     */
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
            if (FileSecurityConstants.isDangerousFile(fileName)) {
                throw new ClientException("不支持的文件类型: " + fileName);
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