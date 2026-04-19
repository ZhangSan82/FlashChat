package com.flashchat.chatservice.service.crypto;

import com.flashchat.chatservice.dao.entity.MessageDO;
import com.flashchat.convention.errorcode.BaseErrorCode;
import com.flashchat.convention.exception.ServiceException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;

/**
 * 负责业务消息对象与存储形态之间的转换。
 *
 * <p>这里是消息正文是否写成旧明文列、还是写成新密文字段的统一边界。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MessageContentCodec {

    private final MessageCryptoService messageCryptoService;

    /**
     * 生成一个可直接入库的副本。
     *
     * <p>原始 {@link MessageDO} 不会被原地修改，因为同一个对象后续还可能继续
     * 被 Redis、WebSocket 或其它内存链路使用。</p>
     */
    public MessageDO encodeForStorage(MessageDO source) {
        MessageDO copy = copyOf(source);
        if (copy == null || !messageCryptoService.isWriteEnabled()) {
            return copy;
        }
        if (copy.getContent() == null) {
            return copy;
        }

        EncryptedContent encrypted = messageCryptoService.encrypt(copy.getContent(), buildAad(copy));
        copy.setContent(null);
        copy.setContentCipher(encrypted.cipherText());
        copy.setContentIv(encrypted.iv());
        copy.setKeyVersion(encrypted.keyVersion());
        return copy;
    }

    /**
     * 对上层统一返回明文正文。
     *
     * <p>不管底层是老的明文存储，还是新的密文存储，上层拿到的都是可直接使用的正文。</p>
     */
    public String decodeContent(MessageDO message) {
        if (message == null) {
            return null;
        }
        if (!hasEncryptedContent(message)) {
            return message.getContent();
        }
        try {
            return messageCryptoService.decrypt(
                    message.getContentCipher(),
                    message.getContentIv(),
                    message.getKeyVersion(),
                    buildAad(message)
            );
        } catch (ServiceException e) {
            log.error("[message content decode failed] msgId={}, dbId={}", message.getMsgId(), message.getId(), e);
            throw e;
        } catch (Exception e) {
            log.error("[message content decode failed] msgId={}, dbId={}", message.getMsgId(), message.getId(), e);
            throw new ServiceException("message content decode failed", e, BaseErrorCode.SERVICE_ERROR);
        }
    }

    public boolean hasEncryptedContent(MessageDO message) {
        return message != null
                && message.getContentCipher() != null
                && !message.getContentCipher().isBlank()
                && message.getContentIv() != null
                && !message.getContentIv().isBlank()
                && message.getKeyVersion() != null;
    }

    public boolean isWriteEnabled() {
        return messageCryptoService.isWriteEnabled();
    }

    /**
     * 把稳定的消息元数据绑定到 AAD。
     *
     * <p>这样一旦有人篡改消息标识、房间号或消息类型，认证校验也会失败。</p>
     */
    private byte[] buildAad(MessageDO message) {
        String aad = String.join("|",
                safe(message.getMsgId()),
                safe(message.getRoomId()),
                message.getSenderId() == null ? "0" : message.getSenderId().toString(),
                message.getMsgType() == null ? "0" : message.getMsgType().toString()
        );
        return aad.getBytes(StandardCharsets.UTF_8);
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    /**
     * 创建一个脱离原对象的副本，避免存储层修改回流到实时消息对象。
     */
    private MessageDO copyOf(MessageDO source) {
        if (source == null) {
            return null;
        }
        return MessageDO.builder()
                .id(source.getId())
                .msgId(source.getMsgId())
                .roomId(source.getRoomId())
                .senderId(source.getSenderId())
                .nickname(source.getNickname())
                .avatarColor(source.getAvatarColor())
                .content(source.getContent())
                .contentCipher(source.getContentCipher())
                .contentIv(source.getContentIv())
                .keyVersion(source.getKeyVersion())
                .body(source.getBody())
                .replyMsgId(source.getReplyMsgId())
                .msgType(source.getMsgType())
                .status(source.getStatus())
                .isHost(source.getIsHost())
                .reactions(source.getReactions())
                .createTime(source.getCreateTime())
                .build();
    }
}
