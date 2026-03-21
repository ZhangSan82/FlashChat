package com.flashchat.chatservice.service.strategy.msg;

import com.flashchat.chatservice.dao.enums.MessageTypeEnum;
import com.flashchat.chatservice.dto.msg.FileDTO;
import com.flashchat.convention.exception.ClientException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 语音消息处理器
 * vue-advanced-chat 渲染条件：files[].type 以 "audio/" 开头 + audio=true
 * 关键字段：url（音频地址）、audio（true）、duration（时长秒数）
 *  重要：必须确保 audio=true
 *   vue-advanced-chat 要求 files[].audio === true 才渲染语音播放器
 *   文件上传接口返回的 FileDTO 不包含 audio 字段（上传接口不知道用途）
 *   所以必须在发消息时由 Handler 补上（通过 enrichFiles）
 *   如果漏了，语音会被渲染成普通文件下载链接
 */
@Slf4j
@Component
public class VoiceMsgHandler extends AbstractMsgHandler {

    /** 语音消息最大时长（秒） */
    private static final double MAX_DURATION_SECONDS = 300.0;

    @Override
    public MessageTypeEnum getMsgTypeEnum() {
        return MessageTypeEnum.VOICE;
    }

    /**
     * 语音消息校验（纯校验，不修改数据）
     */
    @Override
    public void checkMsg(String content, List<FileDTO> files) {
        if (files == null || files.isEmpty()) {
            throw new ClientException("语音消息必须包含文件");
        }
        FileDTO voice = files.get(0);
        if (voice.getDuration() != null && voice.getDuration() > MAX_DURATION_SECONDS) {
            throw new ClientException("语音时长不能超过 " + (int) MAX_DURATION_SECONDS + " 秒");
        }
    }

    /**
     * 补 audio=true
     * vue-advanced-chat 要求 files[].audio === true 才渲染语音播放器
     * 文件上传接口不设此字段，需要发消息时补上
     * 为什么放在 enrichFiles 而不是 checkMsg：
     *   checkMsg 约定只校验不改数据
     *   enrichFiles 约定可以修改 files 内容
     *   职责分离，避免"校验方法偷偷改数据"的副作用
     */
    @Override
    public void enrichFiles(List<FileDTO> files) {
        if (files == null) {
            return;
        }
        for (FileDTO file : files) {
            if (file.getAudio() == null || !file.getAudio()) {
                file.setAudio(true);
            }
        }
    }

    @Override
    public String buildContentSummary(String content, List<FileDTO> files) {
        if (content != null && !content.isBlank()) {
            return content;
        }
        if (files != null && !files.isEmpty() && files.get(0).getDuration() != null) {
            return "[语音] " + files.get(0).getDuration().intValue() + "\"";
        }
        return "[语音]";
    }
}
