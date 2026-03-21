package com.flashchat.chatservice.service.strategy.msg;

import com.flashchat.chatservice.dao.enums.MessageTypeEnum;
import com.flashchat.chatservice.dto.msg.FileDTO;
import com.flashchat.convention.exception.ClientException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 文本消息处理器
 * 特点：
 *   body = null（文本内容存在 content 字段，不需要额外 JSON）
 *   content = 原文
 * 校验：
 *   当前：检查 content 不为空
 *   未来：接入敏感词过滤器（SensitiveWordFilter）
 */
@Slf4j
@Component
public class TextMsgHandler extends AbstractMsgHandler {

    @Override
    public MessageTypeEnum getMsgTypeEnum() {
        return MessageTypeEnum.TEXT;
    }

    /**
     * 文本消息校验（纯校验，不修改数据）
     * 防御性校验说明：
     *   ChatServiceImpl.sendMsg() 已有跨字段校验（content 和 files 同时为空才拦截）
     *   走到 TextMsgHandler 的前提是 files 为空（Factory 路由规则）
     *   此时如果 content 也为空，说明 ChatServiceImpl 的校验被绕过了（不应该发生）
     *   这里做二次防御，确保文本消息一定有内容
     * TODO: 敏感词过滤（注入 SensitiveWordFilter，过滤后需要回写 content）
     */
    @Override
    public void checkMsg(String content, List<FileDTO> files) {
        if (content == null || content.isBlank()) {
            throw new ClientException("文本消息内容不能为空");
        }
    }

    /**
     * 文本消息不需要 body JSON
     * content 字段已经存了原文
     */
    @Override
    public String buildBodyJson(List<FileDTO> files) {
        return null;
    }

    /**
     * 文本消息的摘要就是原文
     */
    @Override
    public String buildContentSummary(String content, List<FileDTO> files) {
        return content;
    }
}

